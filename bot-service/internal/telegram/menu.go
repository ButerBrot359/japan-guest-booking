package telegram

import (
	"strconv"
	"strings"
	"time"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/backend"
)

// Локальная копия ruDate/ruMonths из пакета kafka: пакеты не делят утилиты,
// дублирование маленькое и осознанное.
var ruMonths = [...]string{"января", "февраля", "марта", "апреля", "мая", "июня",
	"июля", "августа", "сентября", "октября", "ноября", "декабря"}

func ruDate(iso string) string {
	t, err := time.Parse("2006-01-02", iso)
	if err != nil {
		return iso
	}
	return strconv.Itoa(t.Day()) + " " + ruMonths[t.Month()-1] + " " + strconv.Itoa(t.Year())
}

// formatActive — активная бронь гостя для сообщения бота.
func formatActive(gb backend.GuestBookings) string {
	if !gb.Linked {
		return "Сначала поделись контактом — нажми /start 📲"
	}
	if gb.Active == nil {
		return "Пока нет активной брони. Выбери даты на сайте 🗓"
	}
	return "📋 Твоя бронь: " + ruDate(gb.Active.CheckIn) + " → " + ruDate(gb.Active.CheckOut) +
		"\n✅ подтверждена"
}

// formatHistory — завершённые поездки гостя.
func formatHistory(gb backend.GuestBookings) string {
	if !gb.Linked {
		return "Сначала поделись контактом — нажми /start 📲"
	}
	if len(gb.History) == 0 {
		return "Пока нет завершённых поездок 🏡"
	}
	var b strings.Builder
	b.WriteString("🗺 Твои поездки:")
	for _, v := range gb.History {
		b.WriteString("\n🏡 " + ruDate(v.CheckIn) + " → " + ruDate(v.CheckOut) +
			" · " + nightsWord(v.Nights))
	}
	return b.String()
}

// nightsWord — склонение «ночь/ночи/ночей» (зеркало фронтового nightsWord).
func nightsWord(n int) string {
	mod10, mod100 := n%10, n%100
	switch {
	case mod10 == 1 && mod100 != 11:
		return strconv.Itoa(n) + " ночь"
	case mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14):
		return strconv.Itoa(n) + " ночи"
	default:
		return strconv.Itoa(n) + " ночей"
	}
}
