package kafka

import (
	"context"
	"encoding/json"
	"log"
	"time"

	kafkago "github.com/segmentio/kafka-go"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/events"
)

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

func (c *consumerCore) handle(ctx context.Context, raw []byte) {
	var env events.Envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		log.Printf("битое событие, пропускаю: %v", err)
		return
	}
	if c.seen[env.EventID] {
		return // at-least-once: дубликат
	}
	c.remember(env.EventID)
	switch env.EventType {
	case "WELCOME":
		var w events.Welcome
		if err := json.Unmarshal(env.Payload, &w); err != nil {
			log.Printf("битый payload WELCOME: %v", err)
			return
		}
		text := "Привет, " + w.Name + "! Telegram привязан — теперь сюда будут " +
			"приходить коды подтверждения и уведомления о бронях."
		if err := c.sender.SendMessage(ctx, w.ChatID, text, false); err != nil {
			log.Printf("отправка WELCOME chat_id=%d: %v", w.ChatID, err)
		}
	default:
		log.Printf("незнакомый event_type %q — пропускаю (совместимость вперёд)", env.EventType)
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

// Consumer — Kafka-транспорт вокруг consumerCore.
type Consumer struct {
	reader *kafkago.Reader
	core   *consumerCore
}

func NewConsumer(brokers []string, sender Sender) *Consumer {
	return &Consumer{
		reader: kafkago.NewReader(kafkago.ReaderConfig{
			Brokers: brokers,
			GroupID: "bot-service",
			Topic:   "notifications.outbound",
		}),
		core: newConsumerCore(sender),
	}
}

// Run читает до отмены контекста; offset коммитится ПОСЛЕ обработки (at-least-once).
func (c *Consumer) Run(ctx context.Context) {
	for ctx.Err() == nil {
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("kafka fetch: %v — повтор через 3с", err)
			select {
			case <-time.After(3 * time.Second):
			case <-ctx.Done():
				return
			}
			continue
		}
		c.core.handle(ctx, msg.Value)
		if err := c.reader.CommitMessages(ctx, msg); err != nil {
			log.Printf("kafka commit: %v", err)
		}
	}
}

func (c *Consumer) Close() error {
	return c.reader.Close()
}
