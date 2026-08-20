-- Набор приветствий гостя (замена users.greeting): случайное показывается на каждый /api/me
CREATE TABLE user_greetings (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    text    VARCHAR(300) NOT NULL
);
CREATE INDEX idx_user_greetings_user ON user_greetings (user_id);

-- Существующее одиночное приветствие переезжает первой строкой набора
INSERT INTO user_greetings (user_id, text)
SELECT id, btrim(greeting) FROM users
WHERE greeting IS NOT NULL AND btrim(greeting) <> '';

ALTER TABLE users DROP COLUMN greeting;

-- COMPLETED = состоявшаяся поездка (история посещений, этап 6.5).
-- Прошедшие CONFIRMED уходят из GiST-исключения и из one_confirmed_booking_per_user —
-- история копится, а новая бронь не упирается в частичный уникальный индекс.
ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
    CHECK (status IN ('PENDING_OTP', 'CONFIRMED', 'CANCELLED', 'COMPLETED'));
