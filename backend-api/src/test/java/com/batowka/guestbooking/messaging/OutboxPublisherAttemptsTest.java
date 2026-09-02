package com.batowka.guestbooking.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherAttemptsTest {

    @Test
    @SuppressWarnings("unchecked")
    void failedSendIncrementsAttempts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", 1L, "topic", "t", "payload", "{}")));
        when(kafka.send(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("кафка упала")));
        when(jdbc.queryForObject(contains("attempts = attempts + 1"), eq(Integer.class), eq(1L)))
                .thenReturn(1);

        new OutboxPublisher(jdbc, kafka).publishPending();

        // счётчик инкрементнулся, published_at не трогали
        verify(jdbc).queryForObject(contains("attempts = attempts + 1"), eq(Integer.class), eq(1L));
        verify(jdbc, org.mockito.Mockito.never()).update(contains("published_at"), org.mockito.ArgumentMatchers.<Object>any());
    }
}
