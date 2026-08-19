package com.batowka.guestbooking.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Кладёт событие в outbox В ТЕКУЩЕЙ транзакции вызывающего —
     * бизнес-эффект и событие атомарны (transactional outbox).
     * Вне транзакции вызов запрещён (MANDATORY).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String topic, String eventType, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", UUID.randomUUID().toString());
        envelope.put("occurred_at", Instant.now().toString());
        envelope.put("event_type", eventType);
        envelope.put("payload", payload);
        jdbc.update("""
                insert into outbox(topic, event_type, payload)
                values (?, ?, ?::jsonb)
                """, topic, eventType, objectMapper.writeValueAsString(envelope));
    }
}
