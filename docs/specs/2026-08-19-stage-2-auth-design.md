# Этап 2: Аутентификация — дизайн

Дата: 2026-08-19. Статус: одобрен владельцем (брейншторминг 2026-08-19).
Родительская спека: `docs/specs/2026-08-13-japan-guest-booking-design.md` (разделы 3, 6, 8).

## 1. Цель и скоуп

Дать системе понятие «кто ты»: логин гостя по телефону из белого списка, логин
админа по телефону+паролю, JWT-сессия в httpOnly cookie, защита роутов по ролям,
rate limit на логины.

**В скоупе:** `POST /api/auth/login`, `POST /api/auth/admin-login`,
`POST /api/auth/logout`, базовый `GET /api/me`, seed админа из конфигурации,
rate limit, catch-all обработчик ошибок, `setup-java@v5` в CI (перенос из
финального ревью этапа 0–1).

**Вне скоупа:** сброс пароля админа (нужен Telegram-бот — этап 3+), заявки на
доступ `POST /api/access-requests` (этап 5), расширение `/api/me` данными о
брони и Telegram (этапы 3–4), Secure-флаг cookie (этап 8, HTTPS).

## 2. Данные

Новых таблиц и миграций нет. Используется `users` из V1: `phone` (UNIQUE),
`name`, `role` (FRIEND|ADMIN), `password_hash` (nullable — у гостей NULL).

**Bootstrap админа:** конфигурация `app.admin.phone` и `app.admin.password`
(локально — application.yml/переменные окружения; прод — `.env`, этап 8).
`AdminSeeder` (`ApplicationRunner`) при старте создаёт админа или обновляет
существующего (телефон совпал → обновить hash и роль). Пароль хешируется
BCrypt. Секреты в git не попадают; для локальной разработки в
`application.yml` допускаются дефолтные dev-значения с комментарием.

Гости добавляются в белый список вне этого этапа (admin API — этап 5; до
тех пор — ручной SQL для разработки).

## 3. JWT и cookie

- Библиотека **jjwt**; подпись HMAC-SHA256; секрет — `app.jwt.secret` из
  конфигурации (≥ 32 байта; dev-дефолт в application.yml, прод — `.env`).
- Claims: `sub` = id пользователя, `role` = FRIEND|ADMIN, `iat`, `exp`.
- Срок жизни 30 дней; refresh-токенов нет (YAGNI: revocation для дома друзей
  не нужна, компрометация лечится сменой секрета).
- Cookie: имя `auth`, `HttpOnly`, `SameSite=Lax`, `Path=/`,
  `Max-Age=30 дней`; `Secure` добавится в этапе 8. CSRF-защита Spring
  отключена (stateless + SameSite=Lax покрывают наш случай).

## 4. Компоненты (пакет `com.batowka.guestbooking.auth`, правки в `common`)

- **`JwtService`** — выпуск и валидация/парсинг токена. Единственное место,
  знающее формат JWT.
- **`JwtAuthFilter`** (`OncePerRequestFilter`) — извлекает cookie `auth`,
  валидирует через `JwtService`, кладёт `Authentication` (principal = userId,
  authority = `ROLE_<role>`) в `SecurityContext`. Нет/битый/протухший токен —
  запрос просто остаётся неаутентифицированным.
- **`SecurityConfig`** — stateless, CSRF off, цепочка правил:
  `/api/calendar`, `/api/auth/**` — permitAll; `/api/admin/**` — ROLE_ADMIN;
  остальные `/api/**` — authenticated. 401/403 отдаются в формате
  `{"code","message"}` (кастомные `AuthenticationEntryPoint` /
  `AccessDeniedHandler`).
- **`AuthController`** — login / admin-login / logout (контракты в §5).
- **`MeController`** — `GET /api/me`.
- **`LoginRateLimiter`** — собственная in-memory реализация «скользящего
  окна» (~20 строк, ConcurrentHashMap по IP), 5 попыток/мин на IP, общий
  для обоих логинов; превышение → 429. (Решение при планировании: вместо
  bucket4j — у него нестабильные Maven-координаты между версиями, а свой
  limiter проще и нагляднее для обучения.)
- **`AdminSeeder`** — см. §2.
- **`common.GlobalExceptionHandler`** — добавить catch-all
  `@ExceptionHandler(Exception.class)` → `500 {"code":"INTERNAL_ERROR"}`
  с логированием stack trace (сообщение исключения клиенту не отдаётся).

## 5. Контракты API

| Запрос | Успех | Ошибки |
|---|---|---|
| `POST /api/auth/login` `{"phone"}` | `204` + Set-Cookie | `401 UNKNOWN_PHONE` (номер не в списке — членство раскрываем осознанно, по спеке; ТАКЖЕ возвращается для номера с ролью ADMIN — беспарольный гостевой логин не должен выдавать админский токен, админ ходит только через admin-login); `400 VALIDATION_ERROR`; `429 RATE_LIMITED` |
| `POST /api/auth/admin-login` `{"phone","password"}` | `204` + Set-Cookie | `401 INVALID_CREDENTIALS` (одинаково для любого провала — различия не раскрываем); `400`; `429` |
| `POST /api/auth/logout` | `204` + затирающая cookie (Max-Age=0) | — (работает и без токена) |
| `GET /api/me` | `200 {"phone","name","role"}` | `401 UNAUTHORIZED` |

Нормализация телефона при логине: убрать пробелы/дефисы/скобки; результат
обязан соответствовать E.164 (`+` и 8–15 цифр), иначе `400 VALIDATION_ERROR`.
В БД телефоны хранятся уже нормализованными.

Общие ошибки: нет/протух токен на защищённом роуте → `401 UNAUTHORIZED`;
не та роль → `403 FORBIDDEN`; неожиданное исключение → `500 INTERNAL_ERROR`.
Формат всех ошибок — `{"code","message"}` (Global Constraint).

## 6. Тестирование

- **Юнит (`JwtServiceTest`):** выпустил→распарсил (roundtrip); протухший →
  отказ; чужая подпись → отказ.
- **Интеграционные** (наследники `AbstractIntegrationTest`, TDD):
  гость: логин известным номером → cookie → `/api/me` даёт профиль;
  неизвестный номер → 401 UNKNOWN_PHONE; кривой формат телефона → 400;
  телефон АДМИНА в гостевой логин → 401 UNKNOWN_PHONE (беспарольный обход
  admin-login закрыт);
  админ: верный пароль → cookie с ролью ADMIN; неверный → 401
  INVALID_CREDENTIALS; гость на `/api/admin/**` → 403; аноним на `/api/me` →
  401; logout затирает cookie; `AdminSeeder` создал/обновил админа из
  тестового конфига; 6-й логин-запрос подряд с одного IP → 429.
- **Слайс (`@WebMvcTest`):** валидация тел запросов, формат ошибок.
- Инфраструктурное: тестовые значения `app.jwt.secret` / `app.admin.*` —
  в тестовом application.yml; TRUNCATE-список AbstractIntegrationTest не
  меняется (новых таблиц нет).

## 7. Переносы из финального ревью этапа 0–1 (входят в план этапа 2)

- catch-all хендлер (§4);
- `actions/setup-java@v4` → `@v5` в `.github/workflows/ci.yml`;
- однострочное уточнение родительской спеки: «имя гостя показывается только
  для CONFIRMED-броней» (код этапа 1 консервативнее текста спеки 3.3).
