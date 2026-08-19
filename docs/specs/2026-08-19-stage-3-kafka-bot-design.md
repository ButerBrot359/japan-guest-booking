# Этап 3: Kafka, transactional outbox, bot-service (Go), онбординг контакта — дизайн

Дата: 2026-08-19. Статус: одобрен владельцем (брейншторминг 2026-08-19).
Родительская спека: `docs/specs/2026-08-13-japan-guest-booking-design.md` (разделы 3.1, 3.6, 4, 7, 8).

## 1. Цель и скоуп

Связать backend-api и Telegram через Kafka «полной петлёй»: гость жмёт Start у
бота и делится контактом → `CONTACT_SHARED` → backend привязывает
`telegram_chat_id` → кладёт `WELCOME` в outbox → паблишер доставляет в Kafka →
bot-service отправляет приветствие в Telegram.

**В скоупе:** контракты событий в `contracts/`; outbox-паблишер и
outbox-writer в backend; консьюмер `CONTACT_SHARED` с идемпотентностью;
`WELCOME`-уведомление; `GET /api/me` + поле `telegramLinked`; каркас
bot-service на Go (свой тонкий Telegram-клиент на net/http, long polling;
Kafka через segmentio/kafka-go); Kafka-контейнер в тестовой инфраструктуре
Java; Go-джоба в CI; живой смоук с реальным ботом (токен владельца от
BotFather, хранится в `.env`).

**Вне скоупа:** все остальные типы событий (`OTP_CODE`, `BOOKING_*`,
`ACCESS_REQUEST_RECEIVED` — этапы 4-5; контрактно описываются сейчас, но не
реализуются); webhook-режим Telegram (только long polling); JSON
Schema-валидация контрактов (описание — для людей); деплой (этап 8).

## 2. Контракты событий (`contracts/`)

Файлы: `contracts/envelope.md` (конверт), `contracts/notifications-outbound.md`,
`contracts/telegram-inbound.md` + обновлённый `contracts/README.md` с
правилами эволюции: поля не удаляются и не переименовываются, новые поля —
только опциональные; каждый событийный тип описан примером JSON.

**Конверт** (все события обоих топиков):

```json
{
  "event_id": "uuid",
  "occurred_at": "2026-08-19T12:00:00Z",
  "event_type": "WELCOME",
  "payload": { }
}
```

**Топик `notifications.outbound`** (backend → bot; 1 партиция):
- `WELCOME` — `{chat_id, name}` (реализуется в этом этапе);
- `OTP_CODE`, `BOOKING_CONFIRMED|CANCELLED|RESCHEDULED`,
  `ACCESS_REQUEST_RECEIVED` — описаны контрактно по родительской спеке §7,
  реализация в этапах 4-5.

**Топик `telegram.inbound`** (bot → backend; 1 партиция):
- `CONTACT_SHARED` — `{chat_id, phone, telegram_username}`
  (`telegram_username` может быть null).

## 3. Backend-api: пакет `messaging` + правки

- **`OutboxWriter`** — `write(topic, eventType, payload)` вставляет строку в
  `outbox` (таблица из V1) с JSON-конвертом; вызывается ТОЛЬКО внутри чужой
  транзакции (`@Transactional(propagation = MANDATORY)`) — бизнес-эффект и
  событие атомарны.
- **`OutboxPublisher`** — `@Scheduled(fixedDelay = 2000)`:
  `SELECT ... WHERE published_at IS NULL ORDER BY id LIMIT 100` →
  `KafkaTemplate.send(topic, payload)` (с ожиданием ack) → `published_at =
  now()`. Ошибка отправки — строка остаётся, придёт следующий цикл; порядок
  по id сохраняет порядок событий.
- **`ContactSharedConsumer`** — `@KafkaListener(topics = "telegram.inbound",
  groupId = "backend-api")`, в одной транзакции: `event_id` уже в
  `processed_events` → пропустить (идемпотентность); телефон нормализуется
  (`Phones.normalize`) и ищется в `users`; найден → `telegram_chat_id = chat_id`
  + `WELCOME` в outbox через `OutboxWriter`; не найден → событие просто
  помечается обработанным (незнакомцы игнорируются молча); в конце — вставка
  в `processed_events`. Повторный онбординг привязанного пользователя (новый
  event_id): тот же `chat_id` — ничего не делать (без второго WELCOME);
  другой `chat_id` (новый Telegram-аккаунт) — обновить привязку и отправить
  WELCOME на новый chat_id.
- **`GET /api/me`** → `{phone, name, role, telegramLinked}` (по
  `telegram_chat_id != null`).
- Конфиг: `spring.kafka.bootstrap-servers` (localhost:9092 dev), сериализация
  строковая (конверт сериализуется Jackson'ом в String).
- Новых таблиц/миграций нет (`outbox`, `processed_events`,
  `users.telegram_chat_id` — с V1).

## 4. bot-service (Go)

```
bot-service/
├── go.mod                  # Go 1.22+; зависимость: github.com/segmentio/kafka-go
├── cmd/bot/main.go         # конфиг из env, запуск горутин, graceful shutdown по SIGINT/SIGTERM
└── internal/
    ├── telegram/client.go  # GetUpdates (long poll timeout 30s), SendMessage — поверх net/http
    ├── telegram/poller.go  # /start → приветствие + reply-кнопка «поделиться контактом»;
    │                       #   контакт → CONTACT_SHARED в telegram.inbound
    ├── kafka/consumer.go   # notifications.outbound → рендер текста по event_type → SendMessage
    ├── kafka/producer.go   # запись событий с конвертом в telegram.inbound
    └── events/events.go    # Go-структуры конверта и payload'ов (зеркало contracts/)
```

- Конфиг из переменных окружения: `BOT_TOKEN`, `KAFKA_BROKERS`
  (localhost:9092). Токен — в корневом `.env` (в `.gitignore`), образец —
  `.env.example` без секретов.
- Принцип границы (родительская спека §4): никакой бизнес-логики — только
  рендер уведомлений и трансляция действий пользователя в события.
- Рендер `WELCOME`: «Привет, {name}! Telegram привязан — теперь сюда будут
  приходить коды подтверждения и уведомления о бронях.» Неизвестный
  `event_type` — логируется и пропускается (вперёд-совместимость с этапами
  4-5).
- Consumer group `bot-service`; коммит offset'а после обработки; дедуп
  последних event_id в памяти (map с ограничением ~1000, потеря при
  рестарте допустима — повторное приветствие не критично, спека §7).

## 5. Надёжность (сводка гарантий)

- Доставка at-least-once на обоих направлениях; дедуп: backend —
  `processed_events` в транзакции с бизнес-эффектом, bot — best-effort в
  памяти.
- Падения: Kafka лежит → исходящие копятся в outbox; бот при этом
  продолжает получать обновления Telegram (long polling независим), но не
  может опубликовать CONTACT_SHARED — повторяет с бэкоффом до
  восстановления; bot лежит → уведомления ждут в топике; backend лежит →
  CONTACT_SHARED ждёт в топике.
- Порядок: 1 партиция на топик — события строго упорядочены.

## 6. Тестирование

- **Java (Testcontainers):** в `AbstractIntegrationTest` добавляется
  singleton Kafka-контейнер (`org.testcontainers.kafka.*`, образ
  `apache/kafka-native` или актуальный для TC 2.x) с `@ServiceConnection`,
  тем же паттерном, что Postgres. Тесты: outbox-паблишер доставляет записанное
  событие в топик (читаем тестовым консьюмером); `ContactSharedConsumer` —
  привязка + WELCOME в outbox (одна транзакция), идемпотентность (дважды тот
  же event_id = один эффект), незнакомый телефон игнорируется; `/api/me`
  отражает привязку. TRUNCATE-список тестовой очистки не меняется (outbox и
  processed_events уже в нём).
- **Go:** юнит-тесты с `httptest.Server` вместо Telegram API (парсинг
  обновлений, Start-приветствие, контакт → событие, рендер WELCOME);
  Kafka-клиенты за интерфейсами, в тестах — фейки. `go vet` чист.
- **CI:** новая джоба `bot` (setup-go, `go test ./...`, `go vet ./...`,
  working-directory bot-service); джоба `backend` не меняется.
- **Ручной смоук:** docker compose (Kafka уже там) + backend + bot с живым
  токеном: Start → контакт → в БД появился chat_id → WELCOME пришёл в
  Telegram → `/api/me` показывает `telegramLinked: true`.

## 7. Переносы и заметки

- Учебный разбор этапа (обязателен по родительской спеке §12):
  `docs/learning/03-kafka-outbox-go.md` — Kafka (топики, consumer groups,
  KRaft, партиции), transactional outbox, at-least-once и идемпотентность,
  основы Go (горутины, контексты, ошибки без исключений) — отдельной
  задачей плана (урок этапа 2: разбор не забывать в плане).
- Бэклоги этапов 5/8 из памяти этапа 2 этого этапа не касаются; ничего из
  них сюда не тянем.
