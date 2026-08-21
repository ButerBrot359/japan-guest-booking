package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookingRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    BookingRepository bookings;

    @Autowired
    JdbcTemplate jdbc;

    private Long createUser(String phone, String name) {
        return jdbc.queryForObject(
                "insert into users(phone, name) values (?, ?) returning id",
                Long.class, phone, name);
    }

    @Test
    void findsOnlyBookingsOverlappingTheRange() {
        Long masha = createUser("+81200000001", "Маша");
        Long petya = createUser("+81200000002", "Петя");
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2026-10-10', '2026-10-15', 'CONFIRMED')
                """, masha);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2026-12-01', '2026-12-05', 'CONFIRMED')
                """, petya);

        List<Booking> found = bookings.findOverlapping(
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"),
                List.of(BookingStatus.PENDING_OTP, BookingStatus.CONFIRMED));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getUser().getName()).isEqualTo("Маша");
        assertThat(found.getFirst().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void checkoutDayCountsAsOccupied() {
        Long masha = createUser("+81200000003", "Маша");
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2026-11-01', '2026-11-05', 'CONFIRMED')
                """, masha);

        // диапазон начинается в день выезда — бронь занимает [checkIn, checkOut] включительно (V8)
        List<Booking> found = bookings.findOverlapping(
                LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-30"),
                List.of(BookingStatus.CONFIRMED));

        assertThat(found).hasSize(1);
    }
}
