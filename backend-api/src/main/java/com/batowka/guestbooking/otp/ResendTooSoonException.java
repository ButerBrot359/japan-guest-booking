package com.batowka.guestbooking.otp;

public class ResendTooSoonException extends RuntimeException {
    public ResendTooSoonException() {
        super("Код уже отправлен — подожди минуту");
    }
}
