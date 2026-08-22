package com.batowka.guestbooking.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthCookiesTest {

    @Test
    void secureFlagAppearsWhenEnabled() {
        String cookie = new AuthCookies(true, 30).session("tok").toString();
        assertThat(cookie).contains("Secure").contains("HttpOnly").contains("SameSite=Lax");
    }

    @Test
    void noSecureFlagByDefaultProfile() {
        assertThat(new AuthCookies(false, 30).session("tok").toString()).doesNotContain("Secure");
    }

    @Test
    void ttlComesFromJwtTtlDays() {
        // 30 дней = 2592000 секунд — кука живёт столько же, сколько JWT
        assertThat(new AuthCookies(false, 30).session("tok").toString())
                .contains("Max-Age=2592000");
    }

    @Test
    void expiredCookieErasesValue() {
        String cookie = new AuthCookies(true, 30).expired().toString();
        assertThat(cookie).contains("Max-Age=0").contains("Secure");
    }
}
