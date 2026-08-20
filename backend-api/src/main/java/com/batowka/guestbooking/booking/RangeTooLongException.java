package com.batowka.guestbooking.booking;

public class RangeTooLongException extends RuntimeException {
    public RangeTooLongException() {
        super("Бронь не может быть длиннее двух недель (14 ночей)");
    }
}
