package com.batowka.guestbooking.messaging;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ContactSharedConsumerTest extends AbstractIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    private Long givenFriend(String phone) {
        return jdbc.queryForObject(
                "insert into users(phone, name) values (?, 'Маша') returning id",
                Long.class, phone);
    }

    private String contactShared(String eventId, long chatId, String phone) {
        return """
                {"event_id": "%s", "occurred_at": "2026-08-19T12:00:00Z",
                 "event_type": "CONTACT_SHARED",
                 "payload": {"chat_id": %d, "phone": "%s", "telegram_username": "masha"}}
                """.formatted(eventId, chatId, phone);
    }

    private void sendAndAwaitProcessed(String eventId, long chatId, String phone) {
        kafka.send("telegram.inbound", contactShared(eventId, chatId, phone)).join();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "select count(*) from processed_events where event_id = ?::uuid",
                        Integer.class, eventId)).isEqualTo(1));
    }

    @Test
    void knownPhoneGetsLinkedAndWelcomed() {
        Long id = givenFriend("+81300000001");

        // телефон от Telegram приходит без плюса — нормализация обязана справиться
        sendAndAwaitProcessed(UUID.randomUUID().toString(), 555001L, "81300000001");

        assertThat(jdbc.queryForObject(
                "select telegram_chat_id from users where id = ?", Long.class, id))
                .isEqualTo(555001L);
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox
                where event_type = 'WELCOME' and payload::text like '%555001%'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void duplicateEventIdHasSingleEffect() {
        givenFriend("+81300000002");
        String eventId = UUID.randomUUID().toString();

        sendAndAwaitProcessed(eventId, 555002L, "81300000002");
        kafka.send("telegram.inbound", contactShared(eventId, 555002L, "81300000002")).join();

        // событие с тем же event_id второй раз эффекта не даёт
        await().during(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "select count(*) from outbox where event_type = 'WELCOME'",
                        Integer.class)).isEqualTo(1));
    }

    @Test
    void unknownPhoneIsSilentlyIgnored() {
        sendAndAwaitProcessed(UUID.randomUUID().toString(), 555003L, "81999999999");

        assertThat(jdbc.queryForObject(
                "select count(*) from users where telegram_chat_id = 555003",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'WELCOME'",
                Integer.class)).isZero();
    }

    @Test
    void reonboardingSameChatIdSendsNoSecondWelcome() {
        givenFriend("+81300000004");

        sendAndAwaitProcessed(UUID.randomUUID().toString(), 555004L, "81300000004");
        sendAndAwaitProcessed(UUID.randomUUID().toString(), 555004L, "81300000004");

        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'WELCOME'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void reonboardingWithNewChatIdRelinksAndWelcomesAgain() {
        Long id = givenFriend("+81300000005");

        sendAndAwaitProcessed(UUID.randomUUID().toString(), 555005L, "81300000005");
        sendAndAwaitProcessed(UUID.randomUUID().toString(), 555006L, "81300000005");

        assertThat(jdbc.queryForObject(
                "select telegram_chat_id from users where id = ?", Long.class, id))
                .isEqualTo(555006L);
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'WELCOME'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void malformedMessageIsSkippedAndNextOneProcessed() throws Exception {
        givenFriend("+81300000009");

        kafka.send("telegram.inbound", "{\"мусор\": true}").join();
        kafka.send("telegram.inbound", "вообще не json").join();
        sendAndAwaitProcessed(UUID.randomUUID().toString(), 555009L, "81300000009");

        assertThat(jdbc.queryForObject(
                "select telegram_chat_id from users where phone = '+81300000009'",
                Long.class)).isEqualTo(555009L);
    }
}
