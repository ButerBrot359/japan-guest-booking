# Japan Guest Booking — дизайн-документ

Дата: 2026-08-13. Статус: утверждён владельцем проекта.

## 1. Что это

Веб-приложение, через которое друзья бронируют даты визита в гости (Япония).
Проект одновременно обучающий: цель — пройти полный продакшн-цикл
(React, Spring Boot, Go, Kafka, PostgreSQL, Docker, CI/CD, VPS) и на каждом
этапе фиксировать разборы изученного в `docs/learning/`.

## 2. Роли

- **Гость (друг)** — человек из белого списка номеров. Смотрит календарь,
  бронирует/переносит/отменяет свою бронь (с подтверждением кодом в Telegram).
- **Админ (владелец)** — управляет белым списком, блокирует даты, отменяет и
  переносит любые брони, рассматривает заявки новых людей. Получает
  уведомления обо всём в Telegram.
- **Посетитель** — любой человек без входа. Видит календарь, может отправить
  заявку на добавление в белый список.

## 3. Функциональные требования

### 3.1 Вход и онбординг
- Сайт открыт для просмотра всем; календарь — публичный.
- Логин гостя: только номер телефона (E.164), без пароля. Номер должен быть
  в белом списке. Сессия — JWT в httpOnly cookie.
- Для бронирования гость обязан привязать Telegram: запустить бота, нажать
  Start и поделиться контактом. Telegram гарантирует подлинность номера;
  backend связывает `telegram_chat_id` с пользователем. Без привязки
  бронирование недоступно (некуда слать коды) — сайт показывает инструкцию.
- Логин админа: номер телефона + пароль (BCrypt). Сброс пароля — кодом через
  Telegram-бота. После 5 неудачных попыток — временная блокировка и
  уведомление админу.

### 3.2 Брони
- Бронь = полуинтервал дат `[check_in, check_out)`, календарные дни по JST,
  без времени. Выезд 15-го и заезд 15-го не конфликтуют.
- Гостевое место одно: брони (и блокировки админа) не пересекаются по датам.
  Гарантия — на уровне БД (exclusion constraint), при гонке второй запрос
  получает 409 и сообщение «даты только что заняли».
- Одна активная (CONFIRMED) бронь на номер телефона.
- Создание/перенос/отмена гостем подтверждаются одноразовым кодом (OTP),
  который бот присылает в Telegram. Код: 5 минут жизни, 3 попытки ввода,
  повторная отправка не чаще раза в минуту.
- Бронь автоподтверждается после ввода кода — одобрение админа не требуется.
  Админ получает уведомление и может сам отменить или перенести любую бронь
  (гостю уходит уведомление).
- Пока бронь в статусе `PENDING_OTP`, даты удерживаются; фоновая задача
  отменяет протухшие pending-брони и освобождает даты.
- Комментарий к брони — опционален («приеду с женой»).
- Лимитов на длину визита и горизонт бронирования нет.

### 3.3 Календарь
- Показывает свободные, занятые и заблокированные дни.
- На занятых датах видно имя гостя (всем посетителям сайта).

### 3.4 Заявки на доступ
- Посетитель не из списка отправляет форму: имя, телефон, сообщение.
- Админу приходит уведомление в Telegram (без кнопок).
- Решение — только в веб-админке: одобрить (создаётся запись в `users`;
  приглашение новичку бот отправит, когда тот запустит бота и поделится
  контактом) или отклонить.

### 3.5 Админка (веб)
- Белый список: добавить/удалить номер, список пользователей.
- Блокировки дат: создать/удалить период с необязательной причиной.
- Брони: список, отмена, перенос.
- Заявки: список, одобрить/отклонить.

### 3.6 Telegram-бот
- Для гостей интерактив один: Start + поделиться контактом (онбординг).
- Всё остальное — исходящие уведомления: OTP-коды, подтверждения/отмены/
  переносы броней, заявки (админу), приглашение новичку.

## 4. Архитектура

Микросервисы, монорепо. Пять контейнеров в Docker Compose:

```
браузер ──HTTPS──▶ nginx (React-статика + прокси /api)
                     │
                     ▼
                backend-api ◀──события──▶ Kafka ◀──события──▶ bot-service
                     │                                            │
                     ▼                                            ▼
                PostgreSQL                                  Telegram API
```

- **frontend** — React + TypeScript + Vite. SPA: календарь, форма брони,
  админка. В проде — статика за nginx.
- **backend-api** — Spring Boot, Java 21, Gradle. Вся бизнес-логика,
  единственный владелец PostgreSQL. Миграции — Flyway.
- **bot-service** — Go. Telegram long polling. Без своей БД и без
  бизнес-логики: рендерит уведомления из событий и передаёт действия
  пользователей событиями обратно.
- **Kafka** — однонодовая, KRaft (без ZooKeeper). Шина между сервисами.
- **PostgreSQL** — основная БД.

Принцип границы: bot-service ничего не знает про брони. Он умеет «доставить
сообщение в chat_id» и «сообщить, что пользователь сделал X в боте».

## 5. Модель данных (PostgreSQL)

- **users** — запись = членство в белом списке.
  `id, phone (uniq, E.164), name, role (FRIEND|ADMIN), password_hash
  (BCrypt, только у ADMIN, иначе NULL), telegram_chat_id (NULL до
  онбординга), created_at`.
- **bookings** — `id, user_id, check_in, check_out, status
  (PENDING_OTP|CONFIRMED|CANCELLED), comment, cancelled_by (GUEST|ADMIN),
  created_at, updated_at`.
  - Частичный уникальный индекс: один CONFIRMED на `user_id`.
  - Exclusion constraint по `daterange(check_in, check_out)` (btree_gist)
    для статусов PENDING_OTP и CONFIRMED — БД физически не допускает
    пересечений, включая гонки.
- **blocked_periods** — `id, start_date, end_date, reason, created_at`.
  Exclusion constraint покрывает только свою таблицу, поэтому пересечение
  новой брони с блокировками проверяется приложением в той же транзакции
  (advisory lock на время проверки+вставки); внутри `bookings` гарантию
  держит constraint.
- **access_requests** — `id, phone, name, message, status
  (PENDING|APPROVED|REJECTED), created_at, resolved_at`.
- **otp_challenges** — `id, user_id, action (CREATE_BOOKING|RESCHEDULE|
  CANCEL|ADMIN_PASSWORD_RESET), payload (JSONB с параметрами действия),
  code_hash, expires_at, attempts, status, created_at`. Сами коды не
  хранятся — только хеш.
- **outbox** — транзакционный outbox для Kafka: `id, topic, event_type,
  payload (JSONB), created_at, published_at`.
- **processed_events** — идемпотентность consumer'а: обработанные `event_id`.

## 6. API (backend-api)

Формат ошибок единый: `{code, message}`.

Публичное:
- `GET /api/calendar?from&to` — дни: свободен/занят(имя гостя)/заблокирован.
- `POST /api/auth/login` — `{phone}` → сессия гостя.
- `POST /api/access-requests` — заявка на доступ.

Гость (авторизован):
- `GET /api/me` — профиль, статус привязки Telegram, активная бронь.
- `POST /api/bookings` — `{check_in, check_out, comment}` → PENDING_OTP,
  бот шлёт код.
- `POST /api/bookings/{id}/confirm` — `{code}` → CONFIRMED.
- `PATCH /api/bookings/{id}` — перенос (тот же OTP-флоу).
- `DELETE /api/bookings/{id}` — отмена (тот же OTP-флоу).

Админ:
- `POST /api/auth/admin-login` — `{phone, password}`.
- `POST /api/auth/admin-reset` + `POST /api/auth/admin-reset/confirm` —
  сброс пароля кодом из Telegram.
- `GET|POST|DELETE /api/admin/users` — белый список.
- `GET|POST|DELETE /api/admin/blocked-periods`.
- `GET /api/admin/bookings`, `DELETE /api/admin/bookings/{id}`,
  `PATCH /api/admin/bookings/{id}` — без OTP.
- `GET /api/admin/access-requests`,
  `POST /api/admin/access-requests/{id}/approve|reject`.

Безопасность: JWT в httpOnly cookie; роли FRIEND/ADMIN в Spring Security;
rate limit по IP на `login` и `access-requests`; гость управляет только
своей бронью.

## 7. Kafka: контракт событий

JSON, схемы — в `contracts/` (язык-нейтральны). Каждое событие несёт
`event_id` (UUID) и `occurred_at`. Доставка at-least-once, consumer'ы
идемпотентны (таблица `processed_events`; bot-service держит дедуп в памяти —
повторная отправка уведомления не критична).

**Топик `notifications.outbound`** (backend-api → bot-service):
- `OTP_CODE` — `{chat_id, code, action, expires_at}`
- `BOOKING_CONFIRMED | BOOKING_CANCELLED | BOOKING_RESCHEDULED` —
  `{chat_id, guest_name, check_in, check_out}` (гостю и админу)
- `ACCESS_REQUEST_RECEIVED` — `{chat_id, name, phone, message}` (админу)
- `WELCOME` — `{chat_id, name}` (одобренному новичку — отправляется в момент
  его онбординга в боте, раньше `chat_id` неизвестен)

**Топик `telegram.inbound`** (bot-service → backend-api):
- `CONTACT_SHARED` — `{chat_id, phone, telegram_username}`

**Надёжность:** события публикуются через transactional outbox — запись
события в таблицу `outbox` в одной транзакции с бизнес-данными, фоновый
publisher перекладывает в Kafka. Сценарий «бронь создалась, а код не ушёл»
исключён.

## 8. Обработка ошибок и граничные случаи

- Гонка за даты → exclusion constraint, второму 409 + свежий календарь.
- Протухшие PENDING_OTP-брони чистит фоновая задача (каждые ~2 мин).
- Kafka/bot-service недоступны → outbox докладывает после восстановления;
  на сайте кнопка «отправить код ещё раз» (лимит 1/мин).
- Telegram не привязан → бронирование заблокировано, показана инструкция.
- Перебор номеров на логине → rate limit по IP (для «своих» приемлемо, что
  ответ раскрывает членство в списке).
- Спам заявками → rate limit по IP.
- Часовой пояс — все даты по JST.

## 9. Тестирование

- **backend-api:** JUnit 5 + Mockito (юниты бизнес-логики); Testcontainers
  (настоящие Postgres + Kafka): пересечения, OTP-флоу, outbox, чистка
  pending; MockMvc: статусы, формат ошибок, авторизация ролей.
- **bot-service:** табличные Go-тесты рендеринга сообщений, Telegram через
  `httptest`; testcontainers-go для Kafka-интеграции.
- **frontend:** Vitest + React Testing Library: календарь (занятые/
  заблокированные дни некликабельны), формы, показ ошибок. E2e (Playwright)
  — вне скоупа первой версии.
- Процесс — TDD; CI гоняет все наборы на каждый push; красное не мёржится.

## 10. Репозиторий

```
japan-guest-booking/
├── docs/
│   ├── specs/          # этот документ
│   └── learning/       # разборы по этапам (обязательная часть процесса)
├── contracts/          # JSON-схемы Kafka-событий
├── backend-api/        # Spring Boot, Java 21, Gradle
├── bot-service/        # Go
├── frontend/           # React + TypeScript + Vite
├── deploy/             # прод: docker-compose.yml, nginx
├── docker-compose.dev.yml   # локалка: Postgres + Kafka
└── .github/workflows/  # CI/CD
```

Локальная разработка: `docker compose -f docker-compose.dev.yml up`
(Postgres + Kafka), сервисы — нативно (`bootRun`, `go run`, `npm run dev`,
Vite проксирует `/api`).

## 11. Деплой и CI/CD

- VPS ~4 ГБ RAM (Vultr Tokyo / Hetzner), Docker Compose, контейнеры:
  nginx, backend-api, bot-service, kafka, postgres.
- HTTPS — Let's Encrypt. Предусловие: купленный домен.
- Секреты — `.env` на сервере (в git не попадают); токен бота, JWT-секрет,
  пароли БД.
- Бэкапы — ежедневный `pg_dump` по cron.
- GitHub Actions: PR → тесты трёх частей; push в `main` → тесты → сборка
  трёх образов → GitHub Container Registry → SSH на VPS →
  `docker compose pull && up -d`.

## 12. Этапы реализации и темы для docs/learning/

Каждый этап завершается кодом с зелёными тестами **и** разбором в
`docs/learning/`. Детальный пошаговый план — отдельным документом
(writing-plans).

| # | Этап | Темы разбора |
|---|------|--------------|
| 0 | Каркас монорепо, docker-compose.dev, скелет CI | Docker Compose, устройство монорепо, анатомия GitHub Actions |
| 1 | backend-api: схема БД, Flyway, календарь-API | Flyway-миграции, exclusion constraints, daterange, частичные индексы |
| 2 | Аутентификация: логин гостя, JWT, админ-логин | Spring Security, JWT vs сессии, BCrypt, rate limiting |
| 3 | Kafka + outbox + каркас bot-service (Go), онбординг контакта | Kafka (топики, consumer groups, KRaft), transactional outbox, at-least-once и идемпотентность, основы Go |
| 4 | Бронирование с OTP end-to-end | Конечные автоматы статусов, фоновые задачи Spring, OTP-безопасность |
| 5 | Админ-API + заявки на доступ | Роли и авторизация в Spring Security, проектирование админ-API |
| 6 | Frontend: календарь + бронирование | React state, работа с API, httpOnly cookie на фронте |
| 7 | Frontend: админка | Защищённые роуты, формы, таблицы |
| 8 | Деплой: VPS, HTTPS, полный CI/CD | Linux, nginx, Let's Encrypt, деплой через Actions, бэкапы |

## 13. Вне скоупа первой версии

- E2e-тесты (Playwright), мониторинг/алертинг, несколько гостевых мест,
  одобрение броней админом, SMS-каналы, интерактивные кнопки в боте для
  админа, мультиязычность интерфейса.
