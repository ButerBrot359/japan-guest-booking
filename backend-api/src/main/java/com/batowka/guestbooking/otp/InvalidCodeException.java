package com.batowka.guestbooking.otp;

public class InvalidCodeException extends RuntimeException {
    public InvalidCodeException() {
        super("Неверный код");
    }
}
