-- Инвариант «одна живая PENDING-заявка на номер» на уровне БД:
-- проверка кодом в submit() уязвима к гонке двух одновременных POST.
CREATE UNIQUE INDEX uq_access_requests_pending_phone
    ON access_requests (phone) WHERE status = 'PENDING';
