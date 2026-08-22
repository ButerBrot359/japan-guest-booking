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

    static final int MAX_ATTEMPTS = 5;

    @Scheduled(fixedDelay = 2000)
    public void publishPending() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, topic, payload::text as payload
                from outbox where published_at is null and attempts < %d
                order by id limit 100
                """.formatted(MAX_ATTEMPTS));
        for (Map<String, Object> row : rows) {
            try {
                kafka.send((String) row.get("topic"), (String) row.get("payload")).join();
            } catch (Exception e) {
                Integer attempts = jdbc.queryForObject(
                        "update outbox set attempts = attempts + 1 where id = ? returning attempts",
                        Integer.class, row.get("id"));
                if (attempts != null && attempts >= MAX_ATTEMPTS) {
                    log.error("Outbox id={} исчерпал {} попыток — строка пропущена, нужен ручной разбор",
                            row.get("id"), MAX_ATTEMPTS, e);
                } else {
                    log.warn("Kafka недоступна, отправка outbox id={} отложена", row.get("id"), e);
                }
                return; // остальные строки подождут следующего цикла — порядок сохраняется
            }
            jdbc.update("update outbox set published_at = now() where id = ?", row.get("id"));
        }
    }

    /** Опубликованные строки старше 7 дней не нужны (в payload бывают сырые OTP-коды). */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    public void cleanupPublished() {
        int deleted = jdbc.update("delete from outbox where published_at < now() - interval '7 days'");
        if (deleted > 0) {
            log.info("Outbox: удалено {} опубликованных строк старше 7 дней", deleted);
        }
    }
}
