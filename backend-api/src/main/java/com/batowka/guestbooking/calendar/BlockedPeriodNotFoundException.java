package com.batowka.guestbooking.calendar;

public class BlockedPeriodNotFoundException extends RuntimeException {
    public BlockedPeriodNotFoundException() {
        super("Блокировка не найдена");
    }
}
