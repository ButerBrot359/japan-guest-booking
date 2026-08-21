---
tutor:
  stage: 8
  title: "Этап 6.6: ОТП на входе, инклюзивные диапазоны, замена брони"
  topics:
    - id: otp-login-reuse
      section: "Перенос ОТП на вход: issue/verifyByAction без изменений бота"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/otp/OtpService.java
          symbol: "issue(UserAccount user, String action, Map<String,Object> payload) / verifyByAction(Long userId, String action, String code)"
          concept: "и бронь, и вход выпускают/проверяют код через одни и те же два метода — единственное, что меняется между ними, это строка action ('CREATE_BOOKING' vs 'LOGIN'), которая едет прямым текстом в otp_challenges и в payload Kafka-события"
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/LoginService.java
          symbol: "requestCode(String rawPhone) — otp.issue(user, \"LOGIN\", Map.of()); verify(String rawPhone, String code) — otp.verifyByAction(user.getId(), \"LOGIN\", code)"
          concept: "LoginService — тонкая обёртка: находит гостя по телефону, дальше делегирует OtpService теми же вызовами, что раньше делал BookingService для подтверждения брони"
      quiz_seeds:
        - "Что в OtpService.issue() и verifyByAction() вообще не знает, для чего именно выпускается код — для входа или для брони?"
        - "Бот получает событие OTP_CODE с полем action. Почему backend не завёл отдельный тип события LOGIN_CODE вместо переиспользования OTP_CODE?"
        - "LoginService.findFriend() явно фильтрует по Role.FRIEND. Что случится, если убрать этот фильтр — кто сможет войти беспарольно?"
      decisions:
        - choice: "один универсальный OtpService с полем action вместо отдельного сервиса под каждый сценарий (вход, бронь, перенос, отмена, сброс пароля админа)"
          alternatives: "отдельный LoginOtpService со своей таблицей/логикой, не трогающий существующий otp_challenges"
          why: "механика кода одинакова везде: сгенерировать 6 цифр, захешировать, вытеснить старые PENDING-челленджи того же юзера, посчитать попытки, сравнить хеш — разница только в том, какой payload несёт код и что происходит после успешной проверки"
          price: "constraint otp_challenges_action_check (V7) приходится расширять при каждом новом сценарии использования кода — это ручная миграция, а не автоматическое масштабирование; зато логика перебора/протухания кода не дублируется по сервисам"
      pitfalls:
        - "LoginService.findFriend() отсекает Role.ADMIN явным .filter(u -> u.getRole() == Role.FRIEND) — не потому что у админа нет телефона, а потому что беспарольный вход по коду в Telegram не должен быть способом получить админский токен. Если убрать фильтр, любой, кто угадает или перехватит телефон админа и получит доступ к его Telegram, беспарольно войдёт с правами администратора — обычный пользовательский флоу входа стал бы дырой в админку."
    - id: inclusive-exclusion-constraint
      section: "Инклюзивный exclusion constraint: почему minusDays(1) исчез из всех вызовов"
      code_anchors:
        - path: backend-api/src/main/resources/db/migration/V8__inclusive_booking_range.sql
          symbol: "весь файл: отмена зависших PENDING_OTP → DROP/ADD CONSTRAINT no_overlapping_bookings EXCLUDE USING gist (daterange(check_in, check_out, '[]') WITH &&) WHERE (status = 'CONFIRMED')"
          concept: "третий аргумент daterange — это границы диапазона; было '[)' (check_in включительно, check_out НЕ включительно — гость съезжает утром, следующий может заехать в тот же день), стало '[]' (check_out тоже занят) — дом «выдыхает» день между гостями"
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/BookingRepository.java
          symbol: "findOverlapping — @Query(\"... where b.checkIn <= :to and b.checkOut >= :from\")"
          concept: "код в Java-запросе не изменился вообще — сравнение checkIn <= to и checkOut >= from математически одинаково работает и для '[)' и для '[]' диапазонов; вся семантика включительности живёт целиком в constraint'е БД, а не дублируется в JPQL"
      quiz_seeds:
        - "Раньше где-то в коде наверняка было check_out.minusDays(1), чтобы день выезда не считался занятым при сравнении дат. Куда он делся и почему стало можно его просто выбросить, а не переписать на minusDays(0)?"
        - "Гость выезжает 10 сентября, новый гость раньше мог заехать 10 сентября тем же днём. Что происходит с этой попыткой после V8?"
        - "Почему семантика включительности диапазона поменялась только в SQL exclusion constraint, а findOverlapping в Java остался буквально тем же кодом?"
      decisions:
        - choice: "инклюзивный '[]' диапазон в exclusion constraint — день выезда занят, между гостями всегда минимум сутки простоя"
          alternatives: "оставить полуоткрытый '[)' диапазон (день выезда свободен, гости могут сменяться день в день)"
          why: "решение владельца (не техническое): дому нужен день на уборку/выдох между гостями, а не мгновенная пересменка"
          price: "меньше доступных ночей в календаре при плотном бронировании — если гость выезжает 10-го, следующий может заехать не раньше 11-го, а не 10-го же; это осознанная потеря пропускной способности ради буфера"
      pitfalls:
        - "V8 заодно отменяет все зависшие PENDING_OTP брони одним UPDATE — потому что pending-флоу подтверждения кодом удалён этим же этапом, и чистильщика для зависших PENDING_OTP больше нет и не будет. Если бы эту строку забыли, такие брони остались бы в БД вечно застрявшими в статусе, которого сам код приложения больше никогда не создаёт и не читает."
    - id: booking-replace-transactional
      section: "Замена брони одним @Transactional-методом: отмена старой + вставка новой"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java
          symbol: "create(Long userId, LocalDate checkIn, LocalDate checkOut, String comment) — отмена старой CONFIRMED через jdbc.update(...), затем insert ... returning id в try/catch(DataIntegrityViolationException)"
          concept: "одна бронь на гостя одновременно (частичный уникальный индекс) — значит новая бронь физически не может существовать рядом со старой CONFIRMED; вместо отдельного эндпоинта «заменить бронь» code делает это одним методом: сначала снимает старую (CANCELLED), потом вставляет новую, и если INSERT упадёт на exclusion constraint — вся транзакция, включая отмену старой, откатится"
      quiz_seeds:
        - "Если INSERT новой брони упадёт из-за DataIntegrityViolationException (даты заняты кем-то другим), что произойдёт со старой бронью, которую метод уже пометил CANCELLED несколькими строками выше?"
        - "Почему отмена старой брони происходит ДО insert новой, а не после — какой constraint БД физически не пропустил бы обратный порядок?"
        - "completePastBooking(userId) вызывается первой строкой в create(). Что случится, если гость только что вернулся из поездки (CONFIRMED с check_out вчера) и тут же пытается забронировать новые даты?"
      decisions:
        - choice: "замена активной брони — один @Transactional метод create(), который сначала CANCELLED старую, потом INSERT новую; при конфликте констрейнта откатывается вся транзакция целиком"
          alternatives: "отдельный явный эндпоинт replaceBooking(oldId, newDates) или двухшаговый API (сначала DELETE, потом POST с отдельными HTTP-запросами)"
          why: "гостю не нужно знать про технические детали «сначала отмени, потом создай заново» — с точки зрения UI это одно действие «забронировать другие даты»; атомарность одной транзакции гарантирует, что гость не может остаться без брони вообще (промежуточное состояние между двумя отдельными HTTP-запросами исключено)"
          price: "create() стал длиннее и делает больше одной вещи (отмена + создание + два уведомления) — цена читаемости метода ради того, чтобы гарантия атомарности не размазывалась по нескольким эндпоинтам и клиентскому коду"
      pitfalls:
        - "Отмена старой брони — обычный jdbc.update с WHERE status = 'CONFIRMED' (условный UPDATE, тот же приём этапа 5), а не DELETE и не безусловный UPDATE. Если бы insert новой брони упал на exclusion constraint (кто-то другой уже занял даты), Spring откатывает всю @Transactional-транзакцию — старая бронь автоматически «воскресает» обратно в CONFIRMED, потому что откат отменяет и её UPDATE тоже. Без этого гость мог бы потерять старую бронь и не получить новую одновременно."
    - id: tailwind-ios-ux
      section: "UX-мелочи: Tailwind v4 preflight и автозум iOS"
      code_anchors:
        - path: frontend/src/index.css
          symbol: "button:not(:disabled) { cursor: pointer; }"
          concept: "Tailwind v4 preflight (сброс стилей) ставит всем кнопкам cursor: default вместо привычного pointer — раньше за курсор отвечал сам браузер, теперь это явный откат к ожидаемому поведению одним глобальным правилом"
        - path: frontend/src/components/LoginCard.tsx
          symbol: "className содержит text-base lg:text-sm на всех текстовых input (номер телефона, имя, сообщение заявки)"
          concept: "text-base — это 16px; мобильный Safari автоматически зумит страницу при фокусе на input с font-size меньше 16px, lg:text-sm возвращает более компактный десктопный размер, потому что зум касается только мобильных браузеров"
      quiz_seeds:
        - "Почему cursor: pointer понадобилось возвращать явным правилом только в этом этапе — раньше кнопки выглядели нормально без него?"
        - "Что увидит пользователь iPhone, если у input стоит text-sm (14px) вместо text-base (16px) при фокусе на поле?"
        - "Почему у одного и того же input два класса размера шрифта (text-base lg:text-sm), а не просто один фиксированный?"
      pitfalls:
        - "text-base lg:text-sm — это не косметика 'на мобиле покрупнее для читаемости', а конкретный порог браузера: 16px — граница, ниже которой мобильный Safari решает, что пользователю нужно приблизить страницу, чтобы видеть, что он печатает, и зумит весь вьюпорт при фокусе. lg: (десктоп) может позволить себе компактный text-sm, потому что автозум — чисто мобильное поведение."
  bugs_and_lessons:
    - "Инклюзивный диапазон (V8) сменил семантику 'какие дни принадлежат брони' в БД и в календарном ответе backend — но одно место во фронтенде осталось жить старой семантикой и тихо сломалось. `CalendarPage.handlePickBusy` (клик по чужому занятому дню — поповер «кто гостит») сканирует дни влево/вправо, пока `guestName` совпадает, и раньше довычислял `to` как `addDays(last, 1)` — потому что backend раньше метил `guestName` только на дни СТРОГО ДО check_out (полуоткрытый интервал), и чтобы показать корректную дату выезда в поповере, приходилось добавлять день руками. После V8 backend стал метить guestName на ВСЕ дни брони включительно, включая сам check_out — а фронтовый `addDays(last, 1)` остался и стал добавлять лишний, никому не принадлежащий день к диапазону в поповере. Баг тихий: не падает, не кидает ошибку — просто показывает даты выезда на день позже настоящих. Поймали и починили в том же PR (`e3c10c2`) через дополненный мок календаря в тесте (третий забронированный день в моке `CalendarPage.busy.test.tsx`, который раньше не давал тесту заметить off-by-one, потому что двух дней было недостаточно, чтобы разница между 'последний день с guestName' и 'последний день с guestName + 1' проявилась в assert). Мораль: смена включительности диапазона на бэкенде — это не только SQL и Java-запрос, это ещё и любой фронтовый код, который вычислял границы диапазона вручную под старую полуоткрытую семантику; grep по `addDays(..., 1)` и подобным местам стоит делать явно при такой миграции, а не полагаться на то, что тесты сами заметят."
  prerequisites: [lazy-completed-transition]
---

# Этап 6.6: ОТП на входе, инклюзивные диапазоны, замена брони

Этап 6.6 меняет три вещи разом: код из Telegram теперь подтверждает не бронь,
а сам вход (`/auth/login` → 202 + код, `/auth/verify` → HttpOnly-кука); день
выезда стал занятым днём в диапазоне брони (эксклюзивный constraint стал
инклюзивным, V8); и бронь гостя заменяется одним атомарным методом вместо
отдельного «сначала отмени — потом создай». Бэкенд-код — в
`backend-api/src/main/java/com/batowka/guestbooking/`, миграции —
`backend-api/src/main/resources/db/migration/`, фронт — `frontend/src/`;
спека этапа — `docs/specs/2026-08-21-stage-6.6-login-otp-ux-redesign-design.md`.
Этот разбор короткий и смещён в сторону интеграционных нюансов между
бэкендом, ботом и фронтом — сам фронт ты и так знаешь построчно.

## 1. Перенос ОТП на вход: issue/verifyByAction без изменений бота

Раньше код из Telegram подтверждал создание/перенос/отмену брони. Теперь тем
же кодом подтверждается вход — и это оказалось возможным сделать, не трогая
`OtpService` вообще: у него уже был универсальный контракт «выпустить код для
какого-то `action`» и «проверить код по `action`»:

```java
/** Выпускает код: вытесняет старые челленджи гостя, пишет OTP_CODE в outbox. */
@Transactional(propagation = Propagation.MANDATORY)
public void issue(UserAccount user, String action, Map<String, Object> payload) {
    ...
    outbox.write("notifications.outbound", "OTP_CODE", Map.of(
            "chat_id", user.getTelegramChatId(),
            "code", code,
            "action", action,
            "expires_at", Instant.now().plus(TTL).toString()));
}

/** Проверяет код активного челленджа гостя по типу действия (вход — action LOGIN). */
@Transactional(propagation = Propagation.MANDATORY)
public ChallengeResult verifyByAction(Long userId, String action, String code) {
    return verifyRow(findActiveByAction(userId, action), code);
}
```

`LoginService` — тонкая обёртка сверху, которая раньше даже не существовала:

```java
/** Шаг 1 входа: код в Telegram. Куку не выдаёт. */
@Transactional
public void requestCode(String rawPhone) {
    UserAccount user = findFriend(rawPhone);
    if (user.getTelegramChatId() == null) {
        throw new TelegramNotLinkedException();
    }
    otp.issue(user, "LOGIN", Map.of());
}

/** Шаг 2 входа: проверка кода. Возвращает JWT для куки. */
@Transactional
public String verify(String rawPhone, String code) {
    UserAccount user = findFriend(rawPhone);
    otp.verifyByAction(user.getId(), "LOGIN", code);
    return jwt.issue(user.getId(), user.getRole());
}
```

Единственное, что различает вход и подтверждение брони с точки зрения
`OtpService` — строка `action` ("LOGIN" вместо "CREATE_BOOKING" и так далее).
Именно поэтому боту вообще не пришлось переписывать логику получения кода —
он как получал `OTP_CODE`-событие из Kafka с полем `action` внутри payload,
так и получает, только раньше видел там `CREATE_BOOKING`/`RESCHEDULE`/`CANCEL`,
а теперь иногда `LOGIN`. Единственная правка со стороны бота — веткование
текста сообщения по этому полю (разобрано в этапе как отдельная задача,
здесь не рассматриваем).

Важная деталь `findFriend`:

```java
UserAccount findFriend(String rawPhone) {
    String phone = Phones.normalize(rawPhone).orElseThrow(InvalidPhoneException::new);
    // Роль ADMIN сюда не пускаем: беспарольный вход не должен выдавать админский токен
    return users.findByPhoneAndDeletedAtIsNull(phone)
            .filter(u -> u.getRole() == Role.FRIEND)
            .orElseThrow(UnknownPhoneException::new);
}
```

Беспарольный вход по коду доступен только `Role.FRIEND`. Это не техническое
ограничение `OtpService` — это сознательный барьер именно в `LoginService`,
чтобы новый лёгкий способ входа не стал побочным способом получить админский
токен.

> **Разбор кода:** `backend-api/src/main/java/com/batowka/guestbooking/otp/OtpService.java`
> — `issue` (строки 48-66), `verifyByAction` (строки 68-72). `backend-api/src/main/java/com/batowka/guestbooking/auth/LoginService.java`
> — весь файл (49 строк), особенно `findFriend` (строки 41-47).

## 2. Инклюзивный exclusion constraint: почему `minusDays(1)` исчез

Раньше диапазон брони в exclusion constraint был полуоткрытым — `[)`: день
заезда занят, день выезда свободен, поэтому следующий гость мог заехать в
тот же день, когда предыдущий выехал. Этап 6.6 меняет это по решению
владельца — дому нужен день простоя между гостями:

```sql
-- Дом должен «выдохнуть» день между гостями: бронь занимает [check_in, check_out]
-- ВКЛЮЧИТЕЛЬНО (решение владельца, этап 6.6). Заодно отменяем зависшие
-- PENDING_OTP-брони: флоу подтверждения кодом удалён, чистильщика больше нет.
UPDATE bookings SET status = 'CANCELLED', cancelled_by = 'GUEST'
WHERE status = 'PENDING_OTP';

ALTER TABLE bookings DROP CONSTRAINT no_overlapping_bookings;
ALTER TABLE bookings ADD CONSTRAINT no_overlapping_bookings EXCLUDE USING gist (
    daterange(check_in, check_out, '[]') WITH &&
) WHERE (status = 'CONFIRMED');
```

Третий аргумент `daterange(...)` — это границы: было `'[)'`, стало `'[]'`.
Postgres теперь сам считает `check_out` частью занятого диапазона при
пересечении с другими бронями — весь смысл «выезд занят» живёт в этой одной
строке SQL, а не в коде приложения.

Именно поэтому Java-запрос, ищущий пересекающиеся брони, не изменился ни на
символ:

```java
/**
 * Брони, пересекающие диапазон дней [from, to] включительно.
 * Бронь занимает [checkIn, checkOut] ВКЛЮЧИТЕЛЬНО (V8): checkIn <= to и checkOut >= from.
 */
@Query("""
        select b from Booking b join fetch b.user
        where b.status in :statuses
          and b.checkIn <= :to
          and b.checkOut >= :from
        """)
List<Booking> findOverlapping(...);
```

Сравнение `checkIn <= to and checkOut >= from` — это стандартная проверка
пересечения двух отрезков, и она работает одинаково для любой включительности
границ, потому что обе даты в запросе уже целые дни, а не моменты времени. До
V8 где-то в вызывающем коде наверняка приходилось делать `checkOut.minusDays(1)`
перед сравнением, чтобы компенсировать полуоткрытость диапазона руками — после
V8 в этом больше нет нужды: constraint в БД сам знает новую границу, а весь
код, который раньше подстраивался под старую, эту подстройку потерял за
ненадобностью.

> **Разбор кода:** `backend-api/src/main/resources/db/migration/V8__inclusive_booking_range.sql`
> — весь файл (10 строк). `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingRepository.java`
> — `findOverlapping` (строки 14-26), обрати внимание на обновлённый комментарий
> (строки 14-16), сам код запроса при этом дословно старый.

## 3. Замена брони одним `@Transactional`-методом

У гостя одновременно может быть только одна активная (`CONFIRMED`) бронь —
это гарантирует частичный уникальный индекс в БД. Когда гость бронирует новые
даты поверх уже существующей брони, `create()` не падает с «у тебя уже есть
бронь» — он молча заменяет старую на новую внутри одной транзакции:

```java
@Transactional
public CreateResult create(Long userId, LocalDate checkIn, LocalDate checkOut, String comment) {
    completePastBooking(userId);
    UserAccount user = requireTelegramLinked(userId);
    validateDates(checkIn, checkOut);
    datesLock.acquire();
    if (!blockedPeriods.findOverlapping(checkIn, checkOut).isEmpty()) {
        throw new DatesTakenException();
    }
    // сначала отмена старой CONFIRMED: освобождает частичный уникальный индекс
    // «одна CONFIRMED на гостя» и даты в exclusion constraint для новой брони
    bookings.findFirstByUserIdAndStatusOrderByIdDesc(userId, BookingStatus.CONFIRMED)
            .ifPresent(old -> {
                jdbc.update("""
                        update bookings set status = 'CANCELLED', cancelled_by = 'GUEST'
                        where id = ? and status = 'CONFIRMED'
                        """, old.getId());
                notifyBookingEvent(user, "BOOKING_CANCELLED",
                        old.getCheckIn(), old.getCheckOut(), "GUEST");
            });
    Long bookingId;
    try {
        bookingId = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status, comment)
                values (?, ?, ?, 'CONFIRMED', ?) returning id
                """, Long.class, userId, checkIn, checkOut, comment);
    } catch (DataIntegrityViolationException e) {
        // чужая бронь заняла даты — откат отменит и отмену старой брони
        throw new DatesTakenException();
    }
    notifyBookingEvent(user, "BOOKING_CONFIRMED", checkIn, checkOut, "GUEST");
    return new CreateResult(bookingId);
}
```

Порядок здесь обязателен и объяснён прямо в комментарии: сначала отмена
старой брони, потом вставка новой — потому что частичный уникальный индекс
«не больше одной CONFIRMED-брони на гостя» не пропустит INSERT, пока старая
CONFIRMED-строка ещё существует. А если после отмены старой брони INSERT
новой упадёт на `no_overlapping_bookings` (кто-то другой успел занять даты
первым), `DataIntegrityViolationException` ловится и превращается в
`DatesTakenException` — но сама Spring-транзакция при этом откатывается
целиком, а значит и `UPDATE ... status = 'CANCELLED'` для старой брони
откатывается вместе с ним. Гость не может оказаться в промежуточном
состоянии «старая бронь отменена, новой при этом нет» — либо обе операции
проходят вместе, либо ни одна.

> **Разбор кода:** `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java`
> — `create` (строки 56-90), особенно комментарии про порядок отмены/вставки
> (строки 67-68) и про откат (строка 85).

## 4. UX-мелочи: Tailwind v4 preflight и автозум iOS

Две маленькие, но конкретные технические детали, пойманные при полировке
формы входа. Первая — Tailwind v4 сбрасывает курсор кнопок в `default`
(обычная стрелка) вместо привычного `pointer` (рука), которым браузеры
исторически помечали кликабельные элементы:

```css
/* Tailwind v4 preflight ставит кнопкам cursor: default — возвращаем поинтер */
button:not(:disabled) {
  cursor: pointer;
}
```

Вторая — все текстовые `input` формы входа (номер телефона, код, имя,
сообщение заявки) держат класс `text-base lg:text-sm`, а не один фиксированный
размер:

```tsx
className="w-full rounded-xl border border-muted/40 bg-paper p-2 text-base lg:text-sm"
```

`text-base` — это 16px. Мобильный Safari при фокусе на `input` с размером
шрифта меньше 16px автоматически зумит весь вьюпорт — считает, что
пользователю иначе не разглядеть, что он печатает. `lg:text-sm` возвращает
более компактный десктопный размер только на широких экранах, где автозума
физически не бывает — мобильные и десктопные требования тут прямо
противоречат друг другу, поэтому у одного и того же поля два класса размера,
переключаемых брейкпоинтом, а не один универсальный.

> **Разбор кода:** `frontend/src/index.css` — правило `cursor: pointer` (строки
> 27-30). `frontend/src/components/LoginCard.tsx` — `text-base lg:text-sm` на
> инпуте телефона (строка 116) и полях заявки на доступ (строки 168, 174).

## Бонус-урок: off-by-one, когда бэкенд сменил включительность, а фронт — нет

Когда V8 сделал день выезда частью занятого диапазона, backend заодно начал
метить `guestName` в ответе `/api/calendar` на ВСЕ дни брони включительно —
раньше `guestName` стоял только на днях строго до `check_out`. Поповер «кто
гостит» во фронтенде компенсировал старую границу вручную:

```tsx
// было (до фикса):
setGuestInfo({ name, from, to: addDays(last, 1) })
```

`last` — последний день подряд с тем же `guestName`. При старой (полуоткрытой)
семантике день выезда не был помечен именем гостя, поэтому `+1` день
дописывался вручную, чтобы показать правильную дату отъезда. После V8
backend сам метит день выезда именем — а фронтовый `+1` остался и стал
показывать дату отъезда на день позже настоящей. Не падение, не исключение —
тихо неверная дата в поповере:

```tsx
// стало:
setGuestInfo({ name, from, to: last })
```

Поймали в том же PR через дополненный мок календаря в тесте — раньше в моке
было только два подряд занятых дня, и разница между «последний день с именем»
и «последний день с именем + 1» не давала assert'у споткнуться; третий день в
моке сделал off-by-one видимым.

> **Разбор кода:** `frontend/src/pages/CalendarPage.tsx` — `handlePickBusy`
> (строки 104-113), комментарий про включительность (строки 101-103).

## Вопросы для самопроверки

1. `OtpService.issue`/`verifyByAction` не менялись вообще при переносе ОТП на
   вход. Что именно в их сигнатуре позволило переиспользовать их без единой
   правки?
2. Диапазон в V8 сменил включительность с `'[)'` на `'[]'`, а Java-запрос
   `findOverlapping` не изменился ни на символ. Почему смена границы диапазона
   не потребовала правки условия `checkIn <= :to and checkOut >= :from`?
3. В `create()` старая бронь отменяется ДО вставки новой. Что случится с этим
   отменённым статусом, если вставка новой брони упадёт на exclusion
   constraint?
4. `text-base lg:text-sm` — зачем два класса размера шрифта на одном и том же
   `input`, а не один фиксированный?
5. Off-by-one в `handlePickBusy` не был пойман сразу — почему мок из двух
   занятых дней в тесте не показывал баг, а мок из трёх дней показал?

Сквозной вопрос: и перенос ОТП на вход (тема 1), и инклюзивный диапазон (тема
2) — это изменения, где вся новая логика уместилась в существующие точки
расширения (`action`-параметр, третий аргумент `daterange`), а не потребовала
новых сервисов или таблиц. А в теме про off-by-one (бонус-урок) видно
обратное: одно и то же изменение семантики (включительность диапазона) не
может остаться только серверным решением, если у клиента есть код, который
эту семантику дублирует вручную. Где ещё во фронте может прятаться похожий
вручную продублированный расчёт границы, которого ты пока не проверял после
V8?
