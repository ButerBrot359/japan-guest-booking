package com.batowka.guestbooking.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class PendingBookingCleaner {

    private final JdbcTemplate jdbc;

    /** PENDING_OTP без живого челленджа и старше 5 минут → CANCELLED (авто, cancelled_by NULL). */
    @Scheduled(fixedDelay = 120_000)
    @Transactional
    public void cleanExpired() {
        int n = jdbc.update("""
                update bookings b set status = 'CANCELLED'
                where b.status = 'PENDING_OTP'
                  and b.created_at < now() - interval '5 minutes'
                  and not exists (
                      select 1 from otp_challenges c
                      where (c.payload->>'booking_id')::bigint = b.id
                        and c.status = 'PENDING' and c.expires_at > now())
                """);
        if (n > 0) {
            log.info("Отменено протухших pending-броней: {}", n);
        }
    }
}
