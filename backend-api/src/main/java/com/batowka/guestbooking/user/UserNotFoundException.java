package com.batowka.guestbooking.user;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException() {
        super("Пользователь не найден");
    }
}
