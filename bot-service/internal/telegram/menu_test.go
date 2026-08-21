package telegram

import (
	"strings"
	"testing"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/backend"
)

func TestFormatActiveConfirmed(t *testing.T) {
	gb := backend.GuestBookings{Linked: true,
		Active: &backend.Booking{CheckIn: "2026-03-12", CheckOut: "2026-03-15", Status: "CONFIRMED"}}
	got := formatActive(gb)
	if !strings.Contains(got, "12 марта 2026") || !strings.Contains(got, "15 марта 2026") ||
		!strings.Contains(got, "📋") {
		t.Fatalf("формат активной брони: %q", got)
	}
}

func TestFormatActiveNoBooking(t *testing.T) {
	if got := formatActive(backend.GuestBookings{Linked: true}); !strings.Contains(got, "нет активной") {
		t.Fatalf("ожидал текст про отсутствие брони: %q", got)
	}
}

func TestFormatActiveNotLinked(t *testing.T) {
	if got := formatActive(backend.GuestBookings{Linked: false}); !strings.Contains(got, "/start") {
		t.Fatalf("ожидал подсказку про /start: %q", got)
	}
}

func TestFormatHistory(t *testing.T) {
	gb := backend.GuestBookings{Linked: true, History: []backend.Visit{
		{CheckIn: "2025-06-01", CheckOut: "2025-06-04", Nights: 3},
		{CheckIn: "2025-01-10", CheckOut: "2025-01-11", Nights: 1},
	}}
	got := formatHistory(gb)
	if !strings.Contains(got, "3 ночи") || !strings.Contains(got, "1 ночь") ||
		!strings.Contains(got, "🏡") {
		t.Fatalf("формат истории: %q", got)
	}
}

func TestFormatHistoryEmpty(t *testing.T) {
	if got := formatHistory(backend.GuestBookings{Linked: true}); !strings.Contains(got, "нет завершённых") {
		t.Fatalf("ожидал текст про пустую историю: %q", got)
	}
}

func TestNightsWord(t *testing.T) {
	cases := map[int]string{1: "1 ночь", 2: "2 ночи", 3: "3 ночи", 5: "5 ночей", 11: "11 ночей", 21: "21 ночь"}
	for n, want := range cases {
		if got := nightsWord(n); got != want {
			t.Errorf("nightsWord(%d) = %q, ожидал %q", n, got, want)
		}
	}
}
