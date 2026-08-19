---
tutor:
  stage: 0
  title: "Монорепо, dev-окружение, CI"
  topics:
    - id: monorepo-structure
      section: "Что такое монорепо и почему мы выбрали его"
      code_anchors:
        - path: README.md
          symbol: "Структура репозитория"
          concept: "backend-api/bot-service/frontend/contracts живут в одном репозитории"
        - path: contracts/README.md
          symbol: "Контракты событий Kafka"
          concept: "общий источник правды о формате сообщений для backend-api и bot-service"
        - path: .github/workflows/ci.yml
          symbol: "jobs.backend.defaults.run.working-directory"
          concept: "job ограничен директорией сервиса, чтобы монорепо не раздувало сборку"
      quiz_seeds:
        - "Почему общий contracts/ снижает риск того, что backend обновили, а bot-service тихо сломался?"
        - "Какой минус монорепо назван в тексте и чем он закрыт в ci.yml уже на этапе 0?"
    - id: docker-compose-postgres
      section: "Как читать docker-compose.dev.yml"
      code_anchors:
        - path: docker-compose.dev.yml
          symbol: "services.postgres"
          concept: "проброс порта 5432:5432 и named volume pgdata, переживающий пересоздание контейнера"
        - path: backend-api/src/main/resources/application.yml
          symbol: "spring.datasource"
          concept: "те же dev/dev и localhost:5432, что и переменные окружения postgres в compose"
      quiz_seeds:
        - "Что случится с данными в базе, если убрать volume pgdata из docker-compose.dev.yml?"
        - "Почему psql с ноутбука подключается на localhost:5432, а не на внутренний адрес контейнера?"
    - id: kafka-kraft-listeners
      section: "Kafka в KRaft-режиме: зачем три listener'а"
      code_anchors:
        - path: docker-compose.dev.yml
          symbol: "services.kafka.environment.KAFKA_LISTENERS"
          concept: "три listener'а — EXTERNAL/INTERNAL/CONTROLLER — и почему ADVERTISED_LISTENERS для них разный"
        - path: docker-compose.dev.yml
          symbol: "services.kafka.environment.KAFKA_CONTROLLER_QUORUM_VOTERS"
          concept: "состав Raft-кворума контроллера при единственном брокере"
      quiz_seeds:
        - "Почему INTERNAL рекламирует kafka:19092, а EXTERNAL — localhost:9092, хотя это один и тот же брокер?"
        - "Зачем нужен отдельный CONTROLLER listener, если клиентам он вообще не нужен?"
    - id: ci-anatomy
      section: "Анатомия GitHub Actions"
      code_anchors:
        - path: .github/workflows/ci.yml
          symbol: "jobs.backend"
          concept: "checkout → setup-java → ./mvnw test, с кэшем Maven-зависимостей"
        - path: .github/workflows/ci.yml
          symbol: "jobs.bot"
          concept: "второй job для bot-service — то самое разрастание CI по мере роста монорепо, о котором говорит проза"
        - path: backend-api/src/test/java/com/batowka/guestbooking/AbstractIntegrationTest.java
          symbol: "POSTGRES"
          concept: "Testcontainers сам поднимает Postgres через Docker daemon раннера, без доп. настройки CI"
      quiz_seeds:
        - "Зачем нужен шаг actions/checkout@v4, если раннер и так виртуальная машина?"
        - "Как интеграционные тесты получают доступ к Docker на GitHub-раннере без специальной настройки CI?"
  bugs_and_lessons:
    - "Минус монорепо — раздувание репозитория и риск, что CI-job попытается собрать код, которого ещё нет (например, ещё не написанный bot-service), — закрыт заранее через defaults.run.working-directory: backend-api в ci.yml, а не обнаружен постфактум на живом провале сборки."
  prerequisites: []
---

# Этап 0: монорепо, dev-окружение, CI

Разбор того, что мы собрали в этапе 0 — каркас репозитория, `docker-compose.dev.yml`
и `.github/workflows/ci.yml`. Ссылки — на реальные файлы, чтобы можно было открыть
рядом и сверить.

## 1. Что такое монорепо и почему мы выбрали его

Монорепо — это когда несколько сервисов, у которых разные языки и разное время жизни,
живут в одном git-репозитории, а не в трёх отдельных. У нас это видно прямо из
`README.md`: `backend-api/` (Java), `bot-service/` (Go) и `frontend/` (React) сидят
рядом, плюс общая папка `contracts/`.

Мы выбрали монорепо по трём причинам. Во-первых, общие contracts: `contracts/README.md`
описывает JSON-схемы Kafka-событий (`notifications.outbound`, `telegram.inbound`) как
единственный источник правды о формате сообщений сразу для backend-api и bot-service —
если схема лежит в одном месте и оба сервиса на неё ссылаются, невозможна ситуация
«backend обновили, а Go-бот тихо сломался, потому что в другом репозитории всё ещё
старая версия контракта». Во-вторых, атомарность: когда фича задевает границу
сервисов (например, добавили новое событие — надо и producer в Java поправить, и
consumer в Go), это один PR с одним коммитом, а не три синхронизированных PR в трёх
репозиториях, которые легко раскоординировать. В-третьих, один CI: пока у нас всего
один workflow `.github/workflows/ci.yml`, но по мере роста легко добавить job на
`bot-service` и `frontend` в тот же файл — не нужно поддерживать три отдельных
CI-конфига и синхронизировать между ними версии зависимостей.

Минус монорепо — репозиторий раздувается и job'ы в CI нужно ограничивать по
директории (мы это уже делаем: `defaults.run.working-directory: backend-api` в
`ci.yml`, чтобы job не пытался собрать несуществующий пока Go-код). Для проекта такого
масштаба (три маленьких сервиса одной команды) плюсы явно перевешивают.

> **Разбор кода:** открой `README.md` — смотри раздел «Структура репозитория»:
> обрати внимание, что `backend-api/`, `bot-service/`, `frontend/` и `contracts/`
> перечислены как соседние папки одного репозитория, а не отдельные проекты.
> Открой `contracts/README.md` — смотри, как один и тот же набор JSON-схем
> описан как источник правды сразу для двух языков (Java и Go). Открой
> `.github/workflows/ci.yml` — смотри `jobs.backend.defaults.run.working-directory`:
> это и есть закрытие минуса монорепо, о котором ниже.

## 2. Как читать `docker-compose.dev.yml`

Файл `docker-compose.dev.yml` поднимает два сервиса для локальной разработки —
`postgres` и `kafka`. У `postgres` (`image: postgres:16-alpine`) порт `5432:5432`
проброшен на хост-машину: значит, слева от двоеточия — порт вашего компьютера, справа
— порт внутри контейнера, и вы можете подключиться к базе с ноутбука обычным
`psql -h localhost -p 5432`. Переменные `POSTGRES_DB: guestbooking`,
`POSTGRES_USER: dev`, `POSTGRES_PASSWORD: dev` — это то, что образ `postgres` читает
при первом старте, чтобы создать базу и пользователя; ровно эти значения потом
используются в `spring.datasource.url` из `backend-api/src/main/resources/application.yml`
(`jdbc:postgresql://localhost:5432/guestbooking`, `dev`/`dev`).

Volume `pgdata:/var/lib/postgresql/data` — это named volume (объявлен внизу файла в
секции `volumes: pgdata:`), и он переживает `docker compose down` (но не `down -v`).
Дело в том, что Postgres внутри контейнера хранит все файлы данных по пути
`/var/lib/postgresql/data`, а сам контейнер — эфемерный: удалили контейнер — всё
внутри него пропало. Named volume — это отдельная сущность, которую Docker хранит на
диске хоста независимо от жизни контейнера, и при следующем `docker compose up`
Postgres примонтирует тот же volume и увидит те же файлы, то есть ту же базу с теми же
таблицами. Если бы мы не задали volume, а просто оставили путь без монтирования,
каждый пересоздание контейнера стирало бы всю базу — при разработке это очень
раздражает: заново гонять миграции и заново руками создавать тестовые брони.

> **Разбор кода:** открой `docker-compose.dev.yml` — смотри секцию
> `services.postgres`: сопоставь `ports`, `environment` и `volumes` с тем, что
> только что обсудили. Открой `backend-api/src/main/resources/application.yml`
> — смотри `spring.datasource`: значения `url`/`username`/`password` буквально
> те же `dev`/`dev`/`localhost:5432`, что и в compose.

## 3. Kafka в KRaft-режиме: зачем три listener'а

В `docker-compose.dev.yml` у Kafka (`apache/kafka:3.9.0`) сразу видно, что современный
Kafka (начиная с версии 3.x) больше не нуждается в ZooKeeper — режим называется KRaft
(Kafka Raft), и брокер сам выступает и как data-плоскость, и как control-плоскость
через consensus-протокол Raft. Три listener'а в `KAFKA_LISTENERS` (`INTERNAL://:19092,
CONTROLLER://:9093,EXTERNAL://:9092`) — это три разных сетевых интерфейса с разными
задачами: `EXTERNAL` — для клиентов, подключающихся снаружи docker-сети (то есть с
вашего хоста); `INTERNAL` — для клиентов внутри docker-сети (например, будущий
backend-api или bot-service, если их тоже завернуть в compose); `CONTROLLER` — служебный
канал, по которому брокеры общаются друг с другом для координации кворума (выборы
лидера партиций и т. п.), и клиентам он не нужен вообще.

Почему `KAFKA_ADVERTISED_LISTENERS` (`INTERNAL://kafka:19092,EXTERNAL://localhost:9092`)
отличается для двух listener'ов — это самая частая засада с Kafka в Docker. Advertised
listener — это адрес, который брокер сообщает клиенту в ответ на его первый запрос
(«вот тебе адрес, куда реально ходить за данными этой партиции»). Если клиент сидит
внутри docker-сети (другой контейнер), для него имя хоста `kafka` резолвится через
Docker DNS в правильный IP — поэтому `INTERNAL` рекламирует `kafka:19092`. А если
клиент — это вы с хост-машины, `kafka` для него ничего не значит (Docker DNS вашей
ОС не подчиняется), поэтому `EXTERNAL` рекламирует `localhost:9092`, которое
резолвится в проброшенный порт. `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093` задаёт
состав controller quorum — список `id@host:port` брокеров, которые голосуют в Raft за
метаданные кластера; у нас брокер один (`KAFKA_NODE_ID: 1`), поэтому кворум состоит из
одного голоса — это нормально для dev-окружения, но в проде для отказоустойчивости
делают нечётное число узлов (3, 5).

> **Разбор кода:** открой `docker-compose.dev.yml` — смотри
> `services.kafka.environment.KAFKA_LISTENERS` и `KAFKA_ADVERTISED_LISTENERS`
> рядом друг с другом: обрати внимание, что у первого три значения, а у
> второго — только два (CONTROLLER не рекламируется вообще). Смотри также
> `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093` — один голос при одном узле.

## 4. Анатомия GitHub Actions

Файл `.github/workflows/ci.yml` — это одна пайплайн-конфигурация. Секция `on` говорит,
какие события её запускают: `push: branches: [main]` — прямой пуш в `main`, и
`pull_request` — любой PR в любую ветку (без фильтра веток, значит на PR куда угодно).
Дальше идёт `jobs` — у нас пока один job с именем `backend`, который выполняется на
виртуалке `ubuntu-latest`. `defaults.run.working-directory: backend-api` означает, что
каждый последующий `run`-шаг выполняется как будто вы стоите в папке `backend-api` —
это избавляет от `cd backend-api &&` перед каждой командой.

Внутри job — список `steps`, они выполняются по порядку сверху вниз. Первые два —
готовые actions (переиспользуемые блоки из чужих репозиториев на GitHub, подключаются
через `uses:`): `actions/checkout@v4` клонирует наш репозиторий на виртуалку (без
этого шага в раннере вообще не будет файлов проекта), `actions/setup-java@v4` ставит
JDK нужной версии (`java-version: "21"`, дистрибутив `temurin`) и включает кэш
зависимостей (`cache: maven` — GitHub Actions между запусками сохраняет содержимое
`~/.m2/repository`, поэтому повторные сборки не перекачивают заново все jar'ы
Spring Boot и его транзитивные зависимости). Последний шаг — `run: ./mvnw test`,
обычная команда: запускает Maven Wrapper (гарантирует одну и ту же версию Maven у всех,
кто клонирует репозиторий, без ручной установки) и гоняет тесты модуля `backend-api`.

Testcontainers (см. `backend-api/src/test/java/com/batowka/guestbooking/AbstractIntegrationTest.java`)
внутри этих тестов сам поднимает Postgres в отдельном контейнере — и на
`ubuntu-latest` runner'ах GitHub это работает без дополнительной настройки, потому что
на этих раннерах уже установлен и запущен Docker. Testcontainers под капотом просто
обращается к Docker daemon через сокет (`/var/run/docker.sock`), как это делает любой
`docker run` с вашего ноутбука — раннеру не нужно ничего специально «включать» для
интеграционных тестов, только чтобы Docker в принципе был на машине.

> **Разбор кода:** открой `.github/workflows/ci.yml` — смотри `jobs.backend`
> целиком (шаги `checkout` → `setup-java` → `./mvnw test`) и рядом `jobs.bot`
> — второй job появился позже, уже когда bot-service существовал, ровно то
> разрастание CI, о котором говорилось в §1. Открой
> `backend-api/src/test/java/com/batowka/guestbooking/AbstractIntegrationTest.java`
> — смотри статическое поле `POSTGRES`: это и есть Testcontainers, которому
> ничего не нужно объяснять про раннер.
