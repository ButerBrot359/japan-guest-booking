package backend

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestGetGuestBookingsParsesResponse(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/bot/bookings" || r.URL.Query().Get("chat_id") != "555" {
			t.Errorf("неожиданный запрос: %s?%s", r.URL.Path, r.URL.RawQuery)
		}
		if r.Header.Get("X-Bot-Token") != "tok" {
			t.Errorf("нет служебного токена: %q", r.Header.Get("X-Bot-Token"))
		}
		w.Write([]byte(`{"linked":true,
			"active":{"checkIn":"2027-06-01","checkOut":"2027-06-05","status":"CONFIRMED"},
			"history":[{"checkIn":"2025-05-01","checkOut":"2025-05-04","nights":3}]}`))
	}))
	defer srv.Close()

	gb, err := NewClient(srv.URL, "tok").GetGuestBookings(context.Background(), 555)
	if err != nil {
		t.Fatalf("GetGuestBookings: %v", err)
	}
	if !gb.Linked || gb.Active == nil || gb.Active.Status != "CONFIRMED" {
		t.Fatalf("активная бронь распарсилась неверно: %+v", gb)
	}
	if len(gb.History) != 1 || gb.History[0].Nights != 3 {
		t.Fatalf("история распарсилась неверно: %+v", gb.History)
	}
}

func TestGetGuestBookingsNotLinked(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"linked":false,"active":null,"history":[]}`))
	}))
	defer srv.Close()

	gb, err := NewClient(srv.URL, "tok").GetGuestBookings(context.Background(), 1)
	if err != nil {
		t.Fatalf("GetGuestBookings: %v", err)
	}
	if gb.Linked || gb.Active != nil || len(gb.History) != 0 {
		t.Fatalf("ожидал не-привязанного гостя: %+v", gb)
	}
}

func TestGetGuestBookingsHttpErrorIsReturned(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	if _, err := NewClient(srv.URL, "tok").GetGuestBookings(context.Background(), 1); err == nil {
		t.Fatal("ожидал ошибку при 500")
	}
}
