---
tutor:
  stage: 4
  title: "Бронирование с OTP: конечные автоматы, безопасность кода, фоновые задачи"
  topics:
    - id: booking-state-machine
      section: "Конечные автоматы статусов и атомарные переходы"
      code_anchors:
        - path: backend-api/src/main/resources/db/migration/V1__init.sql
          symbol: "CHECK (status IN ('PENDING_OTP', 'CONFIRMED', 'CANCELLED')) / one_confirmed_booking_per_user"
          concept: "допустимые статусы и правило «одна CONFIRMED на гостя» зафиксированы на уровне БД, а не только в Java"
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java
          symbol: "confirmCreate — update bookings set status = 'CONFIRMED' where id = ? and status = 'PENDING_OTP'"
          concept: "переход только через UPDATE ... WHERE status = <ожидаемый>; 0 строк = кто-то другой уже сменил статус"
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/PendingBookingCleaner.java
          symbol: "cleanExpired — update bookings b set status = 'CANCELLED' where b.status = 'PENDING_OTP' ..."
          concept: "тот же приём с той же стороны гонки — чистильщик тоже не имеет права поменять статус, который уже не PENDING_OTP"
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/BookingStatus.java
          symbol: "enum BookingStatus { PENDING_OTP, CONFIRMED, CANCELLED }"
          concept: "три статуса, никакой отдельной библиотеки конечных автоматов — переходы существуют только как соглашение в коде и WHERE-условиях"
      quiz_seeds:
        - "Что произойдёт, если confirm гостя и cleanExpired чистильщика физически выполнятся в одну и ту же секунду над одной и той же бронью?"
        - "Почему нельзя было написать `booking.setStatus(CONFIRMED); repository.save(booking)` через JPA-сущность вместо raw UPDATE?"
        - "Зачем в схеме есть одновременно и `CHECK (status IN (...))`, и `UPDATE ... WHERE status = ...` в коде — разве проверки в Java недостаточно?"
      decisions:
        - choice: "любой переход статуса — это один атомарный SQL UPDATE с условием WHERE status = <ожидаемый предыдущий>, и код всегда проверяет число задетых строк"
          alternatives: "явная блокировка строки (SELECT ... FOR UPDATE) перед изменением, либо оптимистическая блокировка через @Version на JPA-сущности"
          why: "у брони минимум два независимых актора, которые могут захотеть сменить её статус одновременно и не знают друг о друге: гость, подтверждающий код, и PendingBookingCleaner, отменяющий протухшую бронь по расписанию. UPDATE ... WHERE — это одна атомарная операция на уровне БД: Postgres сам гарантирует, что из двух одновременных UPDATE с одинаковым WHERE ровно один обновит строку, а второй увидит 0 задетых строк, без явных блокировок и без гонки между «прочитать статус» и «записать новый статус»"
          price: "нельзя просто вызвать `booking.setStatus(x); repository.save(booking)` — JPA-сущность в этом проекте вообще не используется для изменения статуса; каждое место, меняющее статус, обязано вручную писать SQL с WHERE и проверять `updated == 0`, что многословнее одной строки на entity, зато переживает гонку без блокировок"
      pitfalls:
        - "confirmCreate проверяет `if (updated == 0) throw new BookingExpiredException()` после UPDATE PENDING_OTP → CONFIRMED — это не защитный избыточный код «на всякий случай», а рабочий сценарий: гость успел ввести код ровно в момент, когда PendingBookingCleaner (тема 5) уже отменил его бронь как протухшую. Без проверки числа строк код бы решил, что подтверждение прошло успешно, хотя UPDATE фактически не поменял ни одной строки — гость получил бы 204 и уверенность в несуществующей брони."
    - id: otp-security
      section: "OTP-безопасность: хеш, попытки до сравнения, неинформативные ошибки"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/otp/OtpService.java
          symbol: "issue — encoder.encode(code) в code_hash; outbox.write(\"OTP_CODE\", ... \"code\", code ...)"
          concept: "в БД лежит только BCrypt-хеш кода; сырой код существует лишь мгновение — в памяти метода и в payload события OTP_CODE, которое читает bot-service"
        - path: backend-api/src/main/java/com/batowka/guestbooking/otp/OtpService.java
          symbol: "verify — Integer attempts = requiresNew.execute(...); if (attempts > MAX_ATTEMPTS) ...; if (!encoder.matches(code, ...))"
          concept: "attempts инкрементируется ДО сравнения кода и в отдельной REQUIRES_NEW-транзакции — подробности и цена этого решения разобраны в багах этапа ниже"
        - path: backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java
          symbol: "invalidCode / codeExpired / noActiveCode — три разных @ExceptionHandler"
          concept: "неверный код, исчерпанные попытки и отсутствие активного челленджа — три РАЗНЫХ кода ошибки, но ни один не подсказывает переборщику, что именно не так"
        - path: backend-api/src/test/java/com/batowka/guestbooking/otp/OtpServiceTest.java
          symbol: "codeForAnotherBookingIsNotAccepted"
          concept: "код, выпущенный для брони 107, не принимается при попытке подтвердить бронь 999 — привязка через payload.booking_id проверяется реально, а не только в спеке"
      quiz_seeds:
        - "Зачем вообще хешировать 6-значный OTP-код — его и так узнают через Telegram, а не через утечку БД?"
        - "Чем отличается по смыслу 400 INVALID_CODE от 400 CODE_EXPIRED — и почему это разделение НЕ помогает переборщику, хотя коды разные?"
        - "Что мешало бы злоумышленнику подобрать чужой код подтверждения, если бы confirm не принимал bookingId и сверял его с payload челленджа?"
      decisions:
        - choice: "хранить в БД только BCrypt-хеш кода (`code_hash`), а не сам код; сырой код нигде не логируется"
          alternatives: "хранить код открытым текстом — так проще для отладки (можно посмотреть код в БД, если Telegram не пришёл)"
          why: "код подтверждения — это на 5 минут точно такой же секрет, как пароль; дамп таблицы `otp_challenges` (бэкап, утечка, чужой доступ к БД) не должен превращаться в список работающих кодов подтверждения активных операций"
          price: "разработчик не может подсмотреть код в БД при отладке — только через outbox (там код есть, пока событие не вычищено) или дублируя его в тестовый лог; чуть менее удобно, но это та цена, ради которой хеш и существует"
        - choice: "три РАЗНЫХ кода ошибки для трёх разных причин отказа (INVALID_CODE, CODE_EXPIRED, NO_ACTIVE_CODE), но ни один текст сообщения не объясняет ПОЧЕМУ именно так"
          alternatives: "единая ошибка «неверный код» на все случаи — максимально неинформативно; или, наоборот, подробные сообщения («осталась 1 попытка», «код истёк 2 минуты назад») — максимально удобно гостю"
          why: "гостю на своём сайте не нужна подробность про попытки, а вот злоумышленнику, перебирающему код чужой брони, разница между «неверный» и «осталась 1 попытка» — прямая подсказка, сколько ещё пробовать; тот же принцип уже разбирался в docs/learning/02-spring-security-jwt.md про единый 401 логина"
          price: "гостю, который реально запутался (истёк код, а не ошибся), приходится догадываться по коду ошибки CODE_EXPIRED, что нужно жать «отправить код ещё раз», а не пробовать снова — фронт должен явно сопоставить код ошибки конкретной подсказке в UI"
      pitfalls:
        - "Что мешает злоумышленнику, зная только id чужой брони, перебирать 6-значные коды бесконечно, если бы `verify` не учитывал `bookingId`? Ответ в коде: `OtpService.verify(userId, bookingId, code)` ищет активный челлендж НЕ только по userId, а с условием `(payload->>'booking_id')::bigint = ?` — код от одной операции физически не подходит к другой брони того же (или чужого) пользователя, а сам лимит 3 попытки плюс TTL 5 минут делает подбор непрактичным даже для своей брони."
    - id: replace-not-reject
      section: "Паттерн «замена вместо отказа»: willReplaceBooking"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java
          symbol: "create — WillReplace willReplace = bookings.findFirstByUserIdAndStatusOrderByIdDesc(userId, CONFIRMED)..."
          concept: "создание новой брони НЕ отклоняется, даже если у гостя уже есть подтверждённая — просто в ответ добавляется willReplaceBooking с предупреждением"
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java
          symbol: "confirmCreate — сначала UPDATE старой CONFIRMED → CANCELLED, только потом UPDATE новой PENDING_OTP → CONFIRMED"
          concept: "порядок операций не декоративный — от него зависит, пройдёт ли вторая UPDATE через уникальный индекс"
        - path: backend-api/src/main/resources/db/migration/V1__init.sql
          symbol: "CREATE UNIQUE INDEX one_confirmed_booking_per_user ON bookings (user_id) WHERE status = 'CONFIRMED'"
          concept: "именно этот частичный уникальный индекс требует строгого порядка «сначала отмена старой»"
      quiz_seeds:
        - "Что случится при confirm, если код в confirmCreate поменять местами — сначала подтвердить новую бронь, а потом отменять старую?"
        - "Почему создание новой брони при уже существующей CONFIRMED не возвращает гостю ошибку сразу, хотя у гостя может быть максимум одна активная бронь?"
      decisions:
        - choice: "создание новой брони при существующей CONFIRMED не блокируется — запрос выполняется, а ответ содержит `willReplaceBooking {id, checkIn, checkOut}` с предупреждением, что после подтверждения старая бронь будет отменена"
          alternatives: "жёстко отклонять создание («у тебя уже есть активная бронь — сначала отмени») — потребовало бы от гостя двух отдельных операций (отмена + создание), каждая со своим OTP-кодом"
          why: "спека этапа §4 прямо формулирует это как UX-решение: гость, который хочет поменять даты через «новую бронь» (а не через явный перенос), не должен вручную сначала отменять старую — сервис сам делает замену атомарно внутри одного подтверждения кодом"
          price: "серверу приходится помнить и корректно упорядочивать два изменения статуса внутри одной транзакции confirm вместо одного простого перехода, и любая ошибка в порядке ломает частичный уникальный индекс — цена атомарности переложена с UX на аккуратность реализации"
      pitfalls:
        - "В `confirmCreate` замена старой брони на новую написана как `bookings.findFirstByUserIdAndStatusOrderByIdDesc(...).ifPresent(old -> { int n = jdbc.update(...); if (n == 1) notify... })` — если бы вторая UPDATE (новая PENDING_OTP → CONFIRMED) стояла раньше первой, частичный уникальный индекс `one_confirmed_booking_per_user` отверг бы её же собственную вставку: у гостя на мгновение оказалось бы ДВЕ подтверждённых брони, что запрещено индексом — операция просто упала бы с ошибкой уникальности вместо тихой замены. Второй, более тихий нюанс (замечен ревью Task 5 как minor, осознанно отложен): если `n == 0` — то есть старую бронь кто-то (например, PendingBookingCleaner) успел отменить ещё до этого confirm, — код молча пропускает уведомление об отмене и просто не вызывает notifyBookingEvent для старой брони; ошибки при этом нет, потому что для гостя результат тот же самый (старой брони больше нет)."
    - id: reschedule-variant-a
      section: "Вариант A переноса: честный 409 вместо удержания дат"
      code_anchors:
        - path: docs/specs/2026-08-19-stage-4-booking-otp-design.md
          symbol: "§4 RESCHEDULE — «Даты переноса ДО подтверждения не удерживаются (решение владельца: вариант A — честный 409 при гонке)»"
          concept: "формулировка решения владельца прямо в спеке — это не подразумеваемое поведение, а сознательный выбор с именем «вариант A»"
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java
          symbol: "applyReschedule — catch (DataIntegrityViolationException e) { throw new DatesTakenException(); }"
          concept: "исключение из EXCLUDE-констрейнта на UPDATE дат откатывает ВСЮ транзакцию confirm — включая пометку челленджа как USED"
        - path: backend-api/src/test/java/com/batowka/guestbooking/booking/RescheduleCancelTest.java
          symbol: "rescheduleRaceGives409AndKeepsOldDates"
          concept: "тест воспроизводит гонку: пока гость вводит код, кто-то другой занимает целевые даты — confirm откатывается целиком, старые даты остаются"
        - path: docs/specs/2026-08-13-japan-guest-booking-design.md
          symbol: "§8 Обработка ошибок и граничные случаи — «Гонка за даты → exclusion constraint, второму 409 + свежий календарь»"
          concept: "общий принцип родительской спеки, которым этап 4 конкретно реализует перенос — тот же exclusion constraint, что и при создании брони"
      quiz_seeds:
        - "Почему после проигранной гонки за даты OTP-челлендж переноса остаётся PENDING, а не помечается USED или EXPIRED?"
        - "В чём разница между вариантом A (даты не удерживаются) и гипотетическим вариантом Б, где перенос сразу создавал бы вторую pending-бронь на новые даты?"
      decisions:
        - choice: "вариант A: `PATCH /api/bookings/{id}` только выпускает OTP-код с новыми датами в payload, но НИКАК не резервирует эти даты в БД до момента confirm; если за время ввода кода кто-то другой успел занять эти даты, `applyReschedule` ловит нарушение EXCLUDE-констрейнта, откатывает всю транзакцию confirm целиком и возвращает честный 409 DATES_TAKEN, оставляя старую бронь и старые даты нетронутыми"
          alternatives: "вариант Б (отвергнут): при PATCH сразу создавать вторую строку в bookings со статусом PENDING_OTP на новые даты — тогда даты были бы физически удержаны EXCLUDE-констрейнтом с момента запроса переноса, а не с момента подтверждения"
          why: "вариант Б означал бы, что у гостя временно существуют две связанные строки (старая CONFIRMED + новая PENDING_OTP) с отдельной логикой их синхронизации при подтверждении/протухании/отмене — фактически дублирование всей логики паттерна «замена вместо отказа» (тема выше) внутри самого переноса; вариант A даёт то же самое поведение — гость либо получает перенос, либо честный 409 — но без второй строки и без нового класса состояний"
          price: "окно гонки реально существует: даты, которые гость видел свободными в момент PATCH, физически не защищены от другого гостя вплоть до самого confirm; при коротком, но ненулевом объёме бронирований в проекте эта гонка маловероятна, но при проигрыше гость должен повторить перенос заново — цена простоты архитектуры оплачена редким, но настоящим неудобством для гостя"
      pitfalls:
        - "Если бы `applyReschedule` перехватывал `DataIntegrityViolationException` и просто логировал ошибку, не перебрасывая её дальше (частая интуиция «не дать исключению вылезти наружу»), это тихо сломало бы гарантию отката: `otp.verify(...)` уже успел пометить челлендж `USED` в той же транзакции чуть раньше по стеку вызовов `confirm`, и если бы исключение не долетело до границы `@Transactional` на `confirm`, эта пометка `USED` закоммитилась бы вместе с несостоявшимся переносом — гость получил бы код, который никогда не сработает повторно, при этом бронь осталась бы на старых датах. Правильное поведение — рестроить `DatesTakenException` (RuntimeException) именно для того, чтобы она долетела до внешней `@Transactional` и откатила ВСЁ, включая пометку челленджа."
    - id: background-cleaner
      section: "Фоновые задачи: идемпотентный чистильщик и триггер updated_at"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/PendingBookingCleaner.java
          symbol: "cleanExpired — @Scheduled(fixedDelay = 120_000), NOT EXISTS (select 1 from otp_challenges c where ... c.status = 'PENDING' and c.expires_at > now())"
          concept: "каждые 2 минуты один UPDATE с подзапросом отменяет все протухшие PENDING_OTP-брони без живого челленджа разом — не по одной строке в цикле"
        - path: backend-api/src/test/java/com/batowka/guestbooking/booking/CleanerAndResendTest.java
          symbol: "cleanerCancelsStalePendingAndFreesDates / cleanerLeavesFreshPendingAlone"
          concept: "два теста на границу решения: протухшая бронь без челленджа отменяется и освобождает даты (EXCLUDE больше не мешает), а свежая — нет"
        - path: backend-api/src/main/java/com/batowka/guestbooking/otp/OtpService.java
          symbol: "static final Duration TTL = Duration.ofMinutes(5)"
          concept: "TTL челленджа (5 минут) совпадает с порогом «протухшести» брони у чистильщика — не совпадение, а согласованность двух независимых констант"
        - path: backend-api/src/main/resources/db/migration/V2__bookings_updated_at_trigger.sql
          symbol: "CREATE TRIGGER bookings_set_updated_at BEFORE UPDATE ON bookings FOR EACH ROW EXECUTE FUNCTION set_updated_at()"
          concept: "триггер на уровне Postgres, а не аннотация @UpdateTimestamp в JPA — покрывает и raw-JDBC изменения статуса, которых в этом этапе большинство"
      quiz_seeds:
        - "Почему PendingBookingCleaner не отменяет PENDING_OTP-бронь, если у неё есть свежий (непросроченный) PENDING-челлендж, даже если сама бронь создана больше 5 минут назад?"
        - "Что случится, если два экземпляра backend-api запустят cleanExpired одновременно над одной и той же протухшей бронью?"
        - "Зачем нужен именно триггер БД для updated_at, если почти все статусные переходы этого этапа и так проходят через raw JDBC UPDATE, а не через JPA-сущность Booking?"
      decisions:
        - choice: "поллинг раз в 2 минуты (`@Scheduled(fixedDelay = 120_000)`) одним batch-UPDATE с NOT EXISTS-подзапросом вместо построчной обработки"
          alternatives: "цикл «выбрать протухшие id → для каждого вызвать сервисный метод отмены»; либо событийная модель — отдельный таймер/джоб на каждую бронь, который срабатывает ровно через 5 минут после создания"
          why: "batch-UPDATE — это тот же приём атомарного `UPDATE ... WHERE status = ...`, что и в теме о конечных автоматах: Postgres сам находит и меняет все подходящие строки одной операцией, без гонки между чтением списка id и изменением каждой строки по отдельности; таймер на каждую бронь избыточен для темпа «несколько уведомлений в день», который уже был согласован в этапе 3"
          price: "задержка до 2 минут между тем, как бронь формально протухла (прошло 5 минут без завершённого OTP), и тем, как она реально станет CANCELLED и освободит даты — для темпа проекта эта цена признана приемлемой, аналогично 2-секундному интервалу OutboxPublisher из этапа 3"
        - choice: "триггер `set_updated_at()` на уровне Postgres (`BEFORE UPDATE ON bookings`), маппинг `updatedAt` в JPA-сущности остаётся read-only"
          alternatives: "аннотация Hibernate `@UpdateTimestamp` на поле сущности — просит саму JPA проставлять метку при каждом `save()`"
          why: "перенесено из финального ревью этапа 1 (комментарий в самой миграции это фиксирует буквально): бо́льшая часть статусных переходов этого этапа идёт через `JdbcTemplate.update(...)` в обход JPA-сущности `Booking` целиком (confirmCreate, applyReschedule, applyCancel, PendingBookingCleaner) — `@UpdateTimestamp` сработал бы только на путях через `repository.save()`, которых в этом этапе почти нет"
          price: "логика проставления updated_at теперь живёт в SQL-миграции, а не рядом с Java-кодом сущности — при чтении класса `Booking` не видно, откуда берётся updated_at, нужно знать, что искать в db/migration"
      pitfalls:
        - "Пороговое условие чистильщика — `b.created_at < now() - interval '5 minutes' and not exists (... c.status = 'PENDING' and c.expires_at > now())` — специально устроено так, что бронь с ИСТЁКШИМ, но ещё PENDING-челленджем (сам код протух, но статус в БД ещё не EXPIRED) НЕ считается защищённой: подзапрос требует `expires_at > now()`, то есть живой челлендж должен быть не только PENDING по статусу, но и не протухшим по времени. Если бы условие проверяло только `status = 'PENDING'` без сравнения `expires_at`, чистильщик держал бы бронь живой сколько угодно долго после того, как её код уже физически перестал работать в `OtpService.verify` (там истёкший челлендж отдельно ловится через `expires_at`) — цена такой ошибки была бы в том, что бронь навсегда занимала бы даты, а гость не мог бы ни подтвердить (код истёк), ни создать заново (даты заняты им же)."
    - id: reliable-bot-delivery
      section: "Надёжная доставка в боте: remember/commit после успеха"
      code_anchors:
        - path: docs/specs/2026-08-19-stage-4-booking-otp-design.md
          symbol: "§5 «Перенос из бэклога этапа 3 (обязательный): консьюмер бота коммитит offset и запоминает event_id ТОЛЬКО после успешного SendMessage»"
          concept: "прямая формулировка требования и его цены — «перманентный сбой отправки одного сообщения блокирует очередь уведомлений» — в самой спеке"
        - path: bot-service/internal/kafka/consumer.go
          symbol: "consumerCore.send — if err := c.sender.SendMessage(...); err != nil { return err }; c.remember(eventID)"
          concept: "remember (дедуп-память) вызывается СТРОГО после успешной отправки — если SendMessage вернул ошибку, eventID не запоминается вообще"
        - path: bot-service/internal/kafka/consumer.go
          symbol: "Consumer.Run — внутренний for { if err := c.core.handle(...); err == nil { break } ... } / затем CommitMessages ОДНОГО и того же msg"
          concept: "offset коммитится ТОЛЬКО после успешного handle, и ретраится именно уже полученное сообщение msg во внутреннем цикле — не следующий вызов FetchMessage"
        - path: bot-service/internal/kafka/consumer_test.go
          symbol: "TestFailedSendIsRetriedNotDeduplicated / flakySender"
          concept: "тест уровня consumerCore: сначала заставляет отправку упасть, а потом проверяет — то же событие доставляется повторно и ровно один раз успешно, а не теряется и не дублируется"
        - path: bot-service/internal/kafka/consumer_test.go
          symbol: "TestRunRetriesSameMessageUntilSuccess / fakeReader"
          concept: "тест уровня Run поверх фейкового kafkaReader (интерфейс FetchMessage/CommitMessages/Close): sender падает 2 раза подряд, затем успех — ассерты проверяют, что FetchMessage вызван для СЛЕДУЮЩЕГО сообщения только один раз (то есть сбойное не перезапрашивалось), CommitMessages вызван ровно один раз и именно после успеха"
      quiz_seeds:
        - "Если SendMessage упал по сети, а не потому что chat_id заблокировал бота навсегда — что произойдёт с ЭТИМ сообщением и со ВСЕМИ последующими в очереди, пока ошибка не исчезнет?"
        - "Чем сценарий сбоя отправки OTP-кода отличается по цене ошибки от сценария сбоя WELCOME-сообщения из этапа 3, который допускал in-memory дедуп без персистентности?"
        - "Почему `continue` в начало `for ctx.Err() == nil` после сбоя `handle` НЕ вернул бы то же сообщение на следующем `FetchMessage` — что именно kafka-go успевает сделать внутри самого `FetchMessage`, ещё до какого-либо `CommitMessages`?"
      decisions:
        - choice: "и `remember(eventID)`, и коммит Kafka-offset происходят строго ПОСЛЕ успешного `SendMessage`; при сбое `Consumer.Run` не переходит к следующему `FetchMessage`, а ретраит уже полученное сообщение `msg` во внутреннем цикле каждые `retryBackoff` (по умолчанию 3с), пока `handle` не вернёт `nil` — и только тогда коммитит offset этого же `msg`"
          alternatives: "коммитить offset сразу после чтения (до отправки) и просто логировать ошибку отправки — тогда очередь не блокируется, но сообщение, включая коды подтверждения, можно молча потерять; или (баг, пойманный финальным ревью) наивный `continue` в начало внешнего цикла без внутреннего ретрая — выглядит как «повтор», но фактически подставляет под удар СЛЕДУЮЩЕЕ сообщение"
          why: "спека §5 прямо называет это переносом из бэклога этапа 3: OTP-код и уведомления о брони нельзя терять — гость, не получивший код, физически не может подтвердить бронь; здесь цена ошибки выше, чем у WELCOME-сообщения из этапа 3, для которого in-memory дедуп без персистентности был приемлем именно потому, что повторное приветствие ничего не портит"
          price: "head-of-line blocking — если ОДНО сообщение не может быть доставлено НИКОГДА (например, гость заблокировал бота или chat_id стал недействительным), оно будет вечно повторяться каждые `retryBackoff` и не пропустит вперёд себя все последующие уведомления в топике `notifications.outbound`, включая чужие OTP-коды и уведомления других гостей — с единственной партицией (решение этапа 3) и одним консьюмером это касается вообще всех, а не только застрявшего гостя; спека признаёт эту цену принятой сознательно, а не как недосмотр"
      pitfalls:
        - "Головоломка на понимание цены: топик `notifications.outbound` — одна партиция, один консьюмер (`bot-service`, решение этапа 3). Если у гостя А сломалась доставка навсегда (например, он заблокировал бота), а следом в очереди стоит уведомление гостю Б о подтверждении его брони — что произойдёт с уведомлением Б? Ответ по коду: `Consumer.Run` не вызывает `FetchMessage` за следующим сообщением, пока текущее (`msg` гостя А) не обработано без ошибки — внутренний `for { if err := c.core.handle(ctx, msg.Value); err == nil { break } ... }` ретраит именно этот `msg`, не трогая курсор дальше. Уведомление Б будет ждать, пока не решится судьба сообщения гостя А. Для темпа «несколько уведомлений в день» это осознанно принятый риск, а не то, что можно исправить только выбором другой библиотеки."
        - "PITFALL (найден финальным ревью, было реально сломано в первой версии кода): интуитивно кажется, что `continue` в начало `for ctx.Err() == nil` после ошибки `handle` «повторяет то же сообщение» на следующей итерации, ведь `CommitMessages` для него так и не вызывался. Это неверно для kafka-go: `(*kafkago.Reader).FetchMessage` продвигает позицию чтения ридера В ПАМЯТИ сразу в момент вызова, независимо от того, был ли потом коммит — коммит лишь фиксирует эту позицию во внешнем хранилище (consumer group offset), а не управляет тем, какое сообщение отдаст следующий `FetchMessage`. Значит, `continue` без внутреннего ретрая вызывает `FetchMessage` заново — и получает СЛЕДУЮЩЕЕ сообщение партиции, а не то же самое; сбойное сообщение (например, с OTP-кодом) исчезает из процесса безвозвратно, а ошибка `CommitMessages`, случись она позже уже для следующего сообщения, ещё и «похоронит» пропавшее — при рестарте consumer group offset уже указывает дальше него. Урок: коммит offset контролирует, ЧТО читать после рестарта, а не то, какое сообщение вернёт следующий вызов FetchMessage В ТЕКУЩЕМ процессе — эти две вещи легко перепутать, доверяя названию метода `CommitMessages`, а не документации `reader.go`. Правильный fix — ретраить именно уже полученное значение `msg` во внутреннем цикле, коммитя офсет только после его успешной обработки."
  bugs_and_lessons:
    - "CRITICAL, найден на Task 3: `OtpService.verify` инкрементировал `attempts` внутри той же `@Transactional(propagation = MANDATORY)` транзакции, что и весь `confirm`. Пока имплементер писал тест `thirdWrongAttemptExpiresChallenge`, он поставил `@Transactional` на КЛАСС теста (`OtpServiceTest`) — это заставило все отдельные вызовы через `TransactionTemplate` жить внутри одной физической транзакции теста, и UPDATE-инкремент попыток стал виден между вызовами. Тест позеленел. В проде же каждый HTTP-запрос — своя отдельная транзакция: при неверном коде `InvalidCodeException` (RuntimeException) откатывает ВСЮ транзакцию, включая только что сделанный `UPDATE attempts` — счётчик попыток физически не мог накопиться выше 1 ни при каком числе реальных запросов, лимит «3 попытки» не работал вообще, перебор кода был безлимитным. Ревью (sonnet) не поверило зелёному тесту, воспроизвело гипотезу экспериментом (сняло классовую `@Transactional`) и получило именно тот провал, который предсказывало. Исправление — `PROPAGATION_REQUIRES_NEW` для инкремента попыток и для пометки `EXPIRED` (`OtpService.requiresNew`, `expireInNewTx`): эти две операции коммитятся в СВОИХ отдельных транзакциях и переживают откат внешней транзакции confirm, а тест-класс лишился классовой `@Transactional`, чтобы воспроизводить прод-границу, а не маскировать её. Мораль в двух слоях: RuntimeException в @Transactional-методе откатывает ВСЁ, включая счётчики, которые логически должны быть неоткатываемыми; а зелёный тест сам по себе не доказательство корректности, если конфигурация теста (здесь — классовая @Transactional) отличается от прод-границы транзакций, которую тест должен проверять."
    - "Task 1: контролёр отправил ревьюеру ревью-бриф с утверждением о несовпадении числа тестов («было 55, стало 56» против заявленных имплементером 55) — и это оказалось ложной предпосылкой самого контролёра: до задачи было 54 теста, а не 55, так что «Tests run: 55» имплементера было полностью корректно. Флаг сняли, реального расхождения не было. Мораль: предпосылки, зашитые в сам текст задания или ревью-брифа контролёром, — тоже источник ошибок наравне с ошибками имплементера; их стоит проверять так же, как и код, а не принимать на веру только потому, что их сформулировал контролирующий, а не исполняющий агент."
    - "Task 7: единственная находка ревью (haiku) — импорт `tools.jackson.databind.ObjectMapper` вместо `com.fasterxml.jackson...`, названный ошибкой. Формально в мире Jackson 2 это была бы правда: пакет `tools.jackson` не существует. Но проект (начиная с этапа 2) сидит на Spring Boot 4, который перешёл на Jackson 3 — а Jackson 3 переименовал корневой пакет именно в `tools.jackson`. Контролёр отклонил находку, перепроверив факты проекта: `grep` по всему backend-api дал 9 файлов с `tools.jackson` и 0 с `com.fasterxml`, `mvnw compile` прошёл с exit 0, полный набор тестов зелёный дважды. Мораль: у ревьюера (человека или модели) может быть устаревшее обучающее знание о версии библиотеки, которое было верным год назад и разошлось с фактическим состоянием именно этого проекта — при конфликте \"я помню, что так правильно\" против \"так уже реально работает и компилируется в этом репозитории\" выигрывать должен факт репозитория, а не общая эрудиция."
  prerequisites: [transactional-outbox, at-least-once-idempotency]
---

# Этап 4: Бронирование с OTP end-to-end

Разбор того, что мы собрали в этапе 4 — гость сам создаёт, подтверждает, переносит
и отменяет бронь, каждое реальное изменение проходит через одноразовый код из
Telegram. Java-сторона — в `backend-api/src/main/java/com/batowka/guestbooking/booking/`
и `.../otp/`, Go-сторона — новые рендеры и гарантия доставки в
`bot-service/internal/kafka/consumer.go`. Спека этапа —
`docs/specs/2026-08-19-stage-4-booking-otp-design.md`; там же лежат формулировки
решений, на которые ссылается прозиа ниже. Ссылки — на реальные файлы, чтобы
можно было открыть рядом и сверить.

## 1. Конечные автоматы статусов и атомарные переходы

У брони всего три статуса — `PENDING_OTP`, `CONFIRMED`, `CANCELLED`
(`BookingStatus.java`), и всего два разрешённых маршрута: `PENDING_OTP →
CONFIRMED → CANCELLED` (обычный путь: создали, подтвердили, когда-нибудь
отменили) и `PENDING_OTP → CANCELLED` напрямую (гость передумал до
подтверждения, или бронь протухла и её забрал чистильщик). Никакого
отдельного класса `BookingStateMachine` в коде нет — «автомат» существует не
как структура данных, а как соглашение: единственный законный способ сменить
статус — это SQL вида `UPDATE bookings SET status = <новый> WHERE id = ? AND
status = <ожидаемый>`, и вызывающий код ОБЯЗАН проверить, сколько строк
реально обновилось.

Причина в том, что за право сменить статус одной и той же брони конкурируют
как минимум два независимых актора, которые ничего не знают друг о друге:
гость, подтверждающий OTP-код прямо сейчас, и `PendingBookingCleaner`,
который просыпается каждые две минуты и ищет протухшие брони по расписанию
(тема 5). Если бы код сначала читал статус (`SELECT status FROM bookings
WHERE id = ?`), затем в Java-коде решал, что делать, и только потом писал
новый статус — между чтением и записью мог поместиться чужой UPDATE, и оба
актора решили бы, что именно они первыми поменяли статус. `UPDATE ... WHERE
status = ...` убирает это окно целиком: Postgres выполняет проверку условия
и саму запись как одну неделимую операцию, и из двух одновременных UPDATE с
одинаковым WHERE ровно один реально что-то поменяет, а второй увидит 0
задетых строк — без единой явной блокировки с нашей стороны.

Вот как это выглядит в реальном коде подтверждения брони:

```java
int updated = jdbc.update("""
        update bookings set status = 'CONFIRMED'
        where id = ? and status = 'PENDING_OTP'
        """, bookingId);
if (updated == 0) {
    throw new BookingExpiredException();
}
```

`updated == 0` здесь — это не оборонительное программирование «на всякий
случай», а рабочий, тестируемый сценарий: гость мог успеть ввести код ровно
в ту секунду, когда `PendingBookingCleaner` уже отменил его же бронь как
протухшую (её `created_at` было старше 5 минут, а живого челленджа
чистильщик не увидел). Гость в этом случае получает честный 409
`BOOKING_EXPIRED`, а не ложное подтверждение несуществующей брони. Тот же
приём, только с обратной стороны гонки, в `PendingBookingCleaner.cleanExpired`:
чистильщик тоже пишет `where b.status = 'PENDING_OTP'` — если гость успел
подтвердить бронь долей секунды раньше, чистильщик просто не найдёт эту
строку среди подходящих под условие и не тронет уже подтверждённую бронь.

Схема БД дублирует это же правило на своём уровне — `CHECK (status IN
('PENDING_OTP', 'CONFIRMED', 'CANCELLED'))` в `V1__init.sql` не даст записать
в столбец что-то пятое, даже если в Java-коде когда-нибудь появится опечатка.

> **Разбор кода:** открой `backend-api/src/main/resources/db/migration/V1__init.sql`
> — найди `CHECK (status IN (...))` и `one_confirmed_booking_per_user`. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java`
> — смотри `confirmCreate`, конкретно UPDATE с `where ... and status =
> 'PENDING_OTP'` и проверку `updated == 0`. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/booking/PendingBookingCleaner.java`
> — смотри `cleanExpired` и тот же приём с другой стороны гонки. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingStatus.java`
> — смотри, что это просто enum из трёх значений, без какой-либо логики
> переходов внутри.

## 2. OTP-безопасность: хеш, попытки до сравнения, неинформативные ошибки

Шестизначный код подтверждения генерируется `SecureRandom`-ом и живёт в БД не
сам по себе, а как BCrypt-хеш в колонке `code_hash`:

```java
String code = String.format("%06d", random.nextInt(1_000_000));
jdbc.update("""
        insert into otp_challenges(user_id, action, payload, code_hash, expires_at)
        values (?, ?, ?::jsonb, ?, now() + interval '5 minutes')
        """, user.getId(), action,
        objectMapper.writeValueAsString(payload), encoder.encode(code));
outbox.write("notifications.outbound", "OTP_CODE", Map.of(
        "chat_id", user.getTelegramChatId(),
        "code", code, "action", action, ...));
```

Сырой код существует ровно один раз за пределами памяти метода `issue` — в
payload события `OTP_CODE`, которое улетает через outbox в Telegram и больше
никуда не попадает; в базе, включая любой её бэкап или дамп, остаётся только
хеш. Это тот же принцип, что и для пароля: пусть код живёт всего 5 минут,
утечка таблицы `otp_challenges` не должна превращаться в список работающих
кодов.

Второй слой защиты — счётчик попыток инкрементируется ДО сравнения кода, а не
после:

```java
Integer attempts = requiresNew.execute(s -> jdbc.queryForObject(
        "update otp_challenges set attempts = coalesce(attempts, 0) + 1 where id = ? returning attempts",
        Integer.class, id));
if (attempts != null && attempts > MAX_ATTEMPTS) {
    expireInNewTx(id);
    throw new CodeExpiredException();
}
if (!encoder.matches(code, (String) row.get("code_hash"))) {
    ...
}
```

Если бы инкремент стоял внутри `if (!matches)` — то есть засчитывался бы
только на неправильный код, — параллельный перебор мог бы слать несколько
запросов одновременно и обойти лимит: несколько параллельных проверок
успели бы одновременно прочитать «попыток пока 0» до того, как хоть одна из
них записала бы инкремент. Инкремент до сравнения избавляет от этого окна —
неважно, верный код или нет, счётчик растёт первым же действием. Почему это
вообще написано через отдельный `requiresNew` (`PROPAGATION_REQUIRES_NEW`), а
не просто `jdbc.update(...)` внутри общей транзакции — отдельная история, и
она попала не в раздел решений, а в раздел настоящих багов ниже: это был
критический баг этапа, а не решение с самого начала.

Третий слой — сами ошибки. `GlobalExceptionHandler` возвращает три разных
кода: `INVALID_CODE` (неверный код или истёкшее по времени время жизни),
`CODE_EXPIRED` (попытки исчерпаны, нужен `resend`), `NO_ACTIVE_CODE` (для
этой брони вообще нет активного челленджа — например, код уже был для
другой операции). Разница между ними существует для честного гостя, который
запутался, а не для того, чтобы подсказать перебирающему, сколько попыток у
него осталось: ни один из трёх текстов не говорит «осталась 1 попытка» или
«код истёк 12 секунд назад». И наконец, сам `verify` принимает `bookingId`
как обязательный параметр и ищет челлендж с условием `(payload->>'booking_id')::bigint
= ?` — код, выпущенный для одной брони, физически не подходит для
подтверждения другой, что явно проверено тестом `codeForAnotherBookingIsNotAccepted`.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/otp/OtpService.java` —
> смотри `issue` (генерация кода, `encoder.encode`, `outbox.write`) и `verify`
> (инкремент через `requiresNew`, затем `encoder.matches`). Открой
> `backend-api/src/main/java/com/batowka/guestbooking/common/GlobalExceptionHandler.java`
> — смотри три хендлера `invalidCode` / `codeExpired` / `noActiveCode` и их
> тексты. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/otp/OtpServiceTest.java`
> — смотри `codeForAnotherBookingIsNotAccepted`.

## 3. Паттерн «замена вместо отказа»: willReplaceBooking

У гостя может быть максимум одна подтверждённая (`CONFIRMED`) бронь —
это буквально записано в схеме частичным уникальным индексом
`one_confirmed_booking_per_user`. Но когда гость с уже подтверждённой бронью
создаёт новую (например, хочет поменять даты через «создать заново», а не
через явный перенос), `POST /api/bookings` НЕ отклоняет запрос. Вместо этого
`BookingService.create` находит текущую подтверждённую бронь и кладёт её в
ответ отдельным полем-подсказкой:

```java
WillReplace willReplace = bookings
        .findFirstByUserIdAndStatusOrderByIdDesc(userId, BookingStatus.CONFIRMED)
        .map(b -> new WillReplace(b.getId(), b.getCheckIn(), b.getCheckOut()))
        .orElse(null);
return new CreateResult(bookingId, willReplace);
```

Фронт получает `willReplaceBooking {id, checkIn, checkOut}` и может честно
предупредить: «после подтверждения новой брони старая будет отменена». Сама
замена происходит только при подтверждении кодом — атомарно, внутри одной
транзакции `confirm`, и порядок здесь не косметика:

```java
bookings.findFirstByUserIdAndStatusOrderByIdDesc(user.getId(), BookingStatus.CONFIRMED)
        .ifPresent(old -> {
            int n = jdbc.update("""
                    update bookings set status = 'CANCELLED', cancelled_by = 'GUEST'
                    where id = ? and status = 'CONFIRMED'
                    """, old.getId());
            if (n == 1) notifyBookingEvent(user, "BOOKING_CANCELLED", ...);
        });
int updated = jdbc.update("""
        update bookings set status = 'CONFIRMED'
        where id = ? and status = 'PENDING_OTP'
        """, bookingId);
```

Сначала СТАРАЯ бронь становится `CANCELLED`, и только ПОТОМ новая становится
`CONFIRMED`. Если бы порядок был обратным, вторая UPDATE попыталась бы
создать вторую строку со статусом `CONFIRMED` для того же `user_id`, пока
первая всё ещё жива — и `one_confirmed_booking_per_user` отверг бы саму
операцию как нарушение уникальности, вместо того чтобы тихо провести замену.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java`
> — смотри `create` (поле `willReplace`) и `confirmCreate` (порядок двух
> UPDATE). Открой `backend-api/src/main/resources/db/migration/V1__init.sql`
> — смотри `one_confirmed_booking_per_user`, констрейнт, который и требует
> этого порядка.

## 4. Вариант A переноса: честный 409 вместо удержания дат

Перенос (`PATCH /api/bookings/{id}`) устроен иначе, чем можно было бы
ожидать: сам запрос PATCH только выпускает OTP-код с новыми датами внутри
`payload` челленджа — он НИКАК не резервирует эти даты в БД. Спека называет
это прямо: «Даты переноса ДО подтверждения не удерживаются (решение
владельца: вариант A — честный 409 при гонке)». Если, пока гость вводит код
из Telegram, кто-то другой успевает занять эти же даты, `applyReschedule`
ловит нарушение того же EXCLUDE-констрейнта, что защищает от пересечений
броней вообще, и откатывает подтверждение целиком:

```java
try {
    updated = jdbc.update("""
            update bookings set check_in = ?, check_out = ?
            where id = ? and status = 'CONFIRMED'
            """, in, out, bookingId);
} catch (DataIntegrityViolationException e) {
    // Даты заняли за 5 минут — вариант A. Исключение откатывает ВСЮ
    // транзакцию confirm, включая пометку челленджа USED: челлендж
    // остаётся PENDING, гость может запросить новый перенос...
    throw new DatesTakenException();
}
```

Комментарий в самом коде проговаривает то, ради чего это написано именно
так: `DatesTakenException` — это `RuntimeException`, и она обязана долететь
до внешней `@Transactional` на `confirm`, чтобы откатить не только несостоя
вшееся изменение дат, но и пометку челленджа как `USED`, которую `otp.verify`
успел сделать чуть раньше в той же транзакции. Если бы кто-то «упростил» код
и проглотил исключение внутри `applyReschedule`, не перебросив его дальше,
челлендж закоммитился бы как использованный при провалившемся переносе —
гость получил бы код, который больше никогда не сработает, а бронь осталась
бы на старых датах без возможности повторить попытку тем же кодом.

Отвергнутая альтернатива — вариант Б — предлагала бы при самом PATCH сразу
создавать вторую строку `bookings` со статусом `PENDING_OTP` на новые даты:
тогда EXCLUDE-констрейнт держал бы эти даты занятыми с момента запроса
переноса, а не с момента подтверждения. Цена варианта Б — по сути
дублирование всей логики замены из темы 3 (две связанные строки одной
брони, синхронизация их статусов при протухании, отмене, подтверждении)
ради устранения довольно редкого окна гонки. Вариант A даёт тот же
результат для гостя — либо перенос состоялся, либо честный 409 `DATES_TAKEN`
— ценой того, что при проигранной гонке гость должен запросить перенос
заново, а не автоматически получить вторую попытку.

> **Разбор кода:** открой
> `docs/specs/2026-08-19-stage-4-booking-otp-design.md` — раздел §4,
> формулировка «вариант A». Открой
> `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java`
> — смотри `applyReschedule`, `catch (DataIntegrityViolationException e)` и
> комментарий рядом. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/booking/RescheduleCancelTest.java`
> — смотри `rescheduleRaceGives409AndKeepsOldDates`. Открой
> `docs/specs/2026-08-13-japan-guest-booking-design.md` — §8, строка «Гонка
> за даты → exclusion constraint, второму 409» — общий принцип родительской
> спеки, который этот раздел конкретизирует для переноса.

## 5. Фоновые задачи: идемпотентный чистильщик и триггер updated_at

`PendingBookingCleaner` просыпается каждые 2 минуты (`@Scheduled(fixedDelay =
120_000)`) и одним batch-запросом отменяет все протухшие висящие брони разом
— без цикла «выбрать id → отменить по одному»:

```java
int n = jdbc.update("""
        update bookings b set status = 'CANCELLED'
        where b.status = 'PENDING_OTP'
          and b.created_at < now() - interval '5 minutes'
          and not exists (
              select 1 from otp_challenges c
              where (c.payload->>'booking_id')::bigint = b.id
                and c.status = 'PENDING' and c.expires_at > now())
        """);
```

Условие устроено так, что бронь считается протухшей только если она старше
5 минут И у неё НЕТ живого челленджа — живого не только по статусу
(`c.status = 'PENDING'`), но и по времени (`c.expires_at > now()`). Оба
условия важны: если бы проверялся только статус без времени, чистильщик
держал бы бронь живой сколько угодно после того, как её код физически
истёк с точки зрения `OtpService.verify` — гость не смог бы ни подтвердить
(код истёк), ни создать заново (даты всё ещё заняты его же протухшей
бронью). Пятиминутный порог `created_at` не случайно совпадает с TTL
челленджа (`OtpService.TTL = Duration.ofMinutes(5)`) — это согласованная
пара констант: пока код гостя теоретически ещё может сработать, чистильщик
бронь не тронет, даже если сама бронь формально старше 5 минут по времени
создания (что бывает при `resend`, продлевающем свежесть челленджа, но не
дату создания брони).

Запрос идемпотентен по своей природе — повторный запуск того же UPDATE над
уже отменёнными бронями просто не найдёт подходящих строк (`status =
'PENDING_OTP'` уже не выполняется), поэтому два одновременных запуска или
пропущенный сбой не приводят к двойной обработке. Тесты проверяют оба края
этого условия:

```java
@Test
void cleanerLeavesFreshPendingAlone() throws Exception {
    Long id = guest("+81350000004", 777404L);
    long bookingId = createBooking(id, "2027-08-01", "2027-08-05");

    cleaner.cleanExpired();

    assertThat(jdbc.queryForObject(
            "select status from bookings where id = ?", String.class, bookingId))
            .isEqualTo("PENDING_OTP");
}
```

Рядом с этим — миграция `V2__bookings_updated_at_trigger.sql`, перенесённая
из финального ревью этапа 1: триггер на уровне Postgres, а не аннотация
`@UpdateTimestamp` в JPA. Причина ровно в том, что видно во всех примерах
кода выше — почти все статусные переходы этого этапа идут через
`JdbcTemplate.update(...)` в обход JPA-сущности `Booking` целиком, а
`@UpdateTimestamp` сработал бы только на путях через `repository.save()`,
которых здесь почти нет.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/booking/PendingBookingCleaner.java`
> — смотри `cleanExpired` целиком, особенно `NOT EXISTS`-подзапрос. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/booking/CleanerAndResendTest.java`
> — смотри `cleanerCancelsStalePendingAndFreesDates` и
> `cleanerLeavesFreshPendingAlone`. Открой
> `backend-api/src/main/resources/db/migration/V2__bookings_updated_at_trigger.sql`
> — смотри сам триггер и комментарий про этап 1.

## 6. Надёжная доставка в боте: remember/commit только после успеха

Этап 3 оставил в бэклоге риск: если `bot-service` падает ровно между
отправкой сообщения и коммитом offset/записью в дедуп-память, сообщение
может либо потеряться, либо задвоиться в зависимости от порядка операций.
Для `WELCOME`-сообщений это было приемлемо — повторное приветствие ничего не
портит. Для OTP-кода цена другая: гость, не получивший код, физически не
может подтвердить бронь. Спека этапа 4 прямо требует перенести исправление
из бэклога:

```go
func (c *consumerCore) send(ctx context.Context, eventID string, chatID int64, text string) error {
    if err := c.sender.SendMessage(ctx, chatID, text, false); err != nil {
        return err
    }
    c.remember(eventID)
    return nil
}
```

`remember` (дедуп-память `seen map[string]bool`) вызывается СТРОГО после
успешного `SendMessage` — если отправка вернула ошибку, `eventID` вообще не
запоминается. На уровне Kafka-транспорта логика похожа по духу (коммит
только после успеха), но устроена тоньше, чем кажется на первый взгляд —
и именно здесь финальное ревью поймало реальный баг в первой версии кода.

Первая, наивная версия `Consumer.Run` при сбое `handle` делала `continue` в
начало внешнего цикла:

```go
// НЕПРАВИЛЬНО — так было до финального ревью:
msg, _ := c.reader.FetchMessage(ctx)
if err := c.core.handle(ctx, msg.Value); err != nil {
    log.Printf("обработка события: %v — повтор через 3с", err)
    time.Sleep(3 * time.Second)
    continue // возвращает в начало for — но НЕ повторяет msg!
}
c.reader.CommitMessages(ctx, msg)
```

Интуиция подсказывает: раз `CommitMessages` для `msg` не вызывался, offset
не продвинулся, значит следующий `FetchMessage` вернёт то же самое `msg`
снова. Это ложно для kafka-go. `(*kafkago.Reader).FetchMessage` продвигает
позицию чтения ридера **в памяти** сразу в момент вызова — до, а не после
коммита. Коммит лишь публикует эту позицию во внешний consumer-group offset
(на случай рестарта); он не управляет тем, что вернёт следующий вызов
`FetchMessage` в уже работающем процессе. Поэтому `continue` без внутреннего
повтора вызывает `FetchMessage` заново — и получает СЛЕДУЮЩЕЕ сообщение
партиции, а сбойное (например, с OTP-кодом гостя) исчезает из процесса
безвозвратно. Если позже `CommitMessages` всё же случится для какого-то
следующего сообщения, пропавшее будет ещё и «похоронено»: после рестарта
consumer group offset уже указывает дальше него, перечитать его неоткуда.

Исправленная версия ретраит именно уже полученное значение `msg` во
внутреннем цикле — второй, вложенный `for` — и коммитит offset только после
его успешной обработки:

```go
for ctx.Err() == nil {
    msg, err := c.reader.FetchMessage(ctx)
    if err != nil {
        // ... повтор FetchMessage через retryBackoff, как и раньше ...
        continue
    }
    for {
        if err := c.core.handle(ctx, msg.Value); err == nil {
            break
        }
        log.Printf("обработка события: %v — повтор через %s", err, c.retryBackoff)
        if c.sleepOrDone(ctx) {
            return
        }
    }
    if err := c.reader.CommitMessages(ctx, msg); err != nil {
        log.Printf("kafka commit: %v", err)
    }
}
```

Здесь `continue` внешнего цикла остаётся только для сбоя самого
`FetchMessage` (нет соединения с брокером и т.п.) — там ещё нет полученного
`msg`, повторять нечего, кроме самого запроса. А для сбоя `handle` теперь
есть отдельный внутренний `for`, который крутится вокруг ОДНОГО И ТОГО ЖЕ
`msg`, пока `handle` не вернёт `nil`; `CommitMessages(ctx, msg)` вызывается
один раз, уже после успеха, для этого же значения `msg`. Пауза между
попытками вынесена в поле `Consumer.retryBackoff` (по умолчанию 3 секунды)
именно для тестируемости — тесту не нужно ждать реальные 3 секунды.

Практическое следствие для head-of-line blocking то же самое, что и
задумывалось: `Consumer.Run` не читает следующее сообщение партиции, пока
текущее не обработано без ошибки. Если у одного гостя доставка сломана
навсегда (например, он заблокировал бота), его сообщение будет повторяться
каждые `retryBackoff` бесконечно и не пропустит вперёд себя ничьи другие
уведомления — включая OTP-коды других гостей — в том же топике
`notifications.outbound`. Спека признаёт эту цену явно: «Принятая цена:
перманентный сбой отправки одного сообщения блокирует очередь уведомлений».
С единственной партицией и единственным консьюмером (решение этапа 3) это
касается вообще всех, кто ждёт уведомления, а не только застрявшего гостя —
но для темпа «несколько уведомлений в день на семью гостей» эта цена
признана приемлемой ради главной гарантии: код подтверждения никогда не
теряется молча. Разница с наивной версией не в самом трейдоффе — он тот же,
принят сознательно, — а в том, что теперь блокируется действительно ОДНО
конкретное застрявшее сообщение, а не «какое-то, а застрявшее тем временем
потерялось».

Тесты воспроизводят гарантию на двух уровнях. Уровень `consumerCore` — не
«сообщение дошло», а «сообщение не потерялось и не задвоилось при сбое и
повторе»:

```go
func TestFailedSendIsRetriedNotDeduplicated(t *testing.T) {
    sender := &flakySender{failFirst: true}
    ...
}
```

Уровень `Consumer.Run` — это отдельный тест именно на баг из этого раздела:
он проверяет не логику `handle` (она уже покрыта выше), а то, что сам цикл
`Run` ретраит ОДНО сообщение, а не переходит к следующему. Для этого
`Consumer.reader` определён через маленький интерфейс `kafkaReader`
(`FetchMessage`/`CommitMessages`/`Close`), а не как конкретный
`*kafkago.Reader` — прод-код передаёт настоящий ридер, тест подставляет
`fakeReader`:

```go
func TestRunRetriesSameMessageUntilSuccess(t *testing.T) {
    sender := &countingFlakySender{failTimes: 2}
    reader := &fakeReader{msg: kafkago.Message{Value: welcomeJSON("e-run-retry")}}
    c := &Consumer{reader: reader, core: newConsumerCore(sender), retryBackoff: time.Millisecond}
    ...
}
```

`fakeReader.FetchMessage` отдаёт сообщение один раз и после этого блокируется
до отмены контекста — если бы код регрессировал к наивному `continue`, тест
увидел бы второй вызов `FetchMessage` ДО коммита (запрос следующего
сообщения вместо повтора текущего) и упал бы на явном счётчике
`fetchCalls`.

> **Разбор кода:** открой
> `docs/specs/2026-08-19-stage-4-booking-otp-design.md` — §5, абзац «Перенос
> из бэклога этапа 3». Открой `bot-service/internal/kafka/consumer.go` —
> смотри `consumerCore.send` (`remember` после успеха), интерфейс
> `kafkaReader` и `Consumer.Run` (вложенный `for` вокруг `msg`, коммит после
> него). Открой `bot-service/internal/kafka/consumer_test.go` — смотри
> `flakySender` / `TestFailedSendIsRetriedNotDeduplicated` (уровень
> `consumerCore`) и `fakeReader` / `TestRunRetriesSameMessageUntilSuccess`
> (уровень `Run`).

## Итог этапа

Все шесть тем держатся на одной и той же идее с разных сторон: не давать
двум независимым процессам — гостю и чистильщику, гостю и другому гостю,
успешной и неуспешной попытке доставки — расходиться во мнении о текущем
состоянии системы. На уровне БД это `UPDATE ... WHERE status = ...` и
EXCLUDE-констрейнты; на уровне OTP — инкремент до сравнения и привязка кода
к конкретной операции; на уровне бота — commit только после реального
эффекта. Связывающий вопрос: какое из трёх решений этапа (замена вместо
отказа, вариант A переноса, remember-после-успеха) сознательно выбирает
более простую архитектуру ценой более редкого, но настоящего неудобства для
пользователя, а не наоборот?
