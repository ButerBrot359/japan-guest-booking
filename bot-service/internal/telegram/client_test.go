package telegram

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
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
		w.Write([]byte(`{"ok":true,"result":{"message_id":41}}`))
	}))
	defer server.Close()

	client := NewClient("TEST", server.URL)
	msgID, err := client.SendMessage(context.Background(), 555, "привет", true)
	if err != nil {
		t.Fatalf("SendMessage: %v", err)
	}
	if msgID != 41 {
		t.Errorf("ожидал message_id=41 из ответа, получил %d", msgID)
	}
	if body["chat_id"].(float64) != 555 || body["text"].(string) != "привет" {
		t.Errorf("тело неверное: %v", body)
	}
	if body["reply_markup"] == nil {
		t.Error("ожидал reply_markup с кнопкой контакта")
	}
}

func TestDeleteMessage(t *testing.T) {
	var body map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/botTEST/deleteMessage" {
			t.Errorf("неожиданный путь: %s", r.URL.Path)
		}
		json.NewDecoder(r.Body).Decode(&body)
		w.Write([]byte(`{"ok":true,"result":true}`))
	}))
	defer server.Close()

	client := NewClient("TEST", server.URL)
	if err := client.DeleteMessage(context.Background(), 555, 41); err != nil {
		t.Fatalf("DeleteMessage: %v", err)
	}
	if body["chat_id"].(float64) != 555 || body["message_id"].(float64) != 41 {
		t.Errorf("тело неверное: %v", body)
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

func TestSendApprovalButtons(t *testing.T) {
	var body map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewDecoder(r.Body).Decode(&body)
		w.Write([]byte(`{"ok":true,"result":{"message_id":15}}`))
	}))
	defer server.Close()

	id, err := NewClient("TEST", server.URL).SendApprovalButtons(
		context.Background(), 555, "Новая заявка", 42)
	if err != nil || id != 15 {
		t.Fatalf("SendApprovalButtons вернул (%d, %v)", id, err)
	}
	markup, _ := body["reply_markup"].(map[string]any)
	rows, _ := markup["inline_keyboard"].([]any)
	first, _ := rows[0].([]any)
	if len(first) != 2 {
		t.Fatalf("ожидал ряд из двух inline-кнопок: %v", rows)
	}
	b0, _ := first[0].(map[string]any)
	b1, _ := first[1].(map[string]any)
	if b0["callback_data"] != "approve:42" || b1["callback_data"] != "reject:42" {
		t.Fatalf("callback_data кнопок неверные: %v / %v", b0["callback_data"], b1["callback_data"])
	}
}

func TestAnswerCallback(t *testing.T) {
	var body map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/botTEST/answerCallbackQuery" {
			t.Errorf("неожиданный путь: %s", r.URL.Path)
		}
		json.NewDecoder(r.Body).Decode(&body)
		w.Write([]byte(`{"ok":true,"result":true}`))
	}))
	defer server.Close()

	if err := NewClient("TEST", server.URL).AnswerCallback(context.Background(), "cb1", "Готово"); err != nil {
		t.Fatalf("AnswerCallback: %v", err)
	}
	if body["callback_query_id"] != "cb1" || body["text"] != "Готово" {
		t.Errorf("тело неверное: %v", body)
	}
}

func TestEditMessageText(t *testing.T) {
	var body map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/botTEST/editMessageText" {
			t.Errorf("неожиданный путь: %s", r.URL.Path)
		}
		json.NewDecoder(r.Body).Decode(&body)
		w.Write([]byte(`{"ok":true,"result":{}}`))
	}))
	defer server.Close()

	if err := NewClient("TEST", server.URL).EditMessageText(context.Background(), 555, 15, "✅ Добавлен"); err != nil {
		t.Fatalf("EditMessageText: %v", err)
	}
	if body["chat_id"].(float64) != 555 || body["message_id"].(float64) != 15 || body["text"] != "✅ Добавлен" {
		t.Errorf("тело неверное: %v", body)
	}
	// кнопки убираются — reply_markup не отправляем
	if _, has := body["reply_markup"]; has {
		t.Errorf("editMessageText не должен слать reply_markup: %v", body)
	}
}

func TestNetworkErrorDoesNotLeakToken(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	server.Close() // сервер закрыт — запрос гарантированно упадёт по сети,
	// а *url.Error от http.Client.Do содержит полный URL с токеном

	const secretToken = "SECRET-BOT-TOKEN"
	client := NewClient(secretToken, server.URL)
	_, err := client.GetUpdates(context.Background(), 0)
	if err == nil {
		t.Fatal("ожидал сетевую ошибку при обращении к закрытому серверу")
	}
	if strings.Contains(err.Error(), secretToken) {
		t.Fatalf("ошибка содержит токен бота: %v", err)
	}
}
