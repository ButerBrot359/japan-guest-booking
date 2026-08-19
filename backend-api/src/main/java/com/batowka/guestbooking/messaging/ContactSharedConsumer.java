package com.batowka.guestbooking.messaging;

import com.batowka.guestbooking.auth.Phones;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContactSharedConsumer {

    private final JdbcTemplate jdbc;
    private final UserAccountRepository users;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "telegram.inbound", groupId = "backend-api")
    @Transactional
    public void onEvent(String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (RuntimeException e) {
            log.warn("Не-JSON сообщение в telegram.inbound, пропускаю");
            return;
        }
        JsonNode eventIdNode = envelope.get("event_id");
        JsonNode typeNode = envelope.get("event_type");
        if (eventIdNode == null || typeNode == null) {
            log.warn("Событие без event_id/event_type, пропускаю");
            return;
        }
        String eventId = eventIdNode.asString();
        Integer seen = jdbc.queryForObject(
                "select count(*) from processed_events where event_id = ?::uuid",
                Integer.class, eventId);
        if (seen != null && seen > 0) {
            return; // at-least-once: дубликат уже обработан
        }
        if ("CONTACT_SHARED".equals(typeNode.asString())) {
            handleContactShared(envelope.get("payload"));
        } else {
            log.info("Незнакомый event_type в telegram.inbound: {}",
                    typeNode.asString());
        }
        jdbc.update("insert into processed_events(event_id) values (?::uuid)", eventId);
    }

    private void handleContactShared(JsonNode payload) {
        if (payload == null) {
            log.warn("CONTACT_SHARED без payload, пропускаю");
            return;
        }
        JsonNode chatIdNode = payload.get("chat_id");
        JsonNode phoneNode = payload.get("phone");
        if (chatIdNode == null || phoneNode == null) {
            log.warn("CONTACT_SHARED без chat_id или phone, пропускаю");
            return;
        }
        long chatId = chatIdNode.asLong();
        String raw = phoneNode.asString();
        Optional<String> phone = Phones.normalize(raw.startsWith("+") ? raw : "+" + raw);
        if (phone.isEmpty()) {
            log.warn("CONTACT_SHARED с ненормализуемым телефоном, игнорирую");
            return;
        }
        users.findByPhone(phone.get()).ifPresent(user -> link(user, chatId));
        // телефона нет в белом списке — молча игнорируем (спека этапа 3, §3)
    }

    private void link(UserAccount user, long chatId) {
        if (user.getTelegramChatId() != null && user.getTelegramChatId() == chatId) {
            return; // повторный онбординг с тем же аккаунтом — без второго WELCOME
        }
        user.setTelegramChatId(chatId);
        users.save(user);
        outbox.write("notifications.outbound", "WELCOME",
                Map.of("chat_id", chatId, "name", user.getName()));
    }
}
