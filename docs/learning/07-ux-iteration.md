---
tutor:
  stage: 7
  title: "Этап 6.5: UX-итерация — optional auth, V6, ленивый COMPLETED"
  topics:
    - id: optional-auth-calendar
      section: "Optional auth в публичном эндпоинте: Authentication == null vs anonymous"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/calendar/CalendarController.java
          symbol: "getCalendar(..., Authentication auth) — Long viewerId = auth.getPrincipal() instanceof Long id"
          concept: "на permitAll-маршруте Spring Security НЕ отдаёт null в Authentication — аноним приходит как AnonymousAuthenticationToken с principal-строкой; instanceof Long — единственный надёжный способ отличить залогиненного от гостя"
        - path: backend-api/src/main/java/com/batowka/guestbooking/auth/SecurityConfig.java
          symbol: "requestMatchers(\"/api/calendar\", ...).permitAll()"
          concept: "маршрут открыт всем — но это не значит, что зрителя нельзя опознать: JWT-фильтр всё равно разбирает cookie ДО контроллера, если она есть, просто не требует её"
        - path: backend-api/src/main/java/com/batowka/guestbooking/calendar/CalendarService.java
          symbol: "getCalendar — живость зрителя: viewerId != null && users.findById(viewerId).filter(deletedAt == null).isEmpty() → viewerId = null"
          concept: "valid JWT ещё не значит «живой пользователь» — soft-удалённый гость с ещё не протухшей cookie не должен получать mine=true/имена; сервис перепроверяет живость на каждый запрос, а не доверяет токену"
      quiz_seeds:
        - "Почему в CalendarController нельзя было написать `if (auth == null)` и на этом остановиться — что бы сломалось для анонима?"
        - "Гость удалён (soft-delete), но его JWT-cookie ещё не истекла и физически валидна. Что увидит он в календаре — и почему, если сервис не перепроверяет `deletedAt`, это было бы дырой?"
        - "Почему проверку живости зрителя сделали в CalendarService, а не в JWT-фильтре один раз на весь запрос?"
      decisions:
        - choice: "Authentication всегда есть на permitAll-маршруте (AnonymousAuthenticationToken для гостя), различаем по типу principal через instanceof Long"
          alternatives: "сделать отдельный @Nullable параметр или кастомный резолвер аргумента, который явно возвращает null для анонима"
          why: "Spring Security и так подставляет Authentication в любой контроллер через SecurityContext — не нужен отдельный механизм, достаточно знать один факт фреймворка: анонимный principal — это строка \"anonymousUser\", а не null и не Long"
          price: "неочевидность для того, кто не помнit этот факт Spring Security: код, который выглядит как `auth != null`, был бы тихо неправильным (auth ВСЕГДА не null здесь), поэтому нужен именно `instanceof Long` — комментарий в коде существует не просто так"
      pitfalls:
        - "Соблазн — проверить `auth == null` как признак анонима. На permitAll-маршруте это условие никогда не сработает (Spring подставляет AnonymousAuthenticationToken), и код тихо решил бы, что зритель всегда залогинен, пытаясь достать `auth.getPrincipal()` как Long и упал бы ClassCastException на первом же анонимном запросе. Ловится только реальным HTTP-запросом без cookie — юнит-тест с моком Authentication==null его не поймает."
    - id: v6-column-to-child-table
      section: "Миграция «колонка → дочерняя таблица»: перенос данных в той же миграции"
      code_anchors:
        - path: backend-api/src/main/resources/db/migration/V6__user_greetings_and_completed.sql
          symbol: "весь файл: CREATE TABLE user_greetings → INSERT ... SELECT ... FROM users → ALTER TABLE users DROP COLUMN greeting"
          concept: "три шага одной Flyway-миграции в строгом порядке — таблица должна существовать раньше INSERT, а колонка должна пережить INSERT (иначе переносить нечего); DROP COLUMN — последний шаг, после того как данные уже в новом месте"
      quiz_seeds:
        - "Почему в V6 нельзя поменять местами INSERT и DROP COLUMN — что случится с данными, если DROP пойдёт первым?"
        - "Что произойдёт с существующими гостями, у которых `greeting` было NULL или пустой строкой, при этом INSERT — сколько строк в user_greetings они получат?"
        - "Почему это одна миграция, а не две отдельные (сначала таблица+перенос, отдельным PR — DROP COLUMN)?"
      decisions:
        - choice: "одна миграция V6 делает и создание таблицы, и перенос данных, и снос старой колонки атомарно"
          alternatives: "растянуть на две миграции/релиза: сначала завести user_greetings и писать в неё, оставив старую колонку greeting нетронутой; отдельным релизом снести колонку, когда убедились, что новый путь работает"
          why: "проект ещё не в проде под нагрузкой — это не online-миграция большой боевой БД, где двухфазный подход снижает риск отката; здесь можно и нужно сделать всё сразу и проще: DDL Postgres транзакционный, вся миграция — одна транзакция, либо применилась целиком, либо откатилась целиком"
          price: "если бы приложение уже крутилось в проде с трафиком, старый код (читающий колонку greeting) упал бы в момент между DROP и деплоем нового кода — двухфазный подход существует именно для того, чтобы этого избежать; здесь этой ценой сознательно пренебрегли, т.к. риск для учебного проекта минимален"
      pitfalls:
        - "`INSERT ... SELECT ... FROM users WHERE greeting IS NOT NULL AND btrim(greeting) <> ''` — если бы условие проверяло только `IS NOT NULL`, пустые строки `''` или строки из одних пробелов `'   '` превратились бы в мусорные приветствия ('' в user_greetings, которое потом реально показывалось бы гостю). `btrim(greeting) <> ''` — это именно защита от такого мусора при переносе, а не просто стилистика."
    - id: lazy-completed-transition
      section: "Ленивый переход в COMPLETED условным UPDATE вместо шедулера"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java
          symbol: "completePastBooking(Long userId) — jdbc.update(\"update bookings set status = 'COMPLETED' where user_id = ? and status = 'CONFIRMED' and check_out <= ?\", userId, LocalDate.now(JST))"
          concept: "не шедулер, не батч-джоба по крону — переход CONFIRMED → COMPLETED происходит прямо в момент, когда кто-то читает или пишет брони этого юзера; условный UPDATE (WHERE status = 'CONFIRMED') делает операцию идемпотентной: повторный вызов на уже COMPLETED-брони просто не находит строк"
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java
          symbol: "create() вызывает completePastBooking(userId) ДО вычисления willReplaceBooking — activeBooking() и updateComment() вызывают его тоже, каждый в начале"
          concept: "три независимых входа (create, чтение активной брони, смена комментария) — и в каждом первая строчка одна и та же; ленивый переход должен случиться раньше любого чтения статуса, иначе можно принять прошедшую поездку за ещё активную"
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java
          symbol: "activeBooking(Long userId) — @Transactional (БЕЗ readOnly = true)"
          concept: "метод раньше был readOnly = true (чистое чтение); как только внутрь добавили completePastBooking() — запись в БД — readOnly пришлось снять, иначе Hibernate/JDBC либо проигнорировал бы флаг непоследовательно, либо (в зависимости от драйвера/настроек) реально запретил бы запись в readOnly-транзакции"
      quiz_seeds:
        - "Почему UPDATE в completePastBooking содержит `and status = 'CONFIRMED'` в WHERE, а не просто `where id = ?`? Что случится, если вызвать этот метод дважды подряд на одной и той же брони?"
        - "Чем шедулер (крон раз в час, проходящий по всем броням) хуже или лучше ленивого перехода по чтению? В чём разница в задержке и в стоимости?"
        - "activeBooking() перестал быть readOnly, когда получил один-единственный write внутри. Почему нельзя было оставить readOnly = true и просто дать jdbc.update() выполниться «как получится»?"
      decisions:
        - choice: "ленивый переход статуса — UPDATE прямо в путях чтения/записи, без фонового процесса"
          alternatives: "шедулер (@Scheduled раз в N минут проходит по всем CONFIRMED с check_out <= today и переводит их скопом)"
          why: "у шедулера отдельный жизненный цикл (нужно поднимать, тестировать отдельно от бизнес-логики, следить, что он не залипнет), а бизнес-эффект перехода виден только когда кто-то реально смотрит на бронь этого юзера — значит можно посчитать его частью каждого такого чтения. Условный UPDATE к тому же атомарен: это тот же приём этапа 5 (rollback-only + условный UPDATE вместо exists-then-update), примененный тут к смене статуса"
          price: "статус в БД для юзера, который давно не заходил, может физически оставаться CONFIRMED сколько угодно после факта выезда — 'COMPLETED' это не факт, а ленивая проекция, которая материализуется только при следующем обращении именно этого юзера; для отчётов/админки, которые читают статус напрямую из БД, а не через BookingService, это может быть неожиданностью"
      pitfalls:
        - "Если бы `completePastBooking` вызывался ПОСЛЕ вычисления `willReplaceBooking` внутри `create()`, свежесозданная бронь могла бы «заменить» уже состоявшуюся (COMPLETED) поездку в глазах кода — история потерялась бы. Комментарий в коде (`create()`, строка 50-52 сегодня) явно фиксирует порядок именно поэтому."
    - id: dvh-mobile-safari
      section: "100vh vs 100dvh на мобильном Safari"
      code_anchors:
        - path: frontend/src/pages/CalendarPage.tsx
          symbol: "className: 'mx-auto max-w-md min-h-dvh ...'"
          concept: "`100vh` в мобильном Safari считается по высоте экрана БЕЗ адресной строки — при появлении/скрытии адресной строки контент либо обрезается, либо появляется мёртвая полоса; `dvh` (dynamic viewport height) пересчитывается под реальную видимую область"
      quiz_seeds:
        - "Чем `100dvh` отличается от `100vh` конкретно на мобильном Safari, когда юзер скроллит и адресная строка прячется?"
    - id: adaptive-month-grid
      section: "Адаптивная сетка месяцев + двойное монтирование с CSS-скрытием"
      code_anchors:
        - path: frontend/src/pages/CalendarPage.tsx
          symbol: "два блока: <div className=\"lg:hidden\"><Calendar months={mobileMonths} .../></div> и <div className=\"hidden lg:block\"><Calendar months={desktopMonths} .../></div>"
          concept: "мобильная и десктопная версии календаря — это два РАЗНЫХ React-дерева (разные месяцы: 2 месяца со свайпом vs 12 месяцев сеткой), смонтированных ОБА одновременно; какой видно — решает чистый CSS (`lg:hidden` / `hidden lg:block`), а не условный рендер по JS-брейкпоинту"
        - path: frontend/src/components/Calendar.tsx
          symbol: "grid gap-6 sm:grid-cols-2 xl:grid-cols-3"
          concept: "сетка месяцев внутри Calendar — это брейкпоинт-колонки (sm:2, xl:3), а не auto-fill/minmax; выбор брейкпоинтов, а не резинового auto-fill, обсуждался в спеке этапа"
      quiz_seeds:
        - "Зачем монтировать ОБА варианта календаря (мобильный и десктопный) и прятать один CSS'ом, а не рендерить один вариант условно по JS-проверке ширины окна?"
  bugs_and_lessons:
    - "Маска телефона `+7 (XXX) XXX-XX-XX` ловила баг ровно там, где о нём легче всего забыть — в round-trip контролируемого инпута. `value={formatPhone(digits)}` показывает уже отформатированную строку с префиксом `+7 (`; `onChange` получает `e.target.value` — то есть ПОЛНУЮ строку с этим префиксом плюс новый символ. Первая версия `phoneDigits` не срезала префикс перед разбором на цифры — и семёрка из `+7` при каждом нажатии попадала в цифры заново: набор `9990001122` вслепую давал `digits='7777777799'` (commit 0d09482). Второй раунд поймал соседний баг: 7/8-эвристика «11 цифр с ведущей 7/8 = вставка полного номера» применялась ДО среза префикса — и портила номера, которые сами по себе начинались на 7 или 9, когда пользователь допечатывал 11-й символ (commit d9e703c). Обе регрессии живут в `frontend/src/lib/phone.ts` (`phoneDigits`) и видны только если тест реально имитирует пользователя, печатающего по одному символу — `userEvent.type()` в `LoginCard.test.tsx`, а не `userEvent.paste()` или прямая установка value одним куском: паст не проходит через тот же путь `formatPhone(digits) + ch`, где рождается баг, поэтому маскирует именно этот класс ошибок. Мораль: для контролируемых инпутов с маской тест обязан печатать посимвольно, иначе тест зелёный, а баг живой."
  prerequisites: [httponly-cookie-front, dates-boundary]
---

# Этап 6.5: UX-итерация — optional auth, V6, ленивый COMPLETED, 100dvh

Этап 6.5 — доводка гостевого фронта и бэкенда под живое использование:
календарь научился показывать имена гостей только тем, кому можно, приветствие
стало набором вместо одной колонки, прошедшие брони перестали висеть CONFIRMED
навечно, а мобильная вёрстка поймала свои первые грабли реального Safari. Код
бэкенда — в `backend-api/src/main/java/com/batowka/guestbooking/`, фронта — в
`frontend/src/`; спека этапа —
`docs/specs/2026-08-21-stage-6.5-ux-iteration-design.md` (§9 — список тем этого
разбора). Бэкенд-темы разобраны подробно, фронт-темы — по паре абзацев: ты его
и так знаешь, интересна тут в основном граница с бэкендом и мобильными
особенностями.

## 1. Optional auth в публичном эндпоинте: Authentication == null vs anonymous

`/api/calendar` открыт всем — гостю не нужно логиниться, чтобы посмотреть,
какие даты заняты. Но если гость залогинен, календарь обязан вести себя
чуть иначе: показывать имя гостя рядом с занятыми им (и только его) датами
и отмечать `mine: true` на его собственных бронях. Значит, контроллеру нужно
знать «кто смотрит», даже когда смотреть может кто угодно.

Первый инстинкт — проверить `Authentication auth` на `null`. Это неверно, и
код в проекте специально об этом предупреждает:

```java
@GetMapping
public CalendarResponse getCalendar(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        Authentication auth) {
    // На permitAll-маршруте аноним приходит как AnonymousAuthenticationToken
    // с principal-строкой "anonymousUser" — поэтому обязателен instanceof Long.
    Long viewerId = (auth != null && auth.getPrincipal() instanceof Long id) ? id : null;
    return new CalendarResponse(calendar.getCalendar(from, to, viewerId));
}
```

Spring Security на `permitAll()`-маршруте всё равно кладёт в `SecurityContext`
некоторый `Authentication` — просто не настоящий JWT-principal, а
`AnonymousAuthenticationToken` с principal-строкой `"anonymousUser"`. То есть
`auth` почти никогда не `null` в контроллере; отличать залогиненного от гостя
нужно по **типу** principal, а не по его присутствию. У нас JWT-фильтр кладёт
`Long` (id пользователя) как principal для настоящих сессий — значит
`instanceof Long id` и есть надёжная проверка «залогинен ли зритель», а не
`auth != null`.

Но это только половина истории. Что если JWT-cookie валидна (подпись верна,
срок не истёк), а пользователь за это время был **soft-удалён** (правило
этапа 5)? Токен всё ещё говорит «я такой-то», но такого-то уже нет. Сервис
перепроверяет это на каждый запрос:

```java
// Живость зрителя (правило этапа 5): удалённый с валидной cookie — аноним.
if (viewerId != null && users.findById(viewerId)
        .filter(u -> u.getDeletedAt() == null).isEmpty()) {
    viewerId = null;
}
Long viewer = viewerId;
```

Валидный токен — не то же самое, что живой пользователь. Если бы этой
проверки не было, soft-удалённый гость с ещё не протухшей cookie видел бы
`mine: true` на своих старых бронях и чужие имена, как будто он всё ещё
залогинен — тихая дыра ровно в том месте, которое должно быть анонимным.

> **Разбор кода:** открой `backend-api/src/main/java/com/batowka/guestbooking/calendar/CalendarController.java`
> — `getCalendar` (строки 20-29), особенно комментарий про
> `AnonymousAuthenticationToken` (строка 25-26) и `instanceof Long`
> (строка 27). Открой `CalendarService.java` — проверку живости зрителя
> (строки 39-44). Открой `backend-api/src/main/java/com/batowka/guestbooking/auth/SecurityConfig.java`
> — `.requestMatchers("/api/calendar", ...).permitAll()` (строка 36) — маршрут
> открыт, но JWT-фильтр всё равно разбирает cookie раньше контроллера, если
> она есть.

## 2. Миграция «колонка → дочерняя таблица»: перенос данных в той же миграции

У гостя раньше было одно приветствие — колонка `users.greeting`. Этап 6.5
меняет это на набор приветствий (случайное показывается на каждый заход) —
значит нужна дочерняя таблица `user_greetings`, и старые данные из колонки
нельзя просто выбросить. Вся миграция `V6__user_greetings_and_completed.sql`
делает три шага строго по порядку внутри одного файла:

```sql
CREATE TABLE user_greetings (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    text    VARCHAR(300) NOT NULL
);
CREATE INDEX idx_user_greetings_user ON user_greetings (user_id);

-- Существующее одиночное приветствие переезжает первой строкой набора
INSERT INTO user_greetings (user_id, text)
SELECT id, btrim(greeting) FROM users
WHERE greeting IS NOT NULL AND btrim(greeting) <> '';

ALTER TABLE users DROP COLUMN greeting;
```

Порядок здесь не случайный, а обязательный: таблица должна существовать
раньше, чем в неё что-то вставляют; колонка `greeting` должна быть ещё на
месте в момент `INSERT ... SELECT`, потому что это единственный момент,
когда старые данные вообще доступны; и только после того как данные
переехали, колонку можно снести — `DROP COLUMN` идёт последней строкой не
для красоты, а потому что раньше её ставить нельзя.

Обрати внимание на условие `WHERE greeting IS NOT NULL AND btrim(greeting)
<> ''` — это не просто аккуратность. Если бы проверялось только `IS NOT
NULL`, гости с пустой строкой или строкой из пробелов в `greeting` получили
бы мусорную запись в `user_greetings` — пустое «приветствие», которое потом
реально показалось бы кому-то на экране.

Это всё — одна Flyway-миграция, а не растянутый на два релиза процесс. DDL в
Postgres транзакционный, так что весь файл — одна транзакция: либо
применилось всё (таблица создана, данные перенесены, колонка снесена), либо
откатилось всё. Для проекта, который ещё не под боевой нагрузкой, это
осознанно проще двухфазного подхода (сначала завести новую таблицу и жить с
обеими какое-то время, отдельным релизом сносить старую колонку) — цена
которого в том, что старый код мог бы упасть в промежутке между `DROP` и
деплоем нового кода на живом трафике. Здесь этого риска практически нет.

> **Разбор кода:** открой
> `backend-api/src/main/resources/db/migration/V6__user_greetings_and_completed.sql`
> целиком (21 строка) — обрати внимание на порядок `CREATE TABLE` (строка 2) →
> `INSERT ... SELECT` (строка 10) → `DROP COLUMN` (строка 14), и на
> `btrim(greeting) <> ''` в условии (строка 12).

## 3. Ленивый переход в COMPLETED условным UPDATE вместо шедулера

Когда бронь `CONFIRMED` и дата выезда уже прошла — это состоявшаяся поездка,
а не активная бронь. Кто-то должен перевести статус в `COMPLETED`. Соблазн —
шедулер: `@Scheduled` раз в час проходит по всем `CONFIRMED` с `check_out <=
today` и переводит их скопом. В проекте выбран другой путь — переход
случается **лениво**, прямо в момент, когда что-то реально читает или
меняет брони этого юзера:

```java
/**
 * Лениво завершает прошедшие поездки: CONFIRMED с выездом сегодня или раньше → COMPLETED.
 * Атомарный условный UPDATE (урок этапа 5) — без шедулера и без exists-then-update.
 */
public void completePastBooking(Long userId) {
    jdbc.update("update bookings set status = 'COMPLETED' "
                    + "where user_id = ? and status = 'CONFIRMED' and check_out <= ?",
            userId, LocalDate.now(JST));
}
```

`WHERE status = 'CONFIRMED'` в условии — это то же самое эхо урока этапа 5:
атомарный условный UPDATE вместо `exists`-потом-`update`. Он делает метод
идемпотентным: вызови его хоть десять раз подряд на одной и той же брони —
после первого раза строка уже не `CONFIRMED`, и UPDATE просто не находит,
что менять. Никакой отдельной проверки «а не COMPLETED ли она уже» не
нужно — WHERE сам её делает.

Метод вызывается в начале каждого пути, где статус брони имеет значение:

```java
@Transactional
public CreateResult create(Long userId, LocalDate checkIn, LocalDate checkOut, String comment) {
    // до вычисления willReplaceBooking — иначе новая бронь «заменит» уже
    // состоявшуюся поездку и история потеряется
    completePastBooking(userId);
    ...
```

```java
@Transactional
public Optional<Booking> activeBooking(Long userId) {
    completePastBooking(userId);
    return bookings.findFirstByUserIdAndStatusOrderByIdDesc(userId, BookingStatus.CONFIRMED)
            .or(() -> bookings.findFirstByUserIdAndStatusOrderByIdDesc(
                    userId, BookingStatus.PENDING_OTP));
}
```

Три независимых входа — `create`, `activeBooking` (используется в `/api/me`)
и `updateComment` — и в каждом первая строчка одна и та же. Порядок в
`create` неслучаен: комментарий явно говорит, что `completePastBooking`
обязан пройти **до** вычисления `willReplaceBooking` — иначе свежесозданная
бронь могла бы посчитать уже состоявшуюся (и на самом деле только что
переведённую в `COMPLETED`) поездку «активной для замены», и история
поездки потерялась бы в UI как «заменённая».

Здесь же скрыта тонкость, которую легко не заметить: `activeBooking` раньше
был `@Transactional(readOnly = true)` — чистое чтение. Как только внутрь
добавили `completePastBooking()`, то есть запись в БД, `readOnly = true`
пришлось снять:

```java
@Transactional
public Optional<Booking> activeBooking(Long userId) {
```

`readOnly` — это не просто пометка для читателя кода: Spring/Hibernate и
драйвер JDBC могут действительно оптимизировать транзакцию под read-only
режим (например, отключить flush или, в зависимости от настроек, буквально
запретить запись). Метод, который стал писать в БД, обязан перестать быть
`readOnly`, иначе поведение либо непредсказуемо, либо запись тихо не дойдёт.

Цена ленивого подхода: статус в БД для юзера, который давно не заходил в
приложение, может физически оставаться `CONFIRMED` сколько угодно после
факта выезда — `COMPLETED` в этой модели не факт, зафиксированный по
расписанию, а ленивая проекция, которая материализуется только при
следующем обращении именно этого юзера к своим бронями. Для кода, который
читает статус брони напрямую из БД в обход `BookingService` (прямой SQL,
отчёты, будущая админка), это может быть неожиданностью, если о ней не
помнить.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingService.java`
> — `completePastBooking` (строки 309-317), три места вызова: `create`
> (строка 52), `activeBooking` (строка 303) и `updateComment` (строка 325).
> Обрати внимание на аннотацию `activeBooking` (строка 301) — просто
> `@Transactional`, без `readOnly = true`.

## 4. `100vh` vs `100dvh` на мобильном Safari

Мобильный Safari меняет высоту видимой области, когда прячет или показывает
адресную строку при скролле — а `100vh` исторически считается по высоте
экрана без учёта этой строки. Итог — контент либо обрезается снизу, либо
появляется мёртвая полоса при скролле. `dvh` (dynamic viewport height)
пересчитывается под реально видимую область в моменте:

```tsx
'mx-auto max-w-md min-h-dvh bg-paper px-4 py-5',
```

> **Разбор кода:** `frontend/src/pages/CalendarPage.tsx`, `min-h-dvh` (строка 155).

## 5. Адаптивная сетка месяцев и двойное монтирование с CSS-скрытием

Мобильная версия календаря — два месяца со свайпом, десктопная — сразу 12 в
сетке. Вместо того чтобы условно рендерить один вариант по JS-проверке
ширины окна, оба варианта монтируются **одновременно**, а видимость решает
чистый CSS:

```tsx
<div className="lg:hidden">
  <Calendar months={mobileMonths} ... onShiftMonth={shiftMonth} />
</div>
<div className="hidden lg:block">
  <Calendar months={desktopMonths} ... />
</div>
```

Это избавляет от мигания/перескока при ресайзе окна через брейкпоинт (нет
условного unmount/mount целого дерева при изменении ширины — CSS просто
переключает `display`), ценой того, что оба дерева существуют в DOM
одновременно. Сама сетка внутри `Calendar` — брейкпоинт-колонки, а не
резиновый `auto-fill/minmax`:

```tsx
<div className="grid gap-6 sm:grid-cols-2 xl:grid-cols-3">
```

> **Разбор кода:** `frontend/src/pages/CalendarPage.tsx` — оба блока (строки
> 194 и 207). `frontend/src/components/Calendar.tsx` — сетка (строка 106).

## Бонус-урок: round-trip маски телефона — тест обязан печатать посимвольно

Маска `+7 (XXX) XXX-XX-XX` ловила один и тот же класс бага дважды — оба раза
там, где контролируемый инпут отдаёт **себя же** обратно в `onChange`:

```tsx
value={formatPhone(digits)}
onChange={(e) => setDigits(phoneDigits(e.target.value))}
```

`e.target.value` на каждое нажатие — это уже отформатированная строка
(`+7 (778) 7`) плюс новый символ, а не «то, что было плюс один символ».
Первая версия `phoneDigits` не срезала префикс `+7` перед тем как выдёргивать
цифры — семёрка из префикса попадала в цифры заново на каждое нажатие, и
посимвольный набор `9990001122` вслепую давал `digits='7777777799'`. Второй
раунд поймал соседа: эвристика «11 цифр с ведущей 7/8 — это вставка полного
номера, отбросить первую» применялась ДО среза префикса и портила номера,
которые сами по себе начинались на 7 или 9.

Оба бага живут в `frontend/src/lib/phone.ts`, но видны только тесту, который
реально печатает по одному символу:

```ts
it('round-trip: посимвольный набор через отформатированное значение', () => {
  let digits = ''
  for (const ch of '9990001122') {
    digits = phoneDigits(formatPhone(digits) + ch)
  }
  expect(digits).toBe('9990001122')
})
```

`userEvent.type()` в `LoginCard.test.tsx` печатает так же, посимвольно —
`userEvent.paste()` или прямая установка `value` одним куском не проходят
через тот же путь и маскируют именно этот класс ошибок.

> **Разбор кода:** `frontend/src/lib/phone.ts` — `phoneDigits` (строки 1-14).
> `frontend/src/components/LoginCard.tsx` — `value`/`onChange` (строки 26-27).
> `frontend/src/components/LoginCard.test.tsx` — `userEvent.type(...phone-input...)`
> (строка 16).

## Вопросы для самопроверки

1. На `permitAll`-маршруте `/api/calendar` `Authentication auth` в
   контроллере практически никогда не `null`. Что там лежит для анонима и
   как код отличает его от залогиненного гостя?
2. Гость soft-удалён, но его JWT-cookie ещё физически валидна. Почему
   `CalendarService` всё равно не покажет его как «своего» зрителя, и в
   каком именно месте это решается?
3. В миграции V6 три шага строго по порядку: создать таблицу, перенести
   данные, снести колонку. Что сломается, если поменять местами шаги 2 и 3?
4. `completePastBooking` вызывается в начале `create`, `activeBooking` и
   `updateComment`. Почему нельзя было сделать это одной фоновой джобой раз в
   сутки вместо трёх вызовов в разных местах кода?
5. Тест на маску телефона печатает номер посимвольно через `userEvent.type`,
   а не вставляет вставкой. Почему это принципиально для того, чтобы поймать
   баг с дублированием префикса `+7`?

Сквозной вопрос: и оптional auth (перепроверка живости зрителя на каждый
запрос), и ленивый `COMPLETED` (перепроверка статуса на каждое обращение) —
оба сознательно отказываются доверять один раз проверенному факту (валидный
токен, статус в БД) и перепроверяют его заново при каждом реальном
использовании. Где в проекте цена постоянной перепроверки уже была признана
приемлемой раньше (см. этап 5), и почему в обоих новых местах выбор
оказался тем же?
