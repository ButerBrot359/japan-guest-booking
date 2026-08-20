package com.batowka.guestbooking.calendar;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarServiceTest extends AbstractIntegrationTest {

    @Autowired
    CalendarService calendar;

    @Autowired
    JdbcTemplate jdbc;

    private Long givenBooking(String phone, String name, String in, String out, String status) {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name) values (?, ?) returning id",
                Long.class, phone, name);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, ?::date, ?::date, ?)
                """, id, in, out, status);
        return id;
    }

    // отдельный «зритель» с живой сессией — чтобы проверять видимость имени
    // для залогиненного пользователя, не совпадающего с владельцем брони.
    private Long givenViewer(String phone) {
        return jdbc.queryForObject(
                "insert into users(phone, name) values (?, 'Зритель') returning id",
                Long.class, phone);
    }

    private Map<LocalDate, CalendarDay> byDate(List<CalendarDay> days) {
        return days.stream().collect(Collectors.toMap(CalendarDay::date, Function.identity()));
    }

    @Test
    void freeBookedAndBlockedDaysAreMarked() {
        givenBooking("+81300000001", "Маша", "2026-10-10", "2026-10-12", "CONFIRMED");
        Long viewer = givenViewer("+81300000099");
        jdbc.update("""
                insert into blocked_periods(start_date, end_date, reason)
                values ('2026-10-20', '2026-10-21', 'сами в отъезде')
                """);

        List<CalendarDay> days = calendar.getCalendar(
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), viewer);

        assertThat(days).hasSize(31);
        Map<LocalDate, CalendarDay> map = byDate(days);
        assertThat(map.get(LocalDate.parse("2026-10-09")).status()).isEqualTo(DayStatus.FREE);
        assertThat(map.get(LocalDate.parse("2026-10-10")).status()).isEqualTo(DayStatus.BOOKED);
        assertThat(map.get(LocalDate.parse("2026-10-10")).guestName()).isEqualTo("Маша");
        assertThat(map.get(LocalDate.parse("2026-10-11")).status()).isEqualTo(DayStatus.BOOKED);
        // день выезда свободен: [check_in, check_out)
        assertThat(map.get(LocalDate.parse("2026-10-12")).status()).isEqualTo(DayStatus.FREE);
        // блокировка включительно с обеих сторон
        assertThat(map.get(LocalDate.parse("2026-10-20")).status()).isEqualTo(DayStatus.BLOCKED);
        assertThat(map.get(LocalDate.parse("2026-10-21")).status()).isEqualTo(DayStatus.BLOCKED);
        assertThat(map.get(LocalDate.parse("2026-10-21")).guestName()).isNull();
        assertThat(map.get(LocalDate.parse("2026-10-22")).status()).isEqualTo(DayStatus.FREE);
    }

    @Test
    void pendingOtpBookingOccupiesDatesButHidesName() {
        givenBooking("+81300000002", "Петя", "2026-11-10", "2026-11-12", "PENDING_OTP");
        Long viewer = givenViewer("+81300000098");

        // даже залогиненному зрителю имя не показываем — бронь не подтверждена
        Map<LocalDate, CalendarDay> map = byDate(calendar.getCalendar(
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"), viewer));

        assertThat(map.get(LocalDate.parse("2026-11-10")).status()).isEqualTo(DayStatus.BOOKED);
        // имя показываем только для подтверждённых броней
        assertThat(map.get(LocalDate.parse("2026-11-10")).guestName()).isNull();
    }

    @Test
    void cancelledBookingsAreInvisible() {
        givenBooking("+81300000003", "Ира", "2026-12-10", "2026-12-12", "CANCELLED");

        Map<LocalDate, CalendarDay> map = byDate(calendar.getCalendar(
                LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"), null));

        assertThat(map.get(LocalDate.parse("2026-12-10")).status()).isEqualTo(DayStatus.FREE);
    }

    @Test
    void rejectsInvertedAndTooLongRanges() {
        assertThatThrownBy(() -> calendar.getCalendar(
                LocalDate.parse("2026-10-31"), LocalDate.parse("2026-10-01"), null))
                .isInstanceOf(InvalidCalendarRangeException.class);

        assertThatThrownBy(() -> calendar.getCalendar(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2028-01-01"), null))
                .isInstanceOf(InvalidCalendarRangeException.class);
    }

    @Test
    void blockedPeriodShownOverBookingOnOverlap() {
        givenBooking("+81300000004", "Оля", "2027-04-10", "2027-04-15", "CONFIRMED");
        jdbc.update("""
                insert into blocked_periods(start_date, end_date, reason)
                values ('2027-04-12', '2027-04-13', 'ремонт')
                """);

        Map<LocalDate, CalendarDay> map = byDate(calendar.getCalendar(
                LocalDate.parse("2027-04-01"), LocalDate.parse("2027-04-30"), null));

        assertThat(map.get(LocalDate.parse("2027-04-11")).status()).isEqualTo(DayStatus.BOOKED);
        assertThat(map.get(LocalDate.parse("2027-04-12")).status()).isEqualTo(DayStatus.BLOCKED);
        assertThat(map.get(LocalDate.parse("2027-04-12")).guestName()).isNull();
        assertThat(map.get(LocalDate.parse("2027-04-13")).status()).isEqualTo(DayStatus.BLOCKED);
        assertThat(map.get(LocalDate.parse("2027-04-14")).status()).isEqualTo(DayStatus.BOOKED);
    }
}
