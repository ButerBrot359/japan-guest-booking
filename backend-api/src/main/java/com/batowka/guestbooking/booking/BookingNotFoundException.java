package com.batowka.guestbooking.booking;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException() {
        super("Бронь не найдена");
    }
}
