package com.batowka.guestbooking.messaging;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxTest extends AbstractIntegrationTest {

    private static final AtomicInteger testCounter = new AtomicInteger(0);

    @Autowired
    OutboxWriter writer;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private String uniqueTopic() {
        return "outbox.test." + testCounter.incrementAndGet();
    }

    @BeforeEach
    void clearOutbox() {
        jdbc.execute("truncate table outbox restart identity");
    }

    @Test
    void writeOutsideTransactionIsRejected() {
        assertThatThrownBy(() ->
                writer.write("notifications.outbound", "WELCOME", Map.of("chat_id", 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void writtenEventIsPublishedExactlyOnce() {
        String topic = uniqueTopic();
        tx.executeWithoutResult(s ->
                writer.write(topic, "WELCOME",
                        Map.of("chat_id", 42, "name", "Маша")));

        publisher.publishPending();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer(
                kafkaBootstrapServers(), topic)) {
            List<String> messages = consumer.poll(Duration.ofSeconds(15));
            assertThat(messages).hasSize(1);
            tools.jackson.databind.JsonNode parsed = objectMapper.readTree(messages.getFirst());
            assertThat(parsed.get("event_type").asString()).isEqualTo("WELCOME");
            assertThat(parsed.get("event_id").asString()).isNotBlank();
            assertThat(parsed.get("occurred_at").asString()).isNotBlank();
            assertThat(parsed.get("payload").get("name").asString()).isEqualTo("Маша");
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where published_at is null", Integer.class))
                .isZero();

        // повторный прогон паблишера не должен слать дубликат
        publisher.publishPending();
        try (KafkaTestConsumer consumer = new KafkaTestConsumer(
                kafkaBootstrapServers(), topic)) {
            assertThat(consumer.poll(Duration.ofSeconds(5))).hasSize(1);
        }
    }

    @Test
    void stringValuesWithSpacesSurvivePublishingUnchanged() {
        String topic = uniqueTopic();
        tx.executeWithoutResult(s ->
                writer.write(topic, "WELCOME",
                        Map.of("chat_id", 43, "name", "Смирнов, Иван: старший")));

        publisher.publishPending();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer(
                kafkaBootstrapServers(), topic)) {
            List<String> messages = consumer.poll(Duration.ofSeconds(15));
            assertThat(messages).isNotEmpty();
            boolean found = false;
            for (String message : messages) {
                tools.jackson.databind.JsonNode parsed = objectMapper.readTree(message);
                String name = parsed.get("payload").get("name").asString();
                if ("Смирнов, Иван: старший".equals(name)) {
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    void poisonedRowIsSkippedAndQueueMovesOn() {
        jdbc.update("""
                insert into outbox(topic, event_type, payload, attempts)
                values ('bot-commands', 'POISON', '{"k":"v"}'::jsonb, %d)
                """.formatted(OutboxPublisher.MAX_ATTEMPTS));
        jdbc.update("""
                insert into outbox(topic, event_type, payload)
                values ('bot-commands', 'HEALTHY', '{"k":"v"}'::jsonb)
                """);

        // здоровая строка публикуется, ядовитая (attempts=MAX_ATTEMPTS) не блокирует её и остаётся неопубликованной
        org.awaitility.Awaitility.await().untilAsserted(() ->
                org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "select published_at is not null from outbox where event_type = 'HEALTHY'",
                        Boolean.class)).isTrue());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select published_at is null from outbox where event_type = 'POISON'",
                Boolean.class)).isTrue();
    }

    @Test
    void cleanupDeletesOldPublishedRowsOnly() {
        jdbc.update("""
                insert into outbox(topic, event_type, payload, published_at)
                values ('bot-commands', 'OLD_PUBLISHED', '{}'::jsonb, now() - interval '8 days')
                """);
        jdbc.update("""
                insert into outbox(topic, event_type, payload, published_at)
                values ('bot-commands', 'FRESH_PUBLISHED', '{}'::jsonb, now())
                """);
        jdbc.update("""
                insert into outbox(topic, event_type, payload, created_at)
                values ('bot-commands', 'OLD_UNPUBLISHED', '{}'::jsonb, now() - interval '30 days')
                """);

        publisher.cleanupPublished();

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                        "select event_type from outbox where event_type like 'OLD_%' or event_type like 'FRESH_%'",
                        String.class))
                .containsExactlyInAnyOrder("FRESH_PUBLISHED", "OLD_UNPUBLISHED");
    }
}
