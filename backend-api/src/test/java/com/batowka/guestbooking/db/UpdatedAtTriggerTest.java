package com.batowka.guestbooking.db;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class UpdatedAtTriggerTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void updatedAtChangesOnEveryUpdate() {
        Long userId = jdbc.queryForObject(
                "insert into users(phone, name) values ('+81700000001', 'Маша') returning id",
                Long.class);
        Long bookingId = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-05-01', '2027-05-05', 'CANCELLED') returning id
                """, Long.class, userId);
        OffsetDateTime before = jdbc.queryForObject(
                "select updated_at from bookings where id = ?", OffsetDateTime.class, bookingId);

        jdbc.update("update bookings set status = 'CONFIRMED' where id = ?", bookingId);

        OffsetDateTime after = jdbc.queryForObject(
                "select updated_at from bookings where id = ?", OffsetDateTime.class, bookingId);
        assertThat(after).isAfter(before);
    }
}
