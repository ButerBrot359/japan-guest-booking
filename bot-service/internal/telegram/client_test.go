package telegram

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestGetUpdatesParsesContact(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/botTEST/getUpdates" {
			t.Errorf("неожиданный путь: %s", r.URL.Path)
		}
		w.Write([]byte(`{"ok":true,"result":[
			{"update_id":10,"message":{"chat":{"id":555},"from":{"id":777,"username":"masha"},
			 "contact":{"phone_number":"81300000001","user_id":777}}}]}`))
	}))
	defer server.Close()

	client := NewClient("TEST", server.URL)
	updates, err := client.GetUpdates(context.Background(), 0)
	if err != nil {
		t.Fatalf("GetUpdates: %v", err)
	}
	if len(updates) != 1 {
		t.Fatalf("ожидал 1 update, получил %d", len(updates))
	}
	u := updates[0]
	if u.UpdateID != 10 || u.Message.Chat.ID != 555 ||
		u.Message.Contact.PhoneNumber != "81300000001" || u.Message.Contact.UserID != 777 {
		t.Errorf("распарсилось неверно: %+v", u)
	}
}

func TestSendMessageWithContactButton(t *testing.T) {
	var body map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/botTEST/sendMessage" {
			t.Errorf("неожиданный путь: %s", r.URL.Path)
		}
		json.NewDecoder(r.Body).Decode(&body)
		w.Write([]byte(`{"ok":true,"result":{}}`))
	}))
	defer server.Close()

	client := NewClient("TEST", server.URL)
	if err := client.SendMessage(context.Background(), 555, "привет", true); err != nil {
		t.Fatalf("SendMessage: %v", err)
	}
	if body["chat_id"].(float64) != 555 || body["text"].(string) != "привет" {
		t.Errorf("тело неверное: %v", body)
	}
	if body["reply_markup"] == nil {
		t.Error("ожидал reply_markup с кнопкой контакта")
	}
}

func TestApiErrorIsReturned(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"ok":false,"description":"Unauthorized"}`))
	}))
	defer server.Close()

	client := NewClient("TEST", server.URL)
	if _, err := client.GetUpdates(context.Background(), 0); err == nil {
		t.Fatal("ожидал ошибку при ok=false")
	}
}
