package telegram

import (
	"context"
	"log"
	"time"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/backend"
)

// ContactPublisher — публикация CONTACT_SHARED; реализация — internal/kafka.
type ContactPublisher interface {
	PublishContactShared(ctx context.Context, chatID int64, phone, username string) error
}

// BookingsFetcher — синхронное чтение данных гостя (реализация — internal/backend.Client).
type BookingsFetcher interface {
	GetGuestBookings(ctx context.Context, chatID int64) (backend.GuestBookings, error)
}

type Poller struct {
	api       API
	publisher ContactPublisher
	bookings  BookingsFetcher
	offset    int64
	// chat_id → message_id приглашения «поделись контактом»: удаляем его,
	// когда гость поделился контактом. In-memory, переживает всё кроме рестарта.
	startInvite map[int64]int64
}

func NewPoller(api API, publisher ContactPublisher, bookings BookingsFetcher) *Poller {
	return &Poller{api: api, publisher: publisher, bookings: bookings, startInvite: make(map[int64]int64)}
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
		msgID, err := p.api.SendMessage(ctx, m.Chat.ID,
			"Привет! Чтобы получать коды подтверждения и уведомления о бронях, "+
				"поделись, пожалуйста, своим контактом.", true)
		if err != nil {
			log.Printf("sendMessage /start: %v", err)
			return nil
		}
		p.startInvite[m.Chat.ID] = msgID
	case m.Text == MenuBookings:
		p.sendMenuReply(ctx, m.Chat.ID, formatActive)
	case m.Text == MenuHistory:
		p.sendMenuReply(ctx, m.Chat.ID, formatHistory)
	case m.Contact != nil:
		if m.From == nil || m.Contact.UserID != m.From.ID {
			log.Printf("контакт не принадлежит отправителю — игнорирую")
			return nil
		}
		if err := p.publisher.PublishContactShared(ctx, m.Chat.ID,
			m.Contact.PhoneNumber, m.From.Username); err != nil {
			log.Printf("publish CONTACT_SHARED: %v", err)
			return err
		}
		// приглашение отработало — убираем его; ответ (WELCOME / CONTACT_UNKNOWN)
		// придёт из бэкенда, отдельный ack больше не шлём
		if inviteID, ok := p.startInvite[m.Chat.ID]; ok {
			if err := p.api.DeleteMessage(ctx, m.Chat.ID, inviteID); err != nil {
				log.Printf("удаление приглашения /start: %v", err)
			}
			delete(p.startInvite, m.Chat.ID)
		}
	}
	return nil
}

// sendMenuReply тянет данные гостя и шлёт отформатированный ответ; ошибки бэкенда
// не фатальны для poller (offset двигается) — гостю показываем «попробуй позже».
func (p *Poller) sendMenuReply(ctx context.Context, chatID int64, format func(backend.GuestBookings) string) {
	gb, err := p.bookings.GetGuestBookings(ctx, chatID)
	if err != nil {
		log.Printf("bot menu: backend: %v", err)
		if _, err := p.api.SendMessage(ctx, chatID, "Не получилось загрузить, попробуй позже 🙏", false); err != nil {
			log.Printf("sendMessage menu error: %v", err)
		}
		return
	}
	if _, err := p.api.SendMessage(ctx, chatID, format(gb), false); err != nil {
		log.Printf("sendMessage menu: %v", err)
	}
}
