// Package events — Go-зеркало contracts/: конверт и payload'ы событий.
package events

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"time"
)

type Envelope struct {
	EventID    string          `json:"event_id"`
	OccurredAt time.Time       `json:"occurred_at"`
	EventType  string          `json:"event_type"`
	Payload    json.RawMessage `json:"payload"`
}

type ContactShared struct {
	ChatID           int64  `json:"chat_id"`
	Phone            string `json:"phone"`
	TelegramUsername string `json:"telegram_username,omitempty"`
}

type Welcome struct {
	ChatID int64  `json:"chat_id"`
	Name   string `json:"name"`
}

type OtpCode struct {
	ChatID    int64  `json:"chat_id"`
	Code      string `json:"code"`
	Action    string `json:"action"`
	ExpiresAt string `json:"expires_at"`
}

type BookingEvent struct {
	ChatID    int64  `json:"chat_id"`
	GuestName string `json:"guest_name"`
	CheckIn   string `json:"check_in"`
	CheckOut  string `json:"check_out"`
	By        string `json:"by,omitempty"` // GUEST | ADMIN; пусто у старых событий
}

type AccessRequestReceived struct {
	ChatID  int64  `json:"chat_id"`
	Name    string `json:"name"`
	Phone   string `json:"phone"`
	Message string `json:"message,omitempty"`
}

// NewUUID генерирует UUID v4 без внешних зависимостей.
func NewUUID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err) // недоступность crypto/rand — фатальна для процесса
	}
	b[6] = (b[6] & 0x0f) | 0x40 // версия 4
	b[8] = (b[8] & 0x3f) | 0x80 // вариант RFC 4122
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
