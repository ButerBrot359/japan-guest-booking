package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"time"

	kafkago "github.com/segmentio/kafka-go"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/events"
)

var ruMonths = [...]string{"января", "февраля", "марта", "апреля", "мая", "июня",
	"июля", "августа", "сентября", "октября", "ноября", "декабря"}

// ruDate переводит ISO-дату в «22 марта 2026»; нераспознанное отдаёт как есть.
func ruDate(iso string) string {
	t, err := time.Parse("2006-01-02", iso)
	if err != nil {
		return iso
	}
	return fmt.Sprintf("%d %s %d", t.Day(), ruMonths[t.Month()-1], t.Year())
}

// Sender — минимум, который нужен для доставки уведомления (telegram.Client подходит).
type Sender interface {
	SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error
}

// consumerCore — логика обработки без Kafka-транспорта (тестируется юнитами).
type consumerCore struct {
	sender Sender
	seen   map[string]bool
	order  []string
}

const dedupCap = 1000

func newConsumerCore(sender Sender) *consumerCore {
	return &consumerCore{sender: sender, seen: make(map[string]bool)}
}

func (c *consumerCore) handle(ctx context.Context, raw []byte) error {
	var env events.Envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		log.Printf("битое событие, пропускаю: %v", err)
		return nil
	}
	if c.seen[env.EventID] {
		return nil // at-least-once: дубликат
	}
	switch env.EventType {
	case "WELCOME":
		var w events.Welcome
		if err := json.Unmarshal(env.Payload, &w); err != nil {
			log.Printf("битый payload WELCOME: %v", err)
			return nil
		}
		text := "Привет, " + w.Name + "! Telegram привязан — теперь сюда будут " +
			"приходить коды подтверждения и уведомления о бронях."
		return c.send(ctx, env.EventID, w.ChatID, text)
	case "OTP_CODE":
		var p events.OtpCode
		if err := json.Unmarshal(env.Payload, &p); err != nil {
			log.Printf("битый payload OTP_CODE: %v", err)
			return nil
		}
		text := "Код подтверждения: " + p.Code + ". Действует 5 минут."
		if p.Action == "LOGIN" {
			text = "Код для входа: " + p.Code + ". Действует 5 минут."
		}
		return c.send(ctx, env.EventID, p.ChatID, text)
	case "BOOKING_CONFIRMED":
		return c.renderBooking(ctx, env, "Бронь подтверждена")
	case "BOOKING_CANCELLED":
		return c.renderBooking(ctx, env, "Бронь отменена")
	case "BOOKING_RESCHEDULED":
		return c.renderBooking(ctx, env, "Бронь перенесена")
	case "ACCESS_REQUEST_RECEIVED":
		var p events.AccessRequestReceived
		if err := json.Unmarshal(env.Payload, &p); err != nil {
			log.Printf("битый payload ACCESS_REQUEST_RECEIVED: %v", err)
			return nil
		}
		text := "Новая заявка на доступ: " + p.Name + ", " + p.Phone + "."
		if p.Message != "" {
			text += "\nКомментарий: " + p.Message
		}
		return c.send(ctx, env.EventID, p.ChatID, text)
	default:
		log.Printf("незнакомый event_type %q — пропускаю (совместимость вперёд)", env.EventType)
		return nil
	}
}

func (c *consumerCore) remember(eventID string) {
	c.seen[eventID] = true
	c.order = append(c.order, eventID)
	if len(c.order) > dedupCap {
		delete(c.seen, c.order[0])
		c.order = c.order[1:]
	}
}

func (c *consumerCore) send(ctx context.Context, eventID string, chatID int64, text string) error {
	if err := c.sender.SendMessage(ctx, chatID, text, false); err != nil {
		return err
	}
	c.remember(eventID)
	return nil
}

func (c *consumerCore) renderBooking(ctx context.Context, env events.Envelope, prefix string) error {
	var p events.BookingEvent
	if err := json.Unmarshal(env.Payload, &p); err != nil {
		log.Printf("битый payload %s: %v", env.EventType, err)
		return nil
	}
	if p.By == "ADMIN" {
		prefix += " владельцем"
	}
	return c.send(ctx, env.EventID, p.ChatID,
		prefix+": "+p.GuestName+", заезд "+ruDate(p.CheckIn)+", выезд "+ruDate(p.CheckOut)+".")
}

// kafkaReader — минимум от *kafkago.Reader, нужный Run (позволяет подменить фейком в тестах).
type kafkaReader interface {
	FetchMessage(ctx context.Context) (kafkago.Message, error)
	CommitMessages(ctx context.Context, msgs ...kafkago.Message) error
	Close() error
}

const defaultRetryBackoff = 3 * time.Second

// Consumer — Kafka-транспорт вокруг consumerCore.
type Consumer struct {
	reader kafkaReader
	core   *consumerCore

	// retryBackoff — пауза между попытками при сбое fetch/handle. Поле (а не константа),
	// чтобы тесты могли подставить микросекундное значение вместо ожидания реальных 3с.
	retryBackoff time.Duration
}

func NewConsumer(brokers []string, sender Sender) *Consumer {
	return &Consumer{
		reader: kafkago.NewReader(kafkago.ReaderConfig{
			Brokers: brokers,
			GroupID: "bot-service",
			Topic:   "notifications.outbound",
		}),
		core:         newConsumerCore(sender),
		retryBackoff: defaultRetryBackoff,
	}
}

// sleepOrDone ждёт retryBackoff (или его дефолт, если поле не выставлено — например, в старых
// вызовах Consumer{...} напрямую) либо отмену контекста, что наступит раньше. Возвращает true,
// если нужно прекратить работу (контекст отменён).
func (c *Consumer) sleepOrDone(ctx context.Context) (cancelled bool) {
	backoff := c.retryBackoff
	if backoff <= 0 {
		backoff = defaultRetryBackoff
	}
	select {
	case <-time.After(backoff):
		return false
	case <-ctx.Done():
		return true
	}
}

// Run читает до отмены контекста. kafka-go FetchMessage продвигает позицию ридера в памяти сразу
// при вызове — если после сбоя обработки просто продолжить цикл, FetchMessage вернёт СЛЕДУЮЩЕЕ
// сообщение, а сбойное будет потеряно безвозвратно (коммит для него так и не случится, но и
// перечитано оно не будет). Поэтому при сбое отправки ретраим ОДНО И ТО ЖЕ уже полученное
// сообщение во внутреннем цикле и коммитим офсет только после успеха (at-least-once). Это
// осознанный трейдофф head-of-line blocking: пока текущее сообщение не отправлено, следующие не
// читаются.
func (c *Consumer) Run(ctx context.Context) {
	for ctx.Err() == nil {
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("kafka fetch: %v — повтор через %s", err, c.retryBackoff)
			if c.sleepOrDone(ctx) {
				return
			}
			continue
		}
		for {
			if err := c.core.handle(ctx, msg.Value); err == nil {
				break
			} else {
				log.Printf("обработка события: %v — повтор через %s", err, c.retryBackoff)
				if c.sleepOrDone(ctx) {
					return
				}
			}
		}
		if err := c.reader.CommitMessages(ctx, msg); err != nil {
			log.Printf("kafka commit: %v", err)
		}
	}
}

func (c *Consumer) Close() error {
	return c.reader.Close()
}
