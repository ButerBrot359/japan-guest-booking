# Этап 3: Kafka, outbox, bot-service (Go) — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Полная событийная петля backend ↔ Kafka ↔ bot-service ↔ Telegram: онбординг контакта с привязкой chat_id и первым уведомлением WELCOME через transactional outbox.

**Architecture:** Backend пишет события в таблицу `outbox` в транзакции с бизнес-эффектом; `@Scheduled`-паблишер доставляет их в Kafka. bot-service (Go) — две горутины: Telegram long polling (свой клиент на net/http) и Kafka-консьюмер уведомлений. Идемпотентность: backend — `processed_events`, bot — in-memory дедуп.

**Tech Stack:** Java 21 / Spring Boot 4.0.7 / spring-kafka / Testcontainers (Postgres + Kafka); Go 1.22+ / segmentio/kafka-go / net/http; Kafka KRaft из docker-compose.dev.yml.

**Spec:** `docs/specs/2026-08-19-stage-3-kafka-bot-design.md` (родительская: `docs/specs/2026-08-13-japan-guest-booking-design.md` §4, §7, §8).

## Global Constraints

- Java-мир: Boot 4.0.7, Maven (`./mvnw`), пакет `com.batowka.guestbooking`, формат ошибок `{"code","message"}`, TDD. Известные Boot-4 факты: бин ObjectMapper — `tools.jackson.databind.ObjectMapper` (Jackson 3); тестовые аннотации — `org.springframework.boot.webmvc.test.autoconfigure.*`; Testcontainers 2.x, классы в `org.testcontainers.<module>`.
- Go-мир: Go 1.22+; ЕДИНСТВЕННАЯ внешняя зависимость — `github.com/segmentio/kafka-go`; ошибки через `if err != nil`, без паник в штатных путях; TDD (`go test ./...`); `go vet ./...` чист.
- Контракт конверта (оба топика): `{event_id: UUID-строка, occurred_at: RFC3339, event_type, payload}`. Топики: `notifications.outbound`, `telegram.inbound` (по 1 партиции, авто-создание Kafka).
- Consumer groups: `backend-api` (inbound), `bot-service` (outbound).
- Секреты (BOT_TOKEN) — только из env/.env (в .gitignore); в репо — `.env.example` без секретов.
- Bot-service не содержит бизнес-логики (граница из родительской спеки §4).
- «Класс/артефакт не резолвится» → искать переехавший пакет/имя по локальному кэшу (~/.m2, BOM; для Go — pkg.go.dev), НЕ менять подход; адаптацию фиксировать в отчёте.

---

### Task 1: Контракты событий

**Files:**
- Create: `contracts/envelope.md`, `contracts/notifications-outbound.md`, `contracts/telegram-inbound.md`
- Modify: `contracts/README.md`

**Interfaces:**
- Produces: текстовый контракт, против которого пишутся Java- и Go-структуры Tasks 3-6. Точные имена полей: `event_id`, `occurred_at`, `event_type`, `payload`; `chat_id`, `phone`, `telegram_username`; `name`.

- [ ] **Step 1: envelope.md**

```markdown
# Конверт события

Каждое сообщение в топиках `notifications.outbound` и `telegram.inbound` —
JSON-объект:

```json
{
  "event_id": "3f9c9a52-1f3b-4c6e-9b64-2a1d1f8e7c11",
  "occurred_at": "2026-08-19T12:00:00Z",
  "event_type": "WELCOME",
  "payload": { }
}
```

- `event_id` — UUID v4, уникален на событие; консьюмеры дедуплицируют по нему
  (доставка at-least-once).
- `occurred_at` — момент создания события, RFC3339/UTC.
- `event_type` — тип из списка соответствующего топика; незнакомый тип
  консьюмер логирует и пропускает (вперёд-совместимость).
- `payload` — объект, схема зависит от `event_type`.

## Правила эволюции

Поля не удаляются и не переименовываются. Новые поля — только опциональные.
Новый тип события = новый раздел в файле топика, старые консьюмеры его
игнорируют.
```

- [ ] **Step 2: notifications-outbound.md**

```markdown
# Топик `notifications.outbound` (backend-api → bot-service)

1 партиция. Producer: backend-api (через outbox). Consumer group: `bot-service`.
bot-service рендерит уведомление и отправляет в Telegram `chat_id`.

## WELCOME (реализовано с этапа 3)

Одобренный гость привязал Telegram.

```json
{"event_type": "WELCOME", "payload": {"chat_id": 123456789, "name": "Маша"}}
```

## OTP_CODE (контракт зафиксирован, реализация — этап 4)

```json
{"event_type": "OTP_CODE",
 "payload": {"chat_id": 123456789, "code": "482913",
             "action": "CREATE_BOOKING", "expires_at": "2026-08-19T12:05:00Z"}}
```

## BOOKING_CONFIRMED | BOOKING_CANCELLED | BOOKING_RESCHEDULED (этап 4)

```json
{"event_type": "BOOKING_CONFIRMED",
 "payload": {"chat_id": 123456789, "guest_name": "Маша",
             "check_in": "2026-10-10", "check_out": "2026-10-12"}}
```

## ACCESS_REQUEST_RECEIVED (этап 5)

```json
{"event_type": "ACCESS_REQUEST_RECEIVED",
 "payload": {"chat_id": 987654321, "name": "Петя",
             "phone": "+81900000000", "message": "Хочу приехать в марте"}}
```
```

- [ ] **Step 3: telegram-inbound.md**

```markdown
# Топик `telegram.inbound` (bot-service → backend-api)

1 партиция. Producer: bot-service. Consumer group: `backend-api`.

## CONTACT_SHARED (реализовано с этапа 3)

Пользователь нажал Start и поделился СВОИМ контактом (bot-service принимает
контакт только если `contact.user_id == from.id`).

```json
{"event_type": "CONTACT_SHARED",
 "payload": {"chat_id": 123456789, "phone": "+81300000001",
             "telegram_username": "masha"}}
```

- `phone` — как отдал Telegram (может быть без `+`); нормализация в E.164 —
  обязанность backend.
- `telegram_username` — может отсутствовать/быть null (у пользователя нет
  username).
```

- [ ] **Step 4: обновить contracts/README.md**

Заменить содержимое:

```markdown
# Контракты событий Kafka

Язык-нейтральное описание сообщений между backend-api (Java) и bot-service
(Go). Код обоих сервисов пишется против этих файлов.

- [envelope.md](envelope.md) — конверт всех событий и правила эволюции
- [notifications-outbound.md](notifications-outbound.md) — backend → bot
- [telegram-inbound.md](telegram-inbound.md) — bot → backend
```

- [ ] **Step 5: Commit**

```bash
git add contracts
git commit -m "docs: контракты событий Kafka — конверт, оба топика, правила эволюции"
```

---

### Task 2: Kafka в тестовой инфраструктуре Java

**Files:**
- Modify: `backend-api/pom.xml`
- Modify: `backend-api/src/main/resources/application.yml`
- Modify: `backend-api/src/test/java/com/batowka/guestbooking/AbstractIntegrationTest.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/messaging/KafkaSmokeTest.java`

**Interfaces:**
- Consumes: singleton-паттерн контейнеров из AbstractIntegrationTest.
- Produces: рабочие бины `KafkaTemplate<String, String>` в интеграционных тестах; Kafka-контейнер с `@ServiceConnection` для всех наследников AbstractIntegrationTest. Хелпер `KafkaTestConsumer` (см. Step 2) — его переиспользует Task 3.

- [ ] **Step 1: Зависимости и конфиг**

В `backend-api/pom.xml`:

```xml
		<dependency>
			<groupId>org.springframework.kafka</groupId>
			<artifactId>spring-kafka</artifactId>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-kafka</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.awaitility</groupId>
			<artifactId>awaitility</artifactId>
			<scope>test</scope>
		</dependency>
```

Контингенция: если в Boot 4 BOM есть стартер `spring-boot-starter-kafka` —
использовать его вместо голого `spring-kafka` (проверить в
`spring-boot-dependencies` из ~/.m2); версии не указывать (BOM). Если
артефакт testcontainers-kafka называется иначе в TC 2.x — найти в BOM.

В `application.yml` (в секцию `spring`):

```yaml
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest
```

- [ ] **Step 2: Красный тест**

`src/test/java/com/batowka/guestbooking/messaging/KafkaSmokeTest.java`:

```java
package com.batowka.guestbooking.messaging;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaSmokeTest extends AbstractIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Test
    void sentMessageCanBeConsumed() {
        kafka.send("smoke-test-topic", "hello-kafka").join();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer(
                kafkaBootstrapServers(), "smoke-test-topic")) {
            assertThat(consumer.poll(Duration.ofSeconds(15)))
                    .contains("hello-kafka");
        }
    }
}
```

И хелпер `src/test/java/com/batowka/guestbooking/messaging/KafkaTestConsumer.java`
(тестовый консьюмер «прочитай топик с начала»):

```java
package com.batowka.guestbooking.messaging;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class KafkaTestConsumer implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;

    public KafkaTestConsumer(String bootstrapServers, String topic) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
    }

    /** Все value из топика, накопленные за timeout. */
    public List<String> poll(Duration timeout) {
        List<String> values = new ArrayList<>();
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                values.add(r.value());
            }
            if (!values.isEmpty()) {
                break;
            }
        }
        return values;
    }

    @Override
    public void close() {
        consumer.close();
    }
}
```

- [ ] **Step 3: Убедиться в падении**

Run: `cd backend-api && ./mvnw test -Dtest=KafkaSmokeTest`
Expected: FAIL — приложение тянется к localhost:9092 (контейнера Kafka в
тестах нет), таймаут отправки.

- [ ] **Step 4: Kafka-контейнер в AbstractIntegrationTest**

Добавить рядом с POSTGRES (тот же singleton-паттерн):

```java
    @ServiceConnection
    static final org.testcontainers.kafka.KafkaContainer KAFKA =
            new org.testcontainers.kafka.KafkaContainer("apache/kafka:3.9.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    /** Адрес брокера тест-контейнера — для тестовых консьюмеров. */
    protected static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
```

(Блок `static { POSTGRES.start(); }` уже существует — расширить его, не
дублировать. Контингенция: точное имя класса/образа сверить с
testcontainers-kafka jar из ~/.m2; образ должен быть Apache Kafka в
KRaft-режиме.)

- [ ] **Step 5: Зелёный + полный suite**

Run: `cd backend-api && ./mvnw test -Dtest=KafkaSmokeTest` → PASS.
Затем `./mvnw test` — все 44+1 зелёные (Kafka-контейнер стартует один раз на JVM).

- [ ] **Step 6: Commit**

```bash
git add backend-api
git commit -m "feat: Kafka в тестовой инфраструктуре — singleton-контейнер и smoke-тест"
```

---

### Task 3: OutboxWriter и OutboxPublisher

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/messaging/OutboxWriter.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/messaging/OutboxPublisher.java`
- Create: `backend-api/src/main/java/com/batowka/guestbooking/messaging/SchedulingConfig.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/messaging/OutboxTest.java`

**Interfaces:**
- Consumes: таблица `outbox` (V1: id, topic, event_type, payload jsonb, created_at, published_at); `KafkaTemplate<String, String>`; `KafkaTestConsumer` из Task 2.
- Produces: `OutboxWriter.write(String topic, String eventType, Object payload)` — ТОЛЬКО внутри существующей транзакции (MANDATORY); `OutboxPublisher.publishPending()` — публикует и помечает; `@Scheduled` каждые 2с. Task 4 вызывает `write(...)`.

- [ ] **Step 1: Красный тест**

`src/test/java/com/batowka/guestbooking/messaging/OutboxTest.java`:

```java
package com.batowka.guestbooking.messaging;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxTest extends AbstractIntegrationTest {

    @Autowired
    OutboxWriter writer;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void writeOutsideTransactionIsRejected() {
        assertThatThrownBy(() ->
                writer.write("notifications.outbound", "WELCOME", Map.of("chat_id", 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void writtenEventIsPublishedExactlyOnce() {
        tx.executeWithoutResult(s ->
                writer.write("notifications.outbound", "WELCOME",
                        Map.of("chat_id", 42, "name", "Маша")));

        publisher.publishPending();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer(
                kafkaBootstrapServers(), "notifications.outbound")) {
            List<String> messages = consumer.poll(Duration.ofSeconds(15));
            assertThat(messages).hasSize(1);
            String envelope = messages.getFirst();
            assertThat(envelope).contains("\"event_type\":\"WELCOME\"");
            assertThat(envelope).contains("\"event_id\"");
            assertThat(envelope).contains("\"occurred_at\"");
            assertThat(envelope).contains("\"name\":\"Маша\"");
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where published_at is null", Integer.class))
                .isZero();

        // повторный прогон паблишера не должен слать дубликат
        publisher.publishPending();
        try (KafkaTestConsumer consumer = new KafkaTestConsumer(
                kafkaBootstrapServers(), "notifications.outbound")) {
            assertThat(consumer.poll(Duration.ofSeconds(5))).hasSize(1);
        }
    }
}
```

- [ ] **Step 2: Убедиться в падении**

Run: `cd backend-api && ./mvnw test -Dtest=OutboxTest`
Expected: COMPILE FAIL — классов нет.

- [ ] **Step 3: Реализовать**

`messaging/OutboxWriter.java`:

```java
package com.batowka.guestbooking.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Кладёт событие в outbox В ТЕКУЩЕЙ транзакции вызывающего —
     * бизнес-эффект и событие атомарны (transactional outbox).
     * Вне транзакции вызов запрещён (MANDATORY).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String topic, String eventType, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", UUID.randomUUID().toString());
        envelope.put("occurred_at", Instant.now().toString());
        envelope.put("event_type", eventType);
        envelope.put("payload", payload);
        jdbc.update("""
                insert into outbox(topic, event_type, payload)
                values (?, ?, ?::jsonb)
                """, topic, eventType, objectMapper.writeValueAsString(envelope));
    }
}
```

`messaging/OutboxPublisher.java`:

```java
package com.batowka.guestbooking.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    @Scheduled(fixedDelay = 2000)
    public void publishPending() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, topic, payload::text as payload
                from outbox where published_at is null
                order by id limit 100
                """);
        for (Map<String, Object> row : rows) {
            try {
                kafka.send((String) row.get("topic"), (String) row.get("payload")).join();
            } catch (Exception e) {
                log.warn("Kafka недоступна, отправка outbox id={} отложена", row.get("id"), e);
                return; // остальные строки подождут следующего цикла — порядок сохраняется
            }
            jdbc.update("update outbox set published_at = now() where id = ?", row.get("id"));
        }
    }
}
```

`messaging/SchedulingConfig.java`:

```java
package com.batowka.guestbooking.messaging;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

- [ ] **Step 4: Зелёный + полный suite**

Run: `cd backend-api && ./mvnw test -Dtest=OutboxTest` → PASS (2/2).
Затем `./mvnw test` целиком.

- [ ] **Step 5: Commit**

```bash
git add backend-api/src
git commit -m "feat: transactional outbox — writer (MANDATORY tx) и scheduled-паблишер в Kafka"
```

---

### Task 4: ContactSharedConsumer и telegramLinked в /api/me

**Files:**
- Create: `backend-api/src/main/java/com/batowka/guestbooking/messaging/ContactSharedConsumer.java`
- Modify: `backend-api/src/main/java/com/batowka/guestbooking/auth/MeController.java`
- Modify: `backend-api/src/test/java/com/batowka/guestbooking/auth/MeControllerTest.java`
- Test: `backend-api/src/test/java/com/batowka/guestbooking/messaging/ContactSharedConsumerTest.java`

**Interfaces:**
- Consumes: `OutboxWriter.write` (Task 3), `UserAccountRepository.findByPhone`, `Phones.normalize`, таблица `processed_events` (V1), `KafkaTemplate` (слать тестовые события).
- Produces: привязка `users.telegram_chat_id` по событию `CONTACT_SHARED`; `WELCOME` в outbox; `GET /api/me` → `{phone, name, role, telegramLinked}`.

- [ ] **Step 1: Красный тест консьюмера**

`src/test/java/com/batowka/guestbooking/messaging/ContactSharedConsumerTest.java`:

```java
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
}
```

- [ ] **Step 2: Убедиться в падении**

Run: `cd backend-api && ./mvnw test -Dtest=ContactSharedConsumerTest`
Expected: FAIL — события никем не обрабатываются, await по processed_events
истекает таймаутом.

- [ ] **Step 3: Реализовать консьюмер**

`messaging/ContactSharedConsumer.java`:

```java
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
```

- [ ] **Step 4: telegramLinked в /api/me**

В `MeController`: record расширяется полем и заполняется по chat_id:

```java
    public record MeResponse(String phone, String name, Role role, boolean telegramLinked) {
    }

    @GetMapping("/api/me")
    public MeResponse me(Authentication auth) {
        UserAccount user = users.findById((Long) auth.getPrincipal()).orElseThrow();
        return new MeResponse(user.getPhone(), user.getName(), user.getRole(),
                user.getTelegramChatId() != null);
    }
```

В `MeControllerTest.authenticatedGuestSeesOwnProfile` добавить ассерт (после
существующих):

```java
                .andExpect(jsonPath("$.telegramLinked").value(false));
```

- [ ] **Step 5: Зелёный + полный suite**

Run: `cd backend-api && ./mvnw test -Dtest='ContactSharedConsumerTest,MeControllerTest'` → PASS (5+2).
Затем `./mvnw test` целиком, дважды (`./mvnw test && ./mvnw cleanTest test`) —
консьюмер общий на контекст, проверяем отсутствие межтестовых протечек.

- [ ] **Step 6: Commit**

```bash
git add backend-api/src
git commit -m "feat: онбординг контакта — консьюмер CONTACT_SHARED, WELCOME через outbox, telegramLinked в /api/me"
```

---

### Task 5: bot-service — Telegram-клиент, поллер, события

**Files:**
- Create: `bot-service/go.mod`
- Create: `bot-service/internal/events/events.go`
- Create: `bot-service/internal/telegram/client.go`
- Create: `bot-service/internal/telegram/poller.go`
- Test: `bot-service/internal/telegram/client_test.go`, `bot-service/internal/telegram/poller_test.go`

**Interfaces:**
- Consumes: контракты Task 1.
- Produces: `telegram.Client` (реализует интерфейс `telegram.API`: `GetUpdates(ctx, offset) ([]Update, error)`, `SendMessage(ctx, chatID, text, requestContact) error`); `telegram.Poller` с зависимостью `ContactPublisher` (`PublishContactShared(ctx, chatID int64, phone, username string) error`) — реализацию даст Task 6; `events.Envelope/ContactShared/Welcome` — структуры для Task 6.

- [ ] **Step 1: go.mod и события**

```bash
mkdir -p bot-service/internal/{telegram,kafka,events} bot-service/cmd/bot
cd bot-service && go mod init github.com/buterbrot359/japan-guest-booking/bot-service
```

`internal/events/events.go`:

```go
// Package events — Go-зеркало contracts/: конверт и payload'ы событий.
package events

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"time"
)

type Envelope struct {
	EventID    string          `json:"event_id"`
	OccurredAt time.Time       `json:"occurred_at"`
	EventType  string          `json:"event_type"`
	Payload    json.RawMessage `json:"payload"`
}

type ContactShared struct {
	ChatID           int64  `json:"chat_id"`
	Phone            string `json:"phone"`
	TelegramUsername string `json:"telegram_username,omitempty"`
}

type Welcome struct {
	ChatID int64  `json:"chat_id"`
	Name   string `json:"name"`
}

// NewUUID генерирует UUID v4 без внешних зависимостей.
func NewUUID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err) // недоступность crypto/rand — фатальна для процесса
	}
	b[6] = (b[6] & 0x0f) | 0x40 // версия 4
	b[8] = (b[8] & 0x3f) | 0x80 // вариант RFC 4122
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
```

- [ ] **Step 2: Красные тесты клиента**

`internal/telegram/client_test.go`:

```go
package telegram

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestGetUpdatesParsesContact(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/botTEST/getUpdates" {
			t.Errorf("неожиданный путь: %s", r.URL.Path)
		}
		w.Write([]byte(`{"ok":true,"result":[
			{"update_id":10,"message":{"chat":{"id":555},"from":{"id":777,"username":"masha"},
			 "contact":{"phone_number":"81300000001","user_id":777}}}]}`))
	}))
	defer server.Close()

	client := NewClient("TEST", server.URL)
	updates, err := client.GetUpdates(context.Background(), 0)
	if err != nil {
		t.Fatalf("GetUpdates: %v", err)
	}
	if len(updates) != 1 {
		t.Fatalf("ожидал 1 update, получил %d", len(updates))
	}
	u := updates[0]
	if u.UpdateID != 10 || u.Message.Chat.ID != 555 ||
		u.Message.Contact.PhoneNumber != "81300000001" || u.Message.Contact.UserID != 777 {
		t.Errorf("распарсилось неверно: %+v", u)
	}
}

func TestSendMessageWithContactButton(t *testing.T) {
	var body map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/botTEST/sendMessage" {
			t.Errorf("неожиданный путь: %s", r.URL.Path)
		}
		json.NewDecoder(r.Body).Decode(&body)
		w.Write([]byte(`{"ok":true,"result":{}}`))
	}))
	defer server.Close()

	client := NewClient("TEST", server.URL)
	if err := client.SendMessage(context.Background(), 555, "привет", true); err != nil {
		t.Fatalf("SendMessage: %v", err)
	}
	if body["chat_id"].(float64) != 555 || body["text"].(string) != "привет" {
		t.Errorf("тело неверное: %v", body)
	}
	if body["reply_markup"] == nil {
		t.Error("ожидал reply_markup с кнопкой контакта")
	}
}

func TestApiErrorIsReturned(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"ok":false,"description":"Unauthorized"}`))
	}))
	defer server.Close()

	client := NewClient("TEST", server.URL)
	if _, err := client.GetUpdates(context.Background(), 0); err == nil {
		t.Fatal("ожидал ошибку при ok=false")
	}
}
```

Run: `cd bot-service && go test ./internal/telegram/` → COMPILE FAIL (клиента нет).

- [ ] **Step 3: Реализовать клиент**

`internal/telegram/client.go`:

```go
// Package telegram — тонкий клиент Telegram Bot API: getUpdates + sendMessage.
package telegram

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"time"
)

type Update struct {
	UpdateID int64    `json:"update_id"`
	Message  *Message `json:"message"`
}

type Message struct {
	Chat    Chat     `json:"chat"`
	From    *User    `json:"from"`
	Text    string   `json:"text"`
	Contact *Contact `json:"contact"`
}

type Chat struct {
	ID int64 `json:"id"`
}

type User struct {
	ID       int64  `json:"id"`
	Username string `json:"username"`
}

type Contact struct {
	PhoneNumber string `json:"phone_number"`
	UserID      int64  `json:"user_id"`
}

// API — то, что нужно поллеру и консьюмеру; Client её реализует.
type API interface {
	GetUpdates(ctx context.Context, offset int64) ([]Update, error)
	SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error
}

type Client struct {
	base string
	http *http.Client
}

// NewClient: baseURL в проде — "https://api.telegram.org", в тестах — httptest.
func NewClient(token, baseURL string) *Client {
	return &Client{
		base: baseURL + "/bot" + token,
		// long poll 30с + запас; таймаут больше poll-таймаута обязателен
		http: &http.Client{Timeout: 65 * time.Second},
	}
}

type apiResponse struct {
	OK          bool            `json:"ok"`
	Description string          `json:"description"`
	Result      json.RawMessage `json:"result"`
}

func (c *Client) GetUpdates(ctx context.Context, offset int64) ([]Update, error) {
	q := url.Values{}
	q.Set("timeout", "30")
	q.Set("offset", fmt.Sprint(offset))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		c.base+"/getUpdates?"+q.Encode(), nil)
	if err != nil {
		return nil, err
	}
	var updates []Update
	if err := c.do(req, &updates); err != nil {
		return nil, err
	}
	return updates, nil
}

func (c *Client) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error {
	body := map[string]any{"chat_id": chatID, "text": text}
	if requestContact {
		body["reply_markup"] = map[string]any{
			"keyboard": [][]map[string]any{{
				{"text": "Поделиться контактом", "request_contact": true},
			}},
			"resize_keyboard":   true,
			"one_time_keyboard": true,
		}
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.base+"/sendMessage", bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	return c.do(req, nil)
}

func (c *Client) do(req *http.Request, result any) error {
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	var api apiResponse
	if err := json.NewDecoder(resp.Body).Decode(&api); err != nil {
		return fmt.Errorf("telegram: битый ответ: %w", err)
	}
	if !api.OK {
		return fmt.Errorf("telegram: %s", api.Description)
	}
	if result != nil {
		return json.Unmarshal(api.Result, result)
	}
	return nil
}
```

Run: `go test ./internal/telegram/` → клиентские тесты PASS (поллерных ещё нет).

- [ ] **Step 4: Красные тесты поллера**

`internal/telegram/poller_test.go`:

```go
package telegram

import (
	"context"
	"testing"
)

type fakeAPI struct {
	sent           []string
	sentChatIDs    []int64
	contactButtons []bool
}

func (f *fakeAPI) GetUpdates(ctx context.Context, offset int64) ([]Update, error) {
	return nil, nil
}

func (f *fakeAPI) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error {
	f.sent = append(f.sent, text)
	f.sentChatIDs = append(f.sentChatIDs, chatID)
	f.contactButtons = append(f.contactButtons, requestContact)
	return nil
}

type fakePublisher struct {
	published []string // "chatID|phone|username"
	err       error
}

func (f *fakePublisher) PublishContactShared(ctx context.Context, chatID int64, phone, username string) error {
	if f.err != nil {
		return f.err
	}
	f.published = append(f.published, formatKey(chatID, phone, username))
	return nil
}

func formatKey(chatID int64, phone, username string) string {
	return string(rune(chatID)) + "|" + phone + "|" + username
}

func TestStartCommandSendsGreetingWithContactButton(t *testing.T) {
	api := &fakeAPI{}
	p := NewPoller(api, &fakePublisher{})

	p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777}, Text: "/start"}})

	if len(api.sent) != 1 || !api.contactButtons[0] || api.sentChatIDs[0] != 555 {
		t.Fatalf("ожидал приветствие с кнопкой контакта в чат 555: %+v", api)
	}
}

func TestOwnContactIsPublished(t *testing.T) {
	api := &fakeAPI{}
	pub := &fakePublisher{}
	p := NewPoller(api, pub)

	p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777, Username: "masha"},
		Contact: &Contact{PhoneNumber: "81300000001", UserID: 777}}})

	if len(pub.published) != 1 {
		t.Fatalf("ожидал 1 публикацию, получил %d", len(pub.published))
	}
	if len(api.sent) != 1 {
		t.Fatalf("ожидал ack-сообщение пользователю")
	}
}

func TestForeignContactIsRejected(t *testing.T) {
	api := &fakeAPI{}
	pub := &fakePublisher{}
	p := NewPoller(api, pub)

	// контакт чужого пользователя (user_id != from.id) — не публикуем
	p.handle(context.Background(), Update{Message: &Message{
		Chat: Chat{ID: 555}, From: &User{ID: 777},
		Contact: &Contact{PhoneNumber: "81300000001", UserID: 999}}})

	if len(pub.published) != 0 {
		t.Fatal("чужой контакт не должен публиковаться")
	}
}
```

Run: `go test ./internal/telegram/` → COMPILE FAIL (поллера нет).

- [ ] **Step 5: Реализовать поллер**

`internal/telegram/poller.go`:

```go
package telegram

import (
	"context"
	"log"
	"time"
)

// ContactPublisher — публикация CONTACT_SHARED; реализация — internal/kafka.
type ContactPublisher interface {
	PublishContactShared(ctx context.Context, chatID int64, phone, username string) error
}

type Poller struct {
	api       API
	publisher ContactPublisher
	offset    int64
}

func NewPoller(api API, publisher ContactPublisher) *Poller {
	return &Poller{api: api, publisher: publisher}
}

// Run крутит long polling до отмены контекста.
func (p *Poller) Run(ctx context.Context) {
	for ctx.Err() == nil {
		updates, err := p.api.GetUpdates(ctx, p.offset)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("telegram getUpdates: %v — повтор через 3с", err)
			select {
			case <-time.After(3 * time.Second):
			case <-ctx.Done():
				return
			}
			continue
		}
		for _, u := range updates {
			p.handle(ctx, u)
			p.offset = u.UpdateID + 1
		}
	}
}

func (p *Poller) handle(ctx context.Context, u Update) {
	m := u.Message
	if m == nil {
		return
	}
	switch {
	case m.Text == "/start":
		if err := p.api.SendMessage(ctx, m.Chat.ID,
			"Привет! Чтобы получать коды подтверждения и уведомления о бронях, "+
				"поделись, пожалуйста, своим контактом.", true); err != nil {
			log.Printf("sendMessage /start: %v", err)
		}
	case m.Contact != nil:
		if m.From == nil || m.Contact.UserID != m.From.ID {
			log.Printf("контакт не принадлежит отправителю — игнорирую")
			return
		}
		username := ""
		if m.From != nil {
			username = m.From.Username
		}
		if err := p.publisher.PublishContactShared(ctx, m.Chat.ID,
			m.Contact.PhoneNumber, username); err != nil {
			log.Printf("publish CONTACT_SHARED: %v", err)
			return
		}
		if err := p.api.SendMessage(ctx, m.Chat.ID,
			"Принял! Если твой номер в списке гостей — сейчас придёт подтверждение.",
			false); err != nil {
			log.Printf("sendMessage ack: %v", err)
		}
	}
}
```

- [ ] **Step 6: Зелёный + vet**

Run: `cd bot-service && go test ./... && go vet ./...`
Expected: PASS (6 тестов), vet чист.

- [ ] **Step 7: Commit**

```bash
git add bot-service
git commit -m "feat: bot-service — тонкий Telegram-клиент, поллер онбординга, события"
```

---

### Task 6: bot-service — Kafka-петля и main

**Files:**
- Create: `bot-service/internal/kafka/producer.go`, `bot-service/internal/kafka/consumer.go`
- Create: `bot-service/cmd/bot/main.go`
- Test: `bot-service/internal/kafka/producer_test.go`, `bot-service/internal/kafka/consumer_test.go`

**Interfaces:**
- Consumes: `events.*`, `telegram.API` (как Sender), `telegram.ContactPublisher` (реализуем), контракты Task 1.
- Produces: `kafka.Producer` (реализует ContactPublisher; топик `telegram.inbound`); `kafka.Consumer.Run(ctx)` (группа `bot-service`, топик `notifications.outbound`, дедуп по event_id, рендер WELCOME); собираемый бинарь `cmd/bot`.

- [ ] **Step 1: Красные тесты**

`internal/kafka/producer_test.go`:

```go
package kafka

import (
	"encoding/json"
	"testing"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/events"
)

func TestBuildContactSharedEnvelope(t *testing.T) {
	raw, err := buildContactSharedEnvelope(555, "81300000001", "masha")
	if err != nil {
		t.Fatalf("buildContactSharedEnvelope: %v", err)
	}
	var env events.Envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("конверт не парсится: %v", err)
	}
	if env.EventType != "CONTACT_SHARED" || env.EventID == "" || env.OccurredAt.IsZero() {
		t.Errorf("конверт неполный: %+v", env)
	}
	var p events.ContactShared
	if err := json.Unmarshal(env.Payload, &p); err != nil {
		t.Fatalf("payload не парсится: %v", err)
	}
	if p.ChatID != 555 || p.Phone != "81300000001" || p.TelegramUsername != "masha" {
		t.Errorf("payload неверный: %+v", p)
	}
}
```

`internal/kafka/consumer_test.go`:

```go
package kafka

import (
	"context"
	"strings"
	"testing"
)

type fakeSender struct {
	sent []string
}

func (f *fakeSender) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error {
	f.sent = append(f.sent, text)
	return nil
}

func welcomeJSON(eventID string) []byte {
	return []byte(`{"event_id":"` + eventID + `","occurred_at":"2026-08-19T12:00:00Z",` +
		`"event_type":"WELCOME","payload":{"chat_id":555,"name":"Маша"}}`)
}

func TestWelcomeIsRendered(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	c.handle(context.Background(), welcomeJSON("e-1"))

	if len(sender.sent) != 1 || !strings.Contains(sender.sent[0], "Маша") {
		t.Fatalf("ожидал приветствие с именем: %v", sender.sent)
	}
}

func TestDuplicateEventIsSkipped(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	c.handle(context.Background(), welcomeJSON("e-dup"))
	c.handle(context.Background(), welcomeJSON("e-dup"))

	if len(sender.sent) != 1 {
		t.Fatalf("дубликат не должен отправляться повторно: %d", len(sender.sent))
	}
}

func TestUnknownEventTypeIsIgnored(t *testing.T) {
	sender := &fakeSender{}
	c := newConsumerCore(sender)

	c.handle(context.Background(), []byte(`{"event_id":"e-2","occurred_at":"2026-08-19T12:00:00Z",`+
		`"event_type":"OTP_CODE","payload":{}}`))

	if len(sender.sent) != 0 {
		t.Fatal("незнакомый тип не должен ничего отправлять")
	}
}
```

Run: `cd bot-service && go test ./internal/kafka/` → COMPILE FAIL.

- [ ] **Step 2: Реализовать producer**

`internal/kafka/producer.go`:

```go
// Package kafka — производитель и потребитель событий bot-service.
package kafka

import (
	"context"
	"encoding/json"
	"time"

	kafkago "github.com/segmentio/kafka-go"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/events"
)

type Producer struct {
	writer *kafkago.Writer
}

func NewProducer(brokers []string) *Producer {
	return &Producer{writer: &kafkago.Writer{
		Addr:     kafkago.TCP(brokers...),
		Topic:    "telegram.inbound",
		Balancer: &kafkago.LeastBytes{},
		// авто-создание топика при первом сообщении (dev-Kafka это разрешает)
		AllowAutoTopicCreation: true,
	}}
}

func (p *Producer) PublishContactShared(ctx context.Context, chatID int64, phone, username string) error {
	raw, err := buildContactSharedEnvelope(chatID, phone, username)
	if err != nil {
		return err
	}
	return p.writer.WriteMessages(ctx, kafkago.Message{Value: raw})
}

func (p *Producer) Close() error {
	return p.writer.Close()
}

func buildContactSharedEnvelope(chatID int64, phone, username string) ([]byte, error) {
	payload, err := json.Marshal(events.ContactShared{
		ChatID: chatID, Phone: phone, TelegramUsername: username,
	})
	if err != nil {
		return nil, err
	}
	return json.Marshal(events.Envelope{
		EventID:    events.NewUUID(),
		OccurredAt: time.Now().UTC(),
		EventType:  "CONTACT_SHARED",
		Payload:    payload,
	})
}
```

- [ ] **Step 3: Реализовать consumer**

`internal/kafka/consumer.go`:

```go
package kafka

import (
	"context"
	"encoding/json"
	"log"

	kafkago "github.com/segmentio/kafka-go"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/events"
)

// Sender — минимум, который нужен для доставки уведомления (telegram.Client подходит).
type Sender interface {
	SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error
}

// consumerCore — логика обработки без Kafka-транспорта (тестируется юнитами).
type consumerCore struct {
	sender Sender
	seen   map[string]bool
	order  []string
}

const dedupCap = 1000

func newConsumerCore(sender Sender) *consumerCore {
	return &consumerCore{sender: sender, seen: make(map[string]bool)}
}

func (c *consumerCore) handle(ctx context.Context, raw []byte) {
	var env events.Envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		log.Printf("битое событие, пропускаю: %v", err)
		return
	}
	if c.seen[env.EventID] {
		return // at-least-once: дубликат
	}
	c.remember(env.EventID)
	switch env.EventType {
	case "WELCOME":
		var w events.Welcome
		if err := json.Unmarshal(env.Payload, &w); err != nil {
			log.Printf("битый payload WELCOME: %v", err)
			return
		}
		text := "Привет, " + w.Name + "! Telegram привязан — теперь сюда будут " +
			"приходить коды подтверждения и уведомления о бронях."
		if err := c.sender.SendMessage(ctx, w.ChatID, text, false); err != nil {
			log.Printf("отправка WELCOME chat_id=%d: %v", w.ChatID, err)
		}
	default:
		log.Printf("незнакомый event_type %q — пропускаю (совместимость вперёд)", env.EventType)
	}
}

func (c *consumerCore) remember(eventID string) {
	c.seen[eventID] = true
	c.order = append(c.order, eventID)
	if len(c.order) > dedupCap {
		delete(c.seen, c.order[0])
		c.order = c.order[1:]
	}
}

// Consumer — Kafka-транспорт вокруг consumerCore.
type Consumer struct {
	reader *kafkago.Reader
	core   *consumerCore
}

func NewConsumer(brokers []string, sender Sender) *Consumer {
	return &Consumer{
		reader: kafkago.NewReader(kafkago.ReaderConfig{
			Brokers: brokers,
			GroupID: "bot-service",
			Topic:   "notifications.outbound",
		}),
		core: newConsumerCore(sender),
	}
}

// Run читает до отмены контекста; offset коммитится ПОСЛЕ обработки (at-least-once).
func (c *Consumer) Run(ctx context.Context) {
	for ctx.Err() == nil {
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("kafka fetch: %v", err)
			continue
		}
		c.core.handle(ctx, msg.Value)
		if err := c.reader.CommitMessages(ctx, msg); err != nil {
			log.Printf("kafka commit: %v", err)
		}
	}
}

func (c *Consumer) Close() error {
	return c.reader.Close()
}
```

- [ ] **Step 4: main.go**

`cmd/bot/main.go`:

```go
// bot-service: мост Telegram ↔ Kafka. Бизнес-логики нет — только доставка
// уведомлений и трансляция действий пользователя в события.
package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/kafka"
	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/telegram"
)

func main() {
	token := os.Getenv("BOT_TOKEN")
	if token == "" {
		log.Fatal("BOT_TOKEN не задан")
	}
	brokers := strings.Split(envOr("KAFKA_BROKERS", "localhost:9092"), ",")

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	client := telegram.NewClient(token, "https://api.telegram.org")
	producer := kafka.NewProducer(brokers)
	defer producer.Close()
	consumer := kafka.NewConsumer(brokers, client)
	defer consumer.Close()
	poller := telegram.NewPoller(client, producer)

	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); poller.Run(ctx) }()
	go func() { defer wg.Done(); consumer.Run(ctx) }()
	log.Println("bot-service запущен; Ctrl+C для остановки")
	wg.Wait()
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
```

- [ ] **Step 5: Зелёный, vet, сборка**

```bash
cd bot-service && go mod tidy && go test ./... && go vet ./... && go build ./...
```
Expected: тесты PASS (все пакеты), vet чист, бинарь собирается.
`go mod tidy` подтянет segmentio/kafka-go — проверить, что в go.mod ровно
одна прямая зависимость.

- [ ] **Step 6: Commit**

```bash
git add bot-service
git commit -m "feat: bot-service — Kafka-петля (producer CONTACT_SHARED, consumer WELCOME) и main"
```

---

### Task 7: CI-джоба bot, .env.example, README

**Files:**
- Modify: `.github/workflows/ci.yml`
- Create: `.env.example`
- Modify: `README.md`

**Interfaces:**
- Consumes: bot-service из Tasks 5-6.
- Produces: CI гоняет Go-тесты на каждый push/PR; документация запуска.

- [ ] **Step 1: CI-джоба**

В `.github/workflows/ci.yml` добавить после джобы `backend`:

```yaml
  bot:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: bot-service
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with:
          go-version: "1.23"
          cache-dependency-path: bot-service/go.sum
      - run: go vet ./...
      - run: go test ./...
```

- [ ] **Step 2: .env.example**

Создать в корне:

```bash
# Скопируй в .env (он в .gitignore) и заполни. Токен выдаёт @BotFather.
BOT_TOKEN=
KAFKA_BROKERS=localhost:9092
```

Проверить, что `.env` есть в корневом `.gitignore`; если нет — добавить строку `.env`.

- [ ] **Step 3: README — запуск бота**

В `README.md` после блока про backend добавить:

```markdown
### bot-service

```bash
docker compose -f docker-compose.dev.yml up -d   # Kafka
cp .env.example .env                              # и вписать BOT_TOKEN от @BotFather
cd bot-service && BOT_TOKEN=$(grep BOT_TOKEN ../.env | cut -d= -f2) go run ./cmd/bot
```
```

- [ ] **Step 4: Локальная проверка yaml + коммит**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"
git add .github .env.example README.md .gitignore
git commit -m "ci: джоба bot (go test + vet); docs: запуск bot-service, .env.example"
```

---

### Task 8: Учебный разбор этапа 3

**Files:**
- Create: `docs/learning/03-kafka-outbox-go.md`

**Interfaces:**
- Consumes: весь код Tasks 1-7 (ссылки на реальные файлы).
- Produces: учебная статья (обязательна по родительской спеке §12).

- [ ] **Step 1: Написать статью**

`docs/learning/03-kafka-outbox-go.md` — по-русски, для новичка, каждый ответ
1-3 абзаца со ссылками на реальные файлы репо (стиль — как 00-02), структура
`## N.`:

1. Kafka: что такое топик, партиция, offset, consumer group; почему у нас по
   1 партиции и по одной группе на сервис; KRaft-режим (без ZooKeeper) в
   docker-compose.dev.yml.
2. Transactional outbox: какую проблему решает («бронь создалась, а код не
   ушёл»), как `OutboxWriter` с `MANDATORY` гарантирует атомарность, как
   паблишер доставляет и почему падение Kafka не теряет события.
3. At-least-once и идемпотентность: почему «ровно один раз» — миф на
   практике; дедуп через `processed_events` в транзакции (backend) vs
   best-effort в памяти (bot) — почему разным данным разная строгость.
4. Основы Go на нашем коде: горутины и `context` (`poller.Run`/`consumer.Run`
   + graceful shutdown в main), обработка ошибок без исключений, интерфейсы
   (`API`, `Sender`, `ContactPublisher`) и почему они делают код тестируемым
   без моков-фреймворков, `httptest` вместо реального Telegram.
5. Безопасность онбординга: почему бот принимает только СВОЙ контакт
   (`contact.user_id == from.id`), что гарантирует Telegram про подлинность
   номера, почему незнакомцев игнорируем молча.

Перед написанием ПРОЧИТАТЬ файлы, на которые ссылаешься; систематическая
проверка на случайные иноязычные слова в русской прозе (метод Task 9 этапа
0-1).

- [ ] **Step 2: Commit**

```bash
git add docs/learning
git commit -m "docs: разбор этапа 3 — Kafka, transactional outbox, основы Go"
```

---

## Финальная проверка этапа (вне задач — выполняется контролёром с владельцем)

Живой смоук по спеке §6: поднять compose (Kafka), `./mvnw spring-boot:run`,
`go run ./cmd/bot` с реальным BOT_TOKEN из .env; владелец в Telegram: Start →
поделиться контактом (его телефон должен быть в users) → проверить: в БД
появился `telegram_chat_id`, WELCOME пришёл в Telegram, `GET /api/me` отдаёт
`telegramLinked: true`. Выполняется после финального ревью ветки, перед merge.
