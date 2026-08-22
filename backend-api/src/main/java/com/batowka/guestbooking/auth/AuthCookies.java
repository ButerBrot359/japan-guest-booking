package com.batowka.guestbooking.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Сборка auth-куки: httpOnly+SameSite=Lax всегда; Secure и TTL — из конфига. */
@Component
public class AuthCookies {

    private final boolean secure;
    private final Duration ttl;

    public AuthCookies(@Value("${app.cookie.secure:false}") boolean secure,
                       @Value("${app.jwt.ttl-days}") long ttlDays) {
        this.secure = secure;
        this.ttl = Duration.ofDays(ttlDays);
    }

    /** Сессионная кука: живёт столько же, сколько JWT внутри неё. */
    public ResponseCookie session(String token) {
        return build(token, ttl);
    }

    /** Затирающая кука — логаут и «пользователь удалён». */
    public ResponseCookie expired() {
        return build("", Duration.ZERO);
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(JwtAuthFilter.COOKIE_NAME, value)
                .httpOnly(true)
                .sameSite("Lax")
                .secure(secure)
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
