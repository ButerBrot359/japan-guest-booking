package com.batowka.guestbooking.accessrequest;

import com.batowka.guestbooking.auth.InvalidPhoneException;
import com.batowka.guestbooking.auth.Phones;
import com.batowka.guestbooking.messaging.OutboxWriter;
import com.batowka.guestbooking.user.AlreadyMemberException;
import com.batowka.guestbooking.user.UserAccountRepository;
import com.batowka.guestbooking.user.WhitelistService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AccessRequestService {

    private final AccessRequestRepository requests;
    private final UserAccountRepository users;
    private final OutboxWriter outbox;
    private final JdbcTemplate jdbc;
    private final WhitelistService whitelist;
    private final Clock clock;

    @Transactional
    public void submit(String rawPhone, String name, String message) {
        String phone = Phones.normalize(rawPhone).orElseThrow(InvalidPhoneException::new);
        if (users.findByPhoneAndDeletedAtIsNull(phone).isPresent()) {
            throw new AlreadyMemberException();
        }
        // saveAndFlush+catch тут не работает: JPA-эксепшен при flush метит транзакцию
        // rollback-only на уровне Hibernate ещё ДО catch — commit падает с
        // UnexpectedRollbackException независимо от пойманного исключения. Поэтому
        // upsert без исключений: проигравший гонку и повторная заявка — один и тот же
        // идемпотентный путь, вставка молча не происходит, событие не пишется.
        java.util.List<Long> ids = jdbc.queryForList("""
                insert into access_requests(phone, name, message)
                values (?, ?, ?)
                on conflict (phone) where status = 'PENDING' do nothing
                returning id
                """, Long.class, phone, name, message);
        if (ids.isEmpty()) {
            return; // заявка уже ждёт решения (или проиграна гонка) — не плодим и не спамим админа
        }
        long requestId = ids.get(0);
        // уведомление админу той же транзакцией; без привязанного TG — просто некому слать
        jdbc.query("""
                select telegram_chat_id from users
                where role = 'ADMIN' and telegram_chat_id is not null
                """, rs -> {
            outbox.write("notifications.outbound", "ACCESS_REQUEST_RECEIVED", Map.of(
                    "chat_id", rs.getLong(1),
                    "request_id", requestId,
                    "name", name,
                    "phone", phone,
                    "message", message == null ? "" : message));
        });
    }

    @Transactional(readOnly = true)
    public java.util.List<AccessRequest> list(AccessRequestStatus status) {
        return requests.findAllByStatusOrderByIdDesc(status);
    }

    /** Одобрение: заявка APPROVED + человек в белом списке (создание или реактивация). */
    @Transactional
    public void approve(long id) {
        AccessRequest r = resolve(id, AccessRequestStatus.APPROVED);
        boolean live = users.findByPhoneAndDeletedAtIsNull(r.getPhone()).isPresent();
        if (!live) {
            whitelist.addNormalized(r.getPhone(), r.getName());
        }
        // уведомления новичку нет: его TG неизвестен — владелец скажет сам (спека §4)
    }

    @Transactional
    public void reject(long id) {
        resolve(id, AccessRequestStatus.REJECTED);
    }

    private AccessRequest resolve(long id, AccessRequestStatus target) {
        AccessRequest r = requests.findById(id)
                .orElseThrow(AccessRequestNotFoundException::new);
        // смена статуса — только атомарным UPDATE с ожидаемым статусом:
        // параллельный второй resolve получает updated == 0 и честный 409
        int updated = jdbc.update("""
                update access_requests set status = ?, resolved_at = ?
                where id = ? and status = 'PENDING'
                """, target.name(), java.sql.Timestamp.from(clock.instant()), id);
        if (updated == 0) {
            throw new AlreadyResolvedException();
        }
        return r;
    }
}
