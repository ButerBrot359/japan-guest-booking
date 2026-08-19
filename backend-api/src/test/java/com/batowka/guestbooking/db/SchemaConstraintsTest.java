package com.batowka.guestbooking.db;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaConstraintsTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private Long createUser(String phone) {
        return jdbc.queryForObject(
                "insert into users(phone, name) values (?, 'Тест') returning id",
                Long.class, phone);
    }

    private void createBooking(Long userId, String checkIn, String checkOut, String status) {
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, ?::date, ?::date, ?)
                """, userId, checkIn, checkOut, status);
    }

    @Test
    void overlappingActiveBookingsAreRejected() {
        Long masha = createUser("+81100000001");
        Long petya = createUser("+81100000002");
        createBooking(masha, "2026-10-10", "2026-10-15", "CONFIRMED");

        assertThatThrownBy(() ->
                createBooking(petya, "2026-10-12", "2026-10-20", "PENDING_OTP"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void backToBackBookingsAreAllowed() {
        Long masha = createUser("+81100000003");
        Long petya = createUser("+81100000004");
        createBooking(masha, "2026-11-01", "2026-11-05", "CONFIRMED");

        // выезд 5-го и заезд 5-го не конфликтуют: [check_in, check_out)
        assertThatCode(() ->
                createBooking(petya, "2026-11-05", "2026-11-08", "CONFIRMED"))
                .doesNotThrowAnyException();
    }

    @Test
    void cancelledBookingDoesNotBlockDates() {
        Long masha = createUser("+81100000005");
        Long petya = createUser("+81100000006");
        createBooking(masha, "2026-12-01", "2026-12-05", "CANCELLED");

        assertThatCode(() ->
                createBooking(petya, "2026-12-01", "2026-12-05", "CONFIRMED"))
                .doesNotThrowAnyException();
    }

    @Test
    void secondConfirmedBookingForSameUserIsRejected() {
        Long masha = createUser("+81100000007");
        createBooking(masha, "2027-01-01", "2027-01-05", "CONFIRMED");

        assertThatThrownBy(() ->
                createBooking(masha, "2027-02-01", "2027-02-05", "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkOutMustBeAfterCheckIn() {
        Long masha = createUser("+81100000008");

        assertThatThrownBy(() ->
                createBooking(masha, "2027-03-05", "2027-03-05", "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
