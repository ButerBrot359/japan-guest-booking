package com.batowka.guestbooking.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Топики создаются при старте приложения (KafkaAdmin), а не лениво при первой записи. */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic notificationsOutbound() {
        return TopicBuilder.name("notifications.outbound").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic telegramInbound() {
        return TopicBuilder.name("telegram.inbound").partitions(1).replicas(1).build();
    }
}
