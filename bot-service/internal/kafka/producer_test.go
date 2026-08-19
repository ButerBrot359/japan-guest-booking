package kafka

import (
	"encoding/json"
	"testing"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/events"
)

func TestBuildContactSharedEnvelope(t *testing.T) {
	raw, err := buildContactSharedEnvelope(555, "81300000001", "masha")
	if err != nil {
		t.Fatalf("buildContactSharedEnvelope: %v", err)
	}
	var env events.Envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("конверт не парсится: %v", err)
	}
	if env.EventType != "CONTACT_SHARED" || env.EventID == "" || env.OccurredAt.IsZero() {
		t.Errorf("конверт неполный: %+v", env)
	}
	var p events.ContactShared
	if err := json.Unmarshal(env.Payload, &p); err != nil {
		t.Fatalf("payload не парсится: %v", err)
	}
	if p.ChatID != 555 || p.Phone != "81300000001" || p.TelegramUsername != "masha" {
		t.Errorf("payload неверный: %+v", p)
	}
}
