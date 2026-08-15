package com.batowka.guestbooking;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
}
