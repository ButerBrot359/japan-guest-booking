# План №1: каркас, БД и календарь-API (этапы 0–1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Работающий backend-api с реальной схемой БД (constraint'ы пересечений дат) и публичным `GET /api/calendar`, плюс каркас монорепо, dev-окружение и CI.

**Architecture:** Монорепо с тремя сервисами; в этом плане строится только backend-api (Spring Boot + PostgreSQL + Flyway) и инфраструктура вокруг (docker-compose.dev, GitHub Actions). Kafka поднимается в dev-compose уже сейчас, но код её не использует до плана №2.

**Tech Stack:** Java 21, Spring Boot 4.0.x (Gradle Kotlin DSL), PostgreSQL 16, Flyway, Testcontainers, GitHub Actions.

**Spec:** `docs/specs/2026-08-13-japan-guest-booking-design.md`

## Global Constraints

- Java 21, Spring Boot 4.0.x (4.0.7 на момент решения; 3.5.x снят с поддержки и недоступен на start.spring.io — решение от 2026-08-14), Gradle (wrapper коммитится в репо).
- PostgreSQL 16; миграции только через Flyway, `ddl-auto: validate`.
- Все даты — календарные дни (JST), бронь = полуинтервал `[check_in, check_out)`; блокировки админа — включительно `[start_date, end_date]`.
- Формат ошибок API единый: `{"code": "...", "message": "..."}`.
- TDD: сначала красный тест, потом код. Красные тесты не коммитятся.
- Каждый этап спеки завершается разбором в `docs/learning/` (Task 9).
- Пакет backend: `com.batowka.guestbooking`.

---

### Task 1: Каркас монорепо

**Files:**
- Create: `.gitignore`, `README.md`, `contracts/README.md`

**Interfaces:**
- Consumes: —
- Produces: структуру папок, в которую встают все последующие задачи.

- [ ] **Step 1: Создать .gitignore**

```gitignore
# Java / Gradle
backend-api/build/
backend-api/.gradle/

# Node
frontend/node_modules/
frontend/dist/

# Go
bot-service/bin/

# IDE / OS
.idea/
.vscode/
.DS_Store

# Секреты
.env
```

Внимание: `gradle/wrapper/gradle-wrapper.jar` НЕ игнорируется — wrapper коммитится.

- [ ] **Step 2: Создать README.md**

```markdown
# Japan Guest Booking

Бронирование дат визитов друзей. Монорепо:

- `backend-api/` — Spring Boot (Java 21), бизнес-логика и PostgreSQL
- `bot-service/` — Go, Telegram-бот (уведомления и онбординг)
- `frontend/` — React + TypeScript + Vite
- `contracts/` — JSON-схемы Kafka-событий между сервисами
- `docs/` — спека, планы, обучающие разборы

Локальная разработка:

```bash
docker compose -f docker-compose.dev.yml up -d   # Postgres + Kafka
cd backend-api && ./gradlew bootRun
```

Дизайн: `docs/specs/2026-08-13-japan-guest-booking-design.md`
```

- [ ] **Step 3: Создать contracts/README.md**

```markdown
# Contracts

JSON-схемы Kafka-событий (`notifications.outbound`, `telegram.inbound`).
Появятся в плане №2 вместе с самими событиями. Схемы — единственный
источник правды о формате сообщений для backend-api (Java) и bot-service (Go).
```

- [ ] **Step 4: Проверить и закоммитить**

Run: `git add -A && git status --short`
Expected: три новых файла, ничего лишнего.

```bash
git commit -m "chore: каркас монорепо"
```

---

### Task 2: docker-compose.dev.yml (Postgres + Kafka)

**Files:**
- Create: `docker-compose.dev.yml`

**Interfaces:**
- Consumes: —
- Produces: Postgres на `localhost:5432` (db `guestbooking`, user/pass `dev`/`dev`), Kafka на `localhost:9092`. Эти значения используют Task 3 (application.yml) и план №2.

- [ ] **Step 1: Создать docker-compose.dev.yml**

```yaml
services:
  postgres:
    image: postgres:16-alpine
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: guestbooking
      POSTGRES_USER: dev
      POSTGRES_PASSWORD: dev
    volumes:
      - pgdata:/var/lib/postgresql/data

  kafka:
    image: apache/kafka:3.9.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: INTERNAL://:19092,CONTROLLER://:9093,EXTERNAL://:9092
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:19092,EXTERNAL://localhost:9092
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1

volumes:
  pgdata:
```

- [ ] **Step 2: Поднять и проверить Postgres**

Run: `docker compose -f docker-compose.dev.yml up -d && docker compose -f docker-compose.dev.yml exec postgres pg_isready -U dev -d guestbooking`
Expected: `accepting connections`

- [ ] **Step 3: Проверить Kafka**

Run: `docker compose -f docker-compose.dev.yml exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list`
Expected: команда завершается без ошибок (список пуст — топиков ещё нет).

- [ ] **Step 4: Commit**

```bash
git add docker-compose.dev.yml
git commit -m "chore: dev-окружение — Postgres и Kafka в Docker Compose"
```

---

### Task 3: Скелет backend-api

**Files:**
- Create: `backend-api/` (генерируется start.spring.io), `backend-api/src/main/resources/application.yml`, `backend-api/src/test/java/com/batowka/guestbooking/AbstractIntegrationTest.java`
- Delete: сгенерированный `GuestBookingApplicationTests.java` (заменяется на наш базовый класс)

**Interfaces:**
- Consumes: Postgres из Task 2 (для `bootRun`).
- Produces: класс `AbstractIntegrationTest` — базовый для всех интеграционных тестов (Testcontainers + `@ServiceConnection`); приложение `GuestBookingApplication`.

- [ ] **Step 1: Сгенерировать проект**

```bash
mkdir backend-api && curl https://start.spring.io/starter.tgz \
  -d type=gradle-project-kotlin -d language=java -d javaVersion=21 \
  -d bootVersion=4.0.7 -d groupId=com.batowka -d artifactId=backend-api \
  -d name=GuestBooking -d packageName=com.batowka.guestbooking \
  -d dependencies=web,data-jpa,validation,flyway,postgresql,testcontainers,lombok \
  | tar -xzv -C backend-api
```

Проверить в `backend-api/build.gradle.kts`, что среди зависимостей есть:
`spring-boot-starter-web`, `spring-boot-starter-data-jpa`,
`spring-boot-starter-validation`, `flyway-database-postgresql`,
`postgresql` (runtime), `lombok`, `spring-boot-testcontainers`,
`testcontainers:junit-jupiter`, `testcontainers:postgresql`.

- [ ] **Step 2: Написать application.yml**

Заменить сгенерированный `application.properties` на `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/guestbooking
    username: dev
    password: dev
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
```

- [ ] **Step 3: Базовый интеграционный тест**

Удалить сгенерированный `GuestBookingApplicationTests.java`. Создать
`src/test/java/com/batowka/guestbooking/AbstractIntegrationTest.java`:

```java
package com.batowka.guestbooking;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
}
```

И смоук-тест `src/test/java/com/batowka/guestbooking/ContextLoadsTest.java`:

```java
package com.batowka.guestbooking;

import org.junit.jupiter.api.Test;

class ContextLoadsTest extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test`
Expected: PASS (Testcontainers поднимет Postgres; Flyway пока не находит миграций — это допустимо, он молча пропускает).

- [ ] **Step 5: Commit**

```bash
git add backend-api
git commit -m "feat: скелет backend-api со Spring Boot и Testcontainers"
```

---

### Task 4: Миграция V1 — полная схема БД

**Files:**
- Create: `backend-api/src/main/resources/db/migration/V1__init.sql`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/db/SchemaConstraintsTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` из Task 3.
- Produces: все таблицы спеки (`users`, `bookings`, `blocked_periods`, `access_requests`, `otp_challenges`, `outbox`, `processed_events`). На них опираются Task 5–7 и все будущие планы.

- [ ] **Step 1: Написать красные тесты на constraint'ы**

`src/test/java/com/batowka/guestbooking/db/SchemaConstraintsTest.java`:

```java
package com.batowka.guestbooking.db;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaConstraintsTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private Long createUser(String phone) {
        return jdbc.queryForObject(
                "insert into users(phone, name) values (?, 'Тест') returning id",
                Long.class, phone);
    }

    private void createBooking(Long userId, String checkIn, String checkOut, String status) {
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, ?::date, ?::date, ?)
                """, userId, checkIn, checkOut, status);
    }

    @Test
    void overlappingActiveBookingsAreRejected() {
        Long masha = createUser("+81100000001");
        Long petya = createUser("+81100000002");
        createBooking(masha, "2026-10-10", "2026-10-15", "CONFIRMED");

        assertThatThrownBy(() ->
                createBooking(petya, "2026-10-12", "2026-10-20", "PENDING_OTP"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void backToBackBookingsAreAllowed() {
        Long masha = createUser("+81100000003");
        Long petya = createUser("+81100000004");
        createBooking(masha, "2026-11-01", "2026-11-05", "CONFIRMED");

        // выезд 5-го и заезд 5-го не конфликтуют: [check_in, check_out)
        assertThatCode(() ->
                createBooking(petya, "2026-11-05", "2026-11-08", "CONFIRMED"))
                .doesNotThrowAnyException();
    }

    @Test
    void cancelledBookingDoesNotBlockDates() {
        Long masha = createUser("+81100000005");
        Long petya = createUser("+81100000006");
        createBooking(masha, "2026-12-01", "2026-12-05", "CANCELLED");

        assertThatCode(() ->
                createBooking(petya, "2026-12-01", "2026-12-05", "CONFIRMED"))
                .doesNotThrowAnyException();
    }

    @Test
    void secondConfirmedBookingForSameUserIsRejected() {
        Long masha = createUser("+81100000007");
        createBooking(masha, "2027-01-01", "2027-01-05", "CONFIRMED");

        assertThatThrownBy(() ->
                createBooking(masha, "2027-02-01", "2027-02-05", "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkOutMustBeAfterCheckIn() {
        Long masha = createUser("+81100000008");

        assertThatThrownBy(() ->
                createBooking(masha, "2027-03-05", "2027-03-05", "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: Убедиться, что тесты красные**

Run: `cd backend-api && ./gradlew test --tests '*SchemaConstraintsTest*'`
Expected: FAIL — `relation "users" does not exist`.

- [ ] **Step 3: Написать миграцию**

`src/main/resources/db/migration/V1__init.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE users (
    id               BIGSERIAL PRIMARY KEY,
    phone            VARCHAR(20)  NOT NULL UNIQUE,
    name             VARCHAR(100) NOT NULL,
    role             VARCHAR(10)  NOT NULL DEFAULT 'FRIEND'
                     CHECK (role IN ('FRIEND', 'ADMIN')),
    password_hash    VARCHAR(100),
    telegram_chat_id BIGINT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE bookings (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id),
    check_in     DATE        NOT NULL,
    check_out    DATE        NOT NULL,
    status       VARCHAR(15) NOT NULL
                 CHECK (status IN ('PENDING_OTP', 'CONFIRMED', 'CANCELLED')),
    comment      VARCHAR(500),
    cancelled_by VARCHAR(10) CHECK (cancelled_by IN ('GUEST', 'ADMIN')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (check_in < check_out),
    -- Полуинтервал [check_in, check_out): выезд и заезд в один день не конфликтуют.
    CONSTRAINT no_overlapping_bookings EXCLUDE USING gist (
        daterange(check_in, check_out) WITH &&
    ) WHERE (status IN ('PENDING_OTP', 'CONFIRMED'))
);

CREATE UNIQUE INDEX one_confirmed_booking_per_user
    ON bookings (user_id)
    WHERE status = 'CONFIRMED';

CREATE TABLE blocked_periods (
    id         BIGSERIAL PRIMARY KEY,
    start_date DATE NOT NULL,
    end_date   DATE NOT NULL,          -- включительно
    reason     VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (start_date <= end_date)
);

CREATE TABLE access_requests (
    id          BIGSERIAL PRIMARY KEY,
    phone       VARCHAR(20)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    message     VARCHAR(500),
    status      VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

CREATE TABLE otp_challenges (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    action     VARCHAR(25)  NOT NULL
               CHECK (action IN ('CREATE_BOOKING', 'RESCHEDULE', 'CANCEL',
                                 'ADMIN_PASSWORD_RESET')),
    payload    JSONB,
    code_hash  VARCHAR(100) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    attempts   INT          NOT NULL DEFAULT 0,
    status     VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
               CHECK (status IN ('PENDING', 'USED', 'EXPIRED')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        VARCHAR(50) NOT NULL,
    event_type   VARCHAR(40) NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests '*SchemaConstraintsTest*'`
Expected: PASS — все 5 тестов зелёные.

- [ ] **Step 5: Commit**

```bash
git add backend-api/src
git commit -m "feat: схема БД — exclusion constraint на пересечения дат и уникальность активной брони"
```

---

### Task 5: JPA-энтити и репозитории

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/user/UserAccount.java`, `.../user/Role.java`, `.../booking/Booking.java`, `.../booking/BookingStatus.java`, `.../booking/CancelledBy.java`, `.../booking/BookingRepository.java`, `.../calendar/BlockedPeriod.java`, `.../calendar/BlockedPeriodRepository.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/booking/BookingRepositoryTest.java`

**Interfaces:**
- Consumes: схему из Task 4, `AbstractIntegrationTest` из Task 3.
- Produces:
  - `BookingRepository.findOverlapping(LocalDate from, LocalDate to, Collection<BookingStatus> statuses)` → `List<Booking>` (брони, пересекающие диапазон дней `[from, to]` включительно, с загруженным `user`).
  - `BlockedPeriodRepository.findOverlapping(LocalDate from, LocalDate to)` → `List<BlockedPeriod>`.
  - Энтити `Booking` (поля `id`, `user: UserAccount`, `checkIn`, `checkOut`, `status`, `comment`, `cancelledBy`, `createdAt`, `updatedAt`), `UserAccount` (`id`, `phone`, `name`, `role`, `passwordHash`, `telegramChatId`, `createdAt`), `BlockedPeriod` (`id`, `startDate`, `endDate`, `reason`, `createdAt`).

- [ ] **Step 1: Написать красный тест**

`src/test/java/com/batowka/guestbooking/booking/BookingRepositoryTest.java`:

```java
package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookingRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    BookingRepository bookings;

    @Autowired
    JdbcTemplate jdbc;

    private Long createUser(String phone, String name) {
        return jdbc.queryForObject(
                "insert into users(phone, name) values (?, ?) returning id",
                Long.class, phone, name);
    }

    @Test
    void findsOnlyBookingsOverlappingTheRange() {
        Long masha = createUser("+81200000001", "Маша");
        Long petya = createUser("+81200000002", "Петя");
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2026-10-10', '2026-10-15', 'CONFIRMED')
                """, masha);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2026-12-01', '2026-12-05', 'CONFIRMED')
                """, petya);

        List<Booking> found = bookings.findOverlapping(
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"),
                List.of(BookingStatus.PENDING_OTP, BookingStatus.CONFIRMED));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getUser().getName()).isEqualTo("Маша");
        assertThat(found.getFirst().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void checkoutDayDoesNotCountAsOccupied() {
        Long masha = createUser("+81200000003", "Маша");
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2026-11-01', '2026-11-05', 'CONFIRMED')
                """, masha);

        // диапазон начинается в день выезда — бронь уже не занимает эти дни
        List<Booking> found = bookings.findOverlapping(
                LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-30"),
                List.of(BookingStatus.CONFIRMED));

        assertThat(found).isEmpty();
    }
}
```

- [ ] **Step 2: Убедиться, что не компилируется**

Run: `cd backend-api && ./gradlew test --tests '*BookingRepositoryTest*'`
Expected: FAIL — классы `Booking`, `BookingRepository`, `BookingStatus` не существуют.

- [ ] **Step 3: Реализовать энтити и репозитории**

`.../user/Role.java`:

```java
package com.batowka.guestbooking.user;

public enum Role { FRIEND, ADMIN }
```

`.../user/UserAccount.java`:

```java
package com.batowka.guestbooking.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String phone;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.FRIEND;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
```

`.../booking/BookingStatus.java`:

```java
package com.batowka.guestbooking.booking;

public enum BookingStatus { PENDING_OTP, CONFIRMED, CANCELLED }
```

`.../booking/CancelledBy.java`:

```java
package com.batowka.guestbooking.booking;

public enum CancelledBy { GUEST, ADMIN }
```

`.../booking/Booking.java`:

```java
package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.user.UserAccount;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by")
    private CancelledBy cancelledBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
```

`.../booking/BookingRepository.java`:

```java
package com.batowka.guestbooking.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Брони, пересекающие диапазон дней [from, to] включительно.
     * Бронь занимает [checkIn, checkOut), поэтому: checkIn <= to и checkOut > from.
     */
    @Query("""
            select b from Booking b join fetch b.user
            where b.status in :statuses
              and b.checkIn <= :to
              and b.checkOut > :from
            """)
    List<Booking> findOverlapping(@Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  @Param("statuses") Collection<BookingStatus> statuses);
}
```

`.../calendar/BlockedPeriod.java`:

```java
package com.batowka.guestbooking.calendar;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "blocked_periods")
@Getter
@Setter
@NoArgsConstructor
public class BlockedPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Включительно: блокировка «с 1 по 5» занимает и 5-е. */
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    private String reason;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
```

`.../calendar/BlockedPeriodRepository.java`:

```java
package com.batowka.guestbooking.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface BlockedPeriodRepository extends JpaRepository<BlockedPeriod, Long> {

    @Query("""
            select p from BlockedPeriod p
            where p.startDate <= :to and p.endDate >= :from
            """)
    List<BlockedPeriod> findOverlapping(@Param("from") LocalDate from,
                                        @Param("to") LocalDate to);
}
```

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests '*BookingRepositoryTest*'`
Expected: PASS. Дополнительно `./gradlew test` целиком — Hibernate в режиме
`validate` подтвердит соответствие энтити схеме.

- [ ] **Step 5: Commit**

```bash
git add backend-api/src
git commit -m "feat: JPA-энтити и запросы пересечения диапазонов дат"
```

---

### Task 6: CalendarService

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/calendar/CalendarService.java`, `.../calendar/CalendarDay.java`, `.../calendar/DayStatus.java`, `.../calendar/InvalidCalendarRangeException.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/calendar/CalendarServiceTest.java`

**Interfaces:**
- Consumes: `BookingRepository.findOverlapping(...)`, `BlockedPeriodRepository.findOverlapping(...)` из Task 5.
- Produces: `CalendarService.getCalendar(LocalDate from, LocalDate to)` → `List<CalendarDay>`; `record CalendarDay(LocalDate date, DayStatus status, String guestName)`; `enum DayStatus { FREE, BOOKED, BLOCKED }`; `InvalidCalendarRangeException extends RuntimeException`. Их использует Task 7.

- [ ] **Step 1: Написать красный тест**

`src/test/java/com/batowka/guestbooking/calendar/CalendarServiceTest.java`:

```java
package com.batowka.guestbooking.calendar;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarServiceTest extends AbstractIntegrationTest {

    @Autowired
    CalendarService calendar;

    @Autowired
    JdbcTemplate jdbc;

    private void givenBooking(String phone, String name, String in, String out, String status) {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name) values (?, ?) returning id",
                Long.class, phone, name);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, ?::date, ?::date, ?)
                """, id, in, out, status);
    }

    private Map<LocalDate, CalendarDay> byDate(List<CalendarDay> days) {
        return days.stream().collect(Collectors.toMap(CalendarDay::date, Function.identity()));
    }

    @Test
    void freeBookedAndBlockedDaysAreMarked() {
        givenBooking("+81300000001", "Маша", "2026-10-10", "2026-10-12", "CONFIRMED");
        jdbc.update("""
                insert into blocked_periods(start_date, end_date, reason)
                values ('2026-10-20', '2026-10-21', 'сами в отъезде')
                """);

        List<CalendarDay> days = calendar.getCalendar(
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

        assertThat(days).hasSize(31);
        Map<LocalDate, CalendarDay> map = byDate(days);
        assertThat(map.get(LocalDate.parse("2026-10-09")).status()).isEqualTo(DayStatus.FREE);
        assertThat(map.get(LocalDate.parse("2026-10-10")).status()).isEqualTo(DayStatus.BOOKED);
        assertThat(map.get(LocalDate.parse("2026-10-10")).guestName()).isEqualTo("Маша");
        assertThat(map.get(LocalDate.parse("2026-10-11")).status()).isEqualTo(DayStatus.BOOKED);
        // день выезда свободен: [check_in, check_out)
        assertThat(map.get(LocalDate.parse("2026-10-12")).status()).isEqualTo(DayStatus.FREE);
        // блокировка включительно с обеих сторон
        assertThat(map.get(LocalDate.parse("2026-10-20")).status()).isEqualTo(DayStatus.BLOCKED);
        assertThat(map.get(LocalDate.parse("2026-10-21")).status()).isEqualTo(DayStatus.BLOCKED);
        assertThat(map.get(LocalDate.parse("2026-10-21")).guestName()).isNull();
        assertThat(map.get(LocalDate.parse("2026-10-22")).status()).isEqualTo(DayStatus.FREE);
    }

    @Test
    void pendingOtpBookingOccupiesDatesButHidesName() {
        givenBooking("+81300000002", "Петя", "2026-11-10", "2026-11-12", "PENDING_OTP");

        Map<LocalDate, CalendarDay> map = byDate(calendar.getCalendar(
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30")));

        assertThat(map.get(LocalDate.parse("2026-11-10")).status()).isEqualTo(DayStatus.BOOKED);
        // имя показываем только для подтверждённых броней
        assertThat(map.get(LocalDate.parse("2026-11-10")).guestName()).isNull();
    }

    @Test
    void cancelledBookingsAreInvisible() {
        givenBooking("+81300000003", "Ира", "2026-12-10", "2026-12-12", "CANCELLED");

        Map<LocalDate, CalendarDay> map = byDate(calendar.getCalendar(
                LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31")));

        assertThat(map.get(LocalDate.parse("2026-12-10")).status()).isEqualTo(DayStatus.FREE);
    }

    @Test
    void rejectsInvertedAndTooLongRanges() {
        assertThatThrownBy(() -> calendar.getCalendar(
                LocalDate.parse("2026-10-31"), LocalDate.parse("2026-10-01")))
                .isInstanceOf(InvalidCalendarRangeException.class);

        assertThatThrownBy(() -> calendar.getCalendar(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2028-01-01")))
                .isInstanceOf(InvalidCalendarRangeException.class);
    }
}
```

- [ ] **Step 2: Убедиться, что не компилируется**

Run: `cd backend-api && ./gradlew test --tests '*CalendarServiceTest*'`
Expected: FAIL — `CalendarService`, `CalendarDay`, `DayStatus` не существуют.

- [ ] **Step 3: Реализовать**

`.../calendar/DayStatus.java`:

```java
package com.batowka.guestbooking.calendar;

public enum DayStatus { FREE, BOOKED, BLOCKED }
```

`.../calendar/CalendarDay.java`:

```java
package com.batowka.guestbooking.calendar;

import java.time.LocalDate;

public record CalendarDay(LocalDate date, DayStatus status, String guestName) {
}
```

`.../calendar/InvalidCalendarRangeException.java`:

```java
package com.batowka.guestbooking.calendar;

public class InvalidCalendarRangeException extends RuntimeException {

    public InvalidCalendarRangeException(String message) {
        super(message);
    }
}
```

`.../calendar/CalendarService.java`:

```java
package com.batowka.guestbooking.calendar;

import com.batowka.guestbooking.booking.Booking;
import com.batowka.guestbooking.booking.BookingRepository;
import com.batowka.guestbooking.booking.BookingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CalendarService {

    private static final int MAX_RANGE_DAYS = 366;
    private static final List<BookingStatus> OCCUPYING_STATUSES =
            List.of(BookingStatus.PENDING_OTP, BookingStatus.CONFIRMED);

    private final BookingRepository bookings;
    private final BlockedPeriodRepository blockedPeriods;

    @Transactional(readOnly = true)
    public List<CalendarDay> getCalendar(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidCalendarRangeException("Дата конца раньше даты начала");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new InvalidCalendarRangeException(
                    "Диапазон больше " + MAX_RANGE_DAYS + " дней");
        }

        Map<LocalDate, CalendarDay> days = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.put(d, new CalendarDay(d, DayStatus.FREE, null));
        }

        for (Booking b : bookings.findOverlapping(from, to, OCCUPYING_STATUSES)) {
            String name = b.getStatus() == BookingStatus.CONFIRMED
                    ? b.getUser().getName() : null;
            LocalDate start = b.getCheckIn().isBefore(from) ? from : b.getCheckIn();
            for (LocalDate d = start;
                 d.isBefore(b.getCheckOut()) && !d.isAfter(to);
                 d = d.plusDays(1)) {
                days.put(d, new CalendarDay(d, DayStatus.BOOKED, name));
            }
        }

        // блокировки поверх броней: если периоды наложились, показываем BLOCKED
        for (BlockedPeriod p : blockedPeriods.findOverlapping(from, to)) {
            LocalDate start = p.getStartDate().isBefore(from) ? from : p.getStartDate();
            LocalDate end = p.getEndDate().isAfter(to) ? to : p.getEndDate();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                days.put(d, new CalendarDay(d, DayStatus.BLOCKED, null));
            }
        }

        return List.copyOf(days.values());
    }
}
```

- [ ] **Step 4: Прогнать тесты**

Run: `cd backend-api && ./gradlew test --tests '*CalendarServiceTest*'`
Expected: PASS — все 4 теста.

- [ ] **Step 5: Commit**

```bash
git add backend-api/src
git commit -m "feat: CalendarService — статусы дней с приоритетом блокировок"
```

---

### Task 7: GET /api/calendar и формат ошибок

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/calendar/CalendarController.java`, `.../calendar/CalendarResponse.java`, `.../common/ApiError.java`, `.../common/GlobalExceptionHandler.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/calendar/CalendarControllerTest.java`

**Interfaces:**
- Consumes: `CalendarService.getCalendar(from, to)` из Task 6.
- Produces: `GET /api/calendar?from=YYYY-MM-DD&to=YYYY-MM-DD` → `200 {"days":[{"date","status","guestName"}]}`; ошибки → `400 {"code":"VALIDATION_ERROR","message":"..."}`. `record ApiError(String code, String message)` и `GlobalExceptionHandler` переиспользуются всеми будущими эндпоинтами.

- [ ] **Step 1: Написать красный тест**

`src/test/java/com/batowka/guestbooking/calendar/CalendarControllerTest.java`:

```java
package com.batowka.guestbooking.calendar;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CalendarController.class)
class CalendarControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CalendarService calendar;

    @Test
    void returnsDays() throws Exception {
        when(calendar.getCalendar(any(), any())).thenReturn(List.of(
                new CalendarDay(LocalDate.parse("2026-10-10"), DayStatus.BOOKED, "Маша")));

        mvc.perform(get("/api/calendar")
                        .param("from", "2026-10-10")
                        .param("to", "2026-10-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].date").value("2026-10-10"))
                .andExpect(jsonPath("$.days[0].status").value("BOOKED"))
                .andExpect(jsonPath("$.days[0].guestName").value("Маша"));
    }

    @Test
    void invalidRangeBecomes400WithErrorFormat() throws Exception {
        when(calendar.getCalendar(any(), any()))
                .thenThrow(new InvalidCalendarRangeException("Дата конца раньше даты начала"));

        mvc.perform(get("/api/calendar")
                        .param("from", "2026-10-31")
                        .param("to", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Дата конца раньше даты начала"));
    }

    @Test
    void unparsableDateBecomes400() throws Exception {
        mvc.perform(get("/api/calendar")
                        .param("from", "не-дата")
                        .param("to", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void missingParamBecomes400() throws Exception {
        mvc.perform(get("/api/calendar").param("from", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
```

- [ ] **Step 2: Убедиться, что не компилируется**

Run: `cd backend-api && ./gradlew test --tests '*CalendarControllerTest*'`
Expected: FAIL — `CalendarController` не существует.

- [ ] **Step 3: Реализовать**

`.../calendar/CalendarResponse.java`:

```java
package com.batowka.guestbooking.calendar;

import java.util.List;

public record CalendarResponse(List<CalendarDay> days) {
}
```

`.../calendar/CalendarController.java`:

```java
package com.batowka.guestbooking.calendar;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarService calendar;

    @GetMapping
    public CalendarResponse getCalendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new CalendarResponse(calendar.getCalendar(from, to));
    }
}
```

`.../common/ApiError.java`:

```java
package com.batowka.guestbooking.common;

public record ApiError(String code, String message) {
}
```

`.../common/GlobalExceptionHandler.java`:

```java
package com.batowka.guestbooking.common;

import com.batowka.guestbooking.calendar.InvalidCalendarRangeException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCalendarRangeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidRange(InvalidCalendarRangeException ex) {
        return new ApiError("VALIDATION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError typeMismatch(MethodArgumentTypeMismatchException ex) {
        return new ApiError("VALIDATION_ERROR",
                "Неверное значение параметра '" + ex.getName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError missingParam(MissingServletRequestParameterException ex) {
        return new ApiError("VALIDATION_ERROR",
                "Не хватает параметра '" + ex.getParameterName() + "'");
    }
}
```

- [ ] **Step 4: Прогнать все тесты**

Run: `cd backend-api && ./gradlew test`
Expected: PASS — весь набор зелёный.

- [ ] **Step 5: Ручная проверка**

Run (Postgres из Task 2 должен работать):

```bash
cd backend-api && ./gradlew bootRun &
sleep 15 && curl 'http://localhost:8080/api/calendar?from=2026-09-01&to=2026-09-07'
```

Expected: `{"days":[{"date":"2026-09-01","status":"FREE","guestName":null}, ...]}` — 7 дней. Остановить `bootRun`.

- [ ] **Step 6: Commit**

```bash
git add backend-api/src
git commit -m "feat: публичный GET /api/calendar и единый формат ошибок"
```

---

### Task 8: CI — GitHub Actions

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: gradle-тесты из Task 3–7 (Testcontainers работает на ubuntu-latest — Docker там есть).
- Produces: workflow `CI` с джобой `backend`; джобы `frontend` и `bot` добавят планы соответствующих этапов.

- [ ] **Step 1: Создать workflow**

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  backend:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: backend-api
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew test
```

- [ ] **Step 2: Commit и проверка**

```bash
git add .github
git commit -m "ci: тесты backend-api на каждый push и PR"
```

Если удалённый репозиторий на GitHub уже настроен — `git push` и убедиться,
что workflow зелёный (вкладка Actions). Если нет — создать его
(`gh repo create japan-guest-booking --private --source . --push`) или
отложить push до появления репозитория; локальная проверка Task 7 Step 4
уже подтвердила, что тесты проходят.

---

### Task 9: Обучающие разборы этапов 0–1

**Files:**
- Create: `docs/learning/00-monorepo-compose-ci.md`, `docs/learning/01-flyway-exclusion-constraints.md`

**Interfaces:**
- Consumes: результаты Task 1–8 (разборы ссылаются на реальные файлы репо).
- Produces: первые две статьи базы знаний.

- [ ] **Step 1: Написать разбор этапа 0**

`docs/learning/00-monorepo-compose-ci.md` — статья, отвечающая на вопросы
(каждый ответ 1–3 абзаца, со ссылками на файлы репо):

1. Что такое монорепо и почему мы выбрали его, а не три репозитория
   (общие contracts/, атомарные изменения через границы сервисов, один CI).
2. Как читать `docker-compose.dev.yml`: сервисы, порты, volumes; почему
   данные Postgres переживают перезапуск (named volume `pgdata`).
3. Как устроена конфигурация Kafka в KRaft-режиме: зачем три listener'а
   (INTERNAL/CONTROLLER/EXTERNAL), почему advertised.listeners для
   localhost и для docker-сети разные, что делает controller quorum.
4. Анатомия GitHub Actions: события `push`/`pull_request`, jobs, steps,
   готовые actions (`actions/checkout`, `setup-java`, `setup-gradle`);
   почему Testcontainers работает в CI (Docker на раннере).

- [ ] **Step 2: Написать разбор этапа 1**

`docs/learning/01-flyway-exclusion-constraints.md` — вопросы:

1. Зачем миграции и почему `ddl-auto: validate`, а не `update`: схема —
   код с версионированием, Flyway применяет `V1__...` по порядку и хранит
   историю в `flyway_schema_history`.
2. Как работает exclusion constraint: `daterange` + оператор `&&`
   (пересечение), GiST-индекс, частичный `WHERE (status IN ...)`; почему
   это закрывает гонки, которые не закрыть проверкой в коде
   (две транзакции не видят вставок друг друга до commit).
3. Полуинтервалы `[)` для дат заезда/выезда против включительных
   диапазонов у блокировок — и почему смешивать семантики в одной таблице
   было бы ошибкой.
4. Частичный уникальный индекс (`WHERE status = 'CONFIRMED'`) — «одна
   активная бронь», не мешая истории отменённых.
5. Testcontainers: почему тесты против настоящего Postgres, а не H2
   (H2 не знает `daterange`/`btree_gist` — тесты бы врали), как
   `@ServiceConnection` подсовывает datasource.

- [ ] **Step 3: Commit**

```bash
git add docs/learning
git commit -m "docs: разборы этапов 0-1 — монорепо/CI и exclusion constraints"
```

---

## Что дальше (вне этого плана)

Следующие планы пишутся по завершении этого, каждый — той же структуры:

- **План №2 (этапы 2–3):** аутентификация (логин гостя, JWT в httpOnly cookie, админ-логин с BCrypt), Kafka + transactional outbox, каркас bot-service на Go, онбординг контакта, JSON-схемы в `contracts/`.
- **План №3 (этапы 4–5):** OTP-флоу бронирования end-to-end, фоновая чистка PENDING_OTP, админ-API, заявки на доступ, rate limiting.
- **План №4 (этапы 6–7):** frontend — календарь, бронирование, админка.
- **План №5 (этап 8):** VPS, nginx, HTTPS, прод-compose, деплой из Actions, бэкапы.
