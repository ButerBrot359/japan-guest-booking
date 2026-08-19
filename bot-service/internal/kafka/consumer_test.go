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

func welcomeJSON(eventID string) []byte {
	return []byte(`{"event_id":"` + eventID + `","occurred_at":"2026-08-19T12:00:00Z",` +
		`"event_type":"WELCOME","payload":{"chat_id":555,"name":"Маша"}}`)
}

func TestWelcomeIsRendered(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	c.handle(context.Background(), welcomeJSON("e-1"))

	if len(sender.sent) != 1 || !strings.Contains(sender.sent[0], "Маша") {
		t.Fatalf("ожидал приветствие с именем: %v", sender.sent)
	}
}

func TestDuplicateEventIsSkipped(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	c.handle(context.Background(), welcomeJSON("e-dup"))
	c.handle(context.Background(), welcomeJSON("e-dup"))

	if len(sender.sent) != 1 {
		t.Fatalf("дубликат не должен отправляться повторно: %d", len(sender.sent))
	}
}

func TestUnknownEventTypeIsIgnored(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	c.handle(context.Background(), []byte(`{"event_id":"e-2","occurred_at":"2026-08-19T12:00:00Z",`+
		`"event_type":"OTP_CODE","payload":{}}`))

	if len(sender.sent) != 0 {
		t.Fatal("незнакомый тип не должен ничего отправлять")
	}
}
