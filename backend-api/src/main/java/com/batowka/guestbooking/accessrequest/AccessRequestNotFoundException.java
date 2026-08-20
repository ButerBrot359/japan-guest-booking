package com.batowka.guestbooking.accessrequest;

public class AccessRequestNotFoundException extends RuntimeException {
    public AccessRequestNotFoundException() {
        super("Заявка не найдена");
    }
}
