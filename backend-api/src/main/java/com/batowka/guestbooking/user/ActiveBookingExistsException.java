package com.batowka.guestbooking.user;

public class ActiveBookingExistsException extends RuntimeException {
    public ActiveBookingExistsException() {
        super("У пользователя есть активная бронь — сначала отмените её");
    }
}
