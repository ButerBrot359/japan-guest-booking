---
tutor:
  stage: 2
  title: "Spring Security, JWT, BCrypt, rate limiting"
  topics:
    - id: authn-vs-authz
      section: "Аутентификация vs авторизация"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/JwtAuthFilter.java
          symbol: "JwtAuthFilter.doFilterInternal"
          concept: "фильтр только устанавливает Authentication в SecurityContextHolder, никогда не решает, пускать ли запрос"
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/SecurityConfig.java
          symbol: "SecurityConfig.filterChain"
          concept: "requestMatchers(...).hasRole(\"ADMIN\") / .authenticated() — решение «что кому можно» живёт отдельно от «кто ты»"
      quiz_seeds:
        - "Что делает JwtAuthFilter, если cookie нет или она битая — бросает ошибку или просто ничего не кладёт в контекст?"
        - "Что придётся поменять в SecurityConfig, если завтра появится второй способ входа (например, через Telegram-код)?"
      decisions:
        - choice: "собственный JwtAuthFilter (OncePerRequestFilter) + библиотека jjwt для выпуска/парсинга токена"
          alternatives: "spring-boot-starter-oauth2-resource-server — готовый JWT-декодер Spring Security"
          why: "проект учебный: цель — разобраться, что происходит внутри фильтра и токена, а не подключить готовый механизм; формат токена свой и небольшой (нет внешнего issuer/JWKS), под него не нужна вся инфраструктура resource-server"
          price: "то, что resource-server даёт бесплатно (валидация exp/iss/aud из коробки, ротация ключей через JWKS), приходится писать и тестировать руками — например, обработку токена без claim role пришлось чинить отдельным багфиксом"
    - id: jwt-vs-sessions
      section: "JWT против серверных сессий"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/JwtService.java
          symbol: "JwtService.issue"
          concept: "claims (subject, role, issuedAt, expiration) + signWith(key, Jwts.SIG.HS256) — подпись, а не шифрование"
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/AuthController.java
          symbol: "AuthController.authCookie"
          concept: "httpOnly(true) — токен недоступен для JavaScript в браузере, ограничение ущерба от XSS"
        - path: backend-api/src/test/java/com/batowka/guestbooking/auth/JwtServiceTest.java
          symbol: "tokenSignedWithDifferentSecretIsRejected"
          concept: "токен с чужой подписью parse отклоняет"
      quiz_seeds:
        - "Почему JWT не нужно шифровать, а нужно подписывать — в чём разница между секретностью и целостностью тут?"
        - "Какова цена stateless-подхода, если украденный токен нужно отозвать раньше срока?"
      decisions:
        - choice: "JWT хранится в httpOnly cookie"
          alternatives: "JWT в localStorage, читаемый напрямую из JS на фронте"
          why: "cookie с HttpOnly недоступна для JavaScript (document.cookie её не покажет) — даже успешная XSS-атака не может утащить токен; localStorage читается любым скриптом на странице"
          price: "нельзя просто прочитать токен на фронте — например, чтобы показать его payload в devtools при отладке; фронт вынужден доверять серверу и ходить в /api/me, а не декодировать JWT сам"
        - choice: "срок жизни токена 30 дней, без refresh-токенов"
          alternatives: "короткоживущий access-токен + отдельный refresh-токен с возможностью отзыва"
          why: "дизайн-документ (docs/specs/2026-08-19-stage-2-auth-design.md, §3): «YAGNI: revocation для дома друзей не нужна, компрометация лечится сменой секрета»; гостю неудобно логиниться заново каждую неделю ради календаря друзей"
          price: "нет способа отозвать один конкретный украденный токен — единственный рычаг это сменить app.jwt.secret, что разлогинивает вообще всех"
      pitfalls:
        - "Ранняя версия JwtService.parse честно вызывала Role.valueOf(claims.get(\"role\", String.class)) без проверки на null — валидно подписанный токен без claim role ронял NPE вместо спокойного Optional.empty(). Исправлено коммитом d06a634 («fix: токен без claim role отклоняется, а не роняет NPE») явной проверкой roleClaim == null перед Role.valueOf. Мораль: parse() токена — это разбор недоверенных входных данных, и на каждое поле claims нужно закладываться, что его может не оказаться, даже если сам токен подписан правильно."
    - id: filter-chain-request-path
      section: "Путь запроса через цепочку фильтров"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/SecurityConfig.java
          symbol: "SecurityConfig.writeError / authenticationEntryPoint / accessDeniedHandler"
          concept: "401 UNAUTHORIZED против 403 FORBIDDEN — разные хендлеры для «не вошёл» и «нет прав»"
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/JwtAuthFilter.java
          symbol: "JwtAuthFilter.COOKIE_NAME"
          concept: "битая cookie не превращается в ошибку — JwtService.parse ловит исключение и возвращает Optional.empty()"
        - path: backend-api/src/test/java/com/batowka/guestbooking/auth/SecurityFlowTest.java
          symbol: "protectedRouteWithoutTokenGives401InApiFormat / friendOnAdminRouteGives403InApiFormat / garbageTokenIsJustAnonymous"
          concept: "три сценария маршрута через фильтры целиком, от запроса до тела ответа"
      quiz_seeds:
        - "Почему CSRF-защита выключена (.csrf(...disable)) и это не забытая галочка?"
        - "Чем ответ на битую cookie отличается от ответа на полное отсутствие cookie — и почему они одинаковые?"
      decisions:
        - choice: "CSRF-защита выключена (.csrf(AbstractHttpConfigurer::disable))"
          alternatives: "включённый Spring CSRF-токен (стандартный synchronizer token pattern)"
          why: "дизайн-документ (docs/specs/2026-08-19-stage-2-auth-design.md, §3): «stateless + SameSite=Lax покрывают наш случай» — приложение не хранит server-side сессию, а cookie с SameSite=Lax браузер не отправляет при запросах, инициированных с чужого сайта"
          price: "решение завязано именно на SameSite=Lax и httpOnly; если в будущем понадобится сменить SameSite на None (например, кросс-доменный фронт), защиту придётся пересматривать заново, а не считать закрытой раз и навсегда"
    - id: bcrypt-salt-cost
      section: "BCrypt: соль, медленность и первый админ"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/AdminSeeder.java
          symbol: "AdminSeeder.seed"
          concept: "идемпотентный upsert админа при каждом старте приложения"
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/SecurityConfig.java
          symbol: "SecurityConfig.passwordEncoder"
          concept: "BCryptPasswordEncoder — соль внутри хеша, matches() вместо hash ==="
        - path: backend-api/src/test/java/com/batowka/guestbooking/auth/AdminSeederTest.java
          symbol: "seedingTwiceKeepsSingleAdmin"
          concept: "два подряд вызова seed() не плодят второго админа"
      quiz_seeds:
        - "Почему нельзя сравнивать введённый пароль с хешем просто как hash(введённый) == сохранённый_hash?"
        - "Зачем BCrypt специально медленный, в отличие от SHA-256?"
      decisions:
        - choice: "первый админ создаётся идемпотентным AdminSeeder (ApplicationRunner) из app.admin.phone/password при каждом старте приложения"
          alternatives: "создать админа отдельной SQL-миграцией (INSERT в V-файле Flyway)"
          why: "дизайн-документ (docs/specs/2026-08-19-stage-2-auth-design.md, §2): конфигурация (dev — application.yml, прод — .env) плюс апсерт при старте — не нужен отдельный SQL-скрипт с паролем/хешем внутри версионированной миграции, обновление пароля/роли админа не требует новой миграции"
          price: "AdminSeeder выполняется на каждом старте приложения (лишний upsert-запрос), а dev-пароль по умолчанию временно лежит открытым текстом в application.yml с комментарием — риск забыть заменить его перед проде (закрыт явным TODO на этап 8)"
      pitfalls:
        - "AuthController.adminLogin строит цепочку .filter(...).filter(u -> ... && encoder.matches(...)) — если телефон не найден или роль не ADMIN, encoder.matches() вообще не вызывается, цепочка обрывается раньше. BCrypt специально медленный (см. выше), поэтому путь «неизвестный телефон» и путь «неверный пароль» отличаются по времени ответа, хотя оба возвращают один и тот же 401 INVALID_CREDENTIALS. Это тайминг-канал (timing side-channel): по задержке ответа можно статистически отличить «такого админа нет» от «админ есть, пароль неверный», в обход самой идеи единого 401. Как думаешь, что нужно сделать, чтобы убрать эту разницу во времени ответа?"
    - id: rate-limiting-sliding-window
      section: "Rate limiting: скользящее окно и инжектируемое время"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/LoginRateLimiter.java
          symbol: "LoginRateLimiter.check"
          concept: "Deque<Instant> на IP + отбрасывание записей старше WINDOW от текущего now — скользящее, не фиксированное окно"
        - path: backend-api/src/test/java/com/batowka/guestbooking/auth/LoginRateLimiterTest.java
          symbol: "windowSlidesAfterAMinute"
          concept: "TestClock.advance(...) двигает время без реального Thread.sleep"
      quiz_seeds:
        - "В чём именно ломается наивный фиксированный счётчик на стыке минутных окон, а скользящее окно — нет?"
        - "Почему Clock — параметр конструктора, а не Clock.systemUTC() напрямую внутри LoginRateLimiter?"
      decisions:
        - choice: "собственная in-memory реализация скользящего окна (~20 строк, ConcurrentHashMap<String, Deque<Instant>> по IP)"
          alternatives: "готовая библиотека bucket4j"
          why: "дизайн-документ (docs/specs/2026-08-19-stage-2-auth-design.md, §4): «у bucket4j нестабильные Maven-координаты между версиями, а свой limiter проще и нагляднее для обучения»"
          price: "in-memory решение живёт только на один процесс backend-api — если сервис когда-нибудь запустится в нескольких экземплярах, у каждого будет свой независимый счётчик и общий лимит фактически умножится на число процессов; своя реализация к тому же не проверена годами продакшена, как готовая библиотека"
      pitfalls:
        - "@Valid отсекает некорректные тела запроса (пустой JSON, битый формат) ещё до входа в метод контроллера — то есть до вызова rateLimiter.check(...). Такие мусорные запросы лимитером вообще не считаются: можно спамить эндпоинт битыми телами без ограничения по IP. Осознанно припаркованный (PARKED) пункт ревью задачи 7: цена ошибки нулевая (мусором и так можно спамить любой эндпоинт — это не специфика логина), честный фикс — перенос проверки лимита в фильтр, до валидации тела — отложен на этап 8."
    - id: error-disclosure-safety
      section: "Безопасность ошибок: что можно раскрывать, а что нет"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/AuthController.java
          symbol: "AuthController.adminLogin / AuthController.login"
          concept: "единый 401 INVALID_CREDENTIALS у admin-логина против осознанного UNKNOWN_PHONE у гостевого"
        - path: backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java
          symbol: "GlobalExceptionHandler.unexpected"
          concept: "catch-all на Exception.class — полный stack trace в лог, клиенту только стабильный код 500 INTERNAL_ERROR"
        - path: backend-api/src/test/java/com/batowka/guestbooking/auth/AdminLoginTest.java
          symbol: "wrongPasswordGives401 / unknownPhoneGivesSame401"
          concept: "оба провала неотличимы снаружи по коду ответа"
      quiz_seeds:
        - "Почему admin-логин прячет разницу между «нет такого телефона» и «неверный пароль», а гостевой логин — нет?"
        - "Что могло бы утечь наружу, если бы GlobalExceptionHandler отдавал клиенту текст пойманного исключения?"
      decisions:
        - choice: "admin-логин: единый 401 INVALID_CREDENTIALS на любой провал; гостевой login: отдельный 401 UNKNOWN_PHONE"
          alternatives: "единая политика для обоих логинов — либо оба скрывают причину провала, либо оба её раскрывают"
          why: "дизайн-документ (docs/specs/2026-08-19-stage-2-auth-design.md, §5): у admin-логина различимый ответ дал бы атакующему инструмент сначала найти рабочий номер, а потом подбирать пароль (пароль — секрет); у гостевого логина пароля нет вообще, «этот номер есть в списке» — не инструмент компрометации, а просто членство, которое по спеке осознанно можно раскрывать"
          price: "два разных подхода к, казалось бы, одной и той же ситуации «логин не удался» усложняют модель для того, кто читает код впервые — приходится каждый раз вспоминать, почему тут иначе, а не просто скопировать паттерн с одного контроллера на другой"
  bugs_and_lessons:
    - "Инжектируемый Clock в LoginRateLimiter вместо Clock.systemUTC() внутри класса — не абстракция ради абстракции: именно это делает тест сдвига скользящего окна (windowSlidesAfterAMinute) быстрым и детерминированным, без реального ожидания 61 секунды в тесте."
    - "@Valid отсекает некорректные тела запроса ещё до вызова rateLimiter.check(...), поэтому мусорные запросы лимитером не считаются — осознанно припаркованный (PARKED) пробел из ревью задачи 7: цена ошибки нулевая (мусором и так можно спамить любой эндпоинт), честный фикс — перенос проверки лимита в фильтр — отложен на этап 8."
  prerequisites: [migrations-ddl-auto]
---

# Этап 2: Spring Security, JWT, BCrypt, rate limiting

Разбор того, что мы собрали в этапе 2 — пакет
`backend-api/src/main/java/com/batowka/guestbooking/auth/` целиком: `JwtService`,
`JwtAuthFilter`, `SecurityConfig`, `AuthController`, `MeController`,
`LoginRateLimiter`, `AdminSeeder`, `Phones`, плюс правки в
`common/GlobalExceptionHandler.java`. Ссылки — на реальные файлы, чтобы можно было
открыть рядом и сверить.

## 1. Аутентификация vs авторизация

Это два разных вопроса, и в нашем коде они буквально разнесены по разным классам.
Аутентификация — «кто ты» — целиком живёт в `JwtAuthFilter.java`: фильтр достаёт из
запроса cookie `auth`, отдаёт её значение в `JwtService.parse`, и если токен валиден,
кладёт в `SecurityContextHolder` объект `Authentication`, где principal — это `userId`,
а authority — строка вида `ROLE_FRIEND` или `ROLE_ADMIN`. Заметьте: `JwtAuthFilter`
никогда не решает, пускать запрос дальше или нет — он только устанавливает личность
(«если токен есть и валиден — вот кто пришёл»), а если токена нет или он битый, просто
ничего не кладёт в контекст и передаёт запрос дальше (`chain.doFilter`) как есть.

Авторизация — «что тебе можно» — это уже отдельная зона ответственности,
`SecurityConfig.filterChain`: `.requestMatchers("/api/admin/**").hasRole("ADMIN")`,
`.requestMatchers("/api/**").authenticated()`. Здесь Spring Security смотрит на то, что
`JwtAuthFilter` уже положил в контекст, и решает: пропустить, ответить 401 (не
аутентифицирован вообще) или 403 (аутентифицирован, но не та роль). Разделение не
формальность ради красоты — оно означает, что если завтра появится второй способ
аутентификации (скажем, вход по коду из Telegram-бота на этапе 3+), достаточно
добавить ещё один фильтр, который так же кладёт `Authentication` в контекст, а вся
логика «что кому можно» в `SecurityConfig` не поменяется ни на строчку.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/auth/JwtAuthFilter.java` —
> смотри `doFilterInternal`: обрати внимание, что в конце всегда вызывается
> `chain.doFilter`, независимо от того, нашёлся токен или нет. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/auth/SecurityConfig.java` —
> смотри `filterChain` и цепочку `.requestMatchers(...)`.

## 2. JWT против серверных сессий

JWT (JSON Web Token) — это не зашифрованная строка, а *подписанная*. Токен состоит из
трёх частей через точку: header, payload и подпись. В `JwtService.issue` видно, что мы
кладём в payload (в терминах библиотеки — claims) `subject` (id пользователя),
`claim("role", ...)`, `issuedAt` и `expiration`, а `signWith(key, Jwts.SIG.HS256)`
считает HMAC-SHA256 от header+payload с использованием секретного ключа
(`app.jwt.secret` из `application.yml`) и добавляет результат третьей частью. Подделать
токен невозможно, потому что для этого нужно пересчитать правильную подпись, а это
требует секрет, которого у злоумышленника нет; но прочитать токен может кто угодно —
header и payload не зашифрованы, а всего лишь закодированы в Base64, то есть открыв
консоль браузера, любой увидит свой userId и роль открытым текстом. Это осознанный
компромисс: секретность не нужна (userId — не секрет), а целостность (нельзя
подменить чужим id или ролью ADMIN) нужна, и её обеспечивает подпись —
`JwtServiceTest.tokenSignedWithDifferentSecretIsRejected` проверяет это буквально:
токен, подписанный другим секретом, `parse` отклоняет.

Мы выбрали JWT вместо серверных сессий (когда сервер хранит в памяти или Redis
таблицу «id сессии → кто это», а клиенту отдаёт только непрозрачный id) ради
stateless-подхода — приложению не нужно ничего хранить о факте логина, весь необходимый
контекст приезжает в самом токене при каждом запросе, что видно и в
`SecurityConfig.filterChain`:
`.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))`.
Для проекта такого масштаба (один процесс backend-api, никакого горизонтального
масштабирования) это не даёт огромного выигрыша, зато сильно упрощает код: не нужен
отдельный session store, не нужно чистить протухшие записи. Плата за это —
невозможность отозвать конкретный токен раньше срока: если он украден, единственный
способ обесточить именно его — сменить `app.jwt.secret`, что разлогинит вообще всех.
Дизайн-документ (`docs/specs/2026-08-19-stage-2-auth-design.md`, §3) прямо называет это
осознанным YAGNI: «refresh-токенов нет (YAGNI: revocation для дома друзей не нужна,
компрометация лечится сменой секрета)». Срок жизни — 30 дней
(`app.jwt.ttl-days` в `application.yml`, `AuthController.COOKIE_TTL`) — это долго
специально: гостю, который зашёл посмотреть календарь и забронировать дом друзьям,
неприятно логиниться заново каждую неделю.

Токен живёт в httpOnly cookie (`AuthController.authCookie` — `.httpOnly(true)`), а не в
localStorage, и разница здесь не стилистическая, а про конкретную атаку — XSS
(Cross-Site Scripting): если на страницу каким-то образом попадёт чужой JavaScript
(через уязвимую библиотеку, необработанный пользовательский ввод и т. п.), он может
прочитать что угодно из `localStorage` и отправить украденный токен куда угодно.
Cookie с флагом `HttpOnly` для JavaScript в браузере попросту невидима — `document.cookie`
её не покажет, а значит даже успешный XSS не может утащить токен наружу. Это не
защита от самой XSS-уязвимости (её всё равно надо чинить), а ограничение ущерба, если
она вдруг где-то возникнет.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/auth/JwtService.java` —
> смотри `issue`: какие claims кладутся и чем подписывается токен. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/auth/AuthController.java`
> — смотри `authCookie` и флаг `.httpOnly(true)`. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/auth/JwtServiceTest.java`
> — смотри `tokenSignedWithDifferentSecretIsRejected`.

## 3. Путь запроса через цепочку фильтров

Разберём, что происходит с запросом `GET /api/me` без cookie. Spring Security строит
цепочку фильтров вокруг каждого запроса; наш `jwtAuthFilter` добавлен явно
(`.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)`) перед
штатным фильтром логина по паролю форм — нам он не нужен, но именно на его место
принято вставлять кастомную аутентификацию по токену. Раз cookie нет,
`JwtAuthFilter.doFilterInternal` не находит cookie с именем `auth`
(`JwtAuthFilter.COOKIE_NAME`) и просто пропускает запрос дальше без аутентификации.
Дальше `authorizeHttpRequests` смотрит на путь `/api/me` — под правило
`/api/calendar`, `/api/auth/**` он не подпадает, значит действует
`.requestMatchers("/api/**").authenticated()`: раз в контексте нет `Authentication`,
Spring Security вызывает `authenticationEntryPoint`, а мы его переопределили в
`SecurityConfig.writeError` на `401` с телом `{"code":"UNAUTHORIZED","message":"Требуется вход"}` —
именно этот формат проверяет `MeControllerTest.anonymousGets401` и
`SecurityFlowTest.protectedRouteWithoutTokenGives401InApiFormat`. Если бы токен был, но
принадлежал гостю без роли ADMIN, а маршрут требовал `/api/admin/**`, сработал бы
`accessDeniedHandler` — `403 {"code":"FORBIDDEN"}`, что проверяет
`SecurityFlowTest.friendOnAdminRouteGives403InApiFormat`. Обратите внимание на тонкость
в `garbageTokenIsJustAnonymous`: битая cookie не превращается в ошибку сама по себе —
`JwtService.parse` ловит `JwtException`/`IllegalArgumentException` и возвращает
`Optional.empty()`, так что запрос просто остаётся неаутентифицированным и получает
обычный `401`, как будто cookie вообще не было.

CSRF-защита выключена (`.csrf(AbstractHttpConfigurer::disable)`), и это тоже осознанное
решение, а не забытая галочка. CSRF-атака эксплуатирует то, что браузер сам
подкладывает cookie к запросу с любого чужого сайта — если бы аутентификация была
завязана на server-side сессию, чужая страница могла бы незаметно от лица
залогиненного пользователя отправить, скажем, `POST` с его cookie. У нас работает
двойная защита от этого сценария: во-первых, cookie помечена `sameSite("Lax")`
(`AuthController.authCookie`) — браузер не отправляет такую cookie при запросах,
инициированных с чужого сайта (кроме навигации верхнего уровня, что для наших POST-
эндпоинтов не актуально); во-вторых, само приложение stateless — оно не хранит
server-side состояние, которое можно было бы незаметно поменять «от чужого имени»
одним запросом. Именно эту комбинацию — stateless + `SameSite=Lax` — и называет
дизайн-документ (§3) как причину не тащить в проект отдельный CSRF-токен.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/auth/SecurityConfig.java`
> — смотри `writeError`, `authenticationEntryPoint` и `accessDeniedHandler` рядом:
> сравни коды 401 и 403. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/auth/JwtAuthFilter.java` —
> смотри `COOKIE_NAME` и как достаётся cookie. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/auth/SecurityFlowTest.java`
> — смотри три теста подряд: `protectedRouteWithoutTokenGives401InApiFormat`,
> `friendOnAdminRouteGives403InApiFormat`, `garbageTokenIsJustAnonymous`.

## 4. BCrypt: соль, медленность и первый админ

`SecurityConfig.passwordEncoder` возвращает `BCryptPasswordEncoder` — им
`AdminSeeder.seed` хеширует пароль (`encoder.encode(password)`), а
`AuthController.adminLogin` сверяет введённый пароль с хешем через
`encoder.matches(body.password(), u.getPasswordHash())`. Хеш одного и того же пароля
каждый раз выходит разным, потому что BCrypt перед хешированием подмешивает случайную
соль (несколько байт случайных данных) и сохраняет её прямо внутри итоговой строки
хеша — поэтому сравнение делается не банальным `hash(введённый) == сохранённый_hash`
(это сломалось бы из-за разной соли), а специальным методом `matches`, который
достаёт соль из уже сохранённого хеша, применяет её к введённому паролю и сравнивает
результаты. Смысл соли — не дать атакующему с украденной базой хешей просто свериться
с готовой радужной таблицей (rainbow table) популярных паролей: одинаковый пароль у
двух разных пользователей даёт два непохожих хеша, и предвычисленные таблицы
перестают работать.

BCrypt также специально медленный — в отличие, скажем, от SHA-256, который
проектировался быть быстрым (это хорошо для контрольных сумм файлов, но плохо для
паролей). Внутри BCrypt заложен настраиваемый параметр числа раундов (у
`BCryptPasswordEncoder` по умолчанию — 10, что даёт заметные миллисекунды на один
вызов `matches`), и это намеренно: если у атакующего в руках база хешей, перебор всех
комбинаций пароля становится не «миллиард попыток в секунду» (как для быстрого хеша на
GPU), а на порядки медленнее — атака стоимостно перестаёт окупаться.

Первый админ появляется не руками через SQL, а через `AdminSeeder` —
`ApplicationRunner`, который выполняется автоматически при каждом старте приложения.
Метод `seed()` идемпотентен: ищет пользователя по `app.admin.phone` из конфигурации, и
если не находит — создаёт нового, если находит — обновляет ему хеш пароля и роль на
`ADMIN` (комментарий в коде явно проговаривает эту логику: «Идемпотентный upsert
админа»). `AdminSeederTest.seedingTwiceKeepsSingleAdmin` проверяет именно это — два
подряд вызова `seed()` не плодят второго админа. Значения `app.admin.phone` и
`app.admin.password` в `application.yml` сейчас — dev-заглушки с явным комментарием
(«прод — `.env`, этап 8»); секреты для реального деплоя в git не попадают, они придут
через переменные окружения на этапе 8, когда появится VPS.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/auth/AdminSeeder.java` —
> смотри `seed()` и комментарий «Идемпотентный upsert админа». Открой
> `backend-api/src/main/java/com/batowka/guestbooking/auth/SecurityConfig.java`
> — смотри `passwordEncoder`. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/auth/AdminSeederTest.java`
> — смотри `seedingTwiceKeepsSingleAdmin`.

## 5. Rate limiting: скользящее окно и инжектируемое время

`LoginRateLimiter` не пускает больше `LIMIT = 5` попыток логина за `WINDOW =
Duration.ofMinutes(1)` с одного IP — общий лимитер для обоих эндпоинтов логина
(`AuthController.login` и `adminLogin` оба первой строкой вызывают
`rateLimiter.check(request.getRemoteAddr())`). Реализация — честное скользящее окно, а
не наивный фиксированный счётчик. Наивный вариант («сбрасывать счётчик каждую минуту
по границе часов») страдает эффектом на стыке окон: если лимит 5/мин, а атакующий
сделает 5 попыток в 12:00:59 и ещё 5 в 12:01:00, наивный счётчик пропустит все десять
за неполные две секунды, потому что формально это уже «новое окно». Скользящее окно
устроено иначе: для каждого IP хранится `Deque<Instant>` — очередь моментов недавних
попыток, и при каждой проверке (`check`) сначала выбрасываются из начала очереди все
записи старше `WINDOW` относительно *текущего* момента (`window.peekFirst().isBefore(now.minus(WINDOW))`),
и только потом смотрится, не превышен ли лимит оставшимися. Окно едет вместе с
текущим временем запроса, а не привязано к календарным границам, поэтому эффект
стыка невозможен.

`Clock` — не `java.time.Clock.systemUTC()` напрямую внутри `LoginRateLimiter`, а
параметр конструктора, инжектируемый бином из `SecurityConfig.clock()`. Это
классический приём для тестируемости времени: `LoginRateLimiterTest` подсовывает
собственный `TestClock` с методом `advance(Duration)`, который сдвигает время вручную,
без реального `Thread.sleep`. Так тест `windowSlidesAfterAMinute` проверяет, что после
61 секунды (`clock.advance(Duration.ofSeconds(61))`) окно действительно съезжает и
шестая попытка снова разрешена — и делает это мгновенно, а не ждёт настоящую минуту.
Если бы `Clock.systemUTC()` был зашит внутри класса напрямую, единственный способ
протестировать сдвиг окна — реально ждать секундами в тесте, что медленно и
непредсказуемо по времени выполнения на разных машинах.

У решения есть честно признанные границы. Это in-memory `ConcurrentHashMap` на один
процесс — комментарий в коде так и говорит: «In-memory, на один процесс»; если
backend-api когда-нибудь запустится в нескольких экземплярах, у каждого будет свой
независимый счётчик, и общий лимит фактически умножится на число процессов. Кроме
того, лимитер считает IP через `request.getRemoteAddr()` — это надёжно только пока
клиент подключается к приложению напрямую; как только на этапе 8 перед backend-api
встанет nginx (по таблице этапов в `docs/specs/2026-08-13-japan-guest-booking-design.md`,
§12, строка этапа 8: «Linux, nginx, Let's Encrypt»), `getRemoteAddr()` без
дополнительной настройки будет видеть IP самого nginx, а не реального клиента, и
потребуется читать `X-Forwarded-For`. Ещё один отложенный пункт зафиксирован в ревью
задачи 7 (`.superpowers/sdd/2026-08-19-stage-2-auth/final-review-context.md`):
`@Valid` отсекает некорректные тела запроса (пустой JSON, битый формат) ещё до входа в
метод контроллера, то есть до вызова `rateLimiter.check(...)` — значит такие запросы
лимитером вообще не считаются. Это осознанный «припаркованный» (PARKED) пункт: цена
ошибки здесь нулевая (мусорными телами и так можно спамить любой эндпоинт, это не
специфика логина), а честный фикс — перенос проверки лимита в фильтр, до валидации
тела — отложен на этап 8 (прод-hardening).

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/auth/LoginRateLimiter.java`
> — смотри `check`: обрати внимание на цикл, который выбрасывает устаревшие
> записи из `Deque<Instant>` перед проверкой лимита. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/auth/LoginRateLimiterTest.java`
> — смотри `windowSlidesAfterAMinute` и `TestClock.advance`.

## 6. Безопасность ошибок: что можно раскрывать, а что нет

В коде видны два разных подхода к одной на первый взгляд похожей ситуации — «логин не
удался», и это не непоследовательность, а результат разного анализа рисков для двух
разных сценариев. У `adminLogin` любой провал — неизвестный телефон, телефон
существует, но не ADMIN, или неверный пароль — даёт один и тот же ответ:
`401 INVALID_CREDENTIALS` (`AuthController.adminLogin`, комментарий в коде: «Единый 401
на любой провал: не раскрываем, что именно не совпало»). Смысл — не дать атакующему
инструмент для перебора существующих админских телефонов: если бы «неизвестный номер»
и «неверный пароль» отличались по коду ответа, злоумышленник мог бы сначала
перебором найти рабочий номер (по факту получения другого ответа), а уже потом
подбирать пароль именно к нему. `AdminLoginTest.wrongPasswordGives401` и
`unknownPhoneGivesSame401` вместе проверяют, что оба случая неотличимы снаружи.

У гостевого `login`, наоборот, `UNKNOWN_PHONE` возвращается осознанно и прямо —
`GuestLoginTest.unknownPhoneGets401` ждёт именно код `UNKNOWN_PHONE`, а не общий
`INVALID_CREDENTIALS`. Разница в модели угроз оправдана дизайн-документом
(`docs/specs/2026-08-19-stage-2-auth-design.md`, §5): «номер не в списке — членство
раскрываем осознанно, по спеке». Гостевой логин — не пароль, а просто «этот телефон
есть в белом списке друзей дома» — раскрытие факта «вас тут нет» не даёт атакующему
ничего похожего на инструмент компрометации чужого аккаунта (в отличие от подбора
пароля), а гостю, который просто ошибся в написании номера, честная ошибка экономит
время. Отдельный нюанс того же метода: если ввести телефон реального админа в
беспарольный гостевой `login`, ответ будет тем же `UNKNOWN_PHONE`
(`GuestLoginTest.adminPhoneCannotUsePasswordlessLogin`), потому что фильтр
`.filter(u -> u.getRole() == Role.FRIEND)` в `AuthController.login` отсекает роль
ADMIN — беспарольный обход не должен уметь выдать админский токен, даже зная номер
админа.

Наконец, `GlobalExceptionHandler.unexpected` — catch-all
`@ExceptionHandler(Exception.class)`, который ловит вообще всё, что не было
обработано более специфичным хендлером выше по файлу. Он логирует полный stack trace
на сервере (`log.error("Необработанное исключение", ex)`), но клиенту отдаёт только
`500 {"code":"INTERNAL_ERROR","message":"Внутренняя ошибка сервера"}` — без текста
самого исключения. Это тоже осознанная граница: сообщение реального исключения могло
бы случайно содержать что-то чувствительное (имя внутренней таблицы, кусок SQL, путь
на файловой системе), и без catch-all такая информация утекла бы наружу при любой
непредвиденной ошибке; с ним — наружу уходит только стабильный код и общая фраза, а
детали остаются в логах, доступных только тем, кто может зайти на сервер.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/auth/AuthController.java`
> — смотри `adminLogin` и `login` рядом, сравни их обработку провала. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java`
> — смотри `unexpected` и `@ExceptionHandler(Exception.class)`. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/auth/AdminLoginTest.java` —
> смотри `wrongPasswordGives401` и `unknownPhoneGivesSame401` рядом.
