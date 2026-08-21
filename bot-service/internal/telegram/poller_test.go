package telegram

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/backend"
)

type fakeAPI struct {
	sent           []string
	sentChatIDs    []int64
	contactButtons []bool
	deleted        []int64
}

func (f *fakeAPI) GetUpdates(ctx context.Context, offset int64) ([]Update, error) {
	return nil, nil
}

func (f *fakeAPI) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) (int64, error) {
	f.sent = append(f.sent, text)
	f.sentChatIDs = append(f.sentChatIDs, chatID)
	f.contactButtons = append(f.contactButtons, requestContact)
	return int64(len(f.sent)), nil
}

func (f *fakeAPI) DeleteMessage(ctx context.Context, chatID, messageID int64) error {
	f.deleted = append(f.deleted, messageID)
	return nil
}

type fakePublisher struct {
	published []string // "chatID|phone|username"
	err       error
}

func (f *fakePublisher) PublishContactShared(ctx context.Context, chatID int64, phone, username string) error {
	if f.err != nil {
		return f.err
	}
	f.published = append(f.published, formatKey(chatID, phone, username))
	return nil
}

func formatKey(chatID int64, phone, username string) string {
	return string(rune(chatID)) + "|" + phone + "|" + username
}

type fakeFetcher struct {
	gb  backend.GuestBookings
	err error
}

func (f *fakeFetcher) GetGuestBookings(ctx context.Context, chatID int64) (backend.GuestBookings, error) {
	return f.gb, f.err
}

func TestStartCommandSendsGreetingWithContactButton(t *testing.T) {
	api := &fakeAPI{}
	p := NewPoller(api, &fakePublisher{}, &fakeFetcher{})

	p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777}, Text: "/start"}})

	if len(api.sent) != 1 || !api.contactButtons[0] || api.sentChatIDs[0] != 555 {
		t.Fatalf("ожидал приветствие с кнопкой контакта в чат 555: %+v", api)
	}
}

func TestOwnContactIsPublishedWithoutAck(t *testing.T) {
	api := &fakeAPI{}
	pub := &fakePublisher{}
	p := NewPoller(api, pub, &fakeFetcher{})

	p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777, Username: "masha"},
		Contact: &Contact{PhoneNumber: "81300000001", UserID: 777}}})

	if len(pub.published) != 1 {
		t.Fatalf("ожидал 1 публикацию, получил %d", len(pub.published))
	}
	// «Принял!» больше не шлём: ответ придёт из бэкенда (WELCOME или CONTACT_UNKNOWN)
	if len(api.sent) != 0 {
		t.Fatalf("ack-сообщение больше не должно отправляться: %v", api.sent)
	}
}

func TestStartInviteDeletedOnContact(t *testing.T) {
	api := &fakeAPI{}
	p := NewPoller(api, &fakePublisher{}, &fakeFetcher{})

	// /start шлёт приглашение (message_id=1), затем гость делится контактом
	p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777}, Text: "/start"}})
	p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777, Username: "masha"},
		Contact: &Contact{PhoneNumber: "81300000001", UserID: 777}}})

	// приглашение «поделись контактом» отработало — убираем его из чата
	if len(api.deleted) != 1 || api.deleted[0] != 1 {
		t.Fatalf("ожидал удаление приглашения (id=1): %v", api.deleted)
	}
}

func TestContactWithoutPriorStartDoesNotDelete(t *testing.T) {
	api := &fakeAPI{}
	p := NewPoller(api, &fakePublisher{}, &fakeFetcher{})

	p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777, Username: "masha"},
		Contact: &Contact{PhoneNumber: "81300000001", UserID: 777}}})

	if len(api.deleted) != 0 {
		t.Fatalf("без приглашения удалять нечего: %v", api.deleted)
	}
}

func TestForeignContactIsRejected(t *testing.T) {
	api := &fakeAPI{}
	pub := &fakePublisher{}
	p := NewPoller(api, pub, &fakeFetcher{})

	// контакт чужого пользователя (user_id != from.id) — не публикуем
	p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777},
		Contact: &Contact{PhoneNumber: "81300000001", UserID: 999}}})

	if len(pub.published) != 0 {
		t.Fatal("чужой контакт не должен публиковаться")
	}
}

func TestContactPublishErrorIsReturnedAndAckNotSent(t *testing.T) {
	api := &fakeAPI{}
	pub := &fakePublisher{err: errors.New("kafka недоступна")}
	p := NewPoller(api, pub, &fakeFetcher{})

	err := p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777, Username: "masha"},
		Contact: &Contact{PhoneNumber: "81300000001", UserID: 777}}})

	if err == nil {
		t.Fatal("ожидал ошибку публикации из handle")
	}
	if len(api.sent) != 0 {
		t.Fatalf("ack-сообщение не должно отправляться при ошибке публикации: %v", api.sent)
	}
}

// sequencedAPI отдаёт один и тот же update дважды (эмулируя передоставку
// Telegram, когда offset не сдвинут), а затем блокируется до отмены контекста
// — как реальный long polling, который висит до таймаута.
type sequencedAPI struct {
	calls  int
	update Update
}

func (a *sequencedAPI) GetUpdates(ctx context.Context, offset int64) ([]Update, error) {
	a.calls++
	if a.calls <= 2 {
		return []Update{a.update}, nil
	}
	<-ctx.Done()
	return nil, ctx.Err()
}

func (a *sequencedAPI) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) (int64, error) {
	return 1, nil
}

func (a *sequencedAPI) DeleteMessage(ctx context.Context, chatID, messageID int64) error {
	return nil
}

// flakyPublisher падает на первом вызове и успевает со второго.
type flakyPublisher struct {
	calls     int
	published int
}

func (f *flakyPublisher) PublishContactShared(ctx context.Context, chatID int64, phone, username string) error {
	f.calls++
	if f.calls == 1 {
		return errors.New("kafka недоступна")
	}
	f.published++
	return nil
}

func TestRunRedeliversFailedUpdateThenAdvancesOffset(t *testing.T) {
	update := Update{UpdateID: 42, Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777, Username: "masha"},
		Contact: &Contact{PhoneNumber: "81300000001", UserID: 777}}}
	api := &sequencedAPI{update: update}
	pub := &flakyPublisher{}
	p := NewPoller(api, pub, &fakeFetcher{})

	ctx, cancel := context.WithTimeout(context.Background(), 4500*time.Millisecond)
	defer cancel()
	p.Run(ctx)

	if pub.calls != 2 {
		t.Fatalf("ожидал ровно 2 попытки публикации (неудача + успех), получил %d", pub.calls)
	}
	if pub.published != 1 {
		t.Fatalf("ожидал ровно одну успешную публикацию, получил %d", pub.published)
	}
	if p.offset != update.UpdateID+1 {
		t.Fatalf("ожидал offset=%d (update передоставлен и в итоге сдвинут), получил %d",
			update.UpdateID+1, p.offset)
	}
}

func TestMenuButtonShowsActiveBooking(t *testing.T) {
	api := &fakeAPI{}
	fetch := &fakeFetcher{gb: backend.GuestBookings{Linked: true,
		Active: &backend.Booking{CheckIn: "2026-03-12", CheckOut: "2026-03-15", Status: "CONFIRMED"}}}
	p := NewPoller(api, &fakePublisher{}, fetch)

	p.handle(context.Background(), Update{Message: &Message{Chat: Chat{ID: 555}, Text: MenuBookings}})

	if len(api.sent) != 1 || !strings.Contains(api.sent[0], "12 марта 2026") {
		t.Fatalf("ожидал ответ с активной бронью: %v", api.sent)
	}
}

func TestMenuButtonBackendErrorTellsRetry(t *testing.T) {
	api := &fakeAPI{}
	fetch := &fakeFetcher{err: errors.New("бэкенд лёг")}
	p := NewPoller(api, &fakePublisher{}, fetch)

	p.handle(context.Background(), Update{Message: &Message{Chat: Chat{ID: 555}, Text: MenuHistory}})

	if len(api.sent) != 1 || !strings.Contains(api.sent[0], "попробуй позже") {
		t.Fatalf("ожидал совет попробовать позже: %v", api.sent)
	}
}
