package com.batowka.guestbooking.calendar;

import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/** Блокировка поверх активной брони запрещена: каскадов нет, админ разруливает сам. */
@Getter
public class OverlapsBookingException extends RuntimeException {

    public record Conflict(long bookingId, String guestName, LocalDate checkIn, LocalDate checkOut) {
    }

    private final List<Conflict> conflicts;

    public OverlapsBookingException(List<Conflict> conflicts) {
        super("Даты пересекаются с активными бронями — сначала отмените или перенесите их");
        this.conflicts = conflicts;
    }
}
