package telegram

import (
	"context"
	"testing"
)

type fakeAPI struct {
	sent           []string
	sentChatIDs    []int64
	contactButtons []bool
}

func (f *fakeAPI) GetUpdates(ctx context.Context, offset int64) ([]Update, error) {
	return nil, nil
}

func (f *fakeAPI) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error {
	f.sent = append(f.sent, text)
	f.sentChatIDs = append(f.sentChatIDs, chatID)
	f.contactButtons = append(f.contactButtons, requestContact)
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

func TestStartCommandSendsGreetingWithContactButton(t *testing.T) {
	api := &fakeAPI{}
	p := NewPoller(api, &fakePublisher{})

	p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777}, Text: "/start"}})

	if len(api.sent) != 1 || !api.contactButtons[0] || api.sentChatIDs[0] != 555 {
		t.Fatalf("ожидал приветствие с кнопкой контакта в чат 555: %+v", api)
	}
}

func TestOwnContactIsPublished(t *testing.T) {
	api := &fakeAPI{}
	pub := &fakePublisher{}
	p := NewPoller(api, pub)

	p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777, Username: "masha"},
		Contact: &Contact{PhoneNumber: "81300000001", UserID: 777}}})

	if len(pub.published) != 1 {
		t.Fatalf("ожидал 1 публикацию, получил %d", len(pub.published))
	}
	if len(api.sent) != 1 {
		t.Fatalf("ожидал ack-сообщение пользователю")
	}
}

func TestForeignContactIsRejected(t *testing.T) {
	api := &fakeAPI{}
	pub := &fakePublisher{}
	p := NewPoller(api, pub)

	// контакт чужого пользователя (user_id != from.id) — не публикуем
	p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777},
		Contact: &Contact{PhoneNumber: "81300000001", UserID: 999}}})

	if len(pub.published) != 0 {
		t.Fatal("чужой контакт не должен публиковаться")
	}
}
