package com.batowka.guestbooking.auth;

public class InvalidPhoneException extends RuntimeException {

    public InvalidPhoneException() {
        super("Неверный формат телефона");
    }
}
