package com.batowka.guestbooking.booking;

public class InvalidBookingDatesException extends RuntimeException {

    public InvalidBookingDatesException() {
        super("Даты некорректны: заезд должен быть раньше выезда и не в прошлом");
    }
}
