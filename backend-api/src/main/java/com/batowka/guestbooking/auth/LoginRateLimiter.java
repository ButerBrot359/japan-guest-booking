package com.batowka.guestbooking.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Скользящее окно: не более LIMIT попыток за WINDOW с одного IP. In-memory, на один процесс. */
@Component
public class LoginRateLimiter {

    static final int LIMIT = 5;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void check(String ip) {
        Instant now = clock.instant();
        Deque<Instant> window = attempts.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) {
                window.pollFirst();
            }
            if (window.size() >= LIMIT) {
                throw new RateLimitExceededException();
            }
            window.addLast(now);
        }
    }

    public void reset() {
        attempts.clear();
    }
}
