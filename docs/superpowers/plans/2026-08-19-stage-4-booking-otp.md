# Этап 4: Бронирование с OTP — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Гость создаёт/подтверждает кодом из Telegram/переносит/отменяет свою бронь; уведомления гостю и админу; фоновая чистка протухших броней.

**Architecture:** `OtpService` (JdbcTemplate поверх `otp_challenges`, BCrypt-хеш кода, код уходит событием `OTP_CODE` через outbox) + `BookingService` (все переходы статусов — атомарные `UPDATE ... WHERE status`, гонки решает БД: EXCLUDE → 409). Перенос — вариант A: даты не удерживаются до подтверждения. Замена активной брони: create предупреждает, confirm атомарно отменяет старую и подтверждает новую. Бот: доставка «offset только после успешной отправки».

**Tech Stack:** как в этапах 1-3 (Boot 4.0.7/Maven/Testcontainers; Go 1.23/kafka-go).

**Spec:** `docs/specs/2026-08-19-stage-4-booking-otp-design.md` (родительская: `docs/specs/2026-08-13-japan-guest-booking-design.md` §3.2/§5/§6/§8).

## Global Constraints

- Формат ошибок `{"code","message"}`. Коды этапа: 409 `DATES_TAKEN`/`OVERLAPS_OWN_BOOKING`/`TELEGRAM_NOT_LINKED`/`BOOKING_EXPIRED`; 400 `INVALID_CODE`/`CODE_EXPIRED`/`NO_ACTIVE_CODE`/`VALIDATION_ERROR`; 429 `RESEND_TOO_SOON`; 403 `FORBIDDEN` (чужая бронь).
- OTP: 6 цифр SecureRandom; в БД только BCrypt-хеш; TTL 5 минут; 3 попытки, инкремент атомарно ДО сравнения; resend ≤1/мин; у гостя один активный челлендж (новый вытесняет старый); payload всегда содержит `booking_id`; код не логируется.
- Переходы статусов брони — ТОЛЬКО `UPDATE ... WHERE status = <ожидаемый>`; 0 строк = проигранная гонка, честная ошибка.
- Уведомления — только через `OutboxWriter` в той же транзакции, что бизнес-эффект.
- TDD; пакет `com.batowka.guestbooking`; Jackson 3 (`tools.jackson`), новые вызовы — `asString()` (не deprecated `asText()`).
- Go: единственная зависимость kafka-go; gofmt/vet чисты.
- «Класс/поле не резолвится» → искать переехавшее имя в кэшах (~/.m2, ~/go/pkg/mod), не менять подход; адаптации — в отчёт.

---

### Task 1: Миграция V2 — триггер updated_at

**Files:**
- Create: `backend-api/src/main/resources/db/migration/V2__bookings_updated_at_trigger.sql`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/db/UpdatedAtTriggerTest.java`

**Interfaces:**
- Consumes: таблица `bookings` (V1), `AbstractIntegrationTest`.
- Produces: `updated_at` меняется при любом UPDATE строки bookings (Tasks 5-7 на это полагаются для аудита).

- [ ] **Step 1: Красный тест**

```java
package com.batowka.guestbooking.db;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class UpdatedAtTriggerTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void updatedAtChangesOnEveryUpdate() {
        Long userId = jdbc.queryForObject(
                "insert into users(phone, name) values ('+81700000001', 'Маша') returning id",
                Long.class);
        Long bookingId = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-05-01', '2027-05-05', 'PENDING_OTP') returning id
                """, Long.class, userId);
        OffsetDateTime before = jdbc.queryForObject(
                "select updated_at from bookings where id = ?", OffsetDateTime.class, bookingId);

        jdbc.update("update bookings set status = 'CONFIRMED' where id = ?", bookingId);

        OffsetDateTime after = jdbc.queryForObject(
                "select updated_at from bookings where id = ?", OffsetDateTime.class, bookingId);
        assertThat(after).isAfter(before);
    }
}
```

- [ ] **Step 2: Убедиться в падении**

Run: `cd backend-api && ./mvnw test -Dtest=UpdatedAtTriggerTest`
Expected: FAIL — `after` равен `before` (updated_at ставится только DEFAULT'ом при insert).

- [ ] **Step 3: Миграция**

`V2__bookings_updated_at_trigger.sql`:

```sql
-- Перенос из финального ревью этапа 1: updated_at должен отражать реальные
-- изменения. Триггер надёжнее @UpdateTimestamp — покрывает и не-JPA записи.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER bookings_set_updated_at
    BEFORE UPDATE ON bookings
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
```

- [ ] **Step 4: Зелёный + полный suite**

Run: `cd backend-api && ./mvnw test -Dtest=UpdatedAtTriggerTest` → PASS; затем `./mvnw test` (55).

Замечание: `after.isAfter(before)` требует, чтобы `now()` изменился между
insert и update — в одной транзакции Postgres `now()` фиксирован на момент
начала транзакции, но тест НЕ транзакционный (JdbcTemplate, автокоммит),
каждый statement — своя транзакция, время различается. Если тест окажется
флаковым по точности часов — STOP и доложить (не подгонять sleep'ом).

- [ ] **Step 5: Commit**

```bash
git add backend-api/src
git commit -m "feat: V2 — триггер updated_at на bookings"
```

---

### Task 2: Hardening-переносы — валидация конверта, asString, @Column(length)

**Files:**
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/messaging/ContactSharedConsumer.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/booking/Booking.java`, `.../calendar/BlockedPeriod.java`, `.../user/UserAccount.java`
- Modify (замена asText→asString): `backend-api/src/test/java/com/batowka/guestbooking/messaging/OutboxTest.java` и все прочие места по grep
- Test: дополнение `backend-api/src/test/java/com/batowka/guestbooking/messaging/ContactSharedConsumerTest.java`

**Interfaces:**
- Consumes: консьюмер этапа 3.
- Produces: битое сообщение в `telegram.inbound` логируется и пропускается (offset коммитится), не роняя листенер; JSON-строки читаются `asString()`.

- [ ] **Step 1: Красный тест (добавить в ContactSharedConsumerTest)**

```java
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
```

Run: `./mvnw test -Dtest=ContactSharedConsumerTest` — новый тест должен
УПАСТЬ по таймауту await (первое же битое сообщение роняет листенер в
цикл ретраев, до валидного он не доходит быстро) либо показать иное
поведение — зафиксировать фактическое в отчёте.

- [ ] **Step 2: Валидация конверта + asString**

В `ContactSharedConsumer.onEvent` — до дедупа:

```java
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
```

и в `handleContactShared` — null-проверки `payload`, `chat_id`, `phone`
(отсутствуют → log.warn + return). Все `asText()` в main+test коде заменить
на `asString()` (grep `asText` по backend-api — после замены пусто).

`@Column`-длины: `Booking.comment` → `@Column(length = 500)`;
`BlockedPeriod.reason` → `@Column(length = 200)`; `UserAccount.phone` →
`@Column(nullable = false, unique = true, length = 20)`, `name` →
`@Column(nullable = false, length = 100)`.

- [ ] **Step 3: Зелёный + полный suite дважды**

`./mvnw test -Dtest=ContactSharedConsumerTest` → PASS (6/6); `./mvnw test`
и `./mvnw cleanTest test` — зелёные, вывод без deprecation-warning'ов asText.

- [ ] **Step 4: Commit**

```bash
git add backend-api/src
git commit -m "fix: валидация конверта в консьюмере, asString вместо deprecated asText, длины колонок"
```

---

### Task 3: OtpService

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/otp/OtpService.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/otp/InvalidCodeException.java`, `.../otp/CodeExpiredException.java`, `.../otp/NoActiveCodeException.java`, `.../otp/ResendTooSoonException.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/otp/OtpServiceTest.java`

**Interfaces:**
- Consumes: `OutboxWriter` (MANDATORY), `PasswordEncoder`, таблица `otp_challenges` (V1).
- Produces (для Tasks 4-7):
  - `void issue(UserAccount user, String action, Map<String, Object> payload)` — MANDATORY tx; payload обязан содержать `booking_id`; вытесняет старые PENDING гостя; пишет `OTP_CODE` в outbox.
  - `ChallengeResult verify(Long userId, long bookingId, String code)` — MANDATORY tx; `record ChallengeResult(String action, JsonNode payload)`.
  - `void resend(UserAccount user, long bookingId)` — MANDATORY tx; 429 при <1 мин; перевыпускает тот же action/payload.
  - Исключения: `InvalidCodeException`(400 INVALID_CODE), `CodeExpiredException`(400 CODE_EXPIRED), `NoActiveCodeException`(400 NO_ACTIVE_CODE), `ResendTooSoonException`(429 RESEND_TOO_SOON).

- [ ] **Step 1: Красный тест**

```java
package com.batowka.guestbooking.otp;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpServiceTest extends AbstractIntegrationTest {

    @Autowired OtpService otp;
    @Autowired UserAccountRepository users;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private UserAccount guest;

    @BeforeEach
    void createGuest() {
        UserAccount u = new UserAccount();
        u.setPhone("+81310000001");
        u.setName("Маша");
        u.setTelegramChatId(777001L);
        guest = users.save(u);
    }

    private void issue(long bookingId) {
        tx.executeWithoutResult(s ->
                otp.issue(guest, "CREATE_BOOKING", Map.of("booking_id", bookingId)));
    }

    @Autowired
    tools.jackson.databind.ObjectMapper objectMapper;

    private String issuedCode() {
        // код не хранится — достаём из события OTP_CODE в outbox.
        // ВАЖНО: парсим JSON, НЕ регулярки/подстроки — jsonb нормализует
        // форматирование (грабля этапа 3).
        String envelope = jdbc.queryForObject("""
                select payload::text from outbox where event_type = 'OTP_CODE'
                order by id desc limit 1
                """, String.class);
        String code = objectMapper.readTree(envelope).get("payload").get("code").asString();
        assertThat(code).hasSize(6);
        return code;
    }

    @Test
    void issueCreatesChallengeAndOtpEvent() {
        issue(101L);

        assertThat(jdbc.queryForObject("""
                select count(*) from otp_challenges
                where user_id = ? and status = 'PENDING' and code_hash is not null
                """, Integer.class, guest.getId())).isEqualTo(1);
        assertThat(issuedCode()).hasSize(6);
        // сам код в БД не лежит
        assertThat(jdbc.queryForObject(
                "select code_hash from otp_challenges order by id desc limit 1", String.class))
                .isNotEqualTo(issuedCode());
    }

    @Test
    void correctCodeVerifiesAndUsesChallenge() {
        issue(102L);
        String code = issuedCode();

        var result = tx.execute(s -> otp.verify(guest.getId(), 102L, code));

        assertThat(result.action()).isEqualTo("CREATE_BOOKING");
        assertThat(result.payload().get("booking_id").asLong()).isEqualTo(102L);
        assertThat(jdbc.queryForObject(
                "select status from otp_challenges order by id desc limit 1", String.class))
                .isEqualTo("USED");
    }

    @Test
    void thirdWrongAttemptExpiresChallenge() {
        issue(103L);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                    otp.verify(guest.getId(), 103L, "000000")))
                    .isInstanceOf(InvalidCodeException.class);
        }
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                otp.verify(guest.getId(), 103L, "000000")))
                .isInstanceOf(CodeExpiredException.class);
        // после исчерпания даже верный код не работает
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                otp.verify(guest.getId(), 103L, issuedCode())))
                .isInstanceOf(NoActiveCodeException.class);
    }

    @Test
    void expiredChallengeRejectsAnyCode() {
        issue(104L);
        String code = issuedCode();
        jdbc.update("update otp_challenges set expires_at = now() - interval '1 second'");

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                otp.verify(guest.getId(), 104L, code)))
                .isInstanceOf(InvalidCodeException.class);
    }

    @Test
    void newIssueEvictsOldChallenge() {
        issue(105L);
        issue(105L);

        assertThat(jdbc.queryForObject(
                "select count(*) from otp_challenges where status = 'PENDING'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void resendWithinMinuteIsRejectedAfterMinuteAllowed() {
        issue(106L);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> otp.resend(guest, 106L)))
                .isInstanceOf(ResendTooSoonException.class);

        jdbc.update("update otp_challenges set created_at = now() - interval '2 minutes'");
        tx.executeWithoutResult(s -> otp.resend(guest, 106L));

        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'OTP_CODE'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void codeForAnotherBookingIsNotAccepted() {
        issue(107L);
        String code = issuedCode();

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                otp.verify(guest.getId(), 999L, code)))
                .isInstanceOf(NoActiveCodeException.class);
    }
}
```

- [ ] **Step 2: Убедиться в падении**

Run: `cd backend-api && ./mvnw test -Dtest=OtpServiceTest` → COMPILE FAIL.

- [ ] **Step 3: Реализовать**

Исключения — по образцу существующих (`RuntimeException` с русским
сообщением): `InvalidCodeException("Неверный код")`,
`CodeExpiredException("Код недействителен, запроси новый")`,
`NoActiveCodeException("Нет активного кода для этой брони")`,
`ResendTooSoonException("Код уже отправлен — подожди минуту")`.

`otp/OtpService.java`:

```java
package com.batowka.guestbooking.otp;

import com.batowka.guestbooking.messaging.OutboxWriter;
import com.batowka.guestbooking.user.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OtpService {

    static final int MAX_ATTEMPTS = 3;
    static final Duration TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

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

    /** Проверяет код активного челленджа гостя для конкретной брони. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ChallengeResult verify(Long userId, long bookingId, String code) {
        Map<String, Object> row = findActive(userId, bookingId);
        long id = ((Number) row.get("id")).longValue();
        if (((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())) {
            expire(id);
            throw new InvalidCodeException();
        }
        // инкремент ДО сравнения: параллельный перебор не обходит счётчик
        Integer attempts = jdbc.queryForObject(
                "update otp_challenges set attempts = attempts + 1 where id = ? returning attempts",
                Integer.class, id);
        if (attempts != null && attempts > MAX_ATTEMPTS) {
            expire(id);
            throw new CodeExpiredException();
        }
        if (!encoder.matches(code, (String) row.get("code_hash"))) {
            if (attempts != null && attempts >= MAX_ATTEMPTS) {
                expire(id);
                throw new CodeExpiredException();
            }
            throw new InvalidCodeException();
        }
        jdbc.update("update otp_challenges set status = 'USED' where id = ?", id);
        return new ChallengeResult((String) row.get("action"),
                objectMapper.readTree((String) row.get("payload")));
    }

    /** Перевыпуск кода той же операции; не чаще раза в минуту. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resend(UserAccount user, long bookingId) {
        Map<String, Object> row = findActive(user.getId(), bookingId);
        Instant createdAt = ((java.sql.Timestamp) row.get("created_at")).toInstant();
        if (createdAt.isAfter(Instant.now().minus(Duration.ofMinutes(1)))) {
            throw new ResendTooSoonException();
        }
        JsonNode payload = objectMapper.readTree((String) row.get("payload"));
        issue(user, (String) row.get("action"),
                objectMapper.convertValue(payload, Map.class));
    }

    private Map<String, Object> findActive(Long userId, long bookingId) {
        try {
            return jdbc.queryForMap("""
                    select id, action, payload::text as payload, code_hash,
                           expires_at, created_at
                    from otp_challenges
                    where user_id = ? and status = 'PENDING'
                      and (payload->>'booking_id')::bigint = ?
                    """, userId, bookingId);
        } catch (EmptyResultDataAccessException e) {
            throw new NoActiveCodeException();
        }
    }

    private void expire(long id) {
        jdbc.update("update otp_challenges set status = 'EXPIRED' where id = ?", id);
    }
}
```

В `GlobalExceptionHandler` — четыре хендлера:
`InvalidCodeException`→400 INVALID_CODE; `CodeExpiredException`→400
CODE_EXPIRED; `NoActiveCodeException`→400 NO_ACTIVE_CODE;
`ResendTooSoonException`→429 RESEND_TOO_SOON (по образцу RateLimit).

- [ ] **Step 4: Зелёный + полный suite**

`./mvnw test -Dtest=OtpServiceTest` → 7/7; `./mvnw test` целиком.

- [ ] **Step 5: Commit**

```bash
git add backend-api/src
git commit -m "feat: OtpService — выпуск, проверка с лимитом попыток, resend-лимит"
```

---

### Task 4: Создание брони — POST /api/bookings

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java`, `.../booking/BookingController.java`
- Create: исключения `.../booking/DatesTakenException.java`, `OverlapsOwnBookingException.java`, `TelegramNotLinkedException.java`, `NotYourBookingException.java`, `BookingExpiredException.java`
- Modify: `.../booking/BookingRepository.java` (`findByUserIdAndStatus`), `common/GlobalExceptionHandler.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/booking/CreateBookingTest.java`

**Interfaces:**
- Consumes: `OtpService.issue`, `JwtService`/`JwtAuthFilter` (авторизация как в MeControllerTest), `BookingRepository`.
- Produces: `POST /api/bookings {checkIn, checkOut, comment?}` → `200 {bookingId, willReplaceBooking: {id, checkIn, checkOut} | null}`; ошибки по Global Constraints. `BookingService.create(...)`; хелпер тестов `authCookie(userId)` — Tasks 5-7 переиспользуют класс-паттерн. Новые исключения+хендлеры: DatesTaken(409), OverlapsOwnBooking(409), TelegramNotLinked(409), NotYourBooking(403 FORBIDDEN), BookingExpired(409).

- [ ] **Step 1: Красный тест**

```java
package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.auth.JwtAuthFilter;
import com.batowka.guestbooking.auth.JwtService;
import com.batowka.guestbooking.user.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CreateBookingTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Long guest(String phone, Long chatId) {
        return jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, 'Маша', ?) returning id",
                Long.class, phone, chatId);
    }

    private Cookie auth(Long userId) {
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(userId, Role.FRIEND));
    }

    private String body(String in, String out) {
        return "{\"checkIn\": \"%s\", \"checkOut\": \"%s\", \"comment\": \"приеду с женой\"}"
                .formatted(in, out);
    }

    @Test
    void createHoldsDatesAndIssuesOtp() throws Exception {
        Long id = guest("+81320000001", 777101L);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2027-06-01", "2027-06-05")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").isNumber())
                .andExpect(jsonPath("$.willReplaceBooking").value(org.hamcrest.Matchers.nullValue()));

        assertThat(jdbc.queryForObject(
                "select status from bookings order by id desc limit 1", String.class))
                .isEqualTo("PENDING_OTP");
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'OTP_CODE'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void takenDatesGive409() throws Exception {
        Long masha = guest("+81320000002", 777102L);
        Long petya = guest("+81320000003", 777103L);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-07-01', '2027-07-05', 'CONFIRMED')
                """, masha);

        mvc.perform(post("/api/bookings").cookie(auth(petya))
                        .contentType(APPLICATION_JSON).content(body("2027-07-03", "2027-07-08")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));
    }

    @Test
    void overlapWithOwnBookingHintsReschedule() throws Exception {
        Long id = guest("+81320000004", 777104L);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-08-01', '2027-08-05', 'CONFIRMED')
                """, id);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2027-08-03", "2027-08-08")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERLAPS_OWN_BOOKING"));
    }

    @Test
    void existingActiveBookingIsReportedAsWillReplace() throws Exception {
        Long id = guest("+81320000005", 777105L);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-09-01', '2027-09-05', 'CONFIRMED')
                """, id);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2027-10-01", "2027-10-05")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.willReplaceBooking.checkIn").value("2027-09-01"));
    }

    @Test
    void withoutTelegramGives409() throws Exception {
        Long id = guest("+81320000006", null);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2027-11-01", "2027-11-05")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TELEGRAM_NOT_LINKED"));
    }

    @Test
    void pastOrInvertedDatesGive400() throws Exception {
        Long id = guest("+81320000007", 777107L);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2020-01-05", "2020-01-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
```

- [ ] **Step 2: Убедиться в падении** — COMPILE FAIL / 404.

- [ ] **Step 3: Реализовать**

Исключения (по образцу существующих, RuntimeException + сообщение):
`DatesTakenException("Даты только что заняли — обнови календарь")`,
`OverlapsOwnBookingException("Даты пересекаются с твоей текущей бронью — используй перенос")`,
`TelegramNotLinkedException("Сначала привяжи Telegram: открой бота и поделись контактом")`,
`NotYourBookingException("Это не твоя бронь")`,
`BookingExpiredException("Бронь уже отменена — создай новую")`.

В `BookingRepository` добавить:

```java
    Optional<Booking> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, BookingStatus status);
```

(понадобится `import java.util.Optional;`; `user.id` резолвится Spring Data
по пути `user_id`).

`booking/BookingService.java`:

```java
package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.otp.OtpService;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BookingService {

    static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    static final List<BookingStatus> ACTIVE =
            List.of(BookingStatus.PENDING_OTP, BookingStatus.CONFIRMED);

    private final BookingRepository bookings;
    private final UserAccountRepository users;
    private final OtpService otp;
    private final JdbcTemplate jdbc;

    public record WillReplace(long id, LocalDate checkIn, LocalDate checkOut) {
    }

    public record CreateResult(long bookingId, WillReplace willReplaceBooking) {
    }

    @Transactional
    public CreateResult create(Long userId, LocalDate checkIn, LocalDate checkOut, String comment) {
        UserAccount user = requireTelegramLinked(userId);
        validateDates(checkIn, checkOut);
        // пересечение с СОБСТВЕННОЙ активной бронью — подсказываем перенос
        boolean overlapsOwn = bookings
                .findOverlapping(checkIn, checkOut.minusDays(1), ACTIVE).stream()
                .anyMatch(b -> b.getUser().getId().equals(userId));
        if (overlapsOwn) {
            throw new OverlapsOwnBookingException();
        }
        Long bookingId;
        try {
            bookingId = jdbc.queryForObject("""
                    insert into bookings(user_id, check_in, check_out, status, comment)
                    values (?, ?, ?, 'PENDING_OTP', ?) returning id
                    """, Long.class, userId, checkIn, checkOut, comment);
        } catch (DataIntegrityViolationException e) {
            throw new DatesTakenException();
        }
        otp.issue(user, "CREATE_BOOKING", Map.of("booking_id", bookingId));
        WillReplace willReplace = bookings
                .findFirstByUserIdAndStatusOrderByIdDesc(userId, BookingStatus.CONFIRMED)
                .map(b -> new WillReplace(b.getId(), b.getCheckIn(), b.getCheckOut()))
                .orElse(null);
        return new CreateResult(bookingId, willReplace);
    }

    UserAccount requireTelegramLinked(Long userId) {
        UserAccount user = users.findById(userId).orElseThrow();
        if (user.getTelegramChatId() == null) {
            throw new TelegramNotLinkedException();
        }
        return user;
    }

    void requireOwnership(long bookingId, Long userId) {
        Long ownerId = jdbc.queryForObject(
                "select user_id from bookings where id = ?", Long.class, bookingId);
        if (ownerId == null || !ownerId.equals(userId)) {
            throw new NotYourBookingException();
        }
    }

    private void validateDates(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null || !checkIn.isBefore(checkOut)
                || checkIn.isBefore(LocalDate.now(JST))) {
            throw new InvalidBookingDatesException();
        }
    }
}
```

Плюс `booking/InvalidBookingDatesException.java` («Даты некорректны: заезд
должен быть раньше выезда и не в прошлом») с хендлером 400 VALIDATION_ERROR.

Замечание: гонка `queryForObject(select user_id...)` на несуществующей брони
кинет EmptyResultDataAccessException → добавь в GlobalExceptionHandler
маппинг его на 404 `{"code":"NOT_FOUND","message":"Бронь не найдена"}`.

`booking/BookingController.java`:

```java
package com.batowka.guestbooking.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    public record CreateRequest(@NotNull LocalDate checkIn, @NotNull LocalDate checkOut,
                                @Size(max = 500) String comment) {
    }

    @PostMapping
    public BookingService.CreateResult create(@Valid @RequestBody CreateRequest body,
                                              Authentication auth) {
        return bookingService.create((Long) auth.getPrincipal(),
                body.checkIn(), body.checkOut(), body.comment());
    }
}
```

Хендлеры в `GlobalExceptionHandler`: DatesTaken→409 DATES_TAKEN;
OverlapsOwnBooking→409 OVERLAPS_OWN_BOOKING; TelegramNotLinked→409
TELEGRAM_NOT_LINKED; NotYourBooking→403 FORBIDDEN; BookingExpired→409
BOOKING_EXPIRED; InvalidBookingDates→400 VALIDATION_ERROR;
EmptyResultDataAccessException→404 NOT_FOUND.

- [ ] **Step 4: Зелёный + полный suite** — `-Dtest=CreateBookingTest` 6/6, затем целиком.

- [ ] **Step 5: Commit**

```bash
git add backend-api/src
git commit -m "feat: создание брони — удержание дат, OTP-челлендж, предупреждение о замене"
```

---

### Task 5: Подтверждение — confirm CREATE + замена активной брони

**Files:**
- Modify: `BookingService.java`, `BookingController.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/booking/ConfirmBookingTest.java`

**Interfaces:**
- Consumes: `OtpService.verify`, Task 4.
- Produces: `POST /api/bookings/{id}/confirm {code}` → 204; `BookingService.confirm(userId, bookingId, code)`; `notifyBookingEvent(...)` — переиспользует Task 6. Уведомления: гостю всегда; админу — если у админа привязан chat_id.

- [ ] **Step 1: Красный тест**

```java
package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.auth.JwtAuthFilter;
import com.batowka.guestbooking.auth.JwtService;
import com.batowka.guestbooking.user.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ConfirmBookingTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Long guest(String phone, Long chatId) {
        return jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, 'Маша', ?) returning id",
                Long.class, phone, chatId);
    }

    private Cookie auth(Long userId) {
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(userId, Role.FRIEND));
    }

    @Autowired
    tools.jackson.databind.ObjectMapper objectMapper;

    private long createBooking(Long userId, String in, String out) throws Exception {
        var result = mvc.perform(post("/api/bookings").cookie(auth(userId))
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"%s\", \"checkOut\": \"%s\"}".formatted(in, out)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("bookingId").asLong();
    }

    private String lastCode() {
        // парсим JSON, не подстроки — jsonb нормализует форматирование
        String envelope = jdbc.queryForObject("""
                select payload::text from outbox where event_type = 'OTP_CODE'
                order by id desc limit 1
                """, String.class);
        return objectMapper.readTree(envelope).get("payload").get("code").asString();
    }

    @Test
    void confirmMakesBookingConfirmedAndNotifiesGuestAndAdmin() throws Exception {
        // у сидированного админа привязываем chat_id, чтобы проверить админ-уведомление
        jdbc.update("update users set telegram_chat_id = 999000 where role = 'ADMIN'");
        Long id = guest("+81330000001", 777201L);
        long bookingId = createBooking(id, "2027-06-01", "2027-06-05");

        mvc.perform(post("/api/bookings/" + bookingId + "/confirm").cookie(auth(id))
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\": \"" + lastCode() + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, bookingId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox where event_type = 'BOOKING_CONFIRMED'
                """, Integer.class)).isEqualTo(2); // гостю и админу
    }

    @Test
    void confirmReplacesOldActiveBookingAtomically() throws Exception {
        Long id = guest("+81330000002", 777202L);
        Long oldId = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-07-01', '2027-07-05', 'CONFIRMED') returning id
                """, Long.class, id);
        long newId = createBooking(id, "2027-08-01", "2027-08-05");

        mvc.perform(post("/api/bookings/" + newId + "/confirm").cookie(auth(id))
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\": \"" + lastCode() + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, oldId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "select cancelled_by from bookings where id = ?", String.class, oldId))
                .isEqualTo("GUEST");
        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, newId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'BOOKING_CANCELLED'",
                Integer.class)).isEqualTo(1); // админ без chat_id в этом тесте
    }

    @Test
    void wrongCodeGives400AndBookingStaysPending() throws Exception {
        Long id = guest("+81330000003", 777203L);
        long bookingId = createBooking(id, "2027-09-01", "2027-09-05");

        mvc.perform(post("/api/bookings/" + bookingId + "/confirm").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content("{\"code\": \"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));

        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, bookingId))
                .isEqualTo("PENDING_OTP");
    }

    @Test
    void foreignBookingGives403() throws Exception {
        Long masha = guest("+81330000004", 777204L);
        Long petya = guest("+81330000005", 777205L);
        long bookingId = createBooking(masha, "2027-10-01", "2027-10-05");

        mvc.perform(post("/api/bookings/" + bookingId + "/confirm").cookie(auth(petya))
                        .contentType(APPLICATION_JSON).content("{\"code\": \"123456\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void confirmOfCleanedBookingGives409() throws Exception {
        Long id = guest("+81330000006", 777206L);
        long bookingId = createBooking(id, "2027-11-01", "2027-11-05");
        String code = lastCode();
        jdbc.update("update bookings set status = 'CANCELLED' where id = ?", bookingId);

        mvc.perform(post("/api/bookings/" + bookingId + "/confirm").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content("{\"code\": \"" + code + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_EXPIRED"));
    }
}
```

(`createBooking` оформить через autowired `ObjectMapper` — фрагмент с
`JsonMapper.shared()` в листинге помечен НЕ копировать.)

- [ ] **Step 2: Убедиться в падении** — 404 на confirm.

- [ ] **Step 3: Реализовать**

В `BookingService`:

```java
    @Transactional
    public void confirm(Long userId, long bookingId, String code) {
        UserAccount user = requireTelegramLinked(userId);
        requireOwnership(bookingId, userId);
        OtpService.ChallengeResult ch = otp.verify(userId, bookingId, code);
        switch (ch.action()) {
            case "CREATE_BOOKING" -> confirmCreate(user, bookingId);
            case "RESCHEDULE" -> applyReschedule(user, bookingId, ch.payload());
            case "CANCEL" -> applyCancel(user, bookingId);
            default -> throw new IllegalStateException("Неизвестный action: " + ch.action());
        }
    }

    private void confirmCreate(UserAccount user, long bookingId) {
        // порядок обязателен: частичный уникальный индекс «одна CONFIRMED на гостя»
        bookings.findFirstByUserIdAndStatusOrderByIdDesc(user.getId(), BookingStatus.CONFIRMED)
                .ifPresent(old -> {
                    int n = jdbc.update("""
                            update bookings set status = 'CANCELLED', cancelled_by = 'GUEST'
                            where id = ? and status = 'CONFIRMED'
                            """, old.getId());
                    if (n == 1) {
                        notifyBookingEvent(user, "BOOKING_CANCELLED",
                                old.getCheckIn(), old.getCheckOut());
                    }
                });
        int updated = jdbc.update("""
                update bookings set status = 'CONFIRMED'
                where id = ? and status = 'PENDING_OTP'
                """, bookingId);
        if (updated == 0) {
            throw new BookingExpiredException();
        }
        Map<String, Object> dates = jdbc.queryForMap(
                "select check_in, check_out from bookings where id = ?", bookingId);
        notifyBookingEvent(user, "BOOKING_CONFIRMED",
                ((java.sql.Date) dates.get("check_in")).toLocalDate(),
                ((java.sql.Date) dates.get("check_out")).toLocalDate());
    }

    // заглушки — реализуются в Task 6; до тех пор недостижимы (челленджи этих
    // action появятся только в Task 6)
    private void applyReschedule(UserAccount user, long bookingId, tools.jackson.databind.JsonNode payload) {
        throw new UnsupportedOperationException("Task 6");
    }

    private void applyCancel(UserAccount user, long bookingId) {
        throw new UnsupportedOperationException("Task 6");
    }

    /** Событие гостю + админу (если у админа привязан Telegram). */
    void notifyBookingEvent(UserAccount guest, String eventType,
                            LocalDate checkIn, LocalDate checkOut) {
        outboxEvent(guest.getTelegramChatId(), guest, eventType, checkIn, checkOut);
        jdbc.query("""
                select telegram_chat_id from users
                where role = 'ADMIN' and telegram_chat_id is not null
                """, rs -> {
            outboxEvent(rs.getLong(1), guest, eventType, checkIn, checkOut);
        });
    }

    private void outboxEvent(Long chatId, UserAccount guest, String eventType,
                             LocalDate checkIn, LocalDate checkOut) {
        outbox.write("notifications.outbound", eventType, Map.of(
                "chat_id", chatId,
                "guest_name", guest.getName(),
                "check_in", checkIn.toString(),
                "check_out", checkOut.toString()));
    }
```

(полю `outbox` — `private final OutboxWriter outbox;` + импорт.)

В `BookingController`:

```java
    public record ConfirmRequest(@NotBlank String code) {
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable long id,
                                        @Valid @RequestBody ConfirmRequest body,
                                        Authentication auth) {
        bookingService.confirm((Long) auth.getPrincipal(), id, body.code());
        return ResponseEntity.noContent().build();
    }
```

(импорты `@PathVariable`, `@NotBlank`, `ResponseEntity`.)

- [ ] **Step 4: Зелёный + полный suite** — `-Dtest=ConfirmBookingTest` 5/5; целиком.

- [ ] **Step 5: Commit**

```bash
git add backend-api/src
git commit -m "feat: подтверждение брони кодом — CONFIRMED, атомарная замена старой, уведомления"
```

---

### Task 6: Перенос и отмена

**Files:**
- Modify: `BookingService.java` (реализация applyReschedule/applyCancel + requestReschedule/requestCancel), `BookingController.java` (PATCH, DELETE)
- Test: `backend-api/src/test/java/com/batowka/guestbooking/booking/RescheduleCancelTest.java`

**Interfaces:**
- Consumes: Tasks 3-5.
- Produces: `PATCH /api/bookings/{id} {checkIn, checkOut}` → 204 (выпущен код); `DELETE /api/bookings/{id}` → 204 (выпущен код); confirm применяет действия; события `BOOKING_RESCHEDULED`/`BOOKING_CANCELLED`.

- [ ] **Step 1: Красный тест**

```java
package com.batowka.guestbooking.booking;

// импорты как в ConfirmBookingTest (+ delete, patch из MockMvcRequestBuilders)

@AutoConfigureMockMvc
class RescheduleCancelTest extends AbstractIntegrationTest {

    // guest(...)/auth(...)/lastCode() — как в ConfirmBookingTest

    private Long confirmedBooking(Long userId, String in, String out) {
        return jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, ?::date, ?::date, 'CONFIRMED') returning id
                """, Long.class, userId, in, out);
    }

    @Test
    void rescheduleFlowMovesDatesAfterConfirm() throws Exception {
        Long id = guest("+81340000001", 777301L);
        Long bookingId = confirmedBooking(id, "2027-06-01", "2027-06-05");

        mvc.perform(patch("/api/bookings/" + bookingId).cookie(auth(id))
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-06-10\", \"checkOut\": \"2027-06-15\"}"))
                .andExpect(status().isNoContent());
        // даты ещё старые — вариант A: удержания нет до подтверждения
        assertThat(jdbc.queryForObject(
                "select check_in::text from bookings where id = ?", String.class, bookingId))
                .isEqualTo("2027-06-01");

        mvc.perform(post("/api/bookings/" + bookingId + "/confirm").cookie(auth(id))
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\": \"" + lastCode() + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select check_in::text from bookings where id = ?", String.class, bookingId))
                .isEqualTo("2027-06-10");
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'BOOKING_RESCHEDULED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rescheduleRaceGives409AndKeepsOldDates() throws Exception {
        Long masha = guest("+81340000002", 777302L);
        Long petya = guest("+81340000003", 777303L);
        Long bookingId = confirmedBooking(masha, "2027-07-01", "2027-07-05");

        mvc.perform(patch("/api/bookings/" + bookingId).cookie(auth(masha))
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-07-10\", \"checkOut\": \"2027-07-15\"}"))
                .andExpect(status().isNoContent());
        String code = lastCode();
        // Петя занимает целевые даты, пока Маша вводит код
        confirmedBooking(petya, "2027-07-11", "2027-07-13");

        mvc.perform(post("/api/bookings/" + bookingId + "/confirm").cookie(auth(masha))
                        .contentType(APPLICATION_JSON).content("{\"code\": \"" + code + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));

        assertThat(jdbc.queryForObject(
                "select check_in::text from bookings where id = ?", String.class, bookingId))
                .isEqualTo("2027-07-01");
    }

    @Test
    void cancelFlowCancelsAfterConfirm() throws Exception {
        Long id = guest("+81340000004", 777304L);
        Long bookingId = confirmedBooking(id, "2027-08-01", "2027-08-05");

        mvc.perform(delete("/api/bookings/" + bookingId).cookie(auth(id)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/bookings/" + bookingId + "/confirm").cookie(auth(id))
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\": \"" + lastCode() + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status || '/' || cancelled_by from bookings where id = ?",
                String.class, bookingId)).isEqualTo("CANCELLED/GUEST");
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'BOOKING_CANCELLED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rescheduleOfForeignBookingGives403() throws Exception {
        Long masha = guest("+81340000005", 777305L);
        Long petya = guest("+81340000006", 777306L);
        Long bookingId = confirmedBooking(masha, "2027-09-01", "2027-09-05");

        mvc.perform(patch("/api/bookings/" + bookingId).cookie(auth(petya))
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-09-10\", \"checkOut\": \"2027-09-15\"}"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: FAIL** (404/405 на PATCH/DELETE).

- [ ] **Step 3: Реализовать**

`BookingService` — заменить заглушки и добавить request-методы:

```java
    @Transactional
    public void requestReschedule(Long userId, long bookingId,
                                  LocalDate checkIn, LocalDate checkOut) {
        UserAccount user = requireTelegramLinked(userId);
        requireOwnership(bookingId, userId);
        validateDates(checkIn, checkOut);
        requireStatus(bookingId, "CONFIRMED");
        otp.issue(user, "RESCHEDULE", Map.of(
                "booking_id", bookingId,
                "check_in", checkIn.toString(),
                "check_out", checkOut.toString()));
    }

    @Transactional
    public void requestCancel(Long userId, long bookingId) {
        UserAccount user = requireTelegramLinked(userId);
        requireOwnership(bookingId, userId);
        requireStatus(bookingId, "CONFIRMED");
        otp.issue(user, "CANCEL", Map.of("booking_id", bookingId));
    }

    private void requireStatus(long bookingId, String expected) {
        String status = jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, bookingId);
        if (!expected.equals(status)) {
            throw new BookingExpiredException();
        }
    }

    private void applyReschedule(UserAccount user, long bookingId, JsonNode payload) {
        LocalDate in = LocalDate.parse(payload.get("check_in").asString());
        LocalDate out = LocalDate.parse(payload.get("check_out").asString());
        int updated;
        try {
            updated = jdbc.update("""
                    update bookings set check_in = ?, check_out = ?
                    where id = ? and status = 'CONFIRMED'
                    """, in, out, bookingId);
        } catch (DataIntegrityViolationException e) {
            // Даты заняли за 5 минут — вариант A. Исключение откатывает ВСЮ
            // транзакцию confirm, включая пометку челленджа USED: челлендж
            // остаётся PENDING, гость может запросить новый перенос (новый
            // PATCH вытеснит челлендж) или повторить confirm.
            throw new DatesTakenException();
        }
        if (updated == 0) {
            throw new BookingExpiredException();
        }
        notifyBookingEvent(user, "BOOKING_RESCHEDULED", in, out);
    }

    private void applyCancel(UserAccount user, long bookingId) {
        Map<String, Object> dates = jdbc.queryForMap(
                "select check_in, check_out from bookings where id = ?", bookingId);
        int updated = jdbc.update("""
                update bookings set status = 'CANCELLED', cancelled_by = 'GUEST'
                where id = ? and status = 'CONFIRMED'
                """, bookingId);
        if (updated == 0) {
            throw new BookingExpiredException();
        }
        notifyBookingEvent(user, "BOOKING_CANCELLED",
                ((java.sql.Date) dates.get("check_in")).toLocalDate(),
                ((java.sql.Date) dates.get("check_out")).toLocalDate());
    }
```

(сигнатура `applyReschedule(..., JsonNode payload)` — импорт
`tools.jackson.databind.JsonNode`.)

`BookingController`:

```java
    public record RescheduleRequest(@NotNull LocalDate checkIn, @NotNull LocalDate checkOut) {
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> reschedule(@PathVariable long id,
                                           @Valid @RequestBody RescheduleRequest body,
                                           Authentication auth) {
        bookingService.requestReschedule((Long) auth.getPrincipal(), id,
                body.checkIn(), body.checkOut());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable long id, Authentication auth) {
        bookingService.requestCancel((Long) auth.getPrincipal(), id);
        return ResponseEntity.noContent().build();
    }
```

- [ ] **Step 4: Зелёный + полный suite** — 4/4; целиком, дважды.

- [ ] **Step 5: Commit**

```bash
git add backend-api/src
git commit -m "feat: перенос и отмена брони через OTP — вариант A, гонка дат = честный 409"
```

---

### Task 7: resend-code, чистильщик, activeBooking в /api/me

**Files:**
- Modify: `BookingService.java`, `BookingController.java`, `auth/MeController.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/booking/PendingBookingCleaner.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/booking/CleanerAndResendTest.java`; дополнение MeControllerTest

**Interfaces:**
- Consumes: Tasks 3-6.
- Produces: `POST /api/bookings/{id}/resend-code` → 204|429; чистильщик каждые 2 мин; `GET /api/me` → `activeBooking {id, checkIn, checkOut, status} | null`.

- [ ] **Step 1: Красный тест**

```java
package com.batowka.guestbooking.booking;

// импорты как в ConfirmBookingTest

@AutoConfigureMockMvc
class CleanerAndResendTest extends AbstractIntegrationTest {

    @Autowired PendingBookingCleaner cleaner;
    // guest/auth/createBooking/lastCode — как в ConfirmBookingTest

    @Test
    void resendTooSoonGives429ThenWorksAfterMinute() throws Exception {
        Long id = guest("+81350000001", 777401L);
        long bookingId = createBooking(id, "2027-06-01", "2027-06-05");

        mvc.perform(post("/api/bookings/" + bookingId + "/resend-code").cookie(auth(id)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RESEND_TOO_SOON"));

        jdbc.update("update otp_challenges set created_at = now() - interval '2 minutes'");
        mvc.perform(post("/api/bookings/" + bookingId + "/resend-code").cookie(auth(id)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'OTP_CODE'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void cleanerCancelsStalePendingAndFreesDates() throws Exception {
        Long masha = guest("+81350000002", 777402L);
        Long petya = guest("+81350000003", 777403L);
        long bookingId = createBooking(masha, "2027-07-01", "2027-07-05");
        // протухание: челлендж и бронь в прошлом
        jdbc.update("update otp_challenges set expires_at = now() - interval '1 minute'");
        jdbc.update("update bookings set created_at = now() - interval '10 minutes' where id = ?",
                bookingId);

        cleaner.cleanExpired();

        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, bookingId))
                .isEqualTo("CANCELLED");
        // даты освободились — Петя бронирует их же
        mvc.perform(post("/api/bookings").cookie(auth(petya))
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-07-01\", \"checkOut\": \"2027-07-05\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void cleanerLeavesFreshPendingAlone() throws Exception {
        Long id = guest("+81350000004", 777404L);
        long bookingId = createBooking(id, "2027-08-01", "2027-08-05");

        cleaner.cleanExpired();

        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, bookingId))
                .isEqualTo("PENDING_OTP");
    }
}
```

В `MeControllerTest` — дополнить `authenticatedGuestSeesOwnProfile`
(или отдельный тест): у гостя с CONFIRMED-бронью
`$.activeBooking.checkIn` заполнен; без брони — `$.activeBooking` null.

- [ ] **Step 2: FAIL.**

- [ ] **Step 3: Реализовать**

`BookingService`:

```java
    @Transactional
    public void resendCode(Long userId, long bookingId) {
        UserAccount user = requireTelegramLinked(userId);
        requireOwnership(bookingId, userId);
        otp.resend(user, bookingId);
    }

    /** Активная бронь для /api/me: CONFIRMED, иначе свежайшая PENDING_OTP. */
    @Transactional(readOnly = true)
    public Optional<Booking> activeBooking(Long userId) {
        return bookings.findFirstByUserIdAndStatusOrderByIdDesc(userId, BookingStatus.CONFIRMED)
                .or(() -> bookings.findFirstByUserIdAndStatusOrderByIdDesc(
                        userId, BookingStatus.PENDING_OTP));
    }
```

`PendingBookingCleaner`:

```java
package com.batowka.guestbooking.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class PendingBookingCleaner {

    private final JdbcTemplate jdbc;

    /** PENDING_OTP без живого челленджа и старше 5 минут → CANCELLED (авто, cancelled_by NULL). */
    @Scheduled(fixedDelay = 120_000)
    @Transactional
    public void cleanExpired() {
        int n = jdbc.update("""
                update bookings b set status = 'CANCELLED'
                where b.status = 'PENDING_OTP'
                  and b.created_at < now() - interval '5 minutes'
                  and not exists (
                      select 1 from otp_challenges c
                      where (c.payload->>'booking_id')::bigint = b.id
                        and c.status = 'PENDING' and c.expires_at > now())
                """);
        if (n > 0) {
            log.info("Отменено протухших pending-броней: {}", n);
        }
    }
}
```

`BookingController`:

```java
    @PostMapping("/{id}/resend-code")
    public ResponseEntity<Void> resendCode(@PathVariable long id, Authentication auth) {
        bookingService.resendCode((Long) auth.getPrincipal(), id);
        return ResponseEntity.noContent().build();
    }
```

`MeController`: инжектировать `BookingService`, record расширить:

```java
    public record ActiveBooking(long id, LocalDate checkIn, LocalDate checkOut, BookingStatus status) {
    }

    public record MeResponse(String phone, String name, Role role, boolean telegramLinked,
                             ActiveBooking activeBooking) {
    }
```

и заполнять из `bookingService.activeBooking(userId)`.

- [ ] **Step 4: Зелёный + полный suite дважды** (шедулер чистильщика живёт в тестовом контексте — убедиться в отсутствии флаков: свежие брони защищены 5-минутным порогом).

- [ ] **Step 5: Commit**

```bash
git add backend-api/src
git commit -m "feat: resend-code, фоновая чистка протухших броней, activeBooking в /api/me"
```

---

### Task 8: Бот — доставка без потерь (offset после успешной отправки)

**Files:**
- Modify: `bot-service/internal/kafka/consumer.go`
- Test: дополнение `bot-service/internal/kafka/consumer_test.go`

**Interfaces:**
- Consumes: Consumer этапа 3.
- Produces: гарантия «уведомление не теряется при сбое Telegram»: `handle` возвращает error при сбое отправки; `remember(event_id)` — ТОЛЬКО после успешной отправки; `Run` при ошибке handle НЕ коммитит offset и уходит в 3с-бэкофф (сообщение передоставится). Task 9 строится на этом.

- [ ] **Step 1: Красный тест (добавить в consumer_test.go)**

```go
type flakySender struct {
	failFirst bool
	sent      []string
}

func (f *flakySender) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error {
	if f.failFirst {
		f.failFirst = false
		return context.DeadlineExceeded // любая ошибка
	}
	f.sent = append(f.sent, text)
	return nil
}

func TestFailedSendIsRetriedNotDeduplicated(t *testing.T) {
	sender := &flakySender{failFirst: true}
	c := newConsumerCore(sender)

	if err := c.handle(context.Background(), welcomeJSON("e-retry")); err == nil {
		t.Fatal("ожидал ошибку при сбое отправки")
	}
	if err := c.handle(context.Background(), welcomeJSON("e-retry")); err != nil {
		t.Fatalf("повтор должен пройти: %v", err)
	}
	if len(sender.sent) != 1 {
		t.Fatalf("ожидал ровно одну доставку, получил %d", len(sender.sent))
	}
}
```

(существующие тесты обновить под новую сигнатуру `handle(...) error` —
успешные пути возвращают nil; добавить `_ =` где значение не проверяется.)

Run: `cd bot-service && go test ./internal/kafka/` → COMPILE FAIL.

- [ ] **Step 2: Реализовать**

`consumerCore.handle` → `func (c *consumerCore) handle(ctx context.Context, raw []byte) error`:
- битый JSON / незнакомый тип / дубликат → `nil` (пропустить и закоммитить);
- отправка: ошибка → `return err` БЕЗ `remember`; успех → `c.remember(...)`,
  `return nil` (remember переносится ПОСЛЕ SendMessage).

`Consumer.Run`: при `err := c.core.handle(...); err != nil` — лог, БЕЗ
CommitMessages, 3с select-бэкофф (как при ошибке FetchMessage), `continue`
(FetchMessage вернёт то же сообщение — offset не коммичен).

- [ ] **Step 3: Зелёный** — `go test ./... && go vet ./...` (все пакеты).

- [ ] **Step 4: Commit**

```bash
git add bot-service
git commit -m "fix: бот не теряет уведомления — offset и дедуп только после успешной отправки"
```

---

### Task 9: Бот — рендеры OTP_CODE и BOOKING_*

**Files:**
- Modify: `bot-service/internal/events/events.go`, `bot-service/internal/kafka/consumer.go`
- Test: дополнение `bot-service/internal/kafka/consumer_test.go`

**Interfaces:**
- Consumes: Task 8, контракты `contracts/notifications-outbound.md`.
- Produces: доставка всех событий этапа гостю/админу.

- [ ] **Step 1: Красный тест**

```go
func eventJSON(id, eventType, payload string) []byte {
	return []byte(`{"event_id":"` + id + `","occurred_at":"2026-08-19T12:00:00Z",` +
		`"event_type":"` + eventType + `","payload":` + payload + `}`)
}

func TestOtpCodeIsRendered(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	_ = c.handle(context.Background(), eventJSON("e-otp", "OTP_CODE",
		`{"chat_id":555,"code":"482913","action":"CREATE_BOOKING","expires_at":"2026-08-19T12:05:00Z"}`))

	if len(sender.sent) != 1 || !strings.Contains(sender.sent[0], "482913") ||
		!strings.Contains(sender.sent[0], "5 минут") {
		t.Fatalf("ожидал код и срок в сообщении: %v", sender.sent)
	}
}

func TestBookingEventsAreRendered(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)
	payload := `{"chat_id":555,"guest_name":"Маша","check_in":"2027-06-01","check_out":"2027-06-05"}`

	_ = c.handle(context.Background(), eventJSON("e-c", "BOOKING_CONFIRMED", payload))
	_ = c.handle(context.Background(), eventJSON("e-x", "BOOKING_CANCELLED", payload))
	_ = c.handle(context.Background(), eventJSON("e-r", "BOOKING_RESCHEDULED", payload))

	if len(sender.sent) != 3 {
		t.Fatalf("ожидал 3 сообщения: %d", len(sender.sent))
	}
	for i, want := range []string{"подтверждена", "отменена", "перенесена"} {
		if !strings.Contains(sender.sent[i], want) || !strings.Contains(sender.sent[i], "Маша") {
			t.Errorf("сообщение %d: ожидал %q и имя: %q", i, want, sender.sent[i])
		}
	}
}
```

Run → FAIL (типы рендерятся как «незнакомый event_type»).

- [ ] **Step 2: Реализовать**

`events.go` — структуры:

```go
type OtpCode struct {
	ChatID    int64  `json:"chat_id"`
	Code      string `json:"code"`
	Action    string `json:"action"`
	ExpiresAt string `json:"expires_at"`
}

type BookingEvent struct {
	ChatID    int64  `json:"chat_id"`
	GuestName string `json:"guest_name"`
	CheckIn   string `json:"check_in"`
	CheckOut  string `json:"check_out"`
}
```

`consumer.go` — case'ы в switch (после WELCOME):

```go
	case "OTP_CODE":
		var p events.OtpCode
		if err := json.Unmarshal(env.Payload, &p); err != nil {
			log.Printf("битый payload OTP_CODE: %v", err)
			return nil
		}
		return c.send(ctx, env.EventID, p.ChatID,
			"Код подтверждения: "+p.Code+". Действует 5 минут.")
	case "BOOKING_CONFIRMED":
		return c.renderBooking(ctx, env, "Бронь подтверждена")
	case "BOOKING_CANCELLED":
		return c.renderBooking(ctx, env, "Бронь отменена")
	case "BOOKING_RESCHEDULED":
		return c.renderBooking(ctx, env, "Бронь перенесена")
```

с хелперами:

```go
func (c *consumerCore) renderBooking(ctx context.Context, env events.Envelope, prefix string) error {
	var p events.BookingEvent
	if err := json.Unmarshal(env.Payload, &p); err != nil {
		log.Printf("битый payload %s: %v", env.EventType, err)
		return nil
	}
	return c.send(ctx, env.EventID, p.ChatID,
		prefix+": "+p.GuestName+", заезд "+p.CheckIn+", выезд "+p.CheckOut+".")
}

func (c *consumerCore) send(ctx context.Context, eventID string, chatID int64, text string) error {
	if err := c.sender.SendMessage(ctx, chatID, text, false); err != nil {
		return err
	}
	c.remember(eventID)
	return nil
}
```

(WELCOME-ветку переписать через тот же `send`.)

- [ ] **Step 3: Зелёный** — `go test ./... && go vet ./... && gofmt -l .` (пусто).

- [ ] **Step 4: Commit**

```bash
git add bot-service
git commit -m "feat: рендеры OTP_CODE и BOOKING_* в боте"
```

---

### Task 10: Учебный разбор этапа 4

**Files:**
- Create: `docs/learning/04-booking-otp-state-machines.md`

- [ ] **Step 1: Написать статью В ТУТОР-ФОРМАТЕ** (обязательно по
`docs/learning/README.md`: YAML front-matter с topics/code_anchors/
decisions/pitfalls/quiz_seeds + блоки «Разбор кода» после каждого раздела;
якоря проверять Read/grep). Темы (`## N.`):

1. Конечные автоматы статусов: PENDING_OTP→CONFIRMED→CANCELLED; почему
   переходы — только `UPDATE ... WHERE status` (гонка «гость vs чистильщик»).
2. OTP-безопасность: хеш вместо кода, инкремент попыток ДО сравнения,
   неинформативные ошибки, привязка кода к брони через payload.
3. Паттерн «замена вместо отказа»: willReplaceBooking, порядок
   отмена-старой→подтверждение-новой под частичным уникальным индексом.
4. Вариант A переноса: почему даты не удерживаются, честный 409 и спека §8;
   сравнение с отвергнутым вариантом B (вторая pending-строка).
5. Фоновые задачи: идемпотентный чистильщик, 5-минутный порог,
   NOT EXISTS-подзапрос; триггер updated_at (V2) и зачем он.
6. Надёжная доставка в боте: remember/commit только после успешной отправки,
   цена (head-of-line blocking) и почему для OTP она оправдана.

decisions — из спеки этапа 4 (§1 скоуп, §4 вариант A, замена, §5 бот);
pitfalls — реальные грабли этапа по ходу выполнения (пополнить из ревью).

- [ ] **Step 2: Commit**

```bash
git add docs/learning
git commit -m "docs: разбор этапа 4 — конечные автоматы, OTP, фоновые задачи (тутор-формат)"
```

---

## Финальная проверка этапа (контролёр + владелец)

Живой смоук: compose + backend + bot (реальный токен); владелец через curl
с auth-cookie создаёт бронь → код приходит в Telegram → confirm → бронь
CONFIRMED, уведомление в Telegram; перенос и отмена — по желанию. Выполняется
после финального whole-branch ревью, перед merge.
