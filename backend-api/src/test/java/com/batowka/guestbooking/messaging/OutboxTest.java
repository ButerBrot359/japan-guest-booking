package com.batowka.guestbooking.messaging;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxTest extends AbstractIntegrationTest {

    @Autowired
    OutboxWriter writer;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void writeOutsideTransactionIsRejected() {
        assertThatThrownBy(() ->
                writer.write("notifications.outbound", "WELCOME", Map.of("chat_id", 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void writtenEventIsPublishedExactlyOnce() {
        tx.executeWithoutResult(s ->
                writer.write("notifications.outbound", "WELCOME",
                        Map.of("chat_id", 42, "name", "Маша")));

        publisher.publishPending();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer(
                kafkaBootstrapServers(), "notifications.outbound")) {
            List<String> messages = consumer.poll(Duration.ofSeconds(15));
            assertThat(messages).hasSize(1);
            String envelope = messages.getFirst();
            assertThat(envelope).contains("\"event_type\":\"WELCOME\"");
            assertThat(envelope).contains("\"event_id\"");
            assertThat(envelope).contains("\"occurred_at\"");
            assertThat(envelope).contains("\"name\":\"Маша\"");
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where published_at is null", Integer.class))
                .isZero();

        // повторный прогон паблишера не должен слать дубликат
        publisher.publishPending();
        try (KafkaTestConsumer consumer = new KafkaTestConsumer(
                kafkaBootstrapServers(), "notifications.outbound")) {
            assertThat(consumer.poll(Duration.ofSeconds(5))).hasSize(1);
        }
    }
}
