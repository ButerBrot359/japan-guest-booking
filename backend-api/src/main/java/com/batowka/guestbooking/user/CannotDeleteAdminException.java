package com.batowka.guestbooking.user;

public class CannotDeleteAdminException extends RuntimeException {
    public CannotDeleteAdminException() {
        super("Админа удалить нельзя");
    }
}
