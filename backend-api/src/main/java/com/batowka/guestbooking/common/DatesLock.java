package com.batowka.guestbooking.common;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Один advisory lock на все операции «проверь пересечение дат и запиши».
 * Exclusion constraint не умеет между таблицами (bookings ↔ blocked_periods),
 * поэтому проверку делает код, а гонку двух транзакций закрывает этот замок:
 * pg_advisory_xact_lock(KEY) держится до конца транзакции взявшего и
 * выстраивает конкурентов в очередь. Для одного гостевого места — бесплатно.
 */
@Component
@RequiredArgsConstructor
public class DatesLock {

    static final long KEY = 4242L;

    private final JdbcTemplate jdbc;

    public void acquire() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // вне транзакции xact-замок отпустился бы сразу — молчаливая дыра
            throw new IllegalStateException("DatesLock.acquire() требует активной транзакции");
        }
        jdbc.execute("select pg_advisory_xact_lock(" + KEY + ")");
    }
}
