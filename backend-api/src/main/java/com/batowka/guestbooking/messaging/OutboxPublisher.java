package com.batowka.guestbooking.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    @Scheduled(fixedDelay = 2000)
    public void publishPending() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, topic, payload::text as payload
                from outbox where published_at is null
                order by id limit 100
                """);
        for (Map<String, Object> row : rows) {
            try {
                kafka.send((String) row.get("topic"), (String) row.get("payload")).join();
            } catch (Exception e) {
                log.warn("Kafka недоступна, отправка outbox id={} отложена", row.get("id"), e);
                return; // остальные строки подождут следующего цикла — порядок сохраняется
            }
            jdbc.update("update outbox set published_at = now() where id = ?", row.get("id"));
        }
    }
}
