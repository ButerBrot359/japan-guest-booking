package com.batowka.guestbooking.auth;

public class UnknownPhoneException extends RuntimeException {

    public UnknownPhoneException() {
        super("Этого номера нет в списке гостей");
    }
}
