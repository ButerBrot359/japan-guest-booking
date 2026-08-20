package com.batowka.guestbooking.accessrequest;

public class AlreadyResolvedException extends RuntimeException {
    public AlreadyResolvedException() {
        super("Заявка уже рассмотрена");
    }
}
