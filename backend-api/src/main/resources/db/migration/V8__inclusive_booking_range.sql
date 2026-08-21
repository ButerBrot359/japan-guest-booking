-- Дом должен «выдохнуть» день между гостями: бронь занимает [check_in, check_out]
-- ВКЛЮЧИТЕЛЬНО (решение владельца, этап 6.6). Заодно отменяем зависшие
-- PENDING_OTP-брони: флоу подтверждения кодом удалён, чистильщика больше нет.
UPDATE bookings SET status = 'CANCELLED', cancelled_by = 'GUEST'
WHERE status = 'PENDING_OTP';

ALTER TABLE bookings DROP CONSTRAINT no_overlapping_bookings;
ALTER TABLE bookings ADD CONSTRAINT no_overlapping_bookings EXCLUDE USING gist (
    daterange(check_in, check_out, '[]') WITH &&
) WHERE (status = 'CONFIRMED');
