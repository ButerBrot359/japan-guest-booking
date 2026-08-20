-- Личное приветствие владельца гостю; NULL = фронт покажет «Привет, {имя}!»
ALTER TABLE users ADD COLUMN greeting VARCHAR(300);
