-- Перенос из финального ревью этапа 1: updated_at должен отражать реальные
-- изменения. Триггер надёжнее @UpdateTimestamp — покрывает и не-JPA записи.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER bookings_set_updated_at
    BEFORE UPDATE ON bookings
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
