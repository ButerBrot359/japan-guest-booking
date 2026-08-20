package com.batowka.guestbooking.user;

public class AlreadyMemberException extends RuntimeException {
    public AlreadyMemberException() {
        super("Этот номер уже в белом списке");
    }
}
