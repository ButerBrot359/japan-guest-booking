package com.batowka.guestbooking.calendar;

import java.time.LocalDate;

public record CalendarDay(LocalDate date, DayStatus status, String guestName) {
}
