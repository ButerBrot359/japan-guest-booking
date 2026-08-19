---
tutor:
  stage: 1
  title: "Flyway и exclusion constraints"
  topics:
    - id: migrations-ddl-auto
      section: "Зачем миграции и почему ddl-auto: validate, а не update"
      code_anchors:
        - path: backend-api/src/main/resources/application.yml
          symbol: "spring.jpa.hibernate.ddl-auto"
          concept: "validate вместо update — Hibernate только сверяет схему, не меняет её"
        - path: backend-api/src/main/resources/db/migration/V1__init.sql
          symbol: "CREATE TABLE users / bookings / blocked_periods / access_requests / otp_challenges / outbox / processed_events"
          concept: "вся схема этапа 1 одним версионированным SQL-файлом"
      quiz_seeds:
        - "Почему ddl-auto: update соблазнителен на старте, но опасен на проде?"
        - "Как Flyway узнаёт, какие миграции уже применены к конкретной базе?"
    - id: exclusion-constraint-overlap
      section: "Как работает exclusion constraint"
      code_anchors:
        - path: backend-api/src/main/resources/db/migration/V1__init.sql
          symbol: "CONSTRAINT no_overlapping_bookings EXCLUDE USING gist"
          concept: "daterange + оператор && запрещает пересекающиеся активные брони на уровне БД"
        - path: backend-api/src/test/java/com/batowka/guestbooking/db/SchemaConstraintsTest.java
          symbol: "overlappingActiveBookingsAreRejected"
          concept: "пересекающаяся вставка кидает DataIntegrityViolationException"
      quiz_seeds:
        - "Почему проверка check-then-insert в Java-коде не спасает от гонки двух параллельных броней?"
        - "Зачем в миграции CREATE EXTENSION btree_gist, если сам daterange уже умеет GiST-индекс?"
    - id: half-open-intervals
      section: "Полуинтервалы [) против включительных диапазонов"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/booking/BookingRepository.java
          symbol: "findOverlapping"
          concept: "строгое checkOut > from — день выезда не входит в занятость"
        - path: backend-api/src/main/java/com/batowka/guestbooking/calendar/BlockedPeriodRepository.java
          symbol: "findOverlapping"
          concept: "endDate >= from — у ручных блокировок конец включителен"
        - path: backend-api/src/test/java/com/batowka/guestbooking/booking/BookingRepositoryTest.java
          symbol: "checkoutDayDoesNotCountAsOccupied"
          concept: "тест буквально проверяет полуинтервальную семантику bookings"
      quiz_seeds:
        - "Почему день выезда и день заезда следующей брони в один и тот же день — не конфликт?"
        - "Что было бы, если бы bookings и blocked_periods смешивали включительную и полуинтервальную семантику в одной таблице?"
    - id: partial-unique-index
      section: "Частичный уникальный индекс — «одна активная бронь»"
      code_anchors:
        - path: backend-api/src/main/resources/db/migration/V1__init.sql
          symbol: "CREATE UNIQUE INDEX one_confirmed_booking_per_user"
          concept: "WHERE status = 'CONFIRMED' сужает уникальность только на активные брони"
        - path: backend-api/src/test/java/com/batowka/guestbooking/db/SchemaConstraintsTest.java
          symbol: "secondConfirmedBookingForSameUserIsRejected"
          concept: "второй CONFIRMED для того же пользователя падает с DataIntegrityViolationException"
      quiz_seeds:
        - "Чем частичный индекс отличается от обычного UNIQUE INDEX ON bookings (user_id)?"
        - "Почему CANCELLED-брони того же пользователя не мешают новой брони?"
    - id: testcontainers-real-postgres
      section: "Testcontainers: почему настоящий Postgres, а не H2"
      code_anchors:
        - path: backend-api/src/test/java/com/batowka/guestbooking/AbstractIntegrationTest.java
          symbol: "POSTGRES / cleanDatabase"
          concept: "singleton-контейнер (без @Testcontainers) + TRUNCATE ... RESTART IDENTITY CASCADE перед каждым тестом"
        - path: docker-compose.dev.yml
          symbol: "services.postgres.image"
          concept: "тот же образ postgres:16-alpine, что и в тестовом контейнере — dev-окружение и тесты видят одну и ту же БД"
      quiz_seeds:
        - "Почему @Testcontainers (per-class жизненный цикл) не подошёл вместо статического singleton-контейнера?"
        - "Что конкретно из схемы (daterange, &&, EXCLUDE USING gist, btree_gist) не умеет H2 и почему это опасно для тестов, а не просто неудобно?"
  bugs_and_lessons:
    - "Наивная защита «сначала SELECT, потом INSERT» в коде не работает под конкурентным доступом: в READ COMMITTED обе параллельные транзакции не видят чужих незакоммиченных вставок и обе решают, что место свободно. Exclusion constraint переносит эту гарантию на уровень хранилища, где Postgres проверяет её атомарно внутри самой вставки — мораль: гонки между транзакциями нельзя закрыть проверкой в коде, только гарантией БД."
    - "Тесты на H2 вместо настоящего Postgres в этой схеме были бы хуже, чем бесполезны: они могли бы пройти «зелёным», вообще не проверив exclusion constraint, потому что H2 не умеет daterange/GiST — тестовая инфраструктура должна воспроизводить именно те возможности БД, на которые опирается код, иначе тест создаёт ложную уверенность."
  prerequisites: [docker-compose-postgres, ci-anatomy]
---

# Этап 1: Flyway и exclusion constraints

Разбор схемы БД из `backend-api/src/main/resources/db/migration/V1__init.sql` и того,
как тесты (`backend-api/src/test/java/com/batowka/guestbooking/AbstractIntegrationTest.java`,
`backend-api/src/test/java/com/batowka/guestbooking/db/SchemaConstraintsTest.java`) её
проверяют.

## 1. Зачем миграции и почему `ddl-auto: validate`, а не `update`

В `backend-api/src/main/resources/application.yml` стоит `spring.jpa.hibernate.ddl-auto:
validate`. Это значит: при старте приложения Hibernate сверяет JPA-сущности
(`Booking`, `BlockedPeriod`, `UserAccount` и т. д.) с реальной структурой таблиц в базе
и падает, если они разошлись, но сам он таблицы не создаёт и не меняет. Создание и
эволюция схемы — целиком на Flyway, а конкретно на файле
`backend-api/src/main/resources/db/migration/V1__init.sql`, где одним SQL-скриптом
описаны все таблицы этапа 1: `users`, `bookings`, `blocked_periods`, `access_requests`,
`otp_challenges`, `outbox`, `processed_events`.

Идея в том, что схема БД — это код, и относиться к ней нужно так же, как к коду: с
версионированием и историей изменений, а не «правь на лету и надейся, что все среды
одинаковые». `ddl-auto: update` соблазнителен на старте (Hibernate сам подгонит
таблицы под сущности), но он непредсказуем — недетерминированно решает, как менять уже
существующие колонки, не умеет откатывать изменения, и на проде это верный способ
однажды получить рассинхрон между тем, что задумано, и тем, что реально в базе.
Flyway вместо этого требует явных файлов миграций с именами вида `V<номер>__описание.sql`
(следующим будет `V2__...`), применяет их по порядку номеров и после каждого успешного
запуска пишет строку в служебную таблицу `flyway_schema_history` — так Flyway всегда
знает, какие миграции уже применены к конкретной базе, и при следующем старте
пропускает их, выполняя только новые. Это даёт воспроизводимость: раскатили один и тот
же набор `V*.sql` на dev, на CI и на проде — получили гарантированно одинаковую схему.

> **Разбор кода:** открой `backend-api/src/main/resources/application.yml` —
> смотри `spring.jpa.hibernate.ddl-auto: validate`. Открой
> `backend-api/src/main/resources/db/migration/V1__init.sql` — пробегись по
> всем `CREATE TABLE`: обрати внимание, что все семь таблиц этапа 1 описаны
> одним файлом, а не отдельными миграциями на каждую.

## 2. Как работает exclusion constraint

Самое интересное ограничение в схеме — `no_overlapping_bookings` в таблице `bookings`:

```sql
CONSTRAINT no_overlapping_bookings EXCLUDE USING gist (
    daterange(check_in, check_out) WITH &&
) WHERE (status IN ('PENDING_OTP', 'CONFIRMED'))
```

`daterange(check_in, check_out)` строит из двух дат Postgres-тип `daterange` — диапазон
дат. Оператор `&&` для диапазонов означает «пересекается с» (true, если у диапазонов
есть хоть один общий день). `EXCLUDE USING gist (... WITH &&)` — это специальный вид
ограничения (exclusion constraint): Postgres гарантирует, что никакие две строки в
таблице не могут одновременно удовлетворять условию `&&` по этому выражению — то есть
физически невозможно вставить бронь, диапазон дат которой пересекается с диапазоном
уже существующей строки. Работает это через GiST-индекс (Generalized Search Tree) —
структуру данных, которая эффективно ищет по пространственным и range-типам; без
такого индекса Postgres просто не умел бы быстро проверять «пересекается ли новый
диапазон хоть с одним из уже существующих» при каждой вставке.

Первая строка миграции — `CREATE EXTENSION IF NOT EXISTS btree_gist;`. Стоит понимать
точно, зачем оно тут: сам тип `daterange` уже умеет строить GiST-индекс из коробки (это
часть ядра Postgres, никакого расширения не требует) — я проверил это отдельно на
чистом `postgres:16-alpine`: `EXCLUDE USING gist (daterange(...) WITH &&)` без
`btree_gist` создаётся и корректно отклоняет пересекающиеся диапазоны. Расширение
`btree_gist` нужно для другого: оно добавляет GiST-операторные классы для обычных
скалярных типов (`bigint`, `text` и т. п.), которых в нашем текущем constraint нет —
он построен только на `daterange`. Но если позже понадобится сузить exclusion до
конкретной комнаты или объекта размещения (`EXCLUDE USING gist (room_id WITH =,
daterange(...) WITH &&)` — «не пересекаться по датам в рамках одной комнаты»),
`btree_gist` станет обязателен, потому что без него GiST не умеет работать с
равенством по `bigint`. Расширение включено заранее — разумный задел на такое
расширение схемы, но для конкретно текущего `no_overlapping_bookings` строго
необходимым не является.

Частичность (`WHERE (status IN ('PENDING_OTP', 'CONFIRMED'))`) означает, что
ограничение проверяется только среди строк с этими статусами — отменённые брони
(`CANCELLED`) в проверке не участвуют вовсе, то есть можно спокойно хранить историю
отменённых броней на те же даты, не мешая новым бронированиям.

Почему это закрывает гонки, которые не закрыть проверкой в коде: представим, что вместо
constraint мы бы делали «сначала SELECT — проверить, нет ли пересечения — потом
INSERT» в Java-коде. Две параллельные транзакции (два гостя одновременно бронируют
пересекающиеся даты) обе выполнят SELECT до того, как другая сделает commit — в
терминах ACID это классический race condition: в стандартном уровне изоляции
READ COMMITTED транзакция не видит незакоммиченных изменений другой транзакции, поэтому
обе увидят «место свободно» и обе успешно вставят пересекающиеся брони. Constraint
работает иначе — это ограничение уровня хранилища, которое база проверяет атомарно
внутри самой операции INSERT (грубо говоря, index-level lock на затрагиваемый диапазон
GiST-индекса), поэтому вторая параллельная вставка либо заблокируется до коммита
первой, либо, если первая уже закоммитилась, тут же получит ошибку нарушения
ограничения. Именно эту гарантию проверяет
`SchemaConstraintsTest.overlappingActiveBookingsAreRejected` — вставка пересекающейся
брони кидает `DataIntegrityViolationException`.

> **Разбор кода:** открой `backend-api/src/main/resources/db/migration/V1__init.sql`
> — смотри `CONSTRAINT no_overlapping_bookings EXCLUDE USING gist (...)`: сопоставь
> каждую часть выражения (`daterange`, `WITH &&`, `WHERE (status IN ...)`) с тем, что
> только что разобрали. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/db/SchemaConstraintsTest.java`
> — смотри `overlappingActiveBookingsAreRejected`: это живая демонстрация, что
> constraint действительно ловит пересечение, а не просто существует в SQL.

## 3. Полуинтервалы `[)` против включительных диапазонов

В `bookings` даты хранятся как полуинтервал: `check_in` включён, `check_out` — нет.
Комментарий в самой миграции это явно проговаривает: «Полуинтервал
`[check_in, check_out)`: выезд и заезд в один день не конфликтуют». Это ровно бытовая
логика гостиницы — гость выезжает утром, следующий может заехать вечером того же дня,
и это не пересечение. Тест `SchemaConstraintsTest.backToBackBookingsAreAllowed`
проверяет это буквально: бронь до `2026-11-05` и следующая с `2026-11-05` не
конфликтуют. То же самое видно и в Java-коде: комментарий в
`BookingRepository.findOverlapping` — «Бронь занимает `[checkIn, checkOut)`, поэтому:
checkIn <= to и checkOut > from» — строгое `>` для checkOut именно потому, что день
выезда в диапазон занятости не входит; и тест `BookingRepositoryTest.checkoutDayDoesNotCountAsOccupied`
подтверждает: запрос диапазона, начинающегося ровно в день выезда, эту бронь не находит.

У `blocked_periods` — другая семантика: `end_date DATE NOT NULL, -- включительно`.
Это ручные блокировки (например, «хозяева сами живут в доме с 1 по 10 июня») — тут
естественнее говорить «блок действует по 10 июня включительно», а не «до 11-го не
включая». В `BlockedPeriodRepository.findOverlapping` это видно по условию `p.startDate
<= :to and p.endDate >= :from` — оба конца включительны (не строгое неравенство ни с
одной стороны), в отличие от строгого `>` у `checkOut` в `BookingRepository`.

Смешивать эти две семантики в одной таблице было бы ошибкой, потому что тогда каждый
разработчик (и каждый SQL-запрос, и каждый вызывающий Java-код) был бы вынужден
помнить, для каких именно строк граница включительна, а для каких — нет, в зависимости
от какого-то дополнительного признака. Это источник off-by-one багов: рано или поздно
кто-то напишет `<=` там, где нужно было `<`, и день выезда начнёт ошибочно считаться
занятым (или наоборот — свободным, когда гость ещё физически в доме). Разделение на две
таблицы с разной, но фиксированной внутри каждой семантикой снимает эту двусмысленность
раз и навсегда: открыл `bookings` — знаешь, что выезд не включён; открыл
`blocked_periods` — знаешь, что включён.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/booking/BookingRepository.java`
> — смотри `findOverlapping` и строгое `checkOut > from`. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/calendar/BlockedPeriodRepository.java`
> — смотри `findOverlapping` и нестрогое `endDate >= from`: сравни два условия
> рядом. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/booking/BookingRepositoryTest.java`
> — смотри `checkoutDayDoesNotCountAsOccupied`.

## 4. Частичный уникальный индекс — «одна активная бронь»

```sql
CREATE UNIQUE INDEX one_confirmed_booking_per_user
    ON bookings (user_id)
    WHERE status = 'CONFIRMED';
```

Обычный `UNIQUE INDEX ON bookings (user_id)` запретил бы пользователю иметь больше
одной строки `bookings` вообще — что сломало бы саму идею истории (гость съездил
однажды, бронь `CONFIRMED` осталась в таблице, а потом захотел приехать снова).
Частичный индекс (`WHERE status = 'CONFIRMED'`) сужает уникальность только на строки с
этим статусом: уникальность `user_id` проверяется исключительно среди подмножества
«сейчас подтверждённые» строк, а `CANCELLED`- и `PENDING_OTP`-строки того же
пользователя в индекс вообще не попадают и никак не мешают. Это выражает бизнес-правило
«у гостя не может быть двух активных подтверждённых броней одновременно», не мешая при
этом хранить сколько угодно отменённых броней в истории того же пользователя. Тест
`SchemaConstraintsTest.secondConfirmedBookingForSameUserIsRejected` проверяет именно
это: второй `CONFIRMED` для того же пользователя падает с
`DataIntegrityViolationException`, а `cancelledBookingDoesNotBlockDates` — что
`CANCELLED`-бронь вообще не мешает ни другому пользователю на те же даты, ни (что здесь
не тестируется впрямую, но следует из того же механизма) новой брони того же
пользователя.

> **Разбор кода:** открой `backend-api/src/main/resources/db/migration/V1__init.sql`
> — смотри `CREATE UNIQUE INDEX one_confirmed_booking_per_user ... WHERE status =
> 'CONFIRMED'`. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/db/SchemaConstraintsTest.java`
> — смотри `secondConfirmedBookingForSameUserIsRejected`.

## 5. Testcontainers: почему настоящий Postgres, а не H2

Все интеграционные тесты (`SchemaConstraintsTest`, `BookingRepositoryTest` и другие)
наследуют `AbstractIntegrationTest`, который поднимает не встроенную in-memory базу, а
настоящий Postgres в Docker-контейнере через Testcontainers
(`org.testcontainers.postgresql.PostgreSQLContainer`, образ `postgres:16-alpine` — тот
же, что и в `docker-compose.dev.yml`). Причина — в схеме используются вещи, которых
H2 (частый выбор для тестов «полегче») просто не умеет: тип `daterange`, оператор `&&`
для диапазонов, `EXCLUDE USING gist`, расширение `btree_gist`. H2 либо не распознает
такой SQL вовсе, либо (что хуже) в режиме совместимости с Postgres молча проигнорирует
семантику, которую не умеет эмулировать — то есть тест `overlappingActiveBookingsAreRejected`
против H2 либо не скомпилировался бы, либо, в худшем случае, прошёл бы «зелёным», хотя
в реальном Postgres exclusion constraint действительно работает, а в H2 никакой
эквивалентной проверки нет. Тесты на ненастоящей базе в таком случае просто врали бы:
показывали бы, что всё хорошо, хотя ключевая гарантия целостности данных (невозможность
двойного бронирования) не проверена вообще.

`@ServiceConnection` (аннотация Spring Boot на статическом поле `POSTGRES` в
`AbstractIntegrationTest`) — это то, что подключает Testcontainers к Spring: она
автоматически подменяет `spring.datasource.url/username/password` координатами
поднятого контейнера, без единой строчки ручной конфигурации datasource в тестах.
Обратите внимание на нестандартную деталь в этом классе: контейнер — не
`@Container`/`@Testcontainers` (это был бы per-class жизненный цикл, когда JUnit сам
стартует и останавливает контейнер вокруг каждого тест-класса), а **singleton**:
статическая инициализация (`static { POSTGRES.start(); }`) без аннотации `@Testcontainers`
означает, что контейнер стартует один раз на JVM и живёт до конца прогона всех тестов,
JUnit его не трогает. Комментарий в коде объясняет почему: `@Testcontainers` гасил бы
контейнер после каждого тест-класса, а вместе с ним — и закешированный Spring
`ApplicationContext` (Spring Boot кеширует контекст между тестовыми классами, если
конфигурация одинаковая, чтобы не поднимать приложение заново на каждый класс — это
резко замедляет прогон). Если контейнер умирает после первого класса, второй тест-класс
не может переиспользовать закешированный контекст (там уже нет живого datasource) и
Spring поднимает всё заново — singleton-контейнер этого избегает, оставляя контекст и базу
живыми на весь прогон. Изоляция данных между отдельными тестами при этом достигается не
пересозданием контейнера, а `@BeforeEach cleanDatabase()`, который перед каждым тестом
выполняет `TRUNCATE ... RESTART IDENTITY CASCADE` по всем таблицам — так каждый `@Test`
стартует с гарантированно пустой базой и предсказуемыми id (`RESTART IDENTITY`), не
дожидаясь пересоздания инфраструктуры.

> **Разбор кода:** открой
> `backend-api/src/test/java/com/batowka/guestbooking/AbstractIntegrationTest.java`
> — смотри статическое поле `POSTGRES` (без `@Testcontainers`/`@Container`) и метод
> `cleanDatabase()` рядом: это и есть singleton-контейнер + TRUNCATE, о которых
> только что шла речь.
