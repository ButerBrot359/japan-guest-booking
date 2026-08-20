package com.batowka.guestbooking.user;

/** Валидный токен, но пользователь удалён из белого списка: 401 + затирание cookie. */
public class UserGoneException extends RuntimeException {
    public UserGoneException() {
        super("Доступ отозван");
    }
}
