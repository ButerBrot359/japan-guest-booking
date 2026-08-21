package telegram

import (
	"context"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/backend"
)

// ContactPublisher — публикация CONTACT_SHARED; реализация — internal/kafka.
type ContactPublisher interface {
	PublishContactShared(ctx context.Context, chatID int64, phone, username string) error
}

// Backend — синхронные вызовы бота к бэкенду (реализация — internal/backend.Client).
type Backend interface {
	GetGuestBookings(ctx context.Context, chatID int64) (backend.GuestBookings, error)
	ResolveAccessRequest(ctx context.Context, id int64, action string, adminChatID int64) (int, error)
}

type Poller struct {
	api       API
	publisher ContactPublisher
	bookings  Backend
	offset    int64
	// chat_id → message_id приглашения «поделись контактом»: удаляем его,
	// когда гость поделился контактом. In-memory, переживает всё кроме рестарта.
	startInvite map[int64]int64
}

func NewPoller(api API, publisher ContactPublisher, bookings Backend) *Poller {
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
	if u.CallbackQuery != nil {
		p.handleCallback(ctx, u.CallbackQuery)
		return nil
	}
	m := u.Message
	if m == nil {
		return nil
	}
	switch {
	case m.Text == "/start":
		p.handleStart(ctx, m.Chat.ID)
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

// handleStart делает /start универсальным входом: у привязанного гостя показывает
// меню-клавиатуру (иначе уже привязанный её никогда не увидит — она приходит только
// на WELCOME при первой привязке), непривязанному — приглашение поделиться контактом.
// При недоступном бэкенде — безопасный фоллбэк на приглашение (непривязанный сможет войти).
func (p *Poller) handleStart(ctx context.Context, chatID int64) {
	gb, err := p.bookings.GetGuestBookings(ctx, chatID)
	if err == nil && gb.Linked {
		if _, err := p.api.SendMenu(ctx, chatID, "С возвращением! 🏡 Вот твоё меню."); err != nil {
			log.Printf("sendMenu /start: %v", err)
		}
		return
	}
	if err != nil {
		log.Printf("bot /start: backend: %v — показываю приглашение контакта", err)
	}
	msgID, err := p.api.SendMessage(ctx, chatID,
		"Привет! Чтобы получать коды подтверждения и уведомления о бронях, "+
			"поделись, пожалуйста, своим контактом.", true)
	if err != nil {
		log.Printf("sendMessage /start: %v", err)
		return
	}
	p.startInvite[chatID] = msgID
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

// handleCallback обрабатывает нажатие inline-кнопки одобрения/отклонения заявки.
// Не возвращает ошибку: callback не передоставляется как message, а approve идемпотентен.
func (p *Poller) handleCallback(ctx context.Context, cb *CallbackQuery) {
	if cb.Message == nil {
		p.answer(ctx, cb.ID, "Не получилось 🙏")
		return
	}
	action, id, ok := parseCallback(cb.Data)
	if !ok {
		p.answer(ctx, cb.ID, "Не понял кнопку 🤔")
		return
	}
	chatID := cb.Message.Chat.ID
	code, err := p.bookings.ResolveAccessRequest(ctx, id, action, chatID)
	if err != nil {
		log.Printf("bot callback: backend: %v", err)
		p.answer(ctx, cb.ID, "Не получилось, попробуй ещё раз 🙏")
		return
	}
	switch code {
	case http.StatusNoContent:
		p.answer(ctx, cb.ID, "Готово")
		status := "✅ Добавлен"
		if action == ActReject {
			status = "❌ Отклонён"
		}
		p.edit(ctx, chatID, cb.Message.MessageID, status+"\n\n"+cb.Message.Text)
	case http.StatusConflict:
		p.answer(ctx, cb.ID, "Заявка уже обработана")
		p.edit(ctx, chatID, cb.Message.MessageID, "⚠️ Уже обработана\n\n"+cb.Message.Text)
	case http.StatusForbidden:
		p.answer(ctx, cb.ID, "Недостаточно прав")
	default:
		p.answer(ctx, cb.ID, "Не получилось, попробуй ещё раз 🙏")
	}
}

func (p *Poller) answer(ctx context.Context, callbackID, text string) {
	if err := p.api.AnswerCallback(ctx, callbackID, text); err != nil {
		log.Printf("answerCallbackQuery: %v", err)
	}
}

func (p *Poller) edit(ctx context.Context, chatID, messageID int64, text string) {
	if err := p.api.EditMessageText(ctx, chatID, messageID, text); err != nil {
		log.Printf("editMessageText: %v", err)
	}
}

// parseCallback разбирает "approve:<id>" / "reject:<id>".
func parseCallback(data string) (action string, id int64, ok bool) {
	parts := strings.SplitN(data, ":", 2)
	if len(parts) != 2 || (parts[0] != ActApprove && parts[0] != ActReject) {
		return "", 0, false
	}
	n, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil {
		return "", 0, false
	}
	return parts[0], n, true
}
