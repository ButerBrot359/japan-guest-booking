# Контракты событий Kafka

Язык-нейтральное описание сообщений между backend-api (Java) и bot-service
(Go). Код обоих сервисов пишется против этих файлов.

- [envelope.md](envelope.md) — конверт всех событий и правила эволюции
- [notifications-outbound.md](notifications-outbound.md) — backend → bot
- [telegram-inbound.md](telegram-inbound.md) — bot → backend
