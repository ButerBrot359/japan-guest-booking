package com.batowka.guestbooking.accessrequest;

import com.batowka.guestbooking.auth.InvalidPhoneException;
import com.batowka.guestbooking.auth.Phones;
import com.batowka.guestbooking.messaging.OutboxWriter;
import com.batowka.guestbooking.user.AlreadyMemberException;
import com.batowka.guestbooking.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AccessRequestService {

    private final AccessRequestRepository requests;
    private final UserAccountRepository users;
    private final OutboxWriter outbox;
    private final JdbcTemplate jdbc;

    @Transactional
    public void submit(String rawPhone, String name, String message) {
        String phone = Phones.normalize(rawPhone).orElseThrow(InvalidPhoneException::new);
        if (users.findByPhoneAndDeletedAtIsNull(phone).isPresent()) {
            throw new AlreadyMemberException();
        }
        if (requests.existsByPhoneAndStatus(phone, AccessRequestStatus.PENDING)) {
            return; // заявка уже ждёт решения — не плодим и не спамим админа
        }
        AccessRequest r = new AccessRequest();
        r.setPhone(phone);
        r.setName(name);
        r.setMessage(message);
        try {
            requests.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            // проиграли гонку с параллельным POST на тот же телефон (частичный уникальный
            // индекс на PENDING) — проигравший гонку получает тот же идемпотентный успех,
            // что и повтор; событие уже отправил победитель, второй раз слать не нужно
            return;
        }
        // уведомление админу той же транзакцией; без привязанного TG — просто некому слать
        jdbc.query("""
                select telegram_chat_id from users
                where role = 'ADMIN' and telegram_chat_id is not null
                """, rs -> {
            outbox.write("notifications.outbound", "ACCESS_REQUEST_RECEIVED", Map.of(
                    "chat_id", rs.getLong(1),
                    "name", name,
                    "phone", phone,
                    "message", message == null ? "" : message));
        });
    }
}
