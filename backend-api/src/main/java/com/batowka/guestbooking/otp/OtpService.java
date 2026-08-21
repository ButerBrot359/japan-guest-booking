package com.batowka.guestbooking.otp;

import com.batowka.guestbooking.messaging.OutboxWriter;
import com.batowka.guestbooking.user.UserAccount;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
public class OtpService {

    static final int MAX_ATTEMPTS = 3;
    static final Duration TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();
    private final TransactionTemplate requiresNew;

    public OtpService(JdbcTemplate jdbc, PasswordEncoder encoder, OutboxWriter outbox,
                      ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record ChallengeResult(String action, JsonNode payload) {
    }

    /** Выпускает код: вытесняет старые челленджи гостя, пишет OTP_CODE в outbox. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issue(UserAccount user, String action, Map<String, Object> payload) {
        jdbc.update("""
                update otp_challenges set status = 'EXPIRED'
                where user_id = ? and status = 'PENDING'
                """, user.getId());
        String code = String.format("%06d", random.nextInt(1_000_000));
        jdbc.update("""
                insert into otp_challenges(user_id, action, payload, code_hash, expires_at)
                values (?, ?, ?::jsonb, ?, now() + interval '5 minutes')
                """, user.getId(), action,
                objectMapper.writeValueAsString(payload), encoder.encode(code));
        outbox.write("notifications.outbound", "OTP_CODE", Map.of(
                "chat_id", user.getTelegramChatId(),
                "code", code,
                "action", action,
                "expires_at", Instant.now().plus(TTL).toString()));
    }

    /** Проверяет код активного челленджа гостя по типу действия (вход — action LOGIN). */
    @Transactional(propagation = Propagation.MANDATORY)
    public ChallengeResult verifyByAction(Long userId, String action, String code) {
        return verifyRow(findActiveByAction(userId, action), code);
    }

    private ChallengeResult verifyRow(Map<String, Object> row, String code) {
        long id = ((Number) row.get("id")).longValue();
        if (((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())) {
            expireInNewTx(id);
            throw new InvalidCodeException();
        }
        // инкремент ДО сравнения: параллельный перебор не обходит счётчик
        Integer attempts = requiresNew.execute(s -> jdbc.queryForObject(
                "update otp_challenges set attempts = coalesce(attempts, 0) + 1 where id = ? returning attempts",
                Integer.class, id));
        if (attempts != null && attempts > MAX_ATTEMPTS) {
            expireInNewTx(id);
            throw new CodeExpiredException();
        }
        if (!encoder.matches(code, (String) row.get("code_hash"))) {
            if (attempts != null && attempts >= MAX_ATTEMPTS) {
                expireInNewTx(id);
                throw new CodeExpiredException();
            }
            throw new InvalidCodeException();
        }
        jdbc.update("update otp_challenges set status = 'USED' where id = ?", id);
        return new ChallengeResult((String) row.get("action"),
                objectMapper.readTree((String) row.get("payload")));
    }

    private Map<String, Object> findActiveByAction(Long userId, String action) {
        try {
            return jdbc.queryForMap("""
                    select id, action, payload::text as payload, code_hash,
                           expires_at, created_at
                    from otp_challenges
                    where user_id = ? and status = 'PENDING' and action = ?
                    """, userId, action);
        } catch (EmptyResultDataAccessException e) {
            throw new NoActiveCodeException();
        }
    }

    private void expireInNewTx(long id) {
        requiresNew.executeWithoutResult(s ->
                jdbc.update("update otp_challenges set status = 'EXPIRED' where id = ?", id));
    }
}
