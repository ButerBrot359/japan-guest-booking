package com.batowka.guestbooking.otp;

public class NoActiveCodeException extends RuntimeException {
    public NoActiveCodeException() {
        super("Нет активного кода для этой брони");
    }
}
