package com.batowka.guestbooking.auth;

public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException() {
        super("Слишком много попыток, подождите минуту");
    }
}
