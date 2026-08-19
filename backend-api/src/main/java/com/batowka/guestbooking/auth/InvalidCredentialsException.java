package com.batowka.guestbooking.auth;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Неверный телефон или пароль");
    }
}
