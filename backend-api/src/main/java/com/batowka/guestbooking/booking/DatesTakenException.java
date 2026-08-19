package com.batowka.guestbooking.booking;

public class DatesTakenException extends RuntimeException {

    public DatesTakenException() {
        super("Даты только что заняли — обнови календарь");
    }
}
