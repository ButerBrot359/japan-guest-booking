-- PENDING_OTP мёртв: флоу подтверждения кодом удалён в 6.6, строки отменены в V8. Чистим констрейнт.
ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
    CHECK (status IN ('CONFIRMED', 'CANCELLED', 'COMPLETED'));

-- Счётчик попыток публикации: ядовитая строка не должна вечно блокировать очередь (этап 8.1).
ALTER TABLE outbox ADD COLUMN attempts INT NOT NULL DEFAULT 0;
