# Топик `notifications.outbound` (backend-api → bot-service)

1 партиция. Producer: backend-api (через outbox). Consumer group: `bot-service`.
bot-service рендерит уведомление и отправляет в Telegram `chat_id`.

## WELCOME (реализовано с этапа 3)

Одобренный гость привязал Telegram.

```json
{"event_type": "WELCOME", "payload": {"chat_id": 123456789, "name": "Маша"}}
```

## OTP_CODE (контракт зафиксирован, реализация — этап 4)

```json
{"event_type": "OTP_CODE",
 "payload": {"chat_id": 123456789, "code": "482913",
             "action": "CREATE_BOOKING", "expires_at": "2026-08-19T12:05:00Z"}}
```

## BOOKING_CONFIRMED | BOOKING_CANCELLED | BOOKING_RESCHEDULED (этап 4)

```json
{"event_type": "BOOKING_CONFIRMED",
 "payload": {"chat_id": 123456789, "guest_name": "Маша",
             "check_in": "2026-10-10", "check_out": "2026-10-12"}}
```

## ACCESS_REQUEST_RECEIVED (этап 5)

```json
{"event_type": "ACCESS_REQUEST_RECEIVED",
 "payload": {"chat_id": 987654321, "name": "Петя",
             "phone": "+81900000000", "message": "Хочу приехать в марте"}}
```
