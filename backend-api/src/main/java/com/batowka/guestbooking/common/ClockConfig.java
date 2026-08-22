package com.batowka.guestbooking.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Бин {@link Clock} вынесен из SecurityConfig в отдельный класс: SecurityConfig теперь сам
 * зависит от RateLimitFilter -> SlidingWindowRateLimiter -> Clock, и метод-бин на SecurityConfig
 * создавал бы циклическую зависимость (клок нельзя получить, пока сам SecurityConfig не создан).
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
