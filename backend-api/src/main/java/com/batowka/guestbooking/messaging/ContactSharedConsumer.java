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
        JsonNode envelope = objectMapper.readTree(message);
        String eventId = envelope.get("event_id").asText();
        Integer seen = jdbc.queryForObject(
                "select count(*) from processed_events where event_id = ?::uuid",
                Integer.class, eventId);
        if (seen != null && seen > 0) {
            return; // at-least-once: дубликат уже обработан
        }
        if ("CONTACT_SHARED".equals(envelope.get("event_type").asText())) {
            handleContactShared(envelope.get("payload"));
        } else {
            log.info("Незнакомый event_type в telegram.inbound: {}",
                    envelope.get("event_type").asText());
        }
        jdbc.update("insert into processed_events(event_id) values (?::uuid)", eventId);
    }

    private void handleContactShared(JsonNode payload) {
        long chatId = payload.get("chat_id").asLong();
        String raw = payload.get("phone").asText();
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
