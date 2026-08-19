package com.batowka.guestbooking;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
public abstract class AbstractIntegrationTest {

    // Singleton-контейнер: стартует один раз на JVM, JUnit его не останавливает
    // (@Testcontainers гасил бы его после каждого тест-класса, ломая кэш Spring-контекста).
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectProvider<com.batowka.guestbooking.auth.AdminSeeder> adminSeeder;

    @Autowired
    private ObjectProvider<com.batowka.guestbooking.auth.LoginRateLimiter> rateLimiter;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE bookings, blocked_periods, otp_challenges,
                    access_requests, outbox, processed_events, users
                    RESTART IDENTITY CASCADE
                """);
        adminSeeder.ifAvailable(com.batowka.guestbooking.auth.AdminSeeder::seed);
        rateLimiter.ifAvailable(com.batowka.guestbooking.auth.LoginRateLimiter::reset);
    }
}
