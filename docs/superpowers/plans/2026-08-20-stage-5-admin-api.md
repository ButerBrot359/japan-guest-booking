# Этап 5: Админ-API + заявки на доступ — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Админ управляет блокировками дат, любыми бронями (без OTP), белым списком (soft delete) и заявками на доступ; незнакомец подаёт заявку с сайта, админ получает уведомление в Telegram; попутно закрыты переносы из ревью этапов 1–4.

**Architecture:** Новый пакет `admin/` — тонкие контроллеры с `@PreAuthorize`; бизнес-логика в доменных пакетах (`calendar/BlockedPeriodService`, `booking/AdminBookingService`, `user/WhitelistService`, новый `accessrequest/`). Кросс-табличная проверка пересечения дат «бронь ↔ блокировка» сериализуется транзакционным advisory lock (`common/DatesLock`). Soft delete через `users.deleted_at` (миграция V3). Уведомления — через существующий transactional outbox; бот получает новый рендер и русские даты.

**Tech Stack:** Spring Boot 4.0.7 (Jackson 3 — `tools.jackson`), Spring Security (`@EnableMethodSecurity`), Flyway, Testcontainers (Postgres 16 + Kafka 3.9.1), Go (kafka-go) для бота.

**Spec:** `docs/specs/2026-08-20-stage-5-admin-api-design.md`

## Global Constraints

- Ветка: `stage-5-admin-api` (уже создана, спека закоммичена).
- Комментарии, сообщения ошибок, тексты бота и коммиты — по-русски.
- Даты внутри системы (БД/API/события) — только ISO (`LocalDate.toString()`); русский формат — только в текстах бота.
- «Сегодня» — по JST: `LocalDate.now(BookingService.JST)`.
- Все записи в outbox — той же транзакцией, что бизнес-эффект (`OutboxWriter.write`, propagation MANDATORY).
- Пагинации нет (масштаб «друзья» — YAGNI).
- Тесты: интеграционные наследуют `AbstractIntegrationTest` (`@AutoConfigureMockMvc`); TRUNCATE-список уже содержит все нужные таблицы — не трогать.
- Каждая задача: тест → красный → код → зелёный → коммит. Запуск: `cd backend-api && ./gradlew test --tests '<Класс>'` (полный прогон — в конце сессии), Go: `cd bot-service && go test ./...`.
- Учебные пометки: в конце каждой сессии — короткий чекпоинт-разбор для владельца (он учится, ~1 час на сессию).

---

## Сессия 1 — фундамент: advisory lock и честные ошибки

### Task 1: DatesLock + проверка блокировок в create/reschedule гостя

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/common/DatesLock.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java` (create, applyReschedule; JST сделать public)
- Test: `backend-api/src/test/java/com/batowka/guestbooking/booking/BlockedDatesGuardTest.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/calendar/BlockedPeriodRepositoryTest.java`

**Interfaces:**
- Produces: `DatesLock.acquire()` — берёт `pg_advisory_xact_lock` (требует активной транзакции, иначе `IllegalStateException`); используется задачами 3, 6.
- Produces: `BookingService.JST` становится `public static final ZoneId` (нужен задаче 8).
- Consumes: `BlockedPeriodRepository.findOverlapping(from, to)` — существует.

- [ ] **Step 1: Написать падающие тесты**

`BlockedDatesGuardTest` — гость не может встать на заблокированные даты:

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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BlockedDatesGuardTest extends AbstractIntegrationTest {

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

    @Test
    void createOnBlockedDatesGives409() throws Exception {
        Long id = guest("+81350000001", 778101L);
        jdbc.update("insert into blocked_periods(start_date, end_date, reason) values ('2027-09-03', '2027-09-04', 'ремонт')");

        mvc.perform(post("/api/bookings").cookie(auth(id)).contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-09-01\", \"checkOut\": \"2027-09-04\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));
    }

    @Test
    void rescheduleOntoBlockedDatesGives409OnConfirm() throws Exception {
        Long id = guest("+81350000002", 778102L);
        Long bookingId = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-10-01', '2027-10-05', 'CONFIRMED') returning id
                """, Long.class, id);
        jdbc.update("insert into blocked_periods(start_date, end_date) values ('2027-11-02', '2027-11-03')");

        // запросить перенос можно (проверка — при подтверждении кодом)
        mvc.perform(patch("/api/bookings/" + bookingId).cookie(auth(id)).contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-11-01\", \"checkOut\": \"2027-11-05\"}"))
                .andExpect(status().isNoContent());

        String code = latestOtpCode();
        mvc.perform(post("/api/bookings/" + bookingId + "/confirm").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content("{\"code\": \"" + code + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));
    }

    /** Код из последнего OTP_CODE-события в outbox (код живёт только там). */
    private String latestOtpCode() {
        return jdbc.queryForObject("""
                select payload->'payload'->>'code' from outbox
                where event_type = 'OTP_CODE' order by id desc limit 1
                """, String.class);
    }
}
```

`BlockedPeriodRepositoryTest` — прямой тест `findOverlapping` (перенос из ревью этапа 1):

```java
package com.batowka.guestbooking.calendar;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BlockedPeriodRepositoryTest extends AbstractIntegrationTest {

    @Autowired BlockedPeriodRepository repo;
    @Autowired JdbcTemplate jdbc;

    @Test
    void findsTouchingAndMissesDisjoint() {
        jdbc.update("insert into blocked_periods(start_date, end_date) values ('2027-05-10', '2027-05-15')");

        // касание границ включительно
        assertThat(repo.findOverlapping(LocalDate.parse("2027-05-15"), LocalDate.parse("2027-05-20"))).hasSize(1);
        assertThat(repo.findOverlapping(LocalDate.parse("2027-05-01"), LocalDate.parse("2027-05-10"))).hasSize(1);
        // внутри
        assertThat(repo.findOverlapping(LocalDate.parse("2027-05-12"), LocalDate.parse("2027-05-13"))).hasSize(1);
        // мимо с обеих сторон
        assertThat(repo.findOverlapping(LocalDate.parse("2027-05-01"), LocalDate.parse("2027-05-09"))).isEmpty();
        assertThat(repo.findOverlapping(LocalDate.parse("2027-05-16"), LocalDate.parse("2027-05-20"))).isEmpty();
    }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `cd backend-api && ./gradlew test --tests 'BlockedDatesGuardTest' --tests 'BlockedPeriodRepositoryTest'`
Expected: `BlockedPeriodRepositoryTest` зелёный (репозиторий уже работает — это фиксация поведения), `BlockedDatesGuardTest` красный: create/confirm проходят вместо 409.

- [ ] **Step 3: Реализация**

`common/DatesLock.java`:

```java
package com.batowka.guestbooking.common;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Один advisory lock на все операции «проверь пересечение дат и запиши».
 * Exclusion constraint не умеет между таблицами (bookings ↔ blocked_periods),
 * поэтому проверку делает код, а гонку двух транзакций закрывает этот замок:
 * pg_advisory_xact_lock(KEY) держится до конца транзакции взявшего и
 * выстраивает конкурентов в очередь. Для одного гостевого места — бесплатно.
 */
@Component
@RequiredArgsConstructor
public class DatesLock {

    static final long KEY = 4242L;

    private final JdbcTemplate jdbc;

    public void acquire() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // вне транзакции xact-замок отпустился бы сразу — молчаливая дыра
            throw new IllegalStateException("DatesLock.acquire() требует активной транзакции");
        }
        jdbc.execute("select pg_advisory_xact_lock(" + KEY + ")");
    }
}
```

`BookingService` — внедрить `DatesLock datesLock` и `BlockedPeriodRepository blockedPeriods` (поля + конструктор через `@RequiredArgsConstructor` уже есть), `JST` сделать `public`. В `create(...)` перед insert:

```java
        datesLock.acquire();
        // блокировки админа: exclusion constraint их не видит, проверяем кодом под замком
        if (!blockedPeriods.findOverlapping(checkIn, checkOut.minusDays(1)).isEmpty()) {
            throw new DatesTakenException();
        }
```

В `applyReschedule(...)` перед `jdbc.update`:

```java
        datesLock.acquire();
        if (!blockedPeriods.findOverlapping(in, out.minusDays(1)).isEmpty()) {
            throw new DatesTakenException();
        }
```

(`checkOut.minusDays(1)`: бронь занимает [checkIn, checkOut), блокировка — включительно.)

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests 'BlockedDatesGuardTest' --tests 'BlockedPeriodRepositoryTest' --tests 'CreateBookingTest' --tests 'ConfirmBookingTest' --tests 'RescheduleCancelTest'`
Expected: PASS (существующие букинг-тесты не сломаны).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: advisory lock + учёт блокировок в создании и переносе брони"
```

### Task 2: Доменные not-found вместо EmptyResultDataAccessException

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingNotFoundException.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java` (requireOwnership, requireStatus)
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java`
- Modify: `backend-api/src/test/java/com/batowka/guestbooking/common/GlobalExceptionHandlerTest.java` (заменить проверку EmptyResultDataAccessException)

**Interfaces:**
- Produces: `BookingNotFoundException` (RuntimeException, сообщение «Бронь не найдена») → 404 `NOT_FOUND`; используется задачами 6, 9.
- Produces: паттерн «доменное not-found исключение → 404 со своим текстом» — задачи 3, 8, 10 добавляют свои по образцу.

- [ ] **Step 1: Написать падающий тест**

В `GlobalExceptionHandlerTest` (или новый метод в `BlockedDatesGuardTest`-стиле — класс уже существует, добавить):

```java
    @Test
    void unknownBookingIdGives404WithHonestText() throws Exception {
        Long id = guest("+81350000009", 778109L);
        mvc.perform(post("/api/bookings/999999/resend-code").cookie(auth(id)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Бронь не найдена"));
    }
```

(если в `GlobalExceptionHandlerTest` нет хелперов guest/auth — скопировать из Task 1.)

- [ ] **Step 2: Убедиться в текущем поведении**

Run: `cd backend-api && ./gradlew test --tests 'GlobalExceptionHandlerTest'`
Expected: тест может быть даже зелёным (старый маппинг тоже даёт 404 «Бронь не найдена») — цель задачи: то же поведение через доменное исключение, чтобы маппинг перестал быть глобальным. Если зелёный — продолжаем, красным станет после удаления старого хендлера без нового.

- [ ] **Step 3: Реализация**

`booking/BookingNotFoundException.java`:

```java
package com.batowka.guestbooking.booking;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException() {
        super("Бронь не найдена");
    }
}
```

`BookingService.requireOwnership` и `requireStatus` — обернуть `queryForObject`:

```java
    void requireOwnership(long bookingId, Long userId) {
        Long ownerId;
        try {
            ownerId = jdbc.queryForObject(
                    "select user_id from bookings where id = ?", Long.class, bookingId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new BookingNotFoundException();
        }
        if (ownerId == null || !ownerId.equals(userId)) {
            throw new NotYourBookingException();
        }
    }
```

(в `requireStatus` — аналогичный try/catch вокруг `queryForObject`).

В `GlobalExceptionHandler`: удалить хендлер `EmptyResultDataAccessException` (и его import), добавить:

```java
    @ExceptionHandler(BookingNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError bookingNotFound(BookingNotFoundException ex) {
        return new ApiError("NOT_FOUND", ex.getMessage());
    }
```

В `GlobalExceptionHandlerTest` — убрать/переписать тест про `EmptyResultDataAccessException` на `BookingNotFoundException`.

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests 'GlobalExceptionHandlerTest' --tests 'ConfirmBookingTest' --tests 'RescheduleCancelTest' --tests 'CleanerAndResendTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: доменный BookingNotFoundException вместо глобального маппинга EmptyResultDataAccess"
```

**Чекпоинт сессии 1 для владельца:** что такое advisory lock и почему exclusion constraint не спас; почему «глобальный маппинг исключения БД на текст про бронь» — мина.

---

## Сессия 2 — блокировки: CRUD, конфликты, гонка

### Task 3: BlockedPeriodService + админ-контроллер + method security

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/calendar/BlockedPeriodService.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/calendar/BlockedPeriodNotFoundException.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/calendar/OverlapsBookingException.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/admin/AdminBlockedPeriodController.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/auth/SecurityConfig.java` (`@EnableMethodSecurity`)
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/admin/AdminBlockedPeriodTest.java`

**Interfaces:**
- Consumes: `DatesLock.acquire()` (Task 1), `BookingRepository.findOverlapping(from, to, statuses)`, `BookingService.ACTIVE`.
- Produces: `BlockedPeriodService.create(LocalDate startDate, LocalDate endDate, String reason) → BlockedPeriod`, `delete(long id)`, `list() → List<BlockedPeriod>`; `OverlapsBookingException` c `List<OverlapsBookingException.Conflict> getConflicts()`, `record Conflict(long bookingId, String guestName, LocalDate checkIn, LocalDate checkOut)`. Гонка-тест (Task 4) и админ-перенос (Task 6) зависят от них.
- Produces: включённая method security + образец админ-контроллера с `@PreAuthorize("hasRole('ADMIN')")` — задачи 6, 8, 11 повторяют образец.

- [ ] **Step 1: Написать падающий тест**

```java
package com.batowka.guestbooking.admin;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminBlockedPeriodTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    /** Засеянный AdminSeeder'ом админ (телефон из application.yml). */
    private Cookie adminAuth() {
        Long adminId = jdbc.queryForObject(
                "select id from users where role = 'ADMIN'", Long.class);
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(adminId, Role.ADMIN));
    }

    private Cookie friendAuth() {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name) values ('+81360000001', 'Петя') returning id", Long.class);
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(id, Role.FRIEND));
    }

    @Test
    void crudFlow() throws Exception {
        mvc.perform(post("/api/admin/blocked-periods").cookie(adminAuth()).contentType(APPLICATION_JSON)
                        .content("{\"startDate\": \"2027-12-01\", \"endDate\": \"2027-12-10\", \"reason\": \"ремонт\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());

        mvc.perform(get("/api/admin/blocked-periods").cookie(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reason").value("ремонт"));

        Long id = jdbc.queryForObject("select id from blocked_periods limit 1", Long.class);
        mvc.perform(delete("/api/admin/blocked-periods/" + id).cookie(adminAuth()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from blocked_periods", Integer.class)).isZero();
    }

    @Test
    void overlapWithBookingGives409WithConflictList() throws Exception {
        Long guestId = jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values ('+81360000002', 'Маша', 779201) returning id",
                Long.class);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2028-01-05', '2028-01-10', 'CONFIRMED')
                """, guestId);

        mvc.perform(post("/api/admin/blocked-periods").cookie(adminAuth()).contentType(APPLICATION_JSON)
                        .content("{\"startDate\": \"2028-01-08\", \"endDate\": \"2028-01-12\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERLAPS_BOOKING"))
                .andExpect(jsonPath("$.conflicts[0].guestName").value("Маша"))
                .andExpect(jsonPath("$.conflicts[0].checkIn").value("2028-01-05"));
        assertThat(jdbc.queryForObject("select count(*) from blocked_periods", Integer.class)).isZero();
    }

    @Test
    void deleteUnknownGives404() throws Exception {
        mvc.perform(delete("/api/admin/blocked-periods/999").cookie(adminAuth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Блокировка не найдена"));
    }

    @Test
    void friendGets403() throws Exception {
        mvc.perform(get("/api/admin/blocked-periods").cookie(friendAuth()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void invalidRangeGives400() throws Exception {
        mvc.perform(post("/api/admin/blocked-periods").cookie(adminAuth()).contentType(APPLICATION_JSON)
                        .content("{\"startDate\": \"2028-02-10\", \"endDate\": \"2028-02-01\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `cd backend-api && ./gradlew test --tests 'AdminBlockedPeriodTest'`
Expected: FAIL — 404 на несуществующие эндпоинты.

- [ ] **Step 3: Реализация**

`calendar/BlockedPeriodNotFoundException.java`:

```java
package com.batowka.guestbooking.calendar;

public class BlockedPeriodNotFoundException extends RuntimeException {
    public BlockedPeriodNotFoundException() {
        super("Блокировка не найдена");
    }
}
```

`calendar/OverlapsBookingException.java`:

```java
package com.batowka.guestbooking.calendar;

import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/** Блокировка поверх активной брони запрещена: каскадов нет, админ разруливает сам. */
@Getter
public class OverlapsBookingException extends RuntimeException {

    public record Conflict(long bookingId, String guestName, LocalDate checkIn, LocalDate checkOut) {
    }

    private final List<Conflict> conflicts;

    public OverlapsBookingException(List<Conflict> conflicts) {
        super("Даты пересекаются с активными бронями — сначала отмените или перенесите их");
        this.conflicts = conflicts;
    }
}
```

`calendar/BlockedPeriodService.java`:

```java
package com.batowka.guestbooking.calendar;

import com.batowka.guestbooking.booking.BookingService;
import com.batowka.guestbooking.booking.BookingRepository;
import com.batowka.guestbooking.common.DatesLock;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BlockedPeriodService {

    private final BlockedPeriodRepository blockedPeriods;
    private final BookingRepository bookings;
    private final DatesLock datesLock;

    @Transactional(readOnly = true)
    public List<BlockedPeriod> list() {
        return blockedPeriods.findAll(Sort.by("startDate"));
    }

    @Transactional
    public BlockedPeriod create(LocalDate startDate, LocalDate endDate, String reason) {
        if (endDate.isBefore(startDate)) {
            throw new InvalidCalendarRangeException("Конец периода раньше начала");
        }
        datesLock.acquire();
        // бронь занимает [checkIn, checkOut), блокировка — включительно:
        // findOverlapping(from=start, to=end) отдаёт checkIn <= end && checkOut > start
        List<OverlapsBookingException.Conflict> conflicts = bookings
                .findOverlapping(startDate, endDate, BookingService.ACTIVE).stream()
                .map(b -> new OverlapsBookingException.Conflict(
                        b.getId(), b.getUser().getName(), b.getCheckIn(), b.getCheckOut()))
                .toList();
        if (!conflicts.isEmpty()) {
            throw new OverlapsBookingException(conflicts);
        }
        BlockedPeriod p = new BlockedPeriod();
        p.setStartDate(startDate);
        p.setEndDate(endDate);
        p.setReason(reason);
        return blockedPeriods.save(p);
    }

    @Transactional
    public void delete(long id) {
        if (!blockedPeriods.existsById(id)) {
            throw new BlockedPeriodNotFoundException();
        }
        blockedPeriods.deleteById(id);
    }
}
```

(`BookingService.ACTIVE` сделать `public static final` — сейчас package-private.)

`admin/AdminBlockedPeriodController.java`:

```java
package com.batowka.guestbooking.admin;

import com.batowka.guestbooking.calendar.BlockedPeriod;
import com.batowka.guestbooking.calendar.BlockedPeriodService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/blocked-periods")
@PreAuthorize("hasRole('ADMIN')") // вторая линия обороны поверх URL-правила SecurityConfig
@RequiredArgsConstructor
public class AdminBlockedPeriodController {

    private final BlockedPeriodService service;

    public record CreateRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate,
                                @Size(max = 200) String reason) {
    }

    public record PeriodResponse(long id, LocalDate startDate, LocalDate endDate,
                                 String reason, Instant createdAt) {
    }

    @GetMapping
    public List<PeriodResponse> list() {
        return service.list().stream().map(AdminBlockedPeriodController::toResponse).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodResponse create(@Valid @RequestBody CreateRequest body) {
        return toResponse(service.create(body.startDate(), body.endDate(), body.reason()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    private static PeriodResponse toResponse(BlockedPeriod p) {
        return new PeriodResponse(p.getId(), p.getStartDate(), p.getEndDate(),
                p.getReason(), p.getCreatedAt());
    }
}
```

`SecurityConfig` — добавить аннотацию на класс:

```java
@Configuration
@EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
```

`GlobalExceptionHandler` — три хендлера (`AccessDeniedException` обязан стоять ДО catch-all по специфичности — Spring сам выбирает точнейший, но с catch-all `Exception` он бы конфликтовал без явного хендлера):

```java
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError accessDenied(org.springframework.security.access.AccessDeniedException ex) {
        // бросается @PreAuthorize внутри MVC — без этого хендлера catch-all дал бы 500
        return new ApiError("FORBIDDEN", "Недостаточно прав");
    }

    @ExceptionHandler(BlockedPeriodNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError blockedPeriodNotFound(BlockedPeriodNotFoundException ex) {
        return new ApiError("NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(OverlapsBookingException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public OverlapsBookingError overlapsBooking(OverlapsBookingException ex) {
        return new OverlapsBookingError("OVERLAPS_BOOKING", ex.getMessage(), ex.getConflicts());
    }

    public record OverlapsBookingError(String code, String message,
                                       java.util.List<OverlapsBookingException.Conflict> conflicts) {
    }
```

(+ импорты `BlockedPeriodNotFoundException`, `OverlapsBookingException` из `calendar`).

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests 'AdminBlockedPeriodTest' --tests 'SecurityFlowTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: блокировки дат — CRUD админа, 409 со списком конфликтов, method security"
```

### Task 4: Тест гонки «бронь vs блокировка»

**Files:**
- Test: `backend-api/src/test/java/com/batowka/guestbooking/booking/DatesRaceTest.java`

**Interfaces:**
- Consumes: `BookingService.create(...)` (Task 1), `BlockedPeriodService.create(...)` (Task 3).

- [ ] **Step 1: Написать тест-инвариант**

```java
package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.calendar.BlockedPeriodService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Гонка «гость бронирует vs админ блокирует» на одни даты. Advisory lock
 * сериализует проверку+вставку: победить может максимум одна сторона.
 * Без замка обе транзакции проверили бы «свободно» и обе вставили.
 */
class DatesRaceTest extends AbstractIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired BlockedPeriodService blockedPeriodService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void bookingAndBlockNeverCoexistOnSameDates() throws Exception {
        LocalDate in = LocalDate.parse("2028-03-01");
        LocalDate out = LocalDate.parse("2028-03-05");
        Long guestId = jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values ('+81370000001', 'Маша', 779301) returning id",
                Long.class);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> book = () -> {
                start.await();
                try {
                    bookingService.create(guestId, in, out, null);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            };
            Callable<Boolean> block = () -> {
                start.await();
                try {
                    blockedPeriodService.create(in, out.minusDays(1), "гонка");
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            };
            Future<Boolean> f1 = pool.submit(book);
            Future<Boolean> f2 = pool.submit(block);
            start.countDown();
            boolean booked = f1.get(30, TimeUnit.SECONDS);
            boolean blocked = f2.get(30, TimeUnit.SECONDS);

            // инвариант: не «оба успели»
            Integer bookings = jdbc.queryForObject(
                    "select count(*) from bookings where status in ('PENDING_OTP','CONFIRMED')", Integer.class);
            Integer blocks = jdbc.queryForObject(
                    "select count(*) from blocked_periods", Integer.class);
            assertThat(booked && blocked).as("обе стороны выиграли гонку").isFalse();
            assertThat((bookings != null && bookings > 0) && (blocks != null && blocks > 0)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }
}
```

Примечание: бронь гостя PENDING_OTP конфликтует с блокировкой (§3 спеки), поэтому если первым успел гость — блокировка получает 409; если первым админ — гость получает DATES_TAKEN.

- [ ] **Step 2: Прогнать**

Run: `cd backend-api && ./gradlew test --tests 'DatesRaceTest'`
Expected: PASS (замок уже стоит с Task 1/3). Для самопроверки ценности теста: временно закомментировать `datesLock.acquire()` в обоих сервисах — тест должен начать мигать/падать; вернуть обратно.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "test: гонка бронь-vs-блокировка — advisory lock пропускает одного"
```

**Чекпоинт сессии 2 для владельца:** зачем `@EnableMethodSecurity` и как `AccessDeniedException` доезжает до 403; почему тест гонки — с CountDownLatch.

---

## Сессия 3 — админ управляет бронями

### Task 5: Поле `by` в BOOKING_* событиях

**Files:**
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java` (notifyBookingEvent + outboxEvent + все вызовы)
- Test: дополнение в `backend-api/src/test/java/com/batowka/guestbooking/booking/RescheduleCancelTest.java`

**Interfaces:**
- Produces: `notifyBookingEvent(UserAccount guest, String eventType, LocalDate checkIn, LocalDate checkOut, String by)` — пятый параметр `"GUEST"` | `"ADMIN"`; payload событий получает ключ `"by"`. Task 6 вызывает с `"ADMIN"`, Task 12 читает в боте.

- [ ] **Step 1: Написать падающий тест**

В `RescheduleCancelTest` добавить:

```java
    @Test
    void guestCancelEventCarriesByGuest() throws Exception {
        // использовать существующий в классе хелпер создания гостя и CONFIRMED-брони,
        // выполнить отмену с подтверждением кода (по образцу соседних тестов), затем:
        String by = jdbc.queryForObject("""
                select payload->'payload'->>'by' from outbox
                where event_type = 'BOOKING_CANCELLED' order by id desc limit 1
                """, String.class);
        assertThat(by).isEqualTo("GUEST");
    }
```

- [ ] **Step 2: Убедиться, что падает**

Run: `cd backend-api && ./gradlew test --tests 'RescheduleCancelTest'`
Expected: FAIL — `by` = null.

- [ ] **Step 3: Реализация**

В `BookingService`:

```java
    /** Событие гостю + админу (если у админа привязан Telegram). by: GUEST | ADMIN. */
    void notifyBookingEvent(UserAccount guest, String eventType,
                            LocalDate checkIn, LocalDate checkOut, String by) {
        outboxEvent(guest.getTelegramChatId(), guest, eventType, checkIn, checkOut, by);
        jdbc.query("""
                select telegram_chat_id from users
                where role = 'ADMIN' and telegram_chat_id is not null
                """, rs -> {
            outboxEvent(rs.getLong(1), guest, eventType, checkIn, checkOut, by);
        });
    }

    private void outboxEvent(Long chatId, UserAccount guest, String eventType,
                             LocalDate checkIn, LocalDate checkOut, String by) {
        outbox.write("notifications.outbound", eventType, Map.of(
                "chat_id", chatId,
                "guest_name", guest.getName(),
                "check_in", checkIn.toString(),
                "check_out", checkOut.toString(),
                "by", by));
    }
```

Все существующие вызовы `notifyBookingEvent(...)` в `confirmCreate`, `applyReschedule`, `applyCancel` — дописать аргумент `"GUEST"`.

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests 'RescheduleCancelTest' --tests 'ConfirmBookingTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: поле by (GUEST|ADMIN) в BOOKING_*-событиях"
```

### Task 6: AdminBookingService + контроллер

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/booking/AdminBookingService.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/admin/AdminBookingController.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingRepository.java` (findAllWithUser)
- Test: `backend-api/src/test/java/com/batowka/guestbooking/admin/AdminBookingTest.java`

**Interfaces:**
- Consumes: `notifyBookingEvent(..., "ADMIN")` (Task 5), `DatesLock` (Task 1), `BookingNotFoundException` (Task 2).
- Produces: `AdminBookingService.list() → List<BookingRow>`, `cancel(long)`, `reschedule(long, LocalDate, LocalDate)`; `record BookingRow(long id, String guestName, String guestPhone, LocalDate checkIn, LocalDate checkOut, BookingStatus status, String comment)`.

- [ ] **Step 1: Написать падающий тест**

```java
package com.batowka.guestbooking.admin;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminBookingTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Cookie adminAuth() {
        Long adminId = jdbc.queryForObject("select id from users where role = 'ADMIN'", Long.class);
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(adminId, Role.ADMIN));
    }

    private long confirmedBooking(String phone, long chatId, String in, String out) {
        Long userId = jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, 'Маша', ?) returning id",
                Long.class, phone, chatId);
        return jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, ?::date, ?::date, 'CONFIRMED') returning id
                """, Long.class, userId, in, out);
    }

    @Test
    void listShowsGuestNames() throws Exception {
        confirmedBooking("+81380000001", 779401L, "2028-04-01", "2028-04-05");
        mvc.perform(get("/api/admin/bookings").cookie(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].guestName").value("Маша"))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"));
    }

    @Test
    void adminCancelIsImmediateAndNotifiesGuest() throws Exception {
        long id = confirmedBooking("+81380000002", 779402L, "2028-05-01", "2028-05-05");
        mvc.perform(post("/api/admin/bookings/" + id + "/cancel").cookie(adminAuth()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status || ':' || cancelled_by from bookings where id = " + id, String.class))
                .isEqualTo("CANCELLED:ADMIN");
        assertThat(jdbc.queryForObject("""
                select payload->'payload'->>'by' from outbox
                where event_type = 'BOOKING_CANCELLED' order by id desc limit 1
                """, String.class)).isEqualTo("ADMIN");
    }

    @Test
    void adminRescheduleAppliesImmediatelyWithoutOtp() throws Exception {
        long id = confirmedBooking("+81380000003", 779403L, "2028-06-01", "2028-06-05");
        mvc.perform(post("/api/admin/bookings/" + id + "/reschedule").cookie(adminAuth())
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2028-06-10\", \"checkOut\": \"2028-06-15\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select check_in::text from bookings where id = " + id, String.class))
                .isEqualTo("2028-06-10");
        // OTP не выпускался
        assertThat(jdbc.queryForObject("select count(*) from otp_challenges", Integer.class)).isZero();
    }

    @Test
    void adminRescheduleOntoBlockedGives409() throws Exception {
        long id = confirmedBooking("+81380000004", 779404L, "2028-07-01", "2028-07-05");
        jdbc.update("insert into blocked_periods(start_date, end_date) values ('2028-07-12', '2028-07-13')");
        mvc.perform(post("/api/admin/bookings/" + id + "/reschedule").cookie(adminAuth())
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2028-07-11\", \"checkOut\": \"2028-07-14\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));
    }

    @Test
    void cancelUnknownGives404() throws Exception {
        mvc.perform(post("/api/admin/bookings/999999/cancel").cookie(adminAuth()))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `cd backend-api && ./gradlew test --tests 'AdminBookingTest'`
Expected: FAIL — эндпоинтов нет.

- [ ] **Step 3: Реализация**

В `BookingRepository`:

```java
    @Query("select b from Booking b join fetch b.user order by b.checkIn desc")
    List<Booking> findAllWithUser();
```

`booking/AdminBookingService.java`:

```java
package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.calendar.BlockedPeriodRepository;
import com.batowka.guestbooking.common.DatesLock;
import com.batowka.guestbooking.user.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Операции админа над любыми бронями: без OTP, применяются сразу, гость получает уведомление. */
@Service
@RequiredArgsConstructor
public class AdminBookingService {

    private final BookingRepository bookings;
    private final BlockedPeriodRepository blockedPeriods;
    private final BookingService bookingService;
    private final DatesLock datesLock;
    private final JdbcTemplate jdbc;

    public record BookingRow(long id, String guestName, String guestPhone, LocalDate checkIn,
                             LocalDate checkOut, BookingStatus status, String comment) {
    }

    @Transactional(readOnly = true)
    public List<BookingRow> list() {
        return bookings.findAllWithUser().stream()
                .map(b -> new BookingRow(b.getId(), b.getUser().getName(), b.getUser().getPhone(),
                        b.getCheckIn(), b.getCheckOut(), b.getStatus(), b.getComment()))
                .toList();
    }

    @Transactional
    public void cancel(long bookingId) {
        Booking b = bookings.findById(bookingId).orElseThrow(BookingNotFoundException::new);
        UserAccount guest = b.getUser();
        int updated = jdbc.update("""
                update bookings set status = 'CANCELLED', cancelled_by = 'ADMIN'
                where id = ? and status in ('PENDING_OTP', 'CONFIRMED')
                """, bookingId);
        if (updated == 0) {
            throw new BookingExpiredException(); // уже отменена
        }
        bookingService.notifyBookingEvent(guest, "BOOKING_CANCELLED",
                b.getCheckIn(), b.getCheckOut(), "ADMIN");
    }

    @Transactional
    public void reschedule(long bookingId, LocalDate checkIn, LocalDate checkOut) {
        Booking b = bookings.findById(bookingId).orElseThrow(BookingNotFoundException::new);
        if (!checkIn.isBefore(checkOut) || checkIn.isBefore(LocalDate.now(BookingService.JST))) {
            throw new InvalidBookingDatesException();
        }
        datesLock.acquire();
        if (!blockedPeriods.findOverlapping(checkIn, checkOut.minusDays(1)).isEmpty()) {
            throw new DatesTakenException();
        }
        int updated;
        try {
            updated = jdbc.update("""
                    update bookings set check_in = ?, check_out = ?
                    where id = ? and status = 'CONFIRMED'
                    """, checkIn, checkOut, bookingId);
        } catch (DataIntegrityViolationException e) {
            throw new DatesTakenException(); // exclusion constraint: пересечение с чужой бронью
        }
        if (updated == 0) {
            throw new BookingExpiredException(); // переносить можно только CONFIRMED
        }
        bookingService.notifyBookingEvent(b.getUser(), "BOOKING_RESCHEDULED",
                checkIn, checkOut, "ADMIN");
    }
}
```

`admin/AdminBookingController.java`:

```java
package com.batowka.guestbooking.admin;

import com.batowka.guestbooking.booking.AdminBookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/bookings")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminBookingController {

    private final AdminBookingService service;

    public record RescheduleRequest(@NotNull LocalDate checkIn, @NotNull LocalDate checkOut) {
    }

    @GetMapping
    public List<AdminBookingService.BookingRow> list() {
        return service.list();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable long id) {
        service.cancel(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reschedule")
    public ResponseEntity<Void> reschedule(@PathVariable long id,
                                           @Valid @RequestBody RescheduleRequest body) {
        service.reschedule(id, body.checkIn(), body.checkOut());
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests 'AdminBookingTest' --tests 'AdminBlockedPeriodTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: админ отменяет и переносит любые брони без OTP, гость получает уведомление"
```

**Чекпоинт сессии 3 для владельца:** почему у админа нет OTP-шага и как одно и то же `notifyBookingEvent` обслуживает обе роли через `by`.

---

## Сессия 4 — белый список и pending-отмена гостя

### Task 7: V3 (deleted_at) + 401 для удалённых

**Files:**
- Create: `backend-api/src/main/resources/db/migration/V3__users_deleted_at.sql`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/user/UserGoneException.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/user/UserAccount.java` (поле deletedAt)
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/user/UserAccountRepository.java` (findByPhoneAndDeletedAtIsNull)
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/auth/AuthController.java` (login/adminLogin — только живые; authCookie → public)
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/auth/MeController.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java` (requireTelegramLinked)
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/messaging/ContactSharedConsumer.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/user/SoftDeletedUserTest.java`

**Interfaces:**
- Produces: `UserAccount.getDeletedAt()/setDeletedAt(Instant)`; `UserAccountRepository.findByPhoneAndDeletedAtIsNull(String) → Optional<UserAccount>`; `UserGoneException` → 401 + затирающая cookie. Task 8 (softDelete) и Task 10/11 (заявки) зависят.

- [ ] **Step 1: Написать падающий тест**

```java
package com.batowka.guestbooking.user;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.auth.JwtAuthFilter;
import com.batowka.guestbooking.auth.JwtService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class SoftDeletedUserTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Long deletedUser(String phone) {
        return jdbc.queryForObject(
                "insert into users(phone, name, deleted_at) values (?, 'Бывший', now()) returning id",
                Long.class, phone);
    }

    @Test
    void deletedUserCannotLogin() throws Exception {
        deletedUser("+81390000001");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81390000001\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PHONE"));
    }

    @Test
    void staleJwtOfDeletedUserGives401AndClearsCookie() throws Exception {
        Long id = deletedUser("+81390000002");
        Cookie stale = new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(id, Role.FRIEND));

        mvc.perform(get("/api/me").cookie(stale))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge(JwtAuthFilter.COOKIE_NAME, 0));
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `cd backend-api && ./gradlew test --tests 'SoftDeletedUserTest'`
Expected: FAIL — миграции нет (колонка deleted_at отсутствует), /api/me даёт 200 или 500.

- [ ] **Step 3: Реализация**

`V3__users_deleted_at.sql`:

```sql
-- Soft delete: NULL = активен. Телефон уникален, поэтому повторное одобрение
-- ранее удалённого реактивирует запись (история броней возвращается).
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;
```

`UserAccount` — поле:

```java
    @Column(name = "deleted_at")
    private Instant deletedAt;
```

`UserAccountRepository`:

```java
    Optional<UserAccount> findByPhoneAndDeletedAtIsNull(String phone);
```

`user/UserGoneException.java`:

```java
package com.batowka.guestbooking.user;

/** Валидный токен, но пользователь удалён из белого списка: 401 + затирание cookie. */
public class UserGoneException extends RuntimeException {
    public UserGoneException() {
        super("Доступ отозван");
    }
}
```

`AuthController`: `login` и `adminLogin` — заменить `users.findByPhone(...)` на `users.findByPhoneAndDeletedAtIsNull(...)`; `authCookie` и `noContentWithCookie` сделать `public static`.

`MeController.me`:

```java
        UserAccount user = users.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(UserGoneException::new);
```

`BookingService.requireTelegramLinked` — та же замена `orElseThrow()` → `.filter(u -> u.getDeletedAt() == null).orElseThrow(UserGoneException::new)`.

`ContactSharedConsumer.handleContactShared` — `users.findByPhone(...)` → `users.findByPhoneAndDeletedAtIsNull(...)`.

`GlobalExceptionHandler` — хендлер с ResponseEntity (нужен заголовок Set-Cookie):

```java
    @ExceptionHandler(UserGoneException.class)
    public org.springframework.http.ResponseEntity<ApiError> userGone(UserGoneException ex) {
        return org.springframework.http.ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .header(org.springframework.http.HttpHeaders.SET_COOKIE,
                        com.batowka.guestbooking.auth.AuthController
                                .authCookie("", java.time.Duration.ZERO).toString())
                .body(new ApiError("UNAUTHORIZED", ex.getMessage()));
    }
```

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests 'SoftDeletedUserTest' --tests 'GuestLoginTest' --tests 'AdminLoginTest' --tests 'MeControllerTest' --tests 'ContactSharedConsumerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: V3 deleted_at — soft delete закрывает логин, токены и онбординг"
```

### Task 8: WhitelistService + AdminUserController

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/user/WhitelistService.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/user/UserNotFoundException.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/user/AlreadyMemberException.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/user/ActiveBookingExistsException.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/user/CannotDeleteAdminException.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/admin/AdminUserController.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/admin/AdminUserTest.java`

**Interfaces:**
- Consumes: Task 7 (deletedAt, findByPhoneAndDeletedAtIsNull), `Phones.normalize`, `Clock`-бин (SecurityConfig), `BookingService.JST`.
- Produces: `WhitelistService.list() → List<UserRow>`, `add(String rawPhone, String name) → UserAccount`, `addNormalized(String phone, String name) → UserAccount` (телефон УЖЕ нормализован; живой → AlreadyMemberException, удалённый → реактивация, нет → создание FRIEND), `softDelete(long id)`; `record UserRow(long id, String phone, String name, Role role, boolean telegramLinked, java.time.Instant deletedAt)`. Task 11 (approve) вызывает `addNormalized`.
- Produces: коды 409 — `ALREADY_MEMBER`, `ACTIVE_BOOKING_EXISTS`, `CANNOT_DELETE_ADMIN`; 404 «Пользователь не найден».

- [ ] **Step 1: Написать падающий тест**

```java
package com.batowka.guestbooking.admin;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminUserTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Cookie adminAuth() {
        Long adminId = jdbc.queryForObject("select id from users where role = 'ADMIN'", Long.class);
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(adminId, Role.ADMIN));
    }

    @Test
    void addListDelete() throws Exception {
        mvc.perform(post("/api/admin/users").cookie(adminAuth()).contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81311100001\", \"name\": \"Новый друг\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/admin/users").cookie(adminAuth()))
                .andExpect(status().isOk())
                // [0] — админ из сидера, [1] — добавленный
                .andExpect(jsonPath("$[1].name").value("Новый друг"))
                .andExpect(jsonPath("$[1].deletedAt").isEmpty());

        Long id = jdbc.queryForObject(
                "select id from users where phone = '+81311100001'", Long.class);
        mvc.perform(delete("/api/admin/users/" + id).cookie(adminAuth()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from users where id = " + id, Boolean.class)).isTrue();
    }

    @Test
    void duplicateLivePhoneGives409() throws Exception {
        jdbc.update("insert into users(phone, name) values ('+81311100002', 'Есть')");
        mvc.perform(post("/api/admin/users").cookie(adminAuth()).contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81311100002\", \"name\": \"Дубль\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
    }

    @Test
    void deleteWithActiveBookingGives409() throws Exception {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values ('+81311100003', 'Маша', 779501) returning id",
                Long.class);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2028-08-01', '2028-08-05', 'CONFIRMED')
                """, id);
        mvc.perform(delete("/api/admin/users/" + id).cookie(adminAuth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_BOOKING_EXISTS"));
    }

    @Test
    void deleteAdminGives409() throws Exception {
        Long adminId = jdbc.queryForObject("select id from users where role = 'ADMIN'", Long.class);
        mvc.perform(delete("/api/admin/users/" + adminId).cookie(adminAuth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_DELETE_ADMIN"));
    }

    @Test
    void reAddingDeletedReactivatesWithHistory() throws Exception {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name, deleted_at) values ('+81311100004', 'Бывший', now()) returning id",
                Long.class);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status, cancelled_by)
                values (?, '2026-01-01', '2026-01-05', 'CANCELLED', 'GUEST')
                """, id);

        mvc.perform(post("/api/admin/users").cookie(adminAuth()).contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81311100004\", \"name\": \"Вернулся\"}"))
                .andExpect(status().isCreated());

        // та же запись, история броней на месте
        assertThat(jdbc.queryForObject(
                "select count(*) from users where phone = '+81311100004'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select deleted_at is null from users where id = " + id, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from bookings where user_id = " + id, Integer.class)).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `cd backend-api && ./gradlew test --tests 'AdminUserTest'`
Expected: FAIL — эндпоинтов нет.

- [ ] **Step 3: Реализация**

Четыре исключения — по образцу (конструктор с русским сообщением):

```java
package com.batowka.guestbooking.user;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException() {
        super("Пользователь не найден");
    }
}
```

```java
package com.batowka.guestbooking.user;

public class AlreadyMemberException extends RuntimeException {
    public AlreadyMemberException() {
        super("Этот номер уже в белом списке");
    }
}
```

```java
package com.batowka.guestbooking.user;

public class ActiveBookingExistsException extends RuntimeException {
    public ActiveBookingExistsException() {
        super("У пользователя есть активная бронь — сначала отмените её");
    }
}
```

```java
package com.batowka.guestbooking.user;

public class CannotDeleteAdminException extends RuntimeException {
    public CannotDeleteAdminException() {
        super("Админа удалить нельзя");
    }
}
```

`user/WhitelistService.java`:

```java
package com.batowka.guestbooking.user;

import com.batowka.guestbooking.auth.InvalidPhoneException;
import com.batowka.guestbooking.auth.Phones;
import com.batowka.guestbooking.booking.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WhitelistService {

    private final UserAccountRepository users;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public record UserRow(long id, String phone, String name, Role role,
                          boolean telegramLinked, Instant deletedAt) {
    }

    @Transactional(readOnly = true)
    public List<UserRow> list() {
        return users.findAll(Sort.by("id")).stream()
                .map(u -> new UserRow(u.getId(), u.getPhone(), u.getName(), u.getRole(),
                        u.getTelegramChatId() != null, u.getDeletedAt()))
                .toList();
    }

    @Transactional
    public UserAccount add(String rawPhone, String name) {
        String phone = Phones.normalize(rawPhone).orElseThrow(InvalidPhoneException::new);
        return addNormalized(phone, name);
    }

    /** Создание или реактивация (телефон уже нормализован). Живой номер → 409. */
    @Transactional
    public UserAccount addNormalized(String phone, String name) {
        Optional<UserAccount> existing = users.findByPhone(phone);
        if (existing.isPresent() && existing.get().getDeletedAt() == null) {
            throw new AlreadyMemberException();
        }
        UserAccount user = existing.orElseGet(UserAccount::new);
        user.setPhone(phone);
        user.setName(name);
        user.setDeletedAt(null); // реактивация: история броней возвращается владельцу номера
        return users.save(user);
    }

    @Transactional
    public void softDelete(long id) {
        UserAccount user = users.findById(id)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(UserNotFoundException::new);
        if (user.getRole() == Role.ADMIN) {
            throw new CannotDeleteAdminException();
        }
        Integer active = jdbc.queryForObject("""
                select count(*) from bookings
                where user_id = ? and status in ('PENDING_OTP', 'CONFIRMED') and check_out > ?
                """, Integer.class, id, LocalDate.now(BookingService.JST));
        if (active != null && active > 0) {
            throw new ActiveBookingExistsException();
        }
        user.setDeletedAt(clock.instant());
        users.save(user);
    }
}
```

`admin/AdminUserController.java`:

```java
package com.batowka.guestbooking.admin;

import com.batowka.guestbooking.user.WhitelistService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final WhitelistService whitelist;

    public record AddRequest(@NotBlank String phone, @NotBlank @Size(max = 100) String name) {
    }

    @GetMapping
    public List<WhitelistService.UserRow> list() {
        return whitelist.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void add(@Valid @RequestBody AddRequest body) {
        whitelist.add(body.phone(), body.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        whitelist.softDelete(id);
    }
}
```

`GlobalExceptionHandler` — четыре хендлера по образцу существующих: `UserNotFoundException` → 404 `NOT_FOUND`, `AlreadyMemberException` → 409 `ALREADY_MEMBER`, `ActiveBookingExistsException` → 409 `ACTIVE_BOOKING_EXISTS`, `CannotDeleteAdminException` → 409 `CANNOT_DELETE_ADMIN` (каждый: `new ApiError("<КОД>", ex.getMessage())`).

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests 'AdminUserTest' --tests 'SoftDeletedUserTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: белый список — список, добавление, soft delete c проверками"
```

### Task 9: Гостевая отмена pending + мелкие фиксы

**Files:**
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java` (cancelPending, честный OVERLAPS_OWN, resend-статус)
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingController.java` (DELETE /pending, POST → 201)
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/booking/OverlapsOwnBookingException.java` (конструктор с сообщением)
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/otp/OtpService.java` (expireActive)
- Modify: `backend-api/src/test/java/com/batowka/guestbooking/booking/CreateBookingTest.java` (`isOk()` → `isCreated()` в существующих тестах)
- Test: `backend-api/src/test/java/com/batowka/guestbooking/booking/CancelPendingTest.java`

**Interfaces:**
- Consumes: `BookingNotFoundException` (Task 2).
- Produces: `BookingService.cancelPending(Long userId)`; `OtpService.expireActive(Long userId)`; `POST /api/bookings` → 201.

- [ ] **Step 1: Написать падающий тест**

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CancelPendingTest extends AbstractIntegrationTest {

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

    @Test
    void cancelPendingFreesDatesImmediately() throws Exception {
        Long id = guest("+81312200001", 779601L);
        mvc.perform(post("/api/bookings").cookie(auth(id)).contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2028-09-01\", \"checkOut\": \"2028-09-05\"}"))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/bookings/pending").cookie(auth(id)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status || ':' || cancelled_by from bookings order by id desc limit 1",
                String.class)).isEqualTo("CANCELLED:GUEST");
        // челлендж вытеснен — старый код больше не подойдёт
        assertThat(jdbc.queryForObject(
                "select count(*) from otp_challenges where status = 'PENDING'", Integer.class)).isZero();

        // даты сразу свободны для другого гостя
        Long other = guest("+81312200002", 779602L);
        mvc.perform(post("/api/bookings").cookie(auth(other)).contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2028-09-01\", \"checkOut\": \"2028-09-05\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void cancelPendingWithoutPendingGives404() throws Exception {
        Long id = guest("+81312200003", 779603L);
        mvc.perform(delete("/api/bookings/pending").cookie(auth(id)))
                .andExpect(status().isNotFound());
    }

    @Test
    void overlapsOwnPendingHasHonestHint() throws Exception {
        Long id = guest("+81312200004", 779604L);
        mvc.perform(post("/api/bookings").cookie(auth(id)).contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2028-10-01\", \"checkOut\": \"2028-10-05\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/bookings").cookie(auth(id)).contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2028-10-03\", \"checkOut\": \"2028-10-08\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERLAPS_OWN_BOOKING"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("неподтверждённая")));
    }

    @Test
    void resendForCancelledBookingGives409() throws Exception {
        Long id = guest("+81312200005", 779605L);
        Long bookingId = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status, cancelled_by)
                values (?, '2028-11-01', '2028-11-05', 'CANCELLED', 'GUEST') returning id
                """, Long.class, id);
        mvc.perform(post("/api/bookings/" + bookingId + "/resend-code").cookie(auth(id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_EXPIRED"));
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `cd backend-api && ./gradlew test --tests 'CancelPendingTest'`
Expected: FAIL.

- [ ] **Step 3: Реализация**

`OtpService` — публичный метод (SQL тот же, что в начале `issue`):

```java
    /** Вытесняет активный челлендж гостя (отмена pending-брони и т.п.). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void expireActive(Long userId) {
        jdbc.update("""
                update otp_challenges set status = 'EXPIRED'
                where user_id = ? and status = 'PENDING'
                """, userId);
    }
```

`BookingService`:

```java
    /** Явная отмена своей неподтверждённой брони: даты свободны сразу, чистильщика не ждём. */
    @Transactional
    public void cancelPending(Long userId) {
        Booking pending = bookings
                .findFirstByUserIdAndStatusOrderByIdDesc(userId, BookingStatus.PENDING_OTP)
                .orElseThrow(BookingNotFoundException::new);
        jdbc.update("""
                update bookings set status = 'CANCELLED', cancelled_by = 'GUEST'
                where id = ? and status = 'PENDING_OTP'
                """, pending.getId());
        otp.expireActive(userId);
    }
```

Честный OVERLAPS_OWN — заменить блок overlapsOwn в `create(...)`:

```java
        List<Booking> own = bookings
                .findOverlapping(checkIn, checkOut.minusDays(1), ACTIVE).stream()
                .filter(b -> b.getUser().getId().equals(userId))
                .toList();
        if (!own.isEmpty()) {
            boolean pending = own.stream()
                    .anyMatch(b -> b.getStatus() == BookingStatus.PENDING_OTP);
            if (pending) {
                throw new OverlapsOwnBookingException(
                        "Эти даты держит ваша неподтверждённая бронь — подтвердите её кодом или отмените");
            }
            throw new OverlapsOwnBookingException();
        }
```

`OverlapsOwnBookingException` — добавить конструктор `public OverlapsOwnBookingException(String message) { super(message); }` (существующий no-arg конструктор с его сообщением не трогать).

Resend-статус — в `resendCode(...)` после `requireOwnership`:

```java
        requireNotCancelled(bookingId);
```

и приватный метод:

```java
    private void requireNotCancelled(long bookingId) {
        String status;
        try {
            status = jdbc.queryForObject(
                    "select status from bookings where id = ?", String.class, bookingId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new BookingNotFoundException();
        }
        if ("CANCELLED".equals(status)) {
            throw new BookingExpiredException();
        }
    }
```

`BookingController`: на `create` добавить `@ResponseStatus(HttpStatus.CREATED)` (импорты `org.springframework.web.bind.annotation.ResponseStatus`… аннотация из `org.springframework.web.bind.annotation`), новый маппинг ПЕРЕД `@DeleteMapping("/{id}")` (точный литеральный путь выигрывает у шаблонного — порядок в файле не важен, но рядом читабельнее):

```java
    @DeleteMapping("/pending")
    public ResponseEntity<Void> cancelPending(Authentication auth) {
        bookingService.cancelPending((Long) auth.getPrincipal());
        return ResponseEntity.noContent().build();
    }
```

В `CreateBookingTest` заменить все `status().isOk()` на `status().isCreated()` (три места).

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests 'CancelPendingTest' --tests 'CreateBookingTest' --tests 'CleanerAndResendTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: гость отменяет pending-бронь явно; честный OVERLAPS_OWN; POST брони — 201"
```

**Чекпоинт сессии 4 для владельца:** soft delete и его хвосты (уникальность телефона, реактивация, где фильтровать живых); почему `DELETE /pending` не конфликтует с `DELETE /{id}`.

---

## Сессия 5 — заявки на доступ end-to-end

### Task 10: Публичная подача заявки + событие админу

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/accessrequest/AccessRequest.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/accessrequest/AccessRequestStatus.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/accessrequest/AccessRequestRepository.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/accessrequest/AccessRequestService.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/accessrequest/AccessRequestController.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/auth/SecurityConfig.java` (permitAll)
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java` (если ALREADY_MEMBER ещё не замаплен — Task 8 уже добавил)
- Test: `backend-api/src/test/java/com/batowka/guestbooking/accessrequest/SubmitAccessRequestTest.java`

**Interfaces:**
- Consumes: `Phones.normalize`, `LoginRateLimiter.check(ip)` (общий бакет с логином — осознанно: 5/мин с IP на все «дешёвые» публичные POST), `OutboxWriter.write`, `AlreadyMemberException` (Task 8), `findByPhoneAndDeletedAtIsNull` (Task 7).
- Produces: `AccessRequestService.submit(String rawPhone, String name, String message)`; entity `AccessRequest` (getters/setters: id, phone, name, message, status, createdAt, resolvedAt); `AccessRequestRepository.existsByPhoneAndStatus(String, AccessRequestStatus)`, `findAllByStatusOrderByIdDesc(AccessRequestStatus)`; enum `AccessRequestStatus {PENDING, APPROVED, REJECTED}`. Task 11 зависит.

- [ ] **Step 1: Написать падающий тест**

```java
package com.batowka.guestbooking.accessrequest;

import com.batowka.guestbooking.AbstractIntegrationTest;
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
class SubmitAccessRequestTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static final String BODY =
            "{\"phone\": \"+81313300001\", \"name\": \"Незнакомец\", \"message\": \"друг Миши\"}";

    @Test
    void submitCreatesPendingAndNotifiesLinkedAdmin() throws Exception {
        // админ с привязанным TG (сидер создаёт без chat_id)
        jdbc.update("update users set telegram_chat_id = 779700 where role = 'ADMIN'");

        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject(
                "select status from access_requests limit 1", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                select payload->'payload'->>'name' from outbox
                where event_type = 'ACCESS_REQUEST_RECEIVED'
                """, String.class)).isEqualTo("Незнакомец");
    }

    @Test
    void repeatWhilePendingIsIdempotent() throws Exception {
        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject(
                "select count(*) from access_requests", Integer.class)).isEqualTo(1);
    }

    @Test
    void memberPhoneGives409() throws Exception {
        jdbc.update("insert into users(phone, name) values ('+81313300002', 'Свой')");
        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81313300002\", \"name\": \"Свой\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
    }

    @Test
    void unlinkedAdminMeansNoEventButRequestSaved() throws Exception {
        // у сидерного админа telegram_chat_id = null
        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject(
                "select count(*) from access_requests", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'ACCESS_REQUEST_RECEIVED'",
                Integer.class)).isZero();
    }

    @Test
    void rateLimitAfterFiveAttempts() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON)
                            .content("{\"phone\": \"+8131330100" + i + "\", \"name\": \"Спамер\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81313301009\", \"name\": \"Спамер\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `cd backend-api && ./gradlew test --tests 'SubmitAccessRequestTest'`
Expected: FAIL.

- [ ] **Step 3: Реализация**

`accessrequest/AccessRequestStatus.java`:

```java
package com.batowka.guestbooking.accessrequest;

public enum AccessRequestStatus { PENDING, APPROVED, REJECTED }
```

`accessrequest/AccessRequest.java`:

```java
package com.batowka.guestbooking.accessrequest;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "access_requests")
@Getter
@Setter
@NoArgsConstructor
public class AccessRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccessRequestStatus status = AccessRequestStatus.PENDING;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
```

`accessrequest/AccessRequestRepository.java`:

```java
package com.batowka.guestbooking.accessrequest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, Long> {

    boolean existsByPhoneAndStatus(String phone, AccessRequestStatus status);

    List<AccessRequest> findAllByStatusOrderByIdDesc(AccessRequestStatus status);
}
```

`accessrequest/AccessRequestService.java` (submit-часть; approve/reject добавит Task 11):

```java
package com.batowka.guestbooking.accessrequest;

import com.batowka.guestbooking.auth.InvalidPhoneException;
import com.batowka.guestbooking.auth.Phones;
import com.batowka.guestbooking.messaging.OutboxWriter;
import com.batowka.guestbooking.user.AlreadyMemberException;
import com.batowka.guestbooking.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
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
        requests.save(r);
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
```

`accessrequest/AccessRequestController.java`:

```java
package com.batowka.guestbooking.accessrequest;

import com.batowka.guestbooking.auth.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/access-requests")
@RequiredArgsConstructor
public class AccessRequestController {

    private final AccessRequestService service;
    private final LoginRateLimiter rateLimiter;

    public record SubmitRequest(@NotBlank String phone, @NotBlank @Size(max = 100) String name,
                                @Size(max = 500) String message) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void submit(@Valid @RequestBody SubmitRequest body, HttpServletRequest request) {
        // сайт публичный — форму обстреливают; общий бакет с логином: 5/мин с IP
        rateLimiter.check(request.getRemoteAddr());
        service.submit(body.phone(), body.name(), body.message());
    }
}
```

`SecurityConfig` — в `authorizeHttpRequests` первой строкой рядом с auth:

```java
                        .requestMatchers("/api/calendar", "/api/auth/**", "/api/access-requests").permitAll()
```

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests 'SubmitAccessRequestTest' --tests 'LoginRateLimiterTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: публичная заявка на доступ — идемпотентная, с rate limit и событием админу"
```

### Task 11: Approve/reject в админке

**Files:**
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/accessrequest/AccessRequestService.java` (approve, reject, list)
- Create: `backend-api/src/main/java/com/batowka/guestbooking/accessrequest/AccessRequestNotFoundException.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/accessrequest/AlreadyResolvedException.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/admin/AdminAccessRequestController.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/accessrequest/ResolveAccessRequestTest.java`

**Interfaces:**
- Consumes: `WhitelistService.addNormalized(phone, name)` (Task 8), `Clock`-бин, Task 10 целиком.
- Produces: `AccessRequestService.approve(long id)`, `reject(long id)`, `list(AccessRequestStatus) → List<AccessRequest>`; 404 «Заявка не найдена», 409 `ALREADY_RESOLVED`.

- [ ] **Step 1: Написать падающий тест**

```java
package com.batowka.guestbooking.accessrequest;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ResolveAccessRequestTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Cookie adminAuth() {
        Long adminId = jdbc.queryForObject("select id from users where role = 'ADMIN'", Long.class);
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(adminId, Role.ADMIN));
    }

    private long pendingRequest(String phone) {
        return jdbc.queryForObject(
                "insert into access_requests(phone, name, message) values (?, 'Незнакомец', 'пустите') returning id",
                Long.class, phone);
    }

    @Test
    void approveCreatesFriendWhoCanLogin() throws Exception {
        long id = pendingRequest("+81314400001");
        mvc.perform(post("/api/admin/access-requests/" + id + "/approve").cookie(adminAuth()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status || ':' || (resolved_at is not null) from access_requests where id = " + id,
                String.class)).isEqualTo("APPROVED:true");
        assertThat(jdbc.queryForObject(
                "select role from users where phone = '+81314400001'", String.class)).isEqualTo("FRIEND");

        // новый друг может логиниться
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81314400001\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void approveReactivatesDeletedUser() throws Exception {
        jdbc.update("insert into users(phone, name, deleted_at) values ('+81314400002', 'Бывший', now())");
        long id = pendingRequest("+81314400002");
        mvc.perform(post("/api/admin/access-requests/" + id + "/approve").cookie(adminAuth()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "select count(*) from users where phone = '+81314400002' and deleted_at is null",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectOnlyMarksRequest() throws Exception {
        long id = pendingRequest("+81314400003");
        mvc.perform(post("/api/admin/access-requests/" + id + "/reject").cookie(adminAuth()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "select status from access_requests where id = " + id, String.class))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "select count(*) from users where phone = '+81314400003'", Integer.class)).isZero();
    }

    @Test
    void doubleResolveGives409() throws Exception {
        long id = pendingRequest("+81314400004");
        mvc.perform(post("/api/admin/access-requests/" + id + "/reject").cookie(adminAuth()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/access-requests/" + id + "/approve").cookie(adminAuth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RESOLVED"));
    }

    @Test
    void listDefaultsToPending() throws Exception {
        pendingRequest("+81314400005");
        long resolved = pendingRequest("+81314400006");
        jdbc.update("update access_requests set status = 'REJECTED' where id = ?", resolved);

        mvc.perform(get("/api/admin/access-requests").cookie(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].phone").value("+81314400005"));
    }

    @Test
    void unknownRequestGives404() throws Exception {
        mvc.perform(post("/api/admin/access-requests/999/approve").cookie(adminAuth()))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `cd backend-api && ./gradlew test --tests 'ResolveAccessRequestTest'`
Expected: FAIL.

- [ ] **Step 3: Реализация**

Исключения:

```java
package com.batowka.guestbooking.accessrequest;

public class AccessRequestNotFoundException extends RuntimeException {
    public AccessRequestNotFoundException() {
        super("Заявка не найдена");
    }
}
```

```java
package com.batowka.guestbooking.accessrequest;

public class AlreadyResolvedException extends RuntimeException {
    public AlreadyResolvedException() {
        super("Заявка уже рассмотрена");
    }
}
```

В `AccessRequestService` — внедрить `WhitelistService whitelist` и `java.time.Clock clock`, добавить:

```java
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
        if (r.getStatus() != AccessRequestStatus.PENDING) {
            throw new AlreadyResolvedException();
        }
        r.setStatus(target);
        r.setResolvedAt(clock.instant());
        return requests.save(r);
    }
```

`admin/AdminAccessRequestController.java`:

```java
package com.batowka.guestbooking.admin;

import com.batowka.guestbooking.accessrequest.AccessRequest;
import com.batowka.guestbooking.accessrequest.AccessRequestService;
import com.batowka.guestbooking.accessrequest.AccessRequestStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/access-requests")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAccessRequestController {

    private final AccessRequestService service;

    public record RequestRow(long id, String phone, String name, String message,
                             AccessRequestStatus status, Instant createdAt, Instant resolvedAt) {
    }

    @GetMapping
    public List<RequestRow> list(@RequestParam(defaultValue = "PENDING") AccessRequestStatus status) {
        return service.list(status).stream()
                .map(r -> new RequestRow(r.getId(), r.getPhone(), r.getName(), r.getMessage(),
                        r.getStatus(), r.getCreatedAt(), r.getResolvedAt()))
                .toList();
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable long id) {
        service.approve(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable long id) {
        service.reject(id);
        return ResponseEntity.noContent().build();
    }
}
```

`GlobalExceptionHandler` — `AccessRequestNotFoundException` → 404 `NOT_FOUND`, `AlreadyResolvedException` → 409 `ALREADY_RESOLVED` (по образцу).

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests 'ResolveAccessRequestTest' --tests 'SubmitAccessRequestTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: approve/reject заявок — создание или реактивация друга в белом списке"
```

### Task 12: Бот — рендер заявки, `by`, русские даты

**Files:**
- Modify: `bot-service/internal/events/events.go` (AccessRequestReceived, BookingEvent.By)
- Modify: `bot-service/internal/kafka/consumer.go` (кейс ACCESS_REQUEST_RECEIVED, ruDate, тексты «владельцем»)
- Test: `bot-service/internal/kafka/consumer_test.go` (дополнить)

**Interfaces:**
- Consumes: payload'ы из задач 5, 10.
- Produces: тексты бота с русскими датами; функция `ruDate(iso string) string`.

- [ ] **Step 1: Написать падающие тесты**

В `consumer_test.go` добавить (использовать существующий в файле фейковый Sender по образцу соседних тестов; envelope-хелпер — как в существующих тестах файла):

```go
func TestRuDate(t *testing.T) {
	if got := ruDate("2026-03-22"); got != "22 марта 2026" {
		t.Errorf("ruDate: %q", got)
	}
	if got := ruDate("кривая-дата"); got != "кривая-дата" {
		t.Errorf("ruDate fallback: %q", got)
	}
}

func TestBookingCancelledByAdminText(t *testing.T) {
	sender := &fakeSender{}
	core := newConsumerCore(sender)
	raw := []byte(`{"event_id":"11111111-1111-4111-8111-111111111111","event_type":"BOOKING_CANCELLED",` +
		`"payload":{"chat_id":5,"guest_name":"Маша","check_in":"2026-03-22","check_out":"2026-03-25","by":"ADMIN"}}`)
	if err := core.handle(context.Background(), raw); err != nil {
		t.Fatal(err)
	}
	want := "Бронь отменена владельцем: Маша, заезд 22 марта 2026, выезд 25 марта 2026."
	if sender.lastText != want {
		t.Errorf("текст: %q, ожидался %q", sender.lastText, want)
	}
}

func TestAccessRequestReceivedRender(t *testing.T) {
	sender := &fakeSender{}
	core := newConsumerCore(sender)
	raw := []byte(`{"event_id":"22222222-2222-4222-8222-222222222222","event_type":"ACCESS_REQUEST_RECEIVED",` +
		`"payload":{"chat_id":9,"name":"Незнакомец","phone":"+81313300001","message":"друг Миши"}}`)
	if err := core.handle(context.Background(), raw); err != nil {
		t.Fatal(err)
	}
	want := "Новая заявка на доступ: Незнакомец, +81313300001.\nКомментарий: друг Миши"
	if sender.lastText != want {
		t.Errorf("текст: %q, ожидался %q", sender.lastText, want)
	}
}
```

(если у фейкового Sender'а в файле другое имя/поля — подстроиться под существующие; `lastText` — поле с последним отправленным текстом.)

- [ ] **Step 2: Убедиться, что падают**

Run: `cd bot-service && go test ./internal/kafka/`
Expected: FAIL (ruDate не определена — не компилируется; это и есть красный).

- [ ] **Step 3: Реализация**

`events.go`:

```go
type BookingEvent struct {
	ChatID    int64  `json:"chat_id"`
	GuestName string `json:"guest_name"`
	CheckIn   string `json:"check_in"`
	CheckOut  string `json:"check_out"`
	By        string `json:"by,omitempty"` // GUEST | ADMIN; пусто у старых событий
}

type AccessRequestReceived struct {
	ChatID  int64  `json:"chat_id"`
	Name    string `json:"name"`
	Phone   string `json:"phone"`
	Message string `json:"message,omitempty"`
}
```

`consumer.go` — русские даты (ISO живёт внутри системы, русский — только на краю, в тексте):

```go
var ruMonths = [...]string{"января", "февраля", "марта", "апреля", "мая", "июня",
	"июля", "августа", "сентября", "октября", "ноября", "декабря"}

// ruDate переводит ISO-дату в «22 марта 2026»; нераспознанное отдаёт как есть.
func ruDate(iso string) string {
	t, err := time.Parse("2006-01-02", iso)
	if err != nil {
		return iso
	}
	return fmt.Sprintf("%d %s %d", t.Day(), ruMonths[t.Month()-1], t.Year())
}
```

(добавить `"fmt"` в импорты.)

`renderBooking` — учесть `By` и русские даты:

```go
func (c *consumerCore) renderBooking(ctx context.Context, env events.Envelope, prefix string) error {
	var p events.BookingEvent
	if err := json.Unmarshal(env.Payload, &p); err != nil {
		log.Printf("битый payload %s: %v", env.EventType, err)
		return nil
	}
	if p.By == "ADMIN" {
		prefix += " владельцем"
	}
	return c.send(ctx, env.EventID, p.ChatID,
		prefix+": "+p.GuestName+", заезд "+ruDate(p.CheckIn)+", выезд "+ruDate(p.CheckOut)+".")
}
```

Новый кейс в `handle` (перед `default`):

```go
	case "ACCESS_REQUEST_RECEIVED":
		var p events.AccessRequestReceived
		if err := json.Unmarshal(env.Payload, &p); err != nil {
			log.Printf("битый payload ACCESS_REQUEST_RECEIVED: %v", err)
			return nil
		}
		text := "Новая заявка на доступ: " + p.Name + ", " + p.Phone + "."
		if p.Message != "" {
			text += "\nКомментарий: " + p.Message
		}
		return c.send(ctx, env.EventID, p.ChatID, text)
```

- [ ] **Step 4: Прогнать тесты**

Run: `cd bot-service && gofmt -l . && go vet ./... && go test ./...`
Expected: gofmt пусто, тесты PASS (существующие тесты рендеров поправить на русские даты, если они проверяли ISO-текст).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: бот — рендер заявок на доступ, тексты «владельцем», русские даты"
```

**Чекпоинт сессии 5 для владельца:** путь заявки от формы до Telegram-сообщения (форма → outbox → Kafka → бот); почему `by` — расширение payload'а, совместимое со старыми событиями.

---

## Сессия 6 — учебный файл, финальная проверка, PR

### Task 13: docs/learning/05 + полный прогон + PR

**Files:**
- Create: `docs/learning/05-admin-api-authorization.md`
- Modify: `docs/learning/README.md` (строка про 05, по образцу существующих)

**Interfaces:** —

- [ ] **Step 1: Написать учебный файл**

`docs/learning/05-admin-api-authorization.md` — тутор-формат как в `04-booking-otp-state-machines.md`: каждый раздел ведёт от вопроса к решению и заканчивается якорями на реальный код (`файл:строка`). Обязательные разделы:

1. **Роли и авторизация в Spring Security** — путь запроса: JwtAuthFilter кладёт `ROLE_*` в контекст → URL-правило `/api/admin/**` (SecurityConfig) → `@PreAuthorize` на контроллере (вторая линия). Почему две линии, а не одна; куда летит `AccessDeniedException` и почему без хендлера был бы 500 (якоря: `SecurityConfig.java`, `AdminBlockedPeriodController.java`, `GlobalExceptionHandler.accessDenied`).
2. **Advisory locks в Postgres** — почему exclusion constraint не работает между таблицами; что делает `pg_advisory_xact_lock`; почему замок обязан жить в транзакции; чем это отличается от `serializable` (якоря: `DatesLock.java`, `BlockedPeriodService.create`, `DatesRaceTest.java`).
3. **Soft delete и его хвосты** — почему не `DELETE FROM users` (FK на брони, история); уникальность телефона → реактивация; три места, где фильтруются живые (логин, `/api/me`, онбординг контакта); 401 + затирающая cookie для валидного токена удалённого (якоря: `V3__users_deleted_at.sql`, `WhitelistService.java`, `GlobalExceptionHandler.userGone`).
4. **Проектирование админ-API без каскадов** — 409 со списком конфликтов вместо «умной» автоотмены; идемпотентная заявка; конечный автомат заявки PENDING→APPROVED|REJECTED (якоря: `OverlapsBookingException.java`, `AccessRequestService.java`).

- [ ] **Step 2: Полный прогон всего**

Run: `cd backend-api && ./gradlew test && cd ../bot-service && gofmt -l . && go vet ./... && go test ./...`
Expected: всё зелёное, gofmt пусто.

- [ ] **Step 3: Commit учебного файла**

```bash
git add docs/learning/ && git commit -m "docs: разбор этапа 5 — авторизация, advisory locks, soft delete (тутор-формат)"
```

- [ ] **Step 4: Финальное ревью**

Использовать скилл `superpowers:requesting-code-review` на диф `main...stage-5-admin-api`; блокеры — чинить, переносимое — в память проекта (файл stage2-carryover-items).

- [ ] **Step 5: PR**

```bash
git push -u origin stage-5-admin-api
gh pr create --title "Этап 5: админ-API, блокировки, белый список, заявки на доступ" --body "..."
```

(тело PR — краткое резюме по секциям спеки + ссылка на неё; после зелёного CI — мерж по решению владельца.)

---

## Самопроверка плана (выполнена)

- Покрытие спеки: §2 → Task 7; §3 → Tasks 1, 3, 4, 6; §4 публичное → Task 10, админское → Tasks 3, 6, 8, 11, гостевое → Task 9; §5 → Tasks 5, 10, 12; §6 → Tasks 2, 3, 7, 8, 11; §7 → тесты во всех задачах + Task 4; §8-9 → Task 13.
- Мелочи спеки §6: `POST /api/bookings` → 201 и resend-статус — Task 9; `AccessDeniedException` → 403 — Task 3 (в спеке была сессия 1 — сдвинуто к первому реальному `@PreAuthorize`, чтобы тест был честным, а не синтетическим).
- Типы сквозные: `notifyBookingEvent(..., String by)` объявлен в Task 5, используется в Task 6; `addNormalized` объявлен в Task 8, используется в Task 11; `DatesLock.acquire()` объявлен в Task 1, используется в Tasks 3, 6; `findByPhoneAndDeletedAtIsNull` объявлен в Task 7, используется в Tasks 10, 11.
