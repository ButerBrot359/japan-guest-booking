package com.batowka.guestbooking.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

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
        LoginRateLimiter limiter = new LoginRateLimiter(new TestClock());

        for (int i = 0; i < 5; i++) {
            limiter.check("1.2.3.4");
        }

        assertThatThrownBy(() -> limiter.check("1.2.3.4"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void otherIpIsNotAffected() {
        LoginRateLimiter limiter = new LoginRateLimiter(new TestClock());

        for (int i = 0; i < 5; i++) {
            limiter.check("1.2.3.4");
        }

        assertThatCode(() -> limiter.check("5.6.7.8")).doesNotThrowAnyException();
    }

    @Test
    void windowSlidesAfterAMinute() {
        TestClock clock = new TestClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        for (int i = 0; i < 5; i++) {
            limiter.check("1.2.3.4");
        }
        clock.advance(Duration.ofSeconds(61));

        assertThatCode(() -> limiter.check("1.2.3.4")).doesNotThrowAnyException();
    }
}
