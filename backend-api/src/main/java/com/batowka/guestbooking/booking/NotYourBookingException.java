package com.batowka.guestbooking.booking;

public class NotYourBookingException extends RuntimeException {

    public NotYourBookingException() {
        super("Это не твоя бронь");
    }
}
