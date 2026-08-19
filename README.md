# Japan Guest Booking

Бронирование дат визитов друзей. Монорепо:

- `backend-api/` — Spring Boot (Java 21), бизнес-логика и PostgreSQL
- `bot-service/` — Go, Telegram-бот (уведомления и онбординг)
- `frontend/` — React + TypeScript + Vite
- `contracts/` — JSON-схемы Kafka-событий между сервисами
- `docs/` — спека, планы, обучающие разборы

Локальная разработка:

### backend-api

```bash
docker compose -f docker-compose.dev.yml up -d   # Postgres + Kafka
cd backend-api && ./mvnw spring-boot:run
```

### bot-service

```bash
docker compose -f docker-compose.dev.yml up -d   # Kafka
cp .env.example .env                              # и вписать BOT_TOKEN от @BotFather
cd bot-service && BOT_TOKEN=$(grep BOT_TOKEN ../.env | cut -d= -f2) go run ./cmd/bot
```

Дизайн: `docs/specs/2026-08-13-japan-guest-booking-design.md`
