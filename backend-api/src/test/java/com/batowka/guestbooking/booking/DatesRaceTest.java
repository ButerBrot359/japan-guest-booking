package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.calendar.BlockedPeriodService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Гонка «гость бронирует vs админ блокирует» на одни даты. Advisory lock
 * сериализует проверку+вставку: победить может максимум одна сторона.
 * Без замка обе транзакции проверили бы «свободно» и обе вставили.
 */
class DatesRaceTest extends AbstractIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired BlockedPeriodService blockedPeriodService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void bookingAndBlockNeverCoexistOnSameDates() throws Exception {
        LocalDate in = LocalDate.parse("2028-03-01");
        LocalDate out = LocalDate.parse("2028-03-05");
        Long guestId = jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values ('+81370000001', 'Маша', 779301) returning id",
                Long.class);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> book = () -> {
                start.await();
                try {
                    bookingService.create(guestId, in, out, null);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            };
            Callable<Boolean> block = () -> {
                start.await();
                try {
                    blockedPeriodService.create(in, out.minusDays(1), "гонка");
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            };
            Future<Boolean> f1 = pool.submit(book);
            Future<Boolean> f2 = pool.submit(block);
            start.countDown();
            boolean booked = f1.get(30, TimeUnit.SECONDS);
            boolean blocked = f2.get(30, TimeUnit.SECONDS);

            // инвариант: не «оба успели»
            Integer bookings = jdbc.queryForObject(
                    "select count(*) from bookings where status in ('PENDING_OTP','CONFIRMED')", Integer.class);
            Integer blocks = jdbc.queryForObject(
                    "select count(*) from blocked_periods", Integer.class);
            assertThat(booked && blocked).as("обе стороны выиграли гонку").isFalse();
            assertThat(booked || blocked).as("хотя бы одна сторона должна была успеть").isTrue();
            assertThat((bookings != null && bookings > 0) && (blocks != null && blocks > 0)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }
}
