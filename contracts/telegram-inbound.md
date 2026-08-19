# Топик `telegram.inbound` (bot-service → backend-api)

1 партиция. Producer: bot-service. Consumer group: `backend-api`.

## CONTACT_SHARED (реализовано с этапа 3)

Пользователь нажал Start и поделился СВОИМ контактом (bot-service принимает
контакт только если `contact.user_id == from.id`).

```json
{"event_type": "CONTACT_SHARED",
 "payload": {"chat_id": 123456789, "phone": "+81300000001",
             "telegram_username": "masha"}}
```

- `phone` — как отдал Telegram (может быть без `+`); нормализация в E.164 —
  обязанность backend.
- `telegram_username` — может отсутствовать/быть null (у пользователя нет
  username).
