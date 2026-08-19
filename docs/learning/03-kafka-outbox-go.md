---
tutor:
  stage: 3
  title: "Kafka, transactional outbox, основы Go"
  topics:
    - id: kafka-topic-partition-offset-group
      section: "Топик, партиция, offset, consumer group; KRaft"
      code_anchors:
        - path: contracts/notifications-outbound.md
          symbol: "1 партиция"
          concept: "осознанный выбор одной партиции — порядок важнее пропускной способности при таком объёме"
        - path: backend-api/src/main/java/com/batowka/guestbooking/messaging/ContactSharedConsumer.java
          symbol: "@KafkaListener(topics = \"telegram.inbound\", groupId = \"backend-api\")"
          concept: "consumer group backend-api — один читатель, один offset"
        - path: bot-service/internal/kafka/consumer.go
          symbol: "kafkago.ReaderConfig.GroupID"
          concept: "consumer group bot-service со своей стороны потока"
        - path: backend-api/src/test/java/com/batowka/guestbooking/AbstractIntegrationTest.java
          symbol: "KafkaContainer(\"apache/kafka:3.9.1\")"
          concept: "тег 3.9.1, а не 3.9.0 из dev-compose — обходит баг KAFKA-18281"
      quiz_seeds:
        - "Почему у обоих топиков ровно одна партиция, а не несколько для параллелизма?"
        - "Чем отличается набор listener'ов в docker-compose.dev.yml от того, что генерирует Testcontainers, и почему это важно для версии 3.9.0 против 3.9.1?"
    - id: transactional-outbox
      section: "Transactional outbox"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/messaging/OutboxWriter.java
          symbol: "OutboxWriter.write"
          concept: "@Transactional(propagation = Propagation.MANDATORY) — нельзя вызвать вне чужой транзакции"
        - path: backend-api/src/main/java/com/batowka/guestbooking/messaging/ContactSharedConsumer.java
          symbol: "ContactSharedConsumer.link"
          concept: "users.save(user) и outbox.write(...) коммитятся или откатываются вместе, в одной @Transactional"
        - path: backend-api/src/main/java/com/batowka/guestbooking/messaging/OutboxPublisher.java
          symbol: "OutboxPublisher.publishPending"
          concept: "@Scheduled(fixedDelay = 2000), выход из метода при первой же ошибке отправки сохраняет порядок"
      quiz_seeds:
        - "Что произойдёт со строкой outbox, если Kafka окажется недоступна ровно в момент publishPending?"
        - "Почему write() физически нельзя вызвать вне чужой транзакции — что бы сломалось, если бы это было разрешено?"
    - id: at-least-once-idempotency
      section: "At-least-once и идемпотентность"
      code_anchors:
        - path: backend-api/src/main/java/com/batowka/guestbooking/messaging/ContactSharedConsumer.java
          symbol: "ContactSharedConsumer.onEvent"
          concept: "проверка event_id в processed_events и вставка туда же — атомарно в одной @Transactional с эффектом"
        - path: bot-service/internal/kafka/consumer.go
          symbol: "consumerCore.seen / dedupCap"
          concept: "in-memory map[string]bool на 1000 записей — best-effort дедуп без персистентности"
        - path: bot-service/internal/kafka/consumer.go
          symbol: "Consumer.Run"
          concept: "offset коммитится после обработки (CommitMessages после c.core.handle) — at-least-once"
      quiz_seeds:
        - "Почему дедупликация на backend строже (транзакционная таблица), чем на bot-service (in-memory map)?"
        - "Что случится с consumerCore.seen при рестарте bot-service, и почему это приемлемый риск именно для WELCOME-сообщений?"
    - id: go-basics
      section: "Основы Go на нашем коде"
      code_anchors:
        - path: bot-service/cmd/bot/main.go
          symbol: "main — wg.Add(2), go func(), signal.NotifyContext, wg.Wait()"
          concept: "graceful shutdown: обе горутины дожидаются отмены ctx перед Close()"
        - path: bot-service/internal/telegram/poller.go
          symbol: "Poller.handle / type ContactPublisher interface"
          concept: "интерфейс объявлен на стороне потребителя, а не реализации — структурная типизация без implements"
        - path: bot-service/internal/telegram/poller_test.go
          symbol: "fakeAPI / fakePublisher"
          concept: "тестовые подмены без библиотеки моков — просто структуры, удовлетворяющие интерфейсу"
      quiz_seeds:
        - "Как Go решает, что *telegram.Client удовлетворяет интерфейсу Sender, без явного implements?"
        - "Что было бы, если бы poller.Run не проверял ctx.Err() в цикле — как бы это сломало graceful shutdown?"
    - id: onboarding-security
      section: "Безопасность онбординга"
      code_anchors:
        - path: bot-service/internal/telegram/poller.go
          symbol: "Poller.handle — m.Contact.UserID != m.From.ID"
          concept: "контакт принимается только если он принадлежит тому же Telegram-аккаунту, что написал боту"
        - path: backend-api/src/main/java/com/batowka/guestbooking/messaging/ContactSharedConsumer.java
          symbol: "ContactSharedConsumer.handleContactShared"
          concept: "users.findByPhone(...).ifPresent(...) — телефона нет в списке гостей — молча игнорируем"
        - path: contracts/telegram-inbound.md
          symbol: "CONTACT_SHARED"
          concept: "contact.user_id == from.id — оба поля подтверждает сам Telegram, а не клиент"
      quiz_seeds:
        - "Почему бот не отвечает ничего разного на «чужой контакт» и на «телефон не в списке» — чем это отличается от единого 401 у admin-логина?"
        - "Кто именно устанавливает поля from.id и contact.user_id — клиент или сервер Telegram, и почему это важно для проверки?"
  bugs_and_lessons:
    - "Ранняя версия OutboxPublisher чистила payload через payload.replaceAll(\": \", \":\").replaceAll(\", \", \",\") перед отправкой — регулярка резала пробелы везде, где встречала эти подстроки, не отличая разделитель JSON от точно такой же последовательности символов внутри значения строки (пример из коммита e312ce2: «Смирнов, Иван: старший» → «Смирнов,Иван:старший»). Исправление — не трогать текст руками (payload::text из jsonb уже валиден), а тесты заодно переписаны на сравнение распарсенного JSON вместо contains() по подстрокам. Мораль: регулярка видит последовательность символов, а не структуру документа."
    - "Постскриптум: на живом смоуке bot-service стартовал раньше первой публикации backend в notifications.outbound, топика ещё не существовало (ленивое создание), а consumer group kafka-go в этой ситуации не падает и не логирует ошибку — молча висит без партиций. WELCOME дошёл только после рестарта бота; сообщение не потерялось (durability лога), но пользователь не получил его сразу. Исправлено детерминированным созданием топиков при старте (KafkaTopicsConfig на backend, EnsureTopic на bot-service). Мораль: тихие зависания хуже громких ошибок, а порядок запуска сервисов не должен быть негласным протоколом."
  prerequisites: [kafka-kraft-listeners, error-disclosure-safety]
---

# Этап 3: Kafka, transactional outbox, основы Go

Разбор того, что мы собрали в этапе 3 — связь backend-api и bot-service через
Kafka. Контракты событий лежат в `contracts/` (`envelope.md`,
`notifications-outbound.md`, `telegram-inbound.md`), Java-сторона — в
`backend-api/src/main/java/com/batowka/guestbooking/messaging/`, Go-сторона —
в `bot-service/`. Ссылки — на реальные файлы, чтобы можно было открыть рядом
и сверить.

## 1. Топик, партиция, offset, consumer group; KRaft

Топик — это именованный поток сообщений, в котором два наших: `notifications.outbound`
(backend → bot) и `telegram.inbound` (bot → backend), оба описаны в
`contracts/README.md`. Партиция — это то, на что топик физически разбит
внутри: каждая партиция — упорядоченный лог, в конец которого только
дописывают, а offset — просто порядковый номер сообщения внутри конкретной
партиции (0, 1, 2, …), по которому консьюмер помнит, докуда он уже дочитал.
`contracts/notifications-outbound.md` и `contracts/telegram-inbound.md` прямо
говорят: «1 партиция» у каждого топика. Ни в `docker-compose.dev.yml`, ни в
коде партиции явно не заданы — топик создаётся автоматически при первом
сообщении (в `bot-service/internal/kafka/producer.go` это видно буквально:
`AllowAutoTopicCreation: true`), и число партиций берётся из настройки
брокера по умолчанию, которую мы не переопределяли. Одна партиция — не
экономия, а осознанный выбор: партиции существуют, чтобы параллелить
пропускную способность, а порядок Kafka гарантирует только *внутри* одной
партиции — с несколькими партициями сообщения от разных источников могли бы
разъехаться по разным логам и потерять относительный порядок. При объёме
«несколько уведомлений в день на семью гостей» пропускная способность не
узкое место, а порядок важен (`OutboxPublisher` ниже как раз на нём
держится) — значит, партиция должна быть одна.

Consumer group — это подписчик топика, у которого Kafka помнит свой
собственный offset (какая группа докуда дочитала — это независимо для
каждой группы). У нас по одной группе на топик, и обе названы явно: Java
слушает `telegram.inbound` группой `backend-api`
(`ContactSharedConsumer.java`: `@KafkaListener(topics = "telegram.inbound",
groupId = "backend-api")`), Go слушает `notifications.outbound` группой
`bot-service` (`bot-service/internal/kafka/consumer.go`:
`GroupID: "bot-service"` внутри `kafkago.ReaderConfig`). Групп не две на
топик и не общая на оба сервиса — потому что второго независимого читателя
того же потока сообщений у нас просто нет: если бы, скажем, появился второй
сервис, которому тоже нужны те же `WELCOME`-события, но со своим собственным
прогрессом чтения, вот тогда была бы вторая группа. Пока у каждого топика
ровно один читатель — ровно одна группа.

Про сами три listener'а Kafka в KRaft-режиме (`docker-compose.dev.yml`,
секция `kafka`, образ `apache/kafka:3.9.0`, без ZooKeeper,
`KAFKA_PROCESS_ROLES: broker,controller`) подробно уже разобрано в
`docs/learning/00-monorepo-compose-ci.md`, §3 — переповторять не буду. Стоит
добавить одну деталь оттуда, которая понадобится ниже: `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1`,
`KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1` и
`KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1` — это настройки служебных
внутренних топиков самой Kafka (где она хранит закоммиченные offset'ы каждой
группы и состояние транзакций производителей), и по умолчанию они рассчитаны
на кластер из нескольких брокеров (фактор репликации по умолчанию — 3); у нас брокер один,
поэтому фактор реплики принудительно снижен до 1 — без этого Kafka вообще не
поднимется. `TRANSACTION_STATE_LOG*` тут не потому, что мы используем
Kafka-транзакции — не используем, ниже разберём почему.

Тот же образ Kafka всплывает и в тестах, но с другим тегом, и это
поучительная деталь сама по себе.
`backend-api/src/test/java/com/batowka/guestbooking/AbstractIntegrationTest.java`
поднимает `org.testcontainers.kafka.KafkaContainer("apache/kafka:3.9.1")`, а
не `3.9.0`, который стоит в `docker-compose.dev.yml`. Комментарий рядом
объясняет почему: «Тег 3.9.1, не 3.9.0 из брифа: `apache/kafka:3.9.0` несёт
баг KAFKA-18281 (контроллер в KRaft некорректно валидирует 0.0.0.0 у
неанонсируемого CONTROLLER-листенера, что testcontainers всегда настраивает
так же — контейнер падает на "Transitioning from RECOVERY to RUNNING" при
старте). Баг исправлен в 3.9.1/4.0.0». Тот же брокер, тот же KRaft-режим —
но именно версия `3.9.0` ломается на конкретной конфигурации, которую
Testcontainers генерирует автоматически (а `docker-compose.dev.yml`, где
CONTROLLER-листенер прописан вручную по-другому, эту комбинацию не задевает
— поэтому dev-окружение живёт на `3.9.0` спокойно). Урок отсюда простой:
когда контейнер падает на старте необъяснимой ошибкой, а конфигурация с виду
правильная, стоит проверить список изменений конкретного патч-релиза образа, а не
сразу переписывать свой конфиг.

> **Разбор кода:** открой `contracts/notifications-outbound.md` — смотри
> строку про число партиций. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/messaging/ContactSharedConsumer.java`
> — смотри аннотацию `@KafkaListener` над `onEvent` и `groupId`. Открой
> `bot-service/internal/kafka/consumer.go` — смотри `GroupID: "bot-service"`
> внутри `kafkago.ReaderConfig`. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/AbstractIntegrationTest.java`
> — смотри тег образа `KafkaContainer` и комментарий про KAFKA-18281 рядом.

## 2. Transactional outbox

Проблема, которую решает outbox, звучит по-бытовому просто: «бронь создалась,
а код не ушёл» — то есть Java-код сохранил что-то в Postgres, а следом
отдельным вызовом отправил сообщение в Kafka, и между этими двумя действиями
нет ничего общего, что гарантировало бы «либо оба, либо ни одного». Если
процесс упадёт, или Kafka окажется недоступна ровно между записью в БД и
отправкой в Kafka, получится рассинхрон: в базе бронь (или, как у нас в
этапе 3, привязанный `telegram_chat_id`) есть, а сообщение, которое должно
было об этом кому-то сообщить, — нет, и никто не узнает, что оно пропало.

`OutboxWriter.write` решает это не координацией двух разных систем, а тем,
что превращает событие в обычную строку той же таблицы той же базы данных,
что и бизнес-изменение — то есть Postgres обеспечивает атомарность
бесплатно, своей стандартной ACID-транзакцией. Метод помечен
`@Transactional(propagation = Propagation.MANDATORY)`
(`OutboxWriter.java`), и это не формальность: `MANDATORY` требует, чтобы
вызывающий уже находился внутри транзакции, иначе бросает исключение —
`write` физически нельзя вызвать «сам по себе», только как часть чужой
транзакции. В `ContactSharedConsumer.link` это видно наглядно: `user.setTelegramChatId(chatId);
users.save(user); outbox.write("notifications.outbound", "WELCOME", ...)` —
обе строчки выполняются внутри одной и той же `@Transactional` (аннотация на
`onEvent`), и Postgres либо закоммитит обе (привязку телефона к Telegram и
строку в `outbox`), либо, если что-то пойдёт не так, откатит обе. Момента,
когда одно случилось, а другое — нет, просто не существует.

Доставку в Kafka делает отдельный компонент, `OutboxPublisher.publishPending`,
раз в две секунды (`@Scheduled(fixedDelay = 2000)`): выбирает из `outbox`
строки с `published_at is null` по возрастанию `id`, лимит 100, и по очереди
шлёт в Kafka (`kafka.send(...).join()`), помечая `published_at = now()`
только после успешной отправки. Если `kafka.send` бросает исключение (Kafka
недоступна), обработчик логирует предупреждение и **сразу выходит** из
метода (`return`), не трогая оставшиеся строки — комментарий в коде
объясняет зачем: «остальные строки подождут следующего цикла — порядок
сохраняется». Именно поэтому падение Kafka не теряет события: непослатая
строка так и останется с `published_at = null` в базе, и следующий тик
шедулера (через две секунды) снова её подберёт — источник истины не Kafka, а
таблица `outbox`, а сама доставка — это просто повторяемая операция поверх
неё.

Ранняя версия `OutboxPublisher` отправляла в Kafka не `payload::text` как
есть, а строку, пропущенную через `payload.replaceAll(": ", ":").replaceAll(", ", ",")`
— Postgres, отдавая `jsonb`-колонку текстом, расставляет пробелы после
двоеточий и запятых, и это казалось безобидной косметикой перед отправкой.
Коммит `e312ce2` (сообщение: «fix: payload уходит в Kafka без искажений —
убран regex, тесты сравнивают распарсенный JSON») описывает, чем это
обернулось: контрпример «Смирнов, Иван: старший» → «Смирнов,Иван:старший» —
`replaceAll` резал пробелы вообще везде, где встречал `": "` или `", "`, не
различая разделитель JSON и точно такую же подстроку внутри значения
строки. В этом и есть причина, почему регулярки не годятся для правки JSON:
регулярное выражение видит только последовательность символов, а не
структуру документа, и не может отличить «это запятая между полями» от «это
запятая, которую гость сам ввёл в своё имя». Исправление оказалось проще
самой поломки — просто не трогать текст руками: `payload::text` из `jsonb`
уже валидный JSON (пусть и с лишними пробелами), и любой консьюмер, который
по-настоящему парсит JSON, а не ищет в нём подстроки, получит правильные
значения независимо от пробелов вокруг знаков препинания. Тесты заодно
поменяли подход: вместо `assertThat(envelope).contains(...)` по кусочкам
строки — разбор через `ObjectMapper` и сравнение уже распарсенных полей
(`JsonNode`), что само по себе не даёт повторить ту же ошибку в будущем: тест
на строковое совпадение не заметил бы разницы «пробелы переставлены», а тест
на распарсенное значение поля — заметит любое искажение содержимого.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/messaging/OutboxWriter.java`
> — смотри `write` и аннотацию `@Transactional(propagation = Propagation.MANDATORY)`.
> Открой
> `backend-api/src/main/java/com/batowka/guestbooking/messaging/ContactSharedConsumer.java`
> — смотри `link`: две строки (`users.save`, `outbox.write`) внутри одной
> `@Transactional`. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/messaging/OutboxPublisher.java`
> — смотри `publishPending` и что происходит при исключении из `kafka.send`.

## 3. At-least-once и идемпотентность

«Ровно один раз» на практике — миф в том смысле, что его
нельзя получить бесплатно из самой природы сети: если producer отправил
сообщение и не получил подтверждения (сеть моргнула, таймаут), он не может
надёжно отличить «сообщение потерялось» от «сообщение дошло, а потерялся
только ответ» — и единственный безопасный выбор в такой неопределённости —
повторить отправку, рискуя дублем, а не промолчать, рискуя потерей. Ровно та
же дилемма — на стороне потребления: `OutboxPublisher` сначала шлёт
сообщение (`kafka.send(...).join()`), и только потом помечает
`published_at` — если процесс упадёт ровно между этими двумя шагами,
сообщение уже ушло в Kafka, а строка всё ещё выглядит неотправленной, и
следующий цикл пошлёт её снова. Симметрично на стороне bot-service:
`Consumer.Run` сначала обрабатывает сообщение (`c.core.handle(...)`), и
только потом коммитит offset (`c.reader.CommitMessages`) — комментарий над
методом прямо это называет: «offset коммитится ПОСЛЕ обработки
(at-least-once)». Оба места устроены одинаково намеренно: система выбирает
«может продублировать» вместо «может потерять», а не наоборот.

Раз дубликаты неизбежны, их встречает дедупликация, но с разной строгостью
по двум сторонам, и это тоже осознанный выбор, а не недосмотр. На backend
`ContactSharedConsumer.onEvent` проверяет `event_id` в таблице
`processed_events` перед обработкой и вставляет его туда же строкой ниже —
внутри той же `@Transactional`, что и сама обработка события (изменение
`telegram_chat_id` у пользователя). Это транзакционная гарантия: отметка
«это событие обработано» коммитится атомарно вместе с самим эффектом,
поэтому нет окна, в котором эффект уже применился, а отметка — ещё нет (или
наоборот). У bot-service дедуп совсем другого рода — `consumerCore.seen`
(`bot-service/internal/kafka/consumer.go`) — это `map[string]bool` в памяти
процесса, ограниченный `dedupCap = 1000` записей (`remember` вытесняет самую
старую при переполнении), и он нигде не сохраняется — рестарт bot-service
стирает эту память полностью, а слишком поздний дубликат в принципе может
вытеснить свой же `event_id` из окна и проскочить незамеченным.

Разница в строгости отражает разницу в цене ошибки. На backend дубликат
`CONTACT_SHARED` без дедупа означал бы риск некорректно повторно применить
бизнес-эффект в таблице `users` — реальное состояние системы, источник
истины. У bot-service повторная обработка `WELCOME` в худшем случае означает
второе одинаковое сообщение «Привет, …» человеку в Telegram — раздражает, но
ничего не портит и не задваивает никакого состояния (никакая запись в базе
от этого не меняется дважды). Тратить транзакционную, персистентную
дедупликацию там, где цена промаха — лишнее сообщение в чате, было бы
избыточной инженерией; in-memory best-effort ровно соразмерен риску.

> **Разбор кода:** открой
> `backend-api/src/main/java/com/batowka/guestbooking/messaging/ContactSharedConsumer.java`
> — смотри `onEvent`: проверка `processed_events` и вставка в неё внутри
> одной `@Transactional`. Открой `bot-service/internal/kafka/consumer.go` —
> смотри `consumerCore.seen`, `dedupCap` и `Consumer.Run` (порядок: сначала
> `c.core.handle`, потом `c.reader.CommitMessages`).

## 4. Основы Go на нашем коде

Горутина — это лёгкий поток выполнения, которым управляет сама среда
исполнения Go (runtime), а не операционная система, и запускается ключевым
словом `go` перед вызовом функции. В `bot-service/cmd/bot/main.go` это видно
буквально: `wg.Add(2)`, затем `go func() { defer wg.Done(); poller.Run(ctx) }()`
и `go func() { defer wg.Done(); consumer.Run(ctx) }()` — поллер Telegram и
консьюмер Kafka работают в двух независимых горутинах одновременно, разделяя
один и тот же `ctx`. `context.Context` здесь — механизм отмены: `ctx, stop :=
signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)`
создаёт контекст, который отменяется сам, как только процесс получит Ctrl+C
или SIGTERM. Обе функции `Run` в цикле проверяют `ctx.Err() == nil`
(`poller.go`, `consumer.go`) и выходят, как только контекст отменён, а
`wg.Wait()` в `main` блокирует выход из программы, пока обе горутины
действительно не завершатся — только после этого срабатывают отложенные
`producer.Close()`/`consumer.Close()`. Это и есть graceful shutdown:
процесс не обрывается посреди обработки сообщения, а дожидается, пока обе
петли аккуратно остановятся сами.

Обработка ошибок в Go — без исключений: функция, которая может не
получиться, возвращает пару `(результат, error)`, и вызывающий код обязан
явно проверить `err != nil` в каждой точке вызова. Например, в `poller.go`:
`updates, err := p.api.GetUpdates(ctx, p.offset); if err != nil { ...
log.Printf(...); continue }`, или в `consumer.go`:
`if err := json.Unmarshal(raw, &env); err != nil { log.Printf("битое
событие, пропускаю: %v", err); return }`. Ни `try`, ни `catch` в языке нет
вообще — цена такого стиля в многословности (`if err != nil` повторяется
десятки раз по всему коду), а выгода — каждая точка возможного отказа видна
прямо в тексте функции, а не спрятана в невидимый механизм
распространения исключения вверх по стеку.

Интерфейсы `API` и `ContactPublisher` (оба объявлены в
`bot-service/internal/telegram/`, рядом с тем, кто их использует — `Poller`)
и `Sender` (`bot-service/internal/kafka/consumer.go`) определены на стороне
потребителя, а не реализации: `Poller` зависит от `API` и `ContactPublisher`
как интерфейсов, а не от конкретных `*telegram.Client` и `*kafka.Producer`;
`Consumer` зависит от `Sender`, а не напрямую от `*telegram.Client`. В Go
это работает без явного `implements` — `telegram.Client` удовлетворяет
`kafka.Sender` просто потому, что у него есть метод с подходящей сигнатурой
`SendMessage(ctx, chatID, text, requestContact) error`, и компилятор
проверяет это структурно, а не по объявленному списку интерфейсов. Именно
это делает код тестируемым без моков-фреймворков: `poller_test.go`
определяет `fakeAPI` и `fakePublisher` — обычные структуры с полями-срезами,
которые просто запоминают, что в них вызвали, и всё, никакой библиотеки для
генерации моков не нужно, потому что подставить можно любой тип,
удовлетворяющий интерфейсу. `client_test.go` идёт ещё на шаг дальше и вовсе
не подменяет `Client` — вместо этого `httptest.NewServer(...)` поднимает
настоящий локальный HTTP-сервер на случайном порту, а `NewClient("TEST",
server.URL)` заставляет `Client` ходить туда вместо `https://api.telegram.org`;
код самого `Client` при этом ни строчки не знает, что это тест — он
по-настоящему делает HTTP-запрос, просто не в интернет, а на соседний порт.

> **Разбор кода:** открой `bot-service/cmd/bot/main.go` — смотри `wg.Add(2)`,
> обе горутины `go func()` и `wg.Wait()` в конце. Открой
> `bot-service/internal/telegram/poller.go` — смотри `type ContactPublisher
> interface` и `handle`. Открой
> `bot-service/internal/telegram/poller_test.go` — смотри `fakeAPI` и
> `fakePublisher`.

## 5. Безопасность онбординга

Бот принимает контакт только если он принадлежит тому же человеку, что
написал боту — проверка в `poller.go`, метод `handle`: `if m.From == nil ||
m.Contact.UserID != m.From.ID { log.Printf("контакт не принадлежит
отправителю — игнорирую"); return }`. `from.id` — это Telegram-идентификатор
отправителя сообщения, который подтверждает сам сервер Telegram при каждом
апдейте (клиент этого поля не задаёт и не может подделать, оно приходит из
Bot API). `contact.user_id` — отдельное поле объекта «контакт», и Telegram
заполняет его только когда номер телефона, который присылают боту,
действительно привязан к чьему-то настоящему Telegram-аккаунту — то есть
это тоже не то, что клиент вписывает от руки, а то, что подтверждает сама
платформа. Сравнивая эти два значения, бот проверяет: «телефон, который мне
прислали, принадлежит именно тому Telegram-аккаунту, который сейчас со мной
разговаривает», а не произвольной карточке контакта из телефонной книги,
которую можно переслать боту с чьим угодно номером внутри. Это ровно то,
что описывает `contracts/telegram-inbound.md`: «Пользователь нажал Start и
поделился СВОИМ контактом (bot-service принимает контакт только если
`contact.user_id == from.id`)».

Незнакомцев — то есть и чужой контакт, и телефон, которого нет в белом
списке гостей, — бот и backend игнорируют молча, без единого ответного
сообщения или отличающегося по смыслу лога наружу. На уровне bot-service
это тот же `poller.go`: несовпадение id просто логируется на сервере
(`log.Printf`) и обработка обрывается — пользователь ничего не получает в
чат. На уровне backend `ContactSharedConsumer.handleContactShared` делает
то же самое иначе: `users.findByPhone(phone.get()).ifPresent(user ->
link(user, chatId))` — если телефона нет среди пользователей, `ifPresent`
просто ничего не делает, и комментарий рядом называет это явно: «телефона
нет в белом списке — молча игнорируем (спека этапа 3, §3)». Смысл тишины —
не дать постороннему, который случайно нашёл бота, никакого способа
отличить по ответу «этого номера нет в списке» от «этот номер есть, но
контакт не твой» от «всё вообще сломалось»: снаружи все три ситуации
выглядят одинаково — бот просто не подтверждает привязку. Это тот же
принцип, что уже разобран в `docs/learning/02-spring-security-jwt.md`, §6,
про единый `401` админского логина — не давать злоумышленнику различимый по
ответу сигнал, которым можно было бы что-то прощупывать.

> **Разбор кода:** открой `bot-service/internal/telegram/poller.go` — смотри
> условие `m.Contact.UserID != m.From.ID` внутри `handle`. Открой
> `backend-api/src/main/java/com/batowka/guestbooking/messaging/ContactSharedConsumer.java`
> — смотри `handleContactShared` и `users.findByPhone(...).ifPresent(...)`.
> Открой `contracts/telegram-inbound.md` — смотри раздел про `CONTACT_SHARED`
> и условие `contact.user_id == from.id`.

## Постскриптум: баг, который нашёл живой смоук

На живом смоуке bot-service стартовал раньше, чем backend впервые опубликовал
в `notifications.outbound` — а до первой публикации топик не существовал:
backend создавал его лениво, только при записи. kafka-go consumer group в
этой ситуации не падает и не логирует ошибку — он молча висит без единой
назначенной партиции и не переопрашивает брокер, появился ли топик позже.
Пользователь не получил WELCOME сразу. Само сообщение при этом не потерялось:
Kafka его хранила, и после рестарта бота (когда consumer group уже увидел
существующий топик) WELCOME дошёл — живая демонстрация durability лога.
Корень вылечен детерминированным созданием обоих топиков при старте: backend
объявляет их бинами `NewTopic` (создаёт `KafkaAdmin` при поднятии контекста),
bot-service вызывает идемпотентный `EnsureTopic` перед тем, как начать
читать. Мораль в двух строках: тихие зависания хуже громких ошибок — если бы
consumer упал с исключением «топика нет», баг нашли бы за минуту, а не по
смоуку; и порядок запуска сервисов не должен быть протоколом — то, что
работает, только если A стартует раньше B, рано или поздно сломается именно
потому, что этого никто не гарантировал.
