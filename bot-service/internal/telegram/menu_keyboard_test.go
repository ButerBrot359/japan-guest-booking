package telegram

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSendMenuAttachesPersistentKeyboard(t *testing.T) {
	var body map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewDecoder(r.Body).Decode(&body)
		w.Write([]byte(`{"ok":true,"result":{"message_id":7}}`))
	}))
	defer srv.Close()

	id, err := NewClient("TEST", srv.URL).SendMenu(context.Background(), 555, "привязан")
	if err != nil || id != 7 {
		t.Fatalf("SendMenu вернул (%d, %v)", id, err)
	}
	markup, ok := body["reply_markup"].(map[string]any)
	if !ok {
		t.Fatalf("нет reply_markup: %v", body)
	}
	if markup["is_persistent"] != true || markup["resize_keyboard"] != true {
		t.Fatalf("клавиатура должна быть постоянной и resize: %v", markup)
	}
	rows, _ := markup["keyboard"].([]any)
	first, _ := rows[0].([]any)
	if len(first) != 2 {
		t.Fatalf("ожидал ряд из двух кнопок: %v", rows)
	}
	b0, _ := first[0].(map[string]any)
	if b0["text"] != MenuBookings {
		t.Fatalf("первая кнопка — %q, ожидал %q", b0["text"], MenuBookings)
	}
	b1, _ := first[1].(map[string]any)
	if b1["text"] != MenuHistory {
		t.Fatalf("вторая кнопка — %q, ожидал %q", b1["text"], MenuHistory)
	}
}
