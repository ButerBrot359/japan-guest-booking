package kafka

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	kafkago "github.com/segmentio/kafka-go"
)

type fakeSender struct {
	sent      []string
	nextID    int64
	deleted   []int64 // message_id удалённых сообщений
	deleteErr error   // если задана — DeleteMessage падает этой ошибкой
}

func (f *fakeSender) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) (int64, error) {
	f.sent = append(f.sent, text)
	f.nextID++
	return f.nextID, nil
}

func (f *fakeSender) DeleteMessage(ctx context.Context, chatID, messageID int64) error {
	if f.deleteErr != nil {
		return f.deleteErr
	}
	f.deleted = append(f.deleted, messageID)
	return nil
}

type flakySender struct {
	failFirst bool
	sent      []string
}

func (f *flakySender) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) (int64, error) {
	if f.failFirst {
		f.failFirst = false
		return 0, context.DeadlineExceeded // любая ошибка
	}
	f.sent = append(f.sent, text)
	return int64(len(f.sent)), nil
}

func (f *flakySender) DeleteMessage(ctx context.Context, chatID, messageID int64) error { return nil }

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

func TestOtpCodeLoginText(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	_ = c.handle(context.Background(), eventJSON("e-otp-login", "OTP_CODE",
		`{"chat_id":42,"code":"123456","action":"LOGIN","expires_at":"2026-01-01T00:00:00Z"}`))

	if len(sender.sent) != 1 || !strings.Contains(sender.sent[0], "Код для входа: 123456") {
		t.Fatalf("ожидали текст про вход, получили: %q", sender.sent[0])
	}
}

func otpJSON(id string, chatID int64, code string) []byte {
	return eventJSON(id, "OTP_CODE",
		`{"chat_id":`+fmt.Sprint(chatID)+`,"code":"`+code+`","action":"LOGIN","expires_at":"2026-01-01T00:05:00Z"}`)
}

// Новый код вытесняет старый и в БД — сообщение со старым кодом удаляем,
// чтобы чат не превращался в свалку недействительных кодов.
func TestNewOtpDeletesPreviousCodeMessage(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	_ = c.handle(context.Background(), otpJSON("e-o1", 555, "111111"))
	_ = c.handle(context.Background(), otpJSON("e-o2", 555, "222222"))

	if len(sender.deleted) != 1 || sender.deleted[0] != 1 {
		t.Fatalf("ожидал удаление первого сообщения (id=1): %v", sender.deleted)
	}
	if len(sender.sent) != 2 {
		t.Fatalf("оба кода должны быть отправлены: %v", sender.sent)
	}

	// код в другой чат не трогает чужие сообщения
	_ = c.handle(context.Background(), otpJSON("e-o3", 777, "333333"))
	if len(sender.deleted) != 1 {
		t.Fatalf("чужой чат удаляться не должен: %v", sender.deleted)
	}
}

// Успешный вход → бэкенд шлёт OTP_CONSUMED → сообщение с кодом удаляется из чата.
func TestOtpConsumedDeletesCodeMessage(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	_ = c.handle(context.Background(), otpJSON("e-c1", 555, "111111")) // код → message_id=1
	_ = c.handle(context.Background(), eventJSON("e-c2", "OTP_CONSUMED", `{"chat_id":555}`))

	if len(sender.deleted) != 1 || sender.deleted[0] != 1 {
		t.Fatalf("ожидал удаление сообщения с кодом (id=1): %v", sender.deleted)
	}
	// повторный OTP_CONSUMED без активного кода ничего не удаляет
	_ = c.handle(context.Background(), eventJSON("e-c3", "OTP_CONSUMED", `{"chat_id":555}`))
	if len(sender.deleted) != 1 {
		t.Fatalf("нечего удалять во второй раз: %v", sender.deleted)
	}
}

func bookingJSON(id, eventType string, chatID int64, recipient string) []byte {
	return eventJSON(id, eventType,
		`{"chat_id":`+fmt.Sprint(chatID)+`,"guest_name":"Маша","check_in":"2027-06-01",`+
			`"check_out":"2027-06-05","recipient":"`+recipient+`"}`)
}

// В чате гостя висит только последний статус брони — прошлые бот удаляет.
func TestGuestBookingKeepsOnlyLatestStatus(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	_ = c.handle(context.Background(), bookingJSON("e-b1", "BOOKING_CONFIRMED", 555, "GUEST"))
	_ = c.handle(context.Background(), bookingJSON("e-b2", "BOOKING_RESCHEDULED", 555, "GUEST"))
	_ = c.handle(context.Background(), bookingJSON("e-b3", "BOOKING_CANCELLED", 555, "GUEST"))

	// три уведомления отправлены, но два прошлых удалены — в чате остаётся последнее
	if len(sender.sent) != 3 {
		t.Fatalf("ожидал 3 отправки: %v", sender.sent)
	}
	if len(sender.deleted) != 2 || sender.deleted[0] != 1 || sender.deleted[1] != 2 {
		t.Fatalf("ожидал удаление двух прошлых статусов (id 1,2): %v", sender.deleted)
	}
}

// В ленте владельца (recipient=ADMIN) прошлые уведомления НЕ вытесняются.
func TestAdminBookingFeedIsNotPruned(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	_ = c.handle(context.Background(), bookingJSON("e-a1", "BOOKING_CONFIRMED", 900, "ADMIN"))
	_ = c.handle(context.Background(), bookingJSON("e-a2", "BOOKING_CANCELLED", 900, "ADMIN"))

	if len(sender.deleted) != 0 {
		t.Fatalf("лента владельца не должна чиститься: %v", sender.deleted)
	}
}

func TestContactUnknownGetsPoliteReply(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	_ = c.handle(context.Background(), eventJSON("e-u1", "CONTACT_UNKNOWN", `{"chat_id":555}`))

	if len(sender.sent) != 1 || !strings.Contains(sender.sent[0], "пока нет в списке") {
		t.Fatalf("ожидал вежливый ответ чужому: %v", sender.sent)
	}
}

// Старое сообщение могли удалить руками — сбой удаления не должен блокировать доставку кода.
func TestOtpDeleteFailureDoesNotBlockSend(t *testing.T) {
	sender := &fakeSender{deleteErr: errors.New("message to delete not found")}
	c := newConsumerCore(sender)

	_ = c.handle(context.Background(), otpJSON("e-d1", 555, "111111"))
	if err := c.handle(context.Background(), otpJSON("e-d2", 555, "222222")); err != nil {
		t.Fatalf("сбой удаления не должен ронять обработку: %v", err)
	}
	if len(sender.sent) != 2 {
		t.Fatalf("оба кода должны дойти несмотря на сбой удаления: %v", sender.sent)
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

func TestRuDate(t *testing.T) {
	if got := ruDate("2026-03-22"); got != "22 марта 2026" {
		t.Errorf("ruDate: %q", got)
	}
	if got := ruDate("кривая-дата"); got != "кривая-дата" {
		t.Errorf("ruDate fallback: %q", got)
	}
}

func TestBookingCancelledByAdminText(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)
	raw := eventJSON("11111111-1111-4111-8111-111111111111", "BOOKING_CANCELLED",
		`{"chat_id":5,"guest_name":"Маша","check_in":"2026-03-22","check_out":"2026-03-25","by":"ADMIN"}`)
	if err := c.handle(context.Background(), raw); err != nil {
		t.Fatal(err)
	}
	want := "Бронь отменена владельцем: Маша, заезд 22 марта 2026, выезд 25 марта 2026."
	if len(sender.sent) != 1 || sender.sent[0] != want {
		t.Errorf("текст: %v, ожидался %q", sender.sent, want)
	}
}

func TestAccessRequestReceivedRender(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)
	raw := eventJSON("22222222-2222-4222-8222-222222222222", "ACCESS_REQUEST_RECEIVED",
		`{"chat_id":9,"name":"Незнакомец","phone":"+81313300001","message":"друг Миши"}`)
	if err := c.handle(context.Background(), raw); err != nil {
		t.Fatal(err)
	}
	want := "Новая заявка на доступ: Незнакомец, +81313300001.\nКомментарий: друг Миши"
	if len(sender.sent) != 1 || sender.sent[0] != want {
		t.Errorf("текст: %v, ожидался %q", sender.sent, want)
	}
}

// countingFlakySender падает первые failTimes вызовов, затем отправляет успешно.
type countingFlakySender struct {
	failTimes int

	mu    sync.Mutex
	calls int
	sent  []string
}

func (f *countingFlakySender) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	if f.calls <= f.failTimes {
		return 0, errors.New("временный сбой отправки")
	}
	f.sent = append(f.sent, text)
	return int64(len(f.sent)), nil
}

func (f *countingFlakySender) DeleteMessage(ctx context.Context, chatID, messageID int64) error {
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
