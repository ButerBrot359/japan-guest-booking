package com.batowka.guestbooking.messaging;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaSmokeTest extends AbstractIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Test
    void sentMessageCanBeConsumed() {
        kafka.send("smoke-test-topic", "hello-kafka").join();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer(
                kafkaBootstrapServers(), "smoke-test-topic")) {
            assertThat(consumer.poll(Duration.ofSeconds(15)))
                    .contains("hello-kafka");
        }
    }
}
