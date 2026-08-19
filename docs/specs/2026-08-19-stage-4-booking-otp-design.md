# Этап 4: Бронирование с OTP end-to-end — дизайн

Дата: 2026-08-19. Статус: одобрен владельцем (брейншторминг 2026-08-19).
Родительская спека: `docs/specs/2026-08-13-japan-guest-booking-design.md` (§3.2, §5, §6, §8).

## 1. Цель и скоуп

Гость с привязанным Telegram полностью управляет своей бронью: создаёт,
подтверждает одноразовым кодом из Telegram, переносит и отменяет (обе — тоже
через код). Уведомления гостю и админу. Фоновая чистка протухших броней.

**В скоупе:** `POST /api/bookings`, `POST /api/bookings/{id}/confirm`,
`PATCH /api/bookings/{id}` (перенос), `DELETE /api/bookings/{id}` (отмена),
`POST /api/bookings/{id}/resend-code`; `OtpService`; `PendingBookingCleaner`;
миграция V2 (триггер `updated_at`); `activeBooking` в `GET /api/me`; рендеры
`OTP_CODE`/`BOOKING_*` в боте; переносы из бэклога: бот не теряет уведомления
при сбое отправки, валидация конверта в Java-консьюмере, `asText→asString`,
`@Column(length)`+`@Size` на пишемых полях.

**Вне скоупа:** админские операции над бронями (этап 5), UI (этап 6),
SMS-каналы, одобрение броней админом (вне V1 по родительской спеке).

## 2. Данные

- Таблицы `bookings` и `otp_challenges` — из V1, без изменений структуры.
- **Миграция V2__updated_at_trigger.sql:** функция + триггер `BEFORE UPDATE
  ON bookings` → `updated_at = now()` (перенос из финального ревью этапа 1:
  триггер надёжнее `@UpdateTimestamp` — покрывает и не-JPA записи; маппинг
  `updatedAt` в энтити остаётся read-only).
- Конечный автомат брони: `PENDING_OTP → CONFIRMED → CANCELLED`;
  `PENDING_OTP → CANCELLED` (чистильщик или явная отмена неподтверждённой).
  Никаких других переходов; правки статуса — только атомарным
  `UPDATE ... WHERE status = <ожидаемый>` (гонки решает «кто первый»).
- Челлендж: `PENDING → USED | EXPIRED`. У гостя максимум один активный:
  выпуск нового вытесняет старый (`PENDING → EXPIRED`).

## 3. OTP-механика (`otp`-пакет)

- Код: 6 цифр, `SecureRandom`; в БД — только BCrypt-хеш; код нигде не
  логируется, живёт только в payload события `OTP_CODE`.
- `OtpService.issue(userId, action, payload)` → создаёт челлендж
  (TTL 5 минут), кладёт `OTP_CODE {chat_id, code, action, expires_at}` в
  outbox (той же транзакцией), возвращает id челленджа. `payload` ВСЕГДА
  содержит `booking_id` (+ для RESCHEDULE — новые даты); `confirm` сверяет
  `booking_id` из челленджа с id из URL — код от одной операции не применим
  к другой (несовпадение → 400 `NO_ACTIVE_CODE`).
- `OtpService.verify(userId, bookingId?, code)`: находит активный PENDING
  челлендж гостя; `attempts` инкрементируется атомарно ДО сравнения
  (параллельный перебор не обходит счётчик); >5 минут или ≥3 неудачи →
  `EXPIRED`. Успех → `USED`, возвращает `action` + `payload`.
- Повторная отправка: `resend-code` не чаще раза в минуту (по `created_at`
  активного челленджа) → 429 `RESEND_TOO_SOON`; создаёт новый код (старый
  вытесняется).
- Коды ошибок: неверный/просроченный → 400 `INVALID_CODE` (без уточнения —
  не помогаем переборщику); исчерпаны попытки → 400 `CODE_EXPIRED`
  (гостю нужен resend); нет активного челленджа → 400 `NO_ACTIVE_CODE`.

## 4. Бронирование (`booking`-пакет, поверх существующего)

Общие предусловия всех гостевых операций: аутентифицирован; бронь (для
confirm/reschedule/cancel/resend) принадлежит гостю, иначе 403; Telegram
привязан, иначе 409 `TELEGRAM_NOT_LINKED` («привяжи Telegram: инструкция»).

- **Создание** `POST /api/bookings {checkIn, checkOut, comment?}`:
  валидация дат (`checkIn < checkOut`, не в прошлом по JST); вставка
  `PENDING_OTP` — даты удерживаются EXCLUDE-constraint'ом, конфликт → 409
  `DATES_TAKEN` («даты только что заняли» + фронт обновит календарь);
  особый случай: пересечение с СОБСТВЕННОЙ бронью → 409 `OVERLAPS_OWN_BOOKING`
  («используй перенос»); выпуск OTP-челленджа `CREATE_BOOKING`.
  **Замена активной брони:** если у гостя есть CONFIRMED-бронь, запрос НЕ
  отклоняется; ответ содержит `willReplaceBooking {id, checkIn, checkOut}` и
  предупреждение «после подтверждения новой старая будет отменена».
- **Подтверждение** `POST /api/bookings/{id}/confirm {code}`: `verify` →
  по `action` из челленджа, в одной транзакции с outbox:
  - `CREATE_BOOKING`: если есть старая CONFIRMED-бронь гостя — сначала она
    → `CANCELLED (cancelled_by=GUEST)` + событие `BOOKING_CANCELLED`;
    затем новая `PENDING_OTP → CONFIRMED` (атомарный UPDATE WHERE status;
    0 строк = уже отменена чистильщиком → 409 `BOOKING_EXPIRED`) +
    `BOOKING_CONFIRMED` гостю и админу. Порядок обязателен: частичный
    уникальный индекс «одна CONFIRMED на гостя».
  - `RESCHEDULE`: атомарный UPDATE дат из `payload` челленджа; EXCLUDE-
    конфликт (даты заняли за 5 минут) → 409 `DATES_TAKEN`, челлендж
    остаётся USED (новый запрос переноса = новый код); успех →
    `BOOKING_RESCHEDULED` гостю и админу. Даты переноса ДО подтверждения
    не удерживаются (решение владельца: вариант A — честный 409 при гонке).
  - `CANCEL`: `CONFIRMED → CANCELLED (GUEST)` + `BOOKING_CANCELLED` обоим.
- **Перенос** `PATCH /api/bookings/{id} {checkIn, checkOut}`: только для
  своей CONFIRMED-брони; валидация дат; выпуск челленджа `RESCHEDULE` с
  датами в `payload`. **Отмена** `DELETE /api/bookings/{id}`: челлендж
  `CANCEL {booking_id}`. Оба меняют бронь ТОЛЬКО при confirm.
- **`GET /api/me`** дополняется `activeBooking: {id, checkIn, checkOut,
  status} | null` (CONFIRMED, иначе последняя PENDING_OTP, иначе null).

## 5. Уведомления (бот) и надёжность

- Рендеры в bot-service: `OTP_CODE` («Код подтверждения: 123456. Действует
  5 минут.»), `BOOKING_CONFIRMED` / `BOOKING_CANCELLED` /
  `BOOKING_RESCHEDULED` («{guest_name}: заезд {check_in}, выезд {check_out}»
  — формулировки дружелюбные, различать «тебе» и «админу» не нужно:
  события адресные по chat_id). Контракты §7 родительской спеки уже
  зафиксированы в contracts/ (этап 3).
- Админ-уведомления: `chat_id` админа из его записи `users` (админ
  онбордится в бота как все); не привязан → событие админу не создаётся.
- **Перенос из бэклога этапа 3 (обязательный):** консьюмер бота коммитит
  offset и запоминает `event_id` ТОЛЬКО после успешного `SendMessage`;
  при сбое — ретрай с бэкоффом (сообщение с OTP потерять нельзя).
  Принятая цена: перманентный сбой отправки одного сообщения блокирует
  очередь уведомлений (как и глобальный offset поллера — трейдофф «без
  потерь» уже задокументирован).
- `PendingBookingCleaner` — `@Scheduled` каждые 2 минуты: PENDING_OTP-брони
  без активного (PENDING, непросроченного) челленджа → `CANCELLED`
  атомарным UPDATE; идемпотентен; уведомления об авто-отмене не шлём
  (гость и так не довёл флоу).
- Мелкие переносы: валидация конверта в `ContactSharedConsumer` (битое
  сообщение → лог+skip, не NPE); `asText()`→`asString()` по всему Java-коду;
  `@Column(length=...)` + Bean Validation `@Size` на `comment` (500) и
  других пишемых строках.

## 6. Тестирование

- **Юнит (`OtpServiceTest`, Clock-инъекция):** генерация 6 цифр; verify
  успех/неверный; 3 попытки → EXPIRED; TTL 5 минут; resend-лимит 1/мин;
  вытеснение старого челленджа.
- **Интеграционные (TDD, AbstractIntegrationTest + MockMvc):**
  create → PENDING_OTP + OTP_CODE в outbox; confirm → CONFIRMED +
  BOOKING_CONFIRMED (гостю и админу при привязанном админе);
  замена: старая CANCELLED + новая CONFIRMED в одной транзакции, оба
  события; неверный код ×3 → CODE_EXPIRED; протухшая PENDING →
  чистильщик освобождает даты (проверка EXCLUDE: новая бронь на те же
  даты проходит); перенос: happy path + гонка (займём даты между PATCH и
  confirm → 409 DATES_TAKEN, бронь не изменилась); отмена; чужая бронь →
  403; без Telegram → 409 TELEGRAM_NOT_LINKED; пересечение с собственной
  → 409 OVERLAPS_OWN_BOOKING; resend → 429 при спешке; confirm по брони,
  отменённой чистильщиком → 409 BOOKING_EXPIRED.
- **Go:** рендеры 4 новых типов; тест «offset/remember только после
  успешной отправки» (сбойный Sender → сообщение передоставляется).
- **Слайс:** валидация тел (@Size comment, даты), формат ошибок.
- **Живой смоук (с владельцем, перед merge):** реальная бронь через сайт-API
  (curl) + настоящий код из Telegram + уведомление о подтверждении.

## 7. Учебный разбор

`docs/learning/04-booking-otp-state-machines.md` — В ТУТОР-ФОРМАТЕ
(см. docs/learning/README.md: front-matter с topics/code_anchors/decisions/
pitfalls/quiz_seeds + блоки «Разбор кода»). Темы: конечные автоматы статусов
и атомарные переходы; OTP-безопасность (хеш, попытки-до-сравнения,
неинформативные ошибки); фоновые задачи и идемпотентность чистильщика;
гонки и разрешение через БД (UPDATE WHERE + EXCLUDE); паттерн
«замена вместо отказа» в UX.
