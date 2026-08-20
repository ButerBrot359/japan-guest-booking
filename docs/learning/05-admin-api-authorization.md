---
tutor:
  stage: 5
  title: "Админ-API: роли, advisory locks, soft delete, API без каскадов"
  topics:
    - id: roles-method-security
      section: "Роли и авторизация: две линии обороны"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/SecurityConfig.java
          symbol: "filterChain — requestMatchers(\"/api/admin/**\").hasRole(\"ADMIN\") + accessDeniedHandler"
          concept: "первая линия — URL-правило на весь префикс /api/admin/**; свой accessDeniedHandler даёт JSON-403 для запросов, которые фильтр отклонил ДО контроллера"
        - path: backend-api/src/main/java/com/batowka/guestbooking/admin/AdminBlockedPeriodController.java
          symbol: "@PreAuthorize(\"hasRole('ADMIN')\") на классе контроллера"
          concept: "вторая линия — method security на самом контроллере, работает даже если URL-правило кто-то однажды ослабит или добавит новый админский путь мимо /api/admin/**"
        - path: backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java
          symbol: "accessDenied(AccessDeniedException) → 403 FORBIDDEN"
          concept: "@PreAuthorize бросает AccessDeniedException уже ВНУТРИ MVC — её ловит не accessDeniedHandler из SecurityConfig, а этот @ExceptionHandler; без него catch-all отдал бы 500"
        - path: backend-api/src/test/java/com/batowka/guestbooking/admin/AdminBlockedPeriodTest.java
          symbol: "friendGets403"
          concept: "друг (роль FRIEND) с валидным токеном получает 403 на админском эндпоинте — авторизация по роли проверена реально, а не только конфигом"
      quiz_seeds:
        - "Зачем защищать админский контроллер и URL-правилом в SecurityConfig, и @PreAuthorize на классе — разве одной линии мало?"
        - "Друг с валидным токеном дёргает POST /api/admin/blocked-periods. В какой момент его отклонят и кто вернёт ему 403 — фильтр безопасности или контроллер?"
        - "Почему AccessDeniedException от @PreAuthorize нельзя оставить без @ExceptionHandler, хотя в SecurityConfig уже есть accessDeniedHandler?"
      decisions:
        - choice: "две независимые линии авторизации: URL-правило `requestMatchers(\"/api/admin/**\").hasRole(\"ADMIN\")` в SecurityConfig И `@PreAuthorize(\"hasRole('ADMIN')\")` на каждом админском контроллере"
          alternatives: "полагаться только на URL-правило (одна строка, покрывает весь префикс сразу) — или, наоборот, только на @PreAuthorize на методах, без общего URL-правила"
          why: "URL-правило и method security ломаются от разных ошибок: URL-правило перестаёт защищать, если кто-то добавит админский эндпоинт вне префикса `/api/admin/**` или отредактирует цепочку матчеров; @PreAuthorize перестаёт защищать, если контроллер забудут им пометить. Две линии независимы — чтобы дыра открылась, ошибиться нужно в обоих местах сразу; это тот же принцип defence in depth, что и `CHECK`-констрейнт рядом с проверкой в Java из этапа 4"
          price: "дублирование правила `hasRole('ADMIN')` в двух местах: при смене модели ролей (например, появлении роли OWNER) придётся править и SecurityConfig, и аннотации на контроллерах — и легко забыть одно из мест, получив рассинхрон, который тесты поймают, только если на него есть отдельный кейс"
      pitfalls:
        - "AccessDeniedException возникает в ДВУХ разных точках, и обрабатывают её ДВА разных механизма — их легко перепутать. Когда запрос отклоняет URL-правило (`requestMatchers(...).hasRole(...)`), это происходит в фильтре ДО DispatcherServlet, и JSON-ответ пишет `accessDeniedHandler` из SecurityConfig (`GuestBooking/SecurityConfig.java` строка 43). Когда же запрос прошёл фильтр (например, эндпоинт вне `/api/admin/**`, но с `@PreAuthorize`), проверка роли срабатывает уже ВНУТРИ MVC, на входе в метод контроллера — та же по типу `AccessDeniedException` летит по стеку MVC и до `accessDeniedHandler` вообще не доходит, её ловит `@ExceptionHandler(AccessDeniedException.class)` в `GlobalExceptionHandler` (строка 174). Если бы этого хендлера не было, `AccessDeniedException` попала бы в catch-all `@ExceptionHandler(Exception.class)` и превратилась бы в 500 INTERNAL_ERROR — то есть «недостаточно прав» выглядел бы как поломка сервера. Комментарий в коде (`// бросается @PreAuthorize внутри MVC — без этого хендлера catch-all дал бы 500`) фиксирует ровно это. В этапе 5 все админские контроллеры сидят под `/api/admin/**`, поэтому оба пути дают одинаковый 403 — но хендлер в GlobalExceptionHandler нужен именно на случай, когда method security сработает там, где URL-правило уже не сторожит."
    - id: advisory-locks
      section: "Advisory locks: сериализация того, что констрейнт не покрывает"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/common/DatesLock.java
          symbol: "acquire — проверка isActualTransactionActive() + pg_advisory_xact_lock(KEY)"
          concept: "один общий xact-замок на все операции «проверь пересечение дат и запиши»; проверка активной транзакции — не паранойя, а защита от молчаливой дыры"
        - path: backend-api/src/main/java/com/batowka/guestbooking/calendar/BlockedPeriodService.java
          symbol: "create — datesLock.acquire() перед findOverlapping(...) и save"
          concept: "замок берётся ПЕРЕД проверкой конфликтов: между «проверил, что свободно» и «вставил» не должно влезть чужое изменение"
        - path: backend-api/src/test/java/com/batowka/guestbooking/booking/DatesRaceTest.java
          symbol: "bookingAndBlockNeverCoexistOnSameDates"
          concept: "гонка «гость бронирует vs админ блокирует» на одни даты в двух потоках — инвариант «оба выиграли» не выполняется никогда"
      quiz_seeds:
        - "Почему нельзя защитить пересечение брони и блокировки тем же exclusion constraint, что защищает брони между собой?"
        - "Что случилось бы, если бы pg_advisory_xact_lock вызвали вне транзакции — и почему acquire() падает с исключением в этом случае?"
        - "Чем advisory lock здесь лучше, чем перевести обе транзакции в isolation level SERIALIZABLE?"
      decisions:
        - choice: "один именованный advisory-замок (`pg_advisory_xact_lock(4242)`) на все операции, которые проверяют пересечение дат между `bookings` и `blocked_periods` и затем пишут в одну из таблиц"
          alternatives: "EXCLUDE-констрейнт (как между бронями) — не умеет работать МЕЖДУ двумя таблицами; либо `SERIALIZABLE`-изоляция на этих транзакциях; либо явные `SELECT ... FOR UPDATE` на конфликтующих строках"
          why: "пересечение нужно проверять между двумя РАЗНЫМИ таблицами, а exclusion constraint живёт внутри одной таблицы и физически не видит вторую. Advisory lock — это добровольная сериализация именно того участка кода, который читает обе таблицы и пишет: `pg_advisory_xact_lock` держится до конца транзакции взявшего и выстраивает конкурентов в очередь, так что второй читает уже ПОСЛЕ того, как первый закоммитил свою вставку. Для проекта с одним гостевым местом и парой операций в день это буквально бесплатно — блокировка никогда реально не будет оспорена в проде, она стоит там как гарантия корректности, а не как узкое место"
          price: "проверка пересечения ушла из декларативного констрейнта БД (который работал бы всегда, для любого пути записи) в императивный код: любой НОВЫЙ путь, который вставляет бронь или блокировку, ОБЯЗАН сам вызвать `datesLock.acquire()` перед проверкой — БД больше не подстрахует, если про замок забудут. Именно поэтому `acquire()` хотя бы падает, если его позвали вне транзакции, — но «позвать не там» он поймать не может"
      pitfalls:
        - "`pg_advisory_xact_lock` (вариант `_xact_`, а не сессионный `pg_advisory_lock`) отпускается автоматически в конце транзакции — это и есть причина, по которой `DatesLock.acquire()` сначала проверяет `TransactionSynchronizationManager.isActualTransactionActive()` и бросает `IllegalStateException`, если транзакции нет. Если бы кто-то вызвал `acquire()` вне `@Transactional`, Spring выполнил бы `select pg_advisory_xact_lock(...)` в авто-коммит-режиме: замок взялся бы и тут же, по завершении этого единственного стейтмента, отпустился — до `findOverlapping` и `save` он бы уже не дожил. Гонка при этом НЕ дала бы ошибки ни в тестах, ни в проде — просто замок молча не защищал бы ничего, «молчаливая дыра», как и написано в комментарии к проверке. Явная проверка активной транзакции превращает эту незаметную дыру в громкое исключение на старте."
    - id: soft-delete
      section: "Soft delete и его хвосты"
      code_anchors:
        - path: backend-api/src/main/resources/db/migration/V3__users_deleted_at.sql
          symbol: "ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ"
          concept: "удаление из белого списка — это установка отметки времени, а не DELETE; NULL = активен; телефон остаётся уникальным, поэтому запись переживает удаление и может реактивироваться"
        - path: backend-api/src/main/java/com/batowka/guestbooking/user/WhitelistService.java
          symbol: "softDelete — проверки (admin/активная бронь), выставление deletedAt И обнуление telegramChatId"
          concept: "мягкое удаление отзывает не только доступ, но и Telegram-связку — иначе удалённый продолжал бы получать уведомления"
        - path: backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java
          symbol: "userGone(UserGoneException) → 401 UNAUTHORIZED + Set-Cookie с пустым значением и Duration.ZERO"
          concept: "у удалённого гостя токен ещё валиден по подписи и сроку — сервер отвечает 401 и той же ответной cookie затирает его на клиенте"
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/AuthController.java
          symbol: "login / adminLogin — findByPhoneAndDeletedAtIsNull"
          concept: "первое из трёх мест фильтрации живых: удалённый номер не проходит ни беспарольный логин друга, ни админ-логин"
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/MeController.java
          symbol: "me — findById(...).filter(u -> u.getDeletedAt() == null).orElseThrow(UserGoneException::new)"
          concept: "второе место: /api/me для валидного токена удалённого гостя бросает UserGoneException → 401 + затирающая cookie"
        - path: backend-api/src/main/java/com/batowka/guestbooking/messaging/ContactSharedConsumer.java
          symbol: "onContactShared — users.findByPhoneAndDeletedAtIsNull(phone).ifPresent(link)"
          concept: "третье место: онбординг контакта в боте не привяжет chat_id к удалённому номеру — иначе soft delete тихо вернул бы Telegram-связку назад"
        - path: backend-api/src/test/java/com/batowka/guestbooking/admin/AdminUserTest.java
          symbol: "deleteRevokesTelegramLink / reAddingDeletedReactivatesWithHistory"
          concept: "два края решения: удаление обнуляет telegram_chat_id, а повторное добавление того же номера воскрешает ту же запись вместе с историей броней"
      quiz_seeds:
        - "Почему гостя удаляют через deleted_at, а не DELETE FROM users — что сломал бы физический DELETE?"
        - "Токен удалённого гостя ещё не истёк и подпись верна. Как сервер не пускает его в /api/me и что делает с его cookie?"
        - "Что было бы, если бы softDelete не обнулял telegram_chat_id — как удалённый мог бы получить уведомление?"
      decisions:
        - choice: "удаление из белого списка — это soft delete (`deleted_at`), причём атомарно с ним обнуляется `telegram_chat_id`; телефон остаётся уникальным, поэтому повторное добавление того же номера реактивирует ту же строку (`addNormalized` снимает `deletedAt`)"
          alternatives: "физический `DELETE FROM users` — или soft delete, но БЕЗ обнуления telegram-связки и БЕЗ реактивации (каждый раз новая строка)"
          why: "у пользователя есть внешние ключи из `bookings` — физический DELETE либо упал бы на FK, либо (с каскадом) стёр бы историю броней, которую проект обязан хранить. Отметка `deleted_at` гасит доступ, сохраняя строку и всю привязанную историю; а раз телефон уникален, при возврате того же человека история возвращается сама — не нужно ни искать старые брони, ни склеивать две записи одного гостя"
          price: "живого пользователя теперь нельзя выбрать простым `findByPhone` — КАЖДОЕ место, где важна «живость» (логин, `/api/me`, онбординг контакта, подсчёт активных броней), обязано явно фильтровать `deleted_at IS NULL` или ходить через `findByPhoneAndDeletedAtIsNull`; забыть фильтр в новом месте — значит впустить удалённого. Уникальность телефона по всей таблице (а не только среди живых) — цена реактивации: нельзя завести НОВОГО гостя на номер, который когда-то принадлежал удалённому, не реактивировав старую запись"
      pitfalls:
        - "Хвост soft delete, который легко забыть: `softDelete` обязан обнулить `telegram_chat_id` (`WhitelistService` строка 74), а не только выставить `deleted_at`. Логин и `/api/me` фильтруют `deleted_at IS NULL` и удалённого внутрь не пустят — но уведомления идут НЕ через логин, а через outbox прямо на сохранённый `telegram_chat_id`. Если бы связка осталась, админ, отменяющий старую бронь только что удалённого гостя, отправил бы ему BOOKING_CANCELLED в Telegram — человек, которого выгнали из белого списка, продолжал бы получать сообщения о доме. Симметричный хвост на входе — онбординг: `ContactSharedConsumer` привязывает chat_id только через `findByPhoneAndDeletedAtIsNull`, иначе удалённый, повторно поделившийся контактом с ботом, тихо восстановил бы себе связку в обход админа. Soft delete — это не одна отметка, а согласованное закрытие ВСЕХ каналов: и входа по токену, и уведомлений, и повторной самопривязки."
    - id: admin-api-no-cascades
      section: "Проектирование админ-API без каскадов: 409 вместо магии, идемпотентность, атомарный резолв"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/calendar/OverlapsBookingException.java
          symbol: "OverlapsBookingException(List<Conflict>) — «каскадов нет, админ разруливает сам»"
          concept: "блокировка поверх активных броней не отменяет их автоматически, а возвращает 409 со СПИСКОМ конфликтующих броней (id, имя, даты)"
        - path: backend-api/src/main/java/com/batowka/guestbooking/accessrequest/AccessRequestService.java
          symbol: "submit — exists-then-insert + saveAndFlush в try/catch(DataIntegrityViolationException)"
          concept: "идемпотентная заявка: живой pending → тихо выходим; проигравший гонку за уникальный индекс получает тот же успех, что и обычный повтор"
        - path: backend-api/src/main/resources/db/migration/V4__access_requests_pending_unique.sql
          symbol: "CREATE UNIQUE INDEX ... ON access_requests (phone) WHERE status = 'PENDING'"
          concept: "инвариант «одна живая заявка на номер» на уровне БД — то, что проверка кодом в submit не может гарантировать против гонки"
        - path: backend-api/src/main/java/com/batowka/guestbooking/accessrequest/AccessRequestService.java
          symbol: "resolve — условный UPDATE ... WHERE status = 'PENDING', updated == 0 → AlreadyResolvedException"
          concept: "конечный автомат заявки PENDING → APPROVED|REJECTED сменой статуса ровно тем же атомарным UPDATE-приёмом, что и статусы брони в этапе 4"
        - path: backend-api/src/test/java/com/batowka/guestbooking/accessrequest/ResolveAccessRequestTest.java
          symbol: "doubleResolveGives409 / approveReactivatesDeletedUser"
          concept: "два одновременных approve: один выигрывает, второй получает честный 409; одобрение ранее удалённого номера реактивирует его, а не создаёт дубль"
      quiz_seeds:
        - "Почему блокировка дат поверх активной брони не отменяет её сама, а возвращает 409 со списком — чем плоха «умная» автоотмена?"
        - "Зачем в submit И проверка exists-then-insert, И частичный уникальный индекс V4 — разве проверки в коде мало?"
        - "Два админа почти одновременно жмут approve на одной заявке. Что увидит второй и за счёт чего именно?"
      decisions:
        - choice: "админ-API НЕ делает каскадов: создание блокировки поверх активных броней падает с 409 `OVERLAPS_BOOKING` и отдаёт СПИСОК конфликтов (`OverlapsBookingException.Conflict`: id, имя гостя, даты), а не отменяет брони само"
          alternatives: "«умная» автоотмена — при блокировке молча отменить пересекающиеся брони гостей и уведомить их; выглядит удобнее для админа в один клик"
          why: "автоотмена — необратимое действие над чужими бронями, спрятанное внутри операции «заблокировать даты»; админ, ставящий блокировку на ремонт, мог не осознавать, что этим выгоняет уже подтверждённых гостей. Честный 409 со списком делает конфликт видимым: админ сам решает — перенести гостя, отменить с уведомлением или выбрать другие даты — и делает это отдельным осознанным вызовом. Каскад экономит один клик ценой того, что разрушительное действие становится невидимым побочным эффектом"
          price: "админу нужно больше действий: увидеть 409, разобрать список конфликтов, вручную разрулить каждую бронь и повторить блокировку — API не берёт эту работу на себя, а возвращает её человеку вместе с ответственностью за необратимое"
        - choice: "заявка на доступ идемпотентна на трёх уровнях сразу: проверка `existsByPhoneAndStatus(PENDING)` в коде, частичный уникальный индекс `uq_access_requests_pending_phone` (V4) как ловушка гонки, и `catch(DataIntegrityViolationException) → return` — проигравший гонку получает тот же тихий успех, что и обычный повтор"
          alternatives: "только проверка `exists`-then-insert в коде (без индекса) — или, наоборот, только индекс без предварительной проверки (всегда ловить исключение)"
          why: "`exists`-then-insert в одиночку уязвим ровно так же, как «прочитать статус → записать» из этапа 4: два одновременных POST на один номер оба увидят «pending нет» и оба вставят заявку. Частичный уникальный индекс на `phone WHERE status='PENDING'` — второй инструмент против той же гонки, но иного рода, чем advisory lock из темы 2: замок СЕРИАЛИЗУЕТ (выстраивает в очередь), а уникальный индекс ПОЗВОЛЯЕТ обоим бежать параллельно и отбрасывает проигравшего на коммите. Здесь выбран индекс, потому что нужный ответ проигравшему — не ошибка, а тот же идемпотентный успех: `catch → return`, и внешне второй POST неотличим от повтора"
          price: "инвариант размазан по трём местам (код + миграция + catch), и чтобы понять, почему второй POST не плодит заявку, нужно держать в голове все три; `saveAndFlush` вместо `save` обязателен именно чтобы нарушение индекса всплыло как `DataIntegrityViolationException` ЗДЕСЬ, в try/catch, а не отложенно на коммите транзакции, где его уже некому поймать этим catch"
      pitfalls:
        - "`resolve()` в первой версии был неатомарен: `findById` → проверка `status == PENDING` в Java → `save`. Между чтением и записью влезал второй одновременный approve — оба читали PENDING, оба «одобряли», заявка резолвилась дважды, а `approve` дважды добавлял человека в белый список. Лечение — тот же приём, что и для статусов брони в этапе 4: условный `UPDATE access_requests SET status = ? WHERE id = ? AND status = 'PENDING'` и проверка `updated == 0 → AlreadyResolvedException` (`AccessRequestService` строка 89). Теперь из двух гонщиков ровно один получает `updated == 1`, второй — `updated == 0` и честный 409 ALREADY_RESOLVED. Тест `doubleResolveGives409` воспроизводит именно это. Урок повторяет мораль этапа 4: любой переход состояния — атомарный UPDATE с ожидаемым статусом в WHERE, а не «прочитал → проверил в Java → записал»."
        - "Грабля из соседней темы, всплывшая при отмене pending-брони: `expireActive` изначально гасил ВСЕ активные челленджи гостя одним `UPDATE otp_challenges SET status='EXPIRED' WHERE user_id = ? AND status='PENDING'`. Но у гостя одновременно может жить несколько челленджей на РАЗНЫЕ брони — например, живой челлендж ПЕРЕНОСА подтверждённой брони A и заброшенный челлендж брони B. Отмена pending-брони B этим широким UPDATE убивала заодно и код переноса брони A: гость запросил перенос, получил код в Telegram, а код внезапно переставал работать, потому что где-то параллельно отменилась ДРУГАЯ его бронь. Лечение — скоупинг по конкретной брони: `expireActive(userId, bookingId)` добавляет условие `(payload->>'booking_id')::bigint = ?` (`OtpService` строка 70), тот же приём привязки челленджа к брони через payload, что и в `verify` из этапа 4. Тест `cancelPendingDoesNotTouchUnrelatedChallenge` проверяет, что после отмены B челлендж переноса A остаётся PENDING. Мораль: операция над одной сущностью не должна широким WHERE задевать соседние сущности того же владельца."
  bugs_and_lessons:
    - "Сквозная линия всего этапа 5: против гонок в проекте используются ТРИ разных инструмента, и выбор между ними — не вкусовщина, а разный нужный ответ проигравшему. (1) Атомарный `UPDATE ... WHERE status = <ожидаемый>` — для смены состояния одной строки (статус брони в этапе 4, `resolve` заявки здесь): проигравший видит `updated == 0` и получает 409. (2) Advisory lock `pg_advisory_xact_lock` — когда проверка охватывает ДВЕ таблицы и констрейнт бессилен (бронь vs блокировка): гонщики выстраиваются в очередь, проигравший ждёт и видит уже изменённые данные. (3) Частичный уникальный индекс — когда проигравшему нужен не отказ, а тот же идемпотентный успех (вторая заявка на тот же номер): оба бегут параллельно, проигравший ловит `DataIntegrityViolationException` и тихо возвращает успех. Один и тот же вопрос «что делать при гонке» имеет три разных правильных ответа в зависимости от того, что должен получить проигравший — ошибку, очередь или тихий успех."
    - "Повтор морали этапа 4 на новом материале: почти каждая правка-fix этапа 5 (`resolve` заявки, скоупинг `expireActive`) свелась к тому же — заменить «прочитал в Java → проверил → записал» на один атомарный SQL с условием в WHERE, либо сузить слишком широкий WHERE, чтобы операция не задевала соседей. Конечный автомат (брони, заявки) существует не как класс, а как дисциплина: единственный законный переход — условный UPDATE, и вызывающий обязан проверить число задетых строк. Кто усвоил это на бронях этапа 4, тот на заявках этапа 5 уже знал, куда смотреть."
  prerequisites: [booking-state-machine, otp-security]
---

# Этап 5: Админ-API, авторизация, блокировки, soft delete

Разбор того, что мы собрали в этапе 5 — вокруг гостя-друга появился владелец
дома: он блокирует даты под ремонт, ведёт белый список, отменяет и переносит
любые брони без OTP и разбирает заявки на доступ от новых людей. Java-сторона —
в пакетах `admin/`, `calendar/`, `accessrequest/` и `user/` внутри
`backend-api/src/main/java/com/batowka/guestbooking/`, плюс `common/DatesLock.java`
и `auth/SecurityConfig.java`. Спека этапа —
`docs/specs/2026-08-20-stage-5-admin-api-design.md`; там формулировки решений,
на которые опирается проза ниже. Все ссылки — на реальные файлы: открой рядом
и сверь строки.

Этап во многом продолжает этап 4: те же приёмы атомарных переходов и привязки
через payload всплывают снова, только уже на заявках и блокировках. Если
разбор этапа 4 ещё свеж — темы 1 (конечные автоматы) и 2 (OTP) оттуда здесь
пригодятся.

## 1. Роли и авторизация: две линии обороны

До этого этапа все аутентифицированные пользователи были равны. Теперь у
запроса есть роль (`ROLE_ADMIN` или `ROLE_FRIEND`), и путь запроса к
админскому эндпоинту проходит через две независимые проверки роли.

Первая линия — URL-правило в `SecurityConfig`. `JwtAuthFilter` кладёт роль
пользователя в `SecurityContext` ещё до контроллеров, а цепочка матчеров
решает по URL, кого пускать:

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/calendar", "/api/auth/**", "/api/access-requests").permitAll()
        .requestMatchers("/api/admin/**").hasRole("ADMIN")
        .requestMatchers("/api/**").authenticated()
        .anyRequest().permitAll())
```

Весь префикс `/api/admin/**` закрыт ролью `ADMIN` одной строкой. Если сюда
приходит друг с валидным токеном, его отклонят ещё в фильтре, до
DispatcherServlet, и JSON-ответ 403 напишет `accessDeniedHandler`, объявленный
тут же в `exceptionHandling`.

Вторая линия — method security на самом контроллере:

```java
@RestController
@RequestMapping("/api/admin/blocked-periods")
@PreAuthorize("hasRole('ADMIN')") // вторая линия обороны поверх URL-правила SecurityConfig
public class AdminBlockedPeriodController {
```

Зачем дублировать? Потому что две линии ломаются от разных ошибок. URL-правило
перестанет защищать, если кто-нибудь добавит админский эндпоинт вне
`/api/admin/**` или неаккуратно отредактирует цепочку матчеров. `@PreAuthorize`
перестанет защищать, если новый контроллер забудут им пометить. Чтобы дыра
реально открылась, надо ошибиться в обоих местах сразу — это тот же defence in
depth, что `CHECK`-констрейнт рядом с проверкой в Java из этапа 4.

Тонкость — куда летит `AccessDeniedException` в каждом случае. Когда запрос
рубит URL-правило, это происходит в фильтре, и его ловит `accessDeniedHandler`
из `SecurityConfig`. Но когда роль проверяет `@PreAuthorize`, это уже ВНУТРИ
MVC, на входе в метод — та же по типу `AccessDeniedException` идёт по стеку MVC
и до `accessDeniedHandler` не доходит. Её ловит отдельный хендлер в
`GlobalExceptionHandler`:

```java
@ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
@ResponseStatus(HttpStatus.FORBIDDEN)
public ApiError accessDenied(org.springframework.security.access.AccessDeniedException ex) {
    // бросается @PreAuthorize внутри MVC — без этого хендлера catch-all дал бы 500
    return new ApiError("FORBIDDEN", "Недостаточно прав");
}
```

Комментарий говорит прямо: без этого хендлера `AccessDeniedException` от
`@PreAuthorize` провалилась бы в catch-all `@ExceptionHandler(Exception.class)`
и стала бы 500 INTERNAL_ERROR — «недостаточно прав» выглядело бы как поломка
сервера. В этом этапе все админские контроллеры сидят под `/api/admin/**`,
поэтому оба пути дают одинаковый 403, но хендлер нужен на будущее — как только
`@PreAuthorize` окажется там, где URL-правило не сторожит, именно он вернёт
честный 403 вместо 500.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/auth/SecurityConfig.java`
> — смотри `authorizeHttpRequests` (строка с `/api/admin/**` и `hasRole`) и
> `accessDeniedHandler` в `exceptionHandling`. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/admin/AdminBlockedPeriodController.java`
> — смотри `@PreAuthorize` на классе и `@EnableMethodSecurity` в
> `SecurityConfig`, без которого аннотация не работает. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java`
> — смотри `accessDenied` и комментарий про catch-all. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/admin/AdminBlockedPeriodTest.java`
> — смотри `friendGets403`.

## 2. Advisory locks: сериализация того, что констрейнт не покрывает

Брони между собой не пересекаются благодаря EXCLUDE-констрейнту из этапа 1 —
он живёт внутри таблицы `bookings` и Postgres проверяет его сам на каждой
вставке. Но в этапе 5 появилась вторая таблица дат — `blocked_periods`, и
пересечение нужно проверять МЕЖДУ таблицами: блокировку нельзя ставить поверх
активной брони, а бронь — поверх блокировки. Exclusion constraint так не умеет:
он не видит вторую таблицу. Проверку приходится делать кодом — а кодовая
проверка «прочитал, что свободно → вставил» уязвима к гонке ровно как «прочитал
статус → записал» из этапа 4.

Закрывает эту гонку один общий advisory-замок:

```java
public void acquire() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
        // вне транзакции xact-замок отпустился бы сразу — молчаливая дыра
        throw new IllegalStateException("DatesLock.acquire() требует активной транзакции");
    }
    jdbc.execute("select pg_advisory_xact_lock(" + KEY + ")");
}
```

`pg_advisory_xact_lock(KEY)` — это добровольная блокировка по произвольному
числу (`KEY = 4242`), не привязанная ни к какой строке или таблице. Она
держится до конца транзакции взявшего и выстраивает конкурентов в очередь: пока
одна транзакция внутри замка проверяет пересечение и вставляет, вторая на том
же `KEY` ждёт и войдёт только после коммита первой — то есть увидит уже
изменённые данные. Берётся замок ПЕРЕД проверкой конфликтов:

```java
datesLock.acquire();
List<OverlapsBookingException.Conflict> conflicts = bookings
        .findOverlapping(startDate, endDate, BookingService.ACTIVE).stream()
        .map(b -> new OverlapsBookingException.Conflict(
                b.getId(), b.getUser().getName(), b.getCheckIn(), b.getCheckOut()))
        .toList();
if (!conflicts.isEmpty()) {
    throw new OverlapsBookingException(conflicts);
}
```

Тест `bookingAndBlockNeverCoexistOnSameDates` запускает в двух потоках гонку
«гость бронирует vs админ блокирует» на одни даты и проверяет инвариант: обе
стороны выиграть не могут. Без замка обе транзакции прочитали бы «свободно» и
обе вставили бы — замок оставляет ровно одну.

Почему не `SERIALIZABLE`-изоляция? Она бы тоже поймала конфликт, но ценой того,
что Postgres откатывал бы одну из транзакций с ошибкой сериализации, которую
пришлось бы ловить и повторять на КАЖДОЙ операции с датами. Advisory lock
сериализует ровно нужный участок явно и предсказуемо, а для проекта с одним
гостевым местом он практически никогда не будет реально оспорен — стоит как
гарантия корректности, а не как узкое место.

Грабля здесь — вариант `_xact_`. `pg_advisory_xact_lock` отпускается сам в
конце транзакции, поэтому `acquire()` первым делом проверяет, что транзакция
вообще есть. Если бы замок взяли вне `@Transactional`, Spring выполнил бы
`select pg_advisory_xact_lock(...)` в авто-коммите: замок взялся бы и тут же,
на завершении этого единственного стейтмента, отпустился — до `findOverlapping`
и `save` не дожил бы. Гонка при этом не дала бы никакой ошибки, замок просто
молча не защищал бы ничего. Явная проверка активной транзакции превращает эту
невидимую дыру в громкое `IllegalStateException` на старте.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/common/DatesLock.java`
> — смотри `acquire`, проверку `isActualTransactionActive` и
> `pg_advisory_xact_lock`. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/calendar/BlockedPeriodService.java`
> — смотри `create`: `datesLock.acquire()` строго перед `findOverlapping` и
> `save`. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/booking/DatesRaceTest.java`
> — смотри `bookingAndBlockNeverCoexistOnSameDates` и комментарий сверху про
> то, что было бы без замка.

## 3. Soft delete и его хвосты

Удалить гостя из белого списка нельзя простым `DELETE FROM users`: на
пользователя ссылаются брони внешним ключом. Физический DELETE либо упал бы на
FK, либо (с каскадом) стёр бы историю броней, которую проект обязан хранить.
Поэтому удаление — это отметка времени:

```sql
-- Soft delete: NULL = активен. Телефон уникален, поэтому повторное одобрение
-- ранее удалённого реактивирует запись (история броней возвращается).
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;
```

`deleted_at IS NULL` означает «активен». Строка остаётся на месте вместе со
всей историей, а раз телефон по-прежнему уникален по всей таблице, при возврате
того же человека его прежняя запись просто оживает — `addNormalized` снимает
`deletedAt`, и старые брони снова принадлежат владельцу номера.

Цена такого подхода — «живого» пользователя больше нельзя выбрать простым
`findByPhone`. Каждое место, где важна живость, обязано фильтровать явно. Таких
мест три:

- **Логин** (`AuthController`): и беспарольный логин друга, и админ-логин ходят
  через `findByPhoneAndDeletedAtIsNull` — удалённый номер просто «неизвестен».
- **`/api/me`** (`MeController`): токен удалённого гостя ещё валиден по подписи
  и сроку, поэтому фильтр отдельный —
  `findById(...).filter(u -> u.getDeletedAt() == null).orElseThrow(UserGoneException::new)`.
- **Онбординг контакта** (`ContactSharedConsumer`): бот привязывает `chat_id`
  только `findByPhoneAndDeletedAtIsNull(...).ifPresent(...)`.

Второй случай интереснее всего: у удалённого гостя на руках рабочий токен.
Сервер отвечает ему не просто 401, а 401 с затирающей cookie:

```java
@ExceptionHandler(com.batowka.guestbooking.user.UserGoneException.class)
public ResponseEntity<ApiError> userGone(UserGoneException ex) {
    return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.SET_COOKIE,
                    AuthController.authCookie("", Duration.ZERO).toString())
            .body(new ApiError("UNAUTHORIZED", ex.getMessage()));
}
```

`authCookie("", Duration.ZERO)` — это пустое значение с нулевым временем жизни:
браузер удалит cookie сразу, и клиент, ещё считавший себя залогиненным по
валидному токену, окажется разлогинен тем же ответом, что сказал ему 401.

И главный хвост, который легко забыть: `softDelete` обязан обнулить и
`telegram_chat_id`, а не только выставить `deleted_at`.

```java
user.setDeletedAt(clock.instant());
// доступ отозван — отзываем и Telegram-связку; при реактивации человек заново делится контактом с ботом
user.setTelegramChatId(null);
users.save(user);
```

Почему это критично: уведомления идут не через логин, а через outbox прямо на
сохранённый `telegram_chat_id`. Логин и `/api/me` удалённого не пустят, но если
связка осталась, админ, отменяющий старую бронь только что удалённого гостя,
отправил бы ему BOOKING_CANCELLED в Telegram — человек, которого выгнали из
белого списка, продолжал бы получать сообщения о доме. Онбординг —
симметричный хвост на входе: без фильтра `deleted_at IS NULL` удалённый,
повторно поделившийся контактом с ботом, тихо восстановил бы себе связку в
обход админа. Soft delete — это не одна отметка, а согласованное закрытие всех
каналов сразу: входа по токену, уведомлений и повторной самопривязки.

> **Разбор кода:** открой
> `backend-api/src/main/resources/db/migration/V3__users_deleted_at.sql` —
> смотри колонку и комментарий про реактивацию. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/user/WhitelistService.java`
> — смотри `softDelete` (проверки admin/активная бронь, `setDeletedAt` И
> `setTelegramChatId(null)`) и `addNormalized` (реактивация через
> `setDeletedAt(null)`). Открой
> `backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java`
> — смотри `userGone` и затирающую cookie. Открой `AuthController` (login,
> adminLogin), `MeController` (`me`) и `ContactSharedConsumer`
> (`findByPhoneAndDeletedAtIsNull`) — три места фильтрации живых. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/admin/AdminUserTest.java`
> — смотри `deleteRevokesTelegramLink` и `reAddingDeletedReactivatesWithHistory`.

## 4. Проектирование админ-API без каскадов

Общий принцип всех админских операций этапа: сервис не делает необратимых
каскадов молча, а возвращает человеку видимый конфликт и ответственность за
решение.

**409 со списком вместо автоотмены.** Когда админ ставит блокировку поверх
активных броней, сервис их НЕ отменяет — он бросает 409 со списком того, что
мешает:

```java
/** Блокировка поверх активной брони запрещена: каскадов нет, админ разруливает сам. */
public class OverlapsBookingException extends RuntimeException {
    public record Conflict(long bookingId, String guestName, LocalDate checkIn, LocalDate checkOut) {}
    private final List<Conflict> conflicts;
    ...
}
```

Альтернатива — «умная» автоотмена: заблокировал даты, а пересекающиеся брони
гостей молча отменились. Она удобнее в один клик, но прячет необратимое
действие над чужими бронями внутри операции «заблокировать даты» — админ,
ставящий блокировку под ремонт, мог не понимать, что этим выгоняет
подтверждённых гостей. Список конфликтов (id, имя, даты) делает проблему
видимой: админ сам решит — перенести, отменить с уведомлением или выбрать
другие даты — отдельным осознанным вызовом. Каскад экономит клик ценой того,
что разрушение становится невидимым побочным эффектом.

**Идемпотентная заявка.** Публичная заявка на доступ (`POST /api/access-requests`)
не должна плодить дубли и спамить админа при повторных отправках:

```java
if (requests.existsByPhoneAndStatus(phone, AccessRequestStatus.PENDING)) {
    return; // заявка уже ждёт решения — не плодим и не спамим админа
}
AccessRequest r = new AccessRequest();
...
try {
    requests.saveAndFlush(r);
} catch (DataIntegrityViolationException e) {
    // проиграли гонку с параллельным POST на тот же телефон (частичный уникальный
    // индекс на PENDING) — проигравший гонку получает тот же идемпотентный успех,
    // что и повтор; событие уже отправил победитель, второй раз слать не нужно
    return;
}
```

Проверка `exists`-then-insert сама по себе уязвима к гонке ровно как «прочитал
→ записал»: два одновременных POST оба увидят «pending нет» и оба вставят. Ловит
это частичный уникальный индекс из миграции V4:

```sql
CREATE UNIQUE INDEX uq_access_requests_pending_phone
    ON access_requests (phone) WHERE status = 'PENDING';
```

Здесь стоит остановиться на контрасте с темой 2. Против гонки мы применили
ДВА РАЗНЫХ инструмента, и выбор не случаен. Advisory lock СЕРИАЛИЗУЕТ —
выстраивает гонщиков в очередь, проигравший ждёт. Уникальный индекс ПОЗВОЛЯЕТ
обоим бежать параллельно и отбрасывает проигравшего на коммите. Для блокировки
дат нужна была сериализация (проверка охватывает две таблицы, результат
проигравшего — отказ). Для заявки нужен индекс, потому что правильный ответ
проигравшему — не отказ, а тот же тихий успех, что и у обычного повтора:
`catch → return`, и снаружи второй POST неотличим от ретрая. `saveAndFlush`
(а не `save`) здесь обязателен именно чтобы нарушение индекса всплыло как
`DataIntegrityViolationException` тут же, в try/catch, а не отложенно на
коммите, где его уже некому поймать.

**Конечный автомат заявки.** Заявка живёт в автомате `PENDING →
APPROVED | REJECTED`, и переход сделан ровно тем же атомарным приёмом, что и
статусы брони в этапе 4:

```java
int updated = jdbc.update("""
        update access_requests set status = ?, resolved_at = ?
        where id = ? and status = 'PENDING'
        """, target.name(), Timestamp.from(clock.instant()), id);
if (updated == 0) {
    throw new AlreadyResolvedException();
}
```

Это лечение реального бага: в первой версии `resolve` был неатомарен —
`findById` → проверка `status == PENDING` в Java → `save`. Между чтением и
записью влезал второй одновременный approve, оба читали PENDING, заявка
резолвилась дважды, а `approve` дважды добавлял человека в белый список.
Условный `UPDATE ... WHERE status = 'PENDING'` оставляет из двух гонщиков ровно
одного с `updated == 1`; второй получает `updated == 0` и честный 409
ALREADY_RESOLVED (тест `doubleResolveGives409`). А само одобрение уважает soft
delete из темы 3: `approve` проверяет `findByPhoneAndDeletedAtIsNull` и, если
номер ранее был удалён, реактивирует запись, а не создаёт дубль (тест
`approveReactivatesDeletedUser`).

Ещё одна грабля из соседней темы всплыла на отмене pending-брони. `expireActive`
изначально гасил ВСЕ активные челленджи гостя одним широким UPDATE по
`user_id`. Но у гостя одновременно может жить несколько челленджей на разные
брони — живой челлендж переноса подтверждённой брони A и заброшенный челлендж
брони B. Отмена pending-брони B этим широким UPDATE убивала заодно и код
переноса A: гость запросил перенос, получил код, а код внезапно переставал
работать. Лечение — скоупинг по конкретной брони через тот же payload-приём,
что в `verify` этапа 4:

```java
public void expireActive(Long userId, long bookingId) {
    jdbc.update("""
            update otp_challenges set status = 'EXPIRED'
            where user_id = ? and status = 'PENDING'
              and (payload->>'booking_id')::bigint = ?
            """, userId, bookingId);
}
```

Тест `cancelPendingDoesNotTouchUnrelatedChallenge` проверяет, что после отмены B
челлендж переноса A остаётся PENDING. Мораль та же, что у автоматов: операция
над одной сущностью не должна широким WHERE задевать соседние сущности того же
владельца.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/calendar/OverlapsBookingException.java`
> — смотри `Conflict` и комментарий «каскадов нет». Открой
> `backend-api/src/main/java/com/batowka/guestbooking/accessrequest/AccessRequestService.java`
> — смотри `submit` (`exists` + `saveAndFlush` в try/catch), `resolve`
> (условный UPDATE и `updated == 0`) и `approve` (реактивация через
> `findByPhoneAndDeletedAtIsNull`). Открой
> `backend-api/src/main/resources/db/migration/V4__access_requests_pending_unique.sql`
> — смотри частичный уникальный индекс. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/otp/OtpService.java` —
> смотри `expireActive(userId, bookingId)` и условие по `payload->>'booking_id'`.
> Открой
> `backend-api/src/test/java/com/batowka/guestbooking/accessrequest/ResolveAccessRequestTest.java`
> — смотри `doubleResolveGives409` и `approveReactivatesDeletedUser`, и
> `backend-api/src/test/java/com/batowka/guestbooking/booking/CancelPendingTest.java`
> — смотри `cancelPendingDoesNotTouchUnrelatedChallenge`.

## Итог этапа

Этап 5 добавил роль владельца — и почти каждое решение здесь про то, чтобы
власть админа не превращалась в тихое разрушение и чтобы гонки за общий ресурс
не расходились в разных мнениях о состоянии. Авторизация — две независимые
линии, ломающиеся от разных ошибок. Пересечение дат между таблицами, которое
констрейнт не покрывает, закрыто advisory-замком. Удаление — мягкое, с закрытием
всех трёх каналов доступа сразу. Админ-API не делает каскадов: 409 со списком
вместо магии, идемпотентная заявка вместо дублей, атомарный резолв вместо
двойного approve.

Сквозной вопрос, связывающий этап с этапом 4: в проекте против гонок работают
три разных инструмента — атомарный `UPDATE ... WHERE status`, advisory lock и
частичный уникальный индекс. Что определяет, какой из трёх правильный в
конкретном месте — и почему для блокировки дат выбран замок, а для второй
заявки на тот же номер — индекс, хотя обе борются с одной и той же по природе
гонкой?
