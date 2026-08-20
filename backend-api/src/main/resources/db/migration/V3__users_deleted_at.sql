-- Soft delete: NULL = активен. Телефон уникален, поэтому повторное одобрение
-- ранее удалённого реактивирует запись (история броней возвращается).
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;
