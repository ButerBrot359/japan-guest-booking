package com.batowka.guestbooking.messaging;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Топики должны существовать сразу после старта приложения (KafkaAdmin bean-топики),
 * а не создаваться лениво при первой публикации — иначе консьюмеры, стартовавшие
 * раньше продюсера, молча висят без партиций (баг, найденный живым смоуком).
 */
class KafkaTopicsTest extends AbstractIntegrationTest {

    @Test
    void applicationStartupCreatesRequiredTopics() throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());

        try (AdminClient admin = AdminClient.create(props)) {
            Set<String> topics = admin.listTopics().names().get(10, TimeUnit.SECONDS);

            assertThat(topics)
                    .contains("notifications.outbound", "telegram.inbound");
        }
    }
}
