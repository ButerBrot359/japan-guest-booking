package com.batowka.guestbooking.booking;

public class OverlapsOwnBookingException extends RuntimeException {

    public OverlapsOwnBookingException() {
        super("Даты пересекаются с твоей текущей бронью — используй перенос");
    }

    public OverlapsOwnBookingException(String message) {
        super(message);
    }
}
