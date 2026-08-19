package com.batowka.guestbooking.booking;

public class BookingExpiredException extends RuntimeException {

    public BookingExpiredException() {
        super("Бронь уже отменена — создай новую");
    }
}
