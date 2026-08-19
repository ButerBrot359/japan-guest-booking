package kafka

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	kafkago "github.com/segmentio/kafka-go"
)

type fakeSender struct {
	sent []string
}

func (f *fakeSender) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error {
	f.sent = append(f.sent, text)
	return nil
}

type flakySender struct {
	failFirst bool
	sent      []string
}

func (f *flakySender) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error {
	if f.failFirst {
		f.failFirst = false
		return context.DeadlineExceeded // любая ошибка
	}
	f.sent = append(f.sent, text)
	return nil
}

func welcomeJSON(eventID string) []byte {
	return []byte(`{"event_id":"` + eventID + `","occurred_at":"2026-08-19T12:00:00Z",` +
		`"event_type":"WELCOME","payload":{"chat_id":555,"name":"Маша"}}`)
}

func TestWelcomeIsRendered(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	if err := c.handle(context.Background(), welcomeJSON("e-1")); err != nil {
		t.Fatalf("handle должен вернуть nil при успехе: %v", err)
	}

	if len(sender.sent) != 1 || !strings.Contains(sender.sent[0], "Маша") {
		t.Fatalf("ожидал приветствие с именем: %v", sender.sent)
	}
}

func TestDuplicateEventIsSkipped(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	_ = c.handle(context.Background(), welcomeJSON("e-dup"))
	_ = c.handle(context.Background(), welcomeJSON("e-dup"))

	if len(sender.sent) != 1 {
		t.Fatalf("дубликат не должен отправляться повторно: %d", len(sender.sent))
	}
}

func TestUnknownEventTypeIsIgnored(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	_ = c.handle(context.Background(), []byte(`{"event_id":"e-2","occurred_at":"2026-08-19T12:00:00Z",`+
		`"event_type":"UNKNOWN_TYPE","payload":{}}`))

	if len(sender.sent) != 0 {
		t.Fatal("незнакомый тип не должен ничего отправлять")
	}
}

func TestFailedSendIsRetriedNotDeduplicated(t *testing.T) {
	sender := &flakySender{failFirst: true}
	c := newConsumerCore(sender)

	if err := c.handle(context.Background(), welcomeJSON("e-retry")); err == nil {
		t.Fatal("ожидал ошибку при сбое отправки")
	}
	if err := c.handle(context.Background(), welcomeJSON("e-retry")); err != nil {
		t.Fatalf("повтор должен пройти: %v", err)
	}
	if len(sender.sent) != 1 {
		t.Fatalf("ожидал ровно одну доставку, получил %d", len(sender.sent))
	}
}

func eventJSON(id, eventType, payload string) []byte {
	return []byte(`{"event_id":"` + id + `","occurred_at":"2026-08-19T12:00:00Z",` +
		`"event_type":"` + eventType + `","payload":` + payload + `}`)
}

func TestOtpCodeIsRendered(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	_ = c.handle(context.Background(), eventJSON("e-otp", "OTP_CODE",
		`{"chat_id":555,"code":"482913","action":"CREATE_BOOKING","expires_at":"2026-08-19T12:05:00Z"}`))

	if len(sender.sent) != 1 || !strings.Contains(sender.sent[0], "482913") ||
		!strings.Contains(sender.sent[0], "5 минут") {
		t.Fatalf("ожидал код и срок в сообщении: %v", sender.sent)
	}
}

func TestBookingEventsAreRendered(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)
	payload := `{"chat_id":555,"guest_name":"Маша","check_in":"2027-06-01","check_out":"2027-06-05"}`

	_ = c.handle(context.Background(), eventJSON("e-c", "BOOKING_CONFIRMED", payload))
	_ = c.handle(context.Background(), eventJSON("e-x", "BOOKING_CANCELLED", payload))
	_ = c.handle(context.Background(), eventJSON("e-r", "BOOKING_RESCHEDULED", payload))

	if len(sender.sent) != 3 {
		t.Fatalf("ожидал 3 сообщения: %d", len(sender.sent))
	}
	for i, want := range []string{"подтверждена", "отменена", "перенесена"} {
		if !strings.Contains(sender.sent[i], want) || !strings.Contains(sender.sent[i], "Маша") {
			t.Errorf("сообщение %d: ожидал %q и имя: %q", i, want, sender.sent[i])
		}
	}
}

// countingFlakySender падает первые failTimes вызовов, затем отправляет успешно.
type countingFlakySender struct {
	failTimes int

	mu    sync.Mutex
	calls int
	sent  []string
}

func (f *countingFlakySender) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	if f.calls <= f.failTimes {
		return errors.New("временный сбой отправки")
	}
	f.sent = append(f.sent, text)
	return nil
}

func (f *countingFlakySender) snapshot() (calls int, sent []string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls, append([]string(nil), f.sent...)
}

// fakeReader — реализация kafkaReader для теста Run. Отдаёт одно сообщение на первый
// FetchMessage; все последующие вызовы блокируются до отмены контекста (имитирует "следующих
// сообщений в топике нет"), что позволяет тесту детерминированно остановить Run.
type fakeReader struct {
	msg kafkago.Message

	mu          sync.Mutex
	fetchCalls  int
	committed   []kafkago.Message
	commitCalls int
}

func (f *fakeReader) FetchMessage(ctx context.Context) (kafkago.Message, error) {
	f.mu.Lock()
	f.fetchCalls++
	first := f.fetchCalls == 1
	f.mu.Unlock()

	if first {
		return f.msg, nil
	}
	<-ctx.Done()
	return kafkago.Message{}, ctx.Err()
}

func (f *fakeReader) CommitMessages(ctx context.Context, msgs ...kafkago.Message) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.commitCalls++
	f.committed = append(f.committed, msgs...)
	return nil
}

func (f *fakeReader) Close() error { return nil }

func (f *fakeReader) snapshot() (fetchCalls, commitCalls int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.fetchCalls, f.commitCalls
}

// TestRunRetriesSameMessageUntilSuccess — регрессия на баг из финального ревью: наивный
// continue после сбоя обработки в Run молча терял сообщение, потому что kafka-go FetchMessage
// продвигает позицию ридера в памяти сразу при вызове (следующий FetchMessage вернул бы
// СЛЕДУЮЩЕЕ сообщение, а не то же самое). Правильное поведение — ретраить то же сообщение
// внутренним циклом и коммитить офсет только после успешной отправки.
func TestRunRetriesSameMessageUntilSuccess(t *testing.T) {
	sender := &countingFlakySender{failTimes: 2}
	reader := &fakeReader{msg: kafkago.Message{Value: welcomeJSON("e-run-retry")}}
	c := &Consumer{
		reader:       reader,
		core:         newConsumerCore(sender),
		retryBackoff: time.Millisecond, // не ждём реальные 3с в тесте
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() {
		c.Run(ctx)
		close(done)
	}()

	deadline := time.Now().Add(2 * time.Second)
	for {
		if _, commitCalls := reader.snapshot(); commitCalls >= 1 {
			break
		}
		if time.Now().After(deadline) {
			cancel()
			<-done
			t.Fatal("не дождались коммита сообщения после успешной отправки")
		}
		time.Sleep(time.Millisecond)
	}

	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Run не завершился после отмены контекста")
	}

	calls, sent := sender.snapshot()
	if calls != 3 {
		t.Fatalf("ожидал 3 попытки отправки (2 сбоя + успех), получил %d", calls)
	}
	if len(sent) != 1 {
		t.Fatalf("ожидал ровно одну успешную доставку, получил %d: %v", len(sent), sent)
	}

	fetchCalls, commitCalls := reader.snapshot()
	if fetchCalls != 2 {
		t.Fatalf("ожидал 2 вызова FetchMessage (сообщение + блокирующий вызов за следующим), "+
			"получил %d — сообщение перечитывалось повторно", fetchCalls)
	}
	if commitCalls != 1 {
		t.Fatalf("ожидал ровно один коммит, получил %d", commitCalls)
	}
	if len(reader.committed) != 1 || string(reader.committed[0].Value) != string(reader.msg.Value) {
		t.Fatalf("закоммичено не то сообщение: %+v", reader.committed)
	}
}
