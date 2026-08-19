package com.batowka.guestbooking.auth;

import java.util.Optional;
import java.util.regex.Pattern;

public final class Phones {

    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");

    private Phones() {
    }

    /** Нормализует в E.164 (убирает пробелы/дефисы/скобки); empty — если формат не E.164. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = raw.replaceAll("[\\s\\-()]", "");
        return E164.matcher(cleaned).matches() ? Optional.of(cleaned) : Optional.empty();
    }
}
