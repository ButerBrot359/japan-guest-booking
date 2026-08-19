package kafka

import (
	"context"
	"strings"
	"testing"
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
