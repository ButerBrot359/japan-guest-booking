package telegram

import (
	"context"
	"log"
	"time"
)

// ContactPublisher — публикация CONTACT_SHARED; реализация — internal/kafka.
type ContactPublisher interface {
	PublishContactShared(ctx context.Context, chatID int64, phone, username string) error
}

type Poller struct {
	api       API
	publisher ContactPublisher
	offset    int64
}

func NewPoller(api API, publisher ContactPublisher) *Poller {
	return &Poller{api: api, publisher: publisher}
}

// Run крутит long polling до отмены контекста.
func (p *Poller) Run(ctx context.Context) {
	for ctx.Err() == nil {
		updates, err := p.api.GetUpdates(ctx, p.offset)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("telegram getUpdates: %v — повтор через 3с", err)
			select {
			case <-time.After(3 * time.Second):
			case <-ctx.Done():
				return
			}
			continue
		}
		failed := false
		for _, u := range updates {
			if err := p.handle(ctx, u); err != nil {
				// не двигаем offset — Telegram передоставит этот update;
				// дубль на backend безопасен (идемпотентность).
				failed = true
				break
			}
			p.offset = u.UpdateID + 1
		}
		if failed {
			select {
			case <-time.After(3 * time.Second):
			case <-ctx.Done():
				return
			}
		}
	}
}

// handle возвращает ошибку только для фатальной ветки (публикация CONTACT_SHARED
// в Kafka) — её Run обязан ретраить без сдвига offset. Ошибки sendMessage
// (приветствие /start, ack-подтверждение) не фатальны: логируются и гасятся,
// чтобы косметическая неудача не зациклила онбординг повторной доставкой.
func (p *Poller) handle(ctx context.Context, u Update) error {
	m := u.Message
	if m == nil {
		return nil
	}
	switch {
	case m.Text == "/start":
		if err := p.api.SendMessage(ctx, m.Chat.ID,
			"Привет! Чтобы получать коды подтверждения и уведомления о бронях, "+
				"поделись, пожалуйста, своим контактом.", true); err != nil {
			log.Printf("sendMessage /start: %v", err)
		}
	case m.Contact != nil:
		if m.From == nil || m.Contact.UserID != m.From.ID {
			log.Printf("контакт не принадлежит отправителю — игнорирую")
			return nil
		}
		username := ""
		if m.From != nil {
			username = m.From.Username
		}
		if err := p.publisher.PublishContactShared(ctx, m.Chat.ID,
			m.Contact.PhoneNumber, username); err != nil {
			log.Printf("publish CONTACT_SHARED: %v", err)
			return err
		}
		if err := p.api.SendMessage(ctx, m.Chat.ID,
			"Принял! Если твой номер в списке гостей — сейчас придёт подтверждение.",
			false); err != nil {
			log.Printf("sendMessage ack: %v", err)
		}
	}
	return nil
}
