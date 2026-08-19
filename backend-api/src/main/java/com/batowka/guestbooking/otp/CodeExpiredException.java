package com.batowka.guestbooking.otp;

public class CodeExpiredException extends RuntimeException {
    public CodeExpiredException() {
        super("Код недействителен, запроси новый");
    }
}
