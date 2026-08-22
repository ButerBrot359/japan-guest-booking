package com.batowka.guestbooking.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Управляемые часы: тест сам двигает время. */
    private static class TestClock extends Clock {
        private Instant now = Instant.parse("2026-08-19T12:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    @Test
    void sixthAttemptWithinMinuteIsRejected() {
        var limiter = new SlidingWindowRateLimiter(new TestClock());
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("auth:1.2.3.4", 5, WINDOW)).isTrue();
        }
        assertThat(limiter.tryAcquire("auth:1.2.3.4", 5, WINDOW)).isFalse();
    }

    @Test
    void otherKeyIsNotAffected() {
        var limiter = new SlidingWindowRateLimiter(new TestClock());
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("auth:1.2.3.4", 5, WINDOW);
        }
        assertThat(limiter.tryAcquire("requests:1.2.3.4", 5, WINDOW)).isTrue();
    }

    @Test
    void windowSlidesAfterAMinute() {
        TestClock clock = new TestClock();
        var limiter = new SlidingWindowRateLimiter(clock);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("auth:1.2.3.4", 5, WINDOW);
        }
        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire("auth:1.2.3.4", 5, WINDOW)).isTrue();
    }

    @Test
    void evictEmptyRemovesIdleKeysAndKeepsActive() {
        TestClock clock = new TestClock();
        var limiter = new SlidingWindowRateLimiter(clock);
        limiter.tryAcquire("old:1", 5, WINDOW);
        clock.advance(Duration.ofMinutes(2));
        limiter.tryAcquire("fresh:2", 5, WINDOW);

        limiter.evictEmpty();

        // старый ключ выселен: с лимитом 1 попытка снова разрешена (счётчик обнулился)
        assertThat(limiter.tryAcquire("old:1", 1, WINDOW)).isTrue();
        // живой ключ счётчик сохранил: лимит 1 уже исчерпан
        assertThat(limiter.tryAcquire("fresh:2", 1, WINDOW)).isFalse();
    }
}
