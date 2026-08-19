// Package kafka — производитель и потребитель событий bot-service.
package kafka

import (
	"context"
	"encoding/json"
	"time"

	kafkago "github.com/segmentio/kafka-go"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/events"
)

type Producer struct {
	writer *kafkago.Writer
}

func NewProducer(brokers []string) *Producer {
	return &Producer{writer: &kafkago.Writer{
		Addr:     kafkago.TCP(brokers...),
		Topic:    "telegram.inbound",
		Balancer: &kafkago.LeastBytes{},
		// авто-создание топика при первом сообщении (dev-Kafka это разрешает)
		AllowAutoTopicCreation: true,
	}}
}

func (p *Producer) PublishContactShared(ctx context.Context, chatID int64, phone, username string) error {
	raw, err := buildContactSharedEnvelope(chatID, phone, username)
	if err != nil {
		return err
	}
	return p.writer.WriteMessages(ctx, kafkago.Message{Value: raw})
}

func (p *Producer) Close() error {
	return p.writer.Close()
}

func buildContactSharedEnvelope(chatID int64, phone, username string) ([]byte, error) {
	payload, err := json.Marshal(events.ContactShared{
		ChatID: chatID, Phone: phone, TelegramUsername: username,
	})
	if err != nil {
		return nil, err
	}
	return json.Marshal(events.Envelope{
		EventID:    events.NewUUID(),
		OccurredAt: time.Now().UTC(),
		EventType:  "CONTACT_SHARED",
		Payload:    payload,
	})
}
