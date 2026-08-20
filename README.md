# Japan Guest Booking

Бронирование дат визитов друзей. Монорепо:

- `backend-api/` — Spring Boot (Java 21), бизнес-логика и PostgreSQL
- `bot-service/` — Go, Telegram-бот (уведомления и онбординг)
- `frontend/` — React + TypeScript + Vite
- `contracts/` — JSON-схемы Kafka-событий между сервисами
- `docs/` — спека, планы, обучающие разборы

Полный стек в Docker (ручное тестирование — сайт на :3000, API напрямую на :8080, бот с реальным токеном):

```bash
cp .env.example .env   # один раз: вписать BOT_TOKEN от @BotFather
docker compose -f docker-compose.dev.yml --profile app up --build -d
docker compose -f docker-compose.dev.yml --profile app down   # остановить
```

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

### frontend

```bash
cd frontend && cp .env.example .env   # опционально: VITE_BOT_URL — ссылка на Telegram-бота для подсказки в UI
npm install && npm run dev
```

Дизайн: `docs/specs/2026-08-13-japan-guest-booking-design.md`
