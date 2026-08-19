package com.batowka.guestbooking.booking;

public class TelegramNotLinkedException extends RuntimeException {

    public TelegramNotLinkedException() {
        super("Сначала привяжи Telegram: открой бота и поделись контактом");
    }
}
