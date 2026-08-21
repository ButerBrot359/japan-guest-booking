package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.auth.JwtAuthFilter;
import com.batowka.guestbooking.auth.JwtService;
import com.batowka.guestbooking.user.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class RescheduleCancelTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Long guest(String phone, Long chatId) {
        return jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, 'Маша', ?) returning id",
                Long.class, phone, chatId);
    }

    private Cookie auth(Long userId) {
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(userId, Role.FRIEND));
    }

    private Long confirmedBooking(Long userId, String in, String out) {
        return jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, ?::date, ?::date, 'CONFIRMED') returning id
                """, Long.class, userId, in, out);
    }

    @Test
    void rescheduleAppliesImmediately() throws Exception {
        Long id = guest("+81340000001", 777301L);
        Long bookingId = confirmedBooking(id, "2027-06-01", "2027-06-05");

        mvc.perform(patch("/api/bookings/" + bookingId).cookie(auth(id))
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-10-10\", \"checkOut\": \"2027-10-12\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select check_in::text from bookings where id = ?", String.class, bookingId))
                .isEqualTo("2027-10-10");
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'BOOKING_RESCHEDULED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void cancelAppliesImmediately() throws Exception {
        Long id = guest("+81340000004", 777304L);
        Long bookingId = confirmedBooking(id, "2027-08-01", "2027-08-05");

        mvc.perform(delete("/api/bookings/" + bookingId).cookie(auth(id)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, bookingId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'BOOKING_CANCELLED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void guestCancelEventCarriesByGuest() throws Exception {
        Long id = guest("+81340000007", 777307L);
        Long bookingId = confirmedBooking(id, "2027-10-01", "2027-10-05");

        mvc.perform(delete("/api/bookings/" + bookingId).cookie(auth(id)))
                .andExpect(status().isNoContent());

        String by = jdbc.queryForObject("""
                select payload->'payload'->>'by' from outbox
                where event_type = 'BOOKING_CANCELLED' order by id desc limit 1
                """, String.class);
        assertThat(by).isEqualTo("GUEST");
    }

    @Test
    void rescheduleOntoTakenDatesGives409AndKeepsOldDates() throws Exception {
        Long masha = guest("+81340000002", 777302L);
        Long petya = guest("+81340000003", 777303L);
        Long bookingId = confirmedBooking(masha, "2027-07-01", "2027-07-05");
        // Петя уже занял целевые даты
        confirmedBooking(petya, "2027-07-11", "2027-07-13");

        mvc.perform(patch("/api/bookings/" + bookingId).cookie(auth(masha))
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-07-10\", \"checkOut\": \"2027-07-15\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));

        assertThat(jdbc.queryForObject(
                "select check_in::text from bookings where id = ?", String.class, bookingId))
                .isEqualTo("2027-07-01");
    }

    @Test
    void rescheduleOfNotConfirmedBookingGives409() throws Exception {
        Long id = guest("+81340000008", 777308L);
        Long bookingId = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status, cancelled_by)
                values (?, '2027-11-01', '2027-11-05', 'CANCELLED', 'GUEST') returning id
                """, Long.class, id);

        mvc.perform(patch("/api/bookings/" + bookingId).cookie(auth(id))
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-11-10\", \"checkOut\": \"2027-11-12\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_EXPIRED"));
    }

    @Test
    void cancelOfNotConfirmedBookingGives409() throws Exception {
        Long id = guest("+81340000009", 777309L);
        Long bookingId = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status, cancelled_by)
                values (?, '2027-12-01', '2027-12-05', 'CANCELLED', 'GUEST') returning id
                """, Long.class, id);

        mvc.perform(delete("/api/bookings/" + bookingId).cookie(auth(id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_EXPIRED"));
    }

    @Test
    void rescheduleOfForeignBookingGives403() throws Exception {
        Long masha = guest("+81340000005", 777305L);
        Long petya = guest("+81340000006", 777306L);
        Long bookingId = confirmedBooking(masha, "2027-09-01", "2027-09-05");

        mvc.perform(patch("/api/bookings/" + bookingId).cookie(auth(petya))
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-09-10\", \"checkOut\": \"2027-09-15\"}"))
                .andExpect(status().isForbidden());
    }
}
