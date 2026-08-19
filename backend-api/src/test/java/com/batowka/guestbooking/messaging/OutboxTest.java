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
            assertThat(parsed.get("event_type").asText()).isEqualTo("WELCOME");
            assertThat(parsed.get("event_id").asText()).isNotBlank();
            assertThat(parsed.get("occurred_at").asText()).isNotBlank();
            assertThat(parsed.get("payload").get("name").asText()).isEqualTo("Маша");
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
                String name = parsed.get("payload").get("name").asText();
                if ("Смирнов, Иван: старший".equals(name)) {
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        }
    }
}
