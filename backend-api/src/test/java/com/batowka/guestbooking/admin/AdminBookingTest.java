package com.batowka.guestbooking.admin;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminBookingTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Cookie adminAuth() {
        Long adminId = jdbc.queryForObject("select id from users where role = 'ADMIN'", Long.class);
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(adminId, Role.ADMIN));
    }

    private long confirmedBooking(String phone, long chatId, String in, String out) {
        Long userId = jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, 'Маша', ?) returning id",
                Long.class, phone, chatId);
        return jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, ?::date, ?::date, 'CONFIRMED') returning id
                """, Long.class, userId, in, out);
    }

    @Test
    void listShowsGuestNames() throws Exception {
        confirmedBooking("+81380000001", 779401L, "2028-04-01", "2028-04-05");
        mvc.perform(get("/api/admin/bookings").cookie(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].guestName").value("Маша"))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"));
    }

    @Test
    void adminCancelIsImmediateAndNotifiesGuest() throws Exception {
        long id = confirmedBooking("+81380000002", 779402L, "2028-05-01", "2028-05-05");
        mvc.perform(post("/api/admin/bookings/" + id + "/cancel").cookie(adminAuth()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status || ':' || cancelled_by from bookings where id = " + id, String.class))
                .isEqualTo("CANCELLED:ADMIN");
        assertThat(jdbc.queryForObject("""
                select payload->'payload'->>'by' from outbox
                where event_type = 'BOOKING_CANCELLED' order by id desc limit 1
                """, String.class)).isEqualTo("ADMIN");
    }

    @Test
    void adminRescheduleAppliesImmediatelyWithoutOtp() throws Exception {
        long id = confirmedBooking("+81380000003", 779403L, "2028-06-01", "2028-06-05");
        mvc.perform(post("/api/admin/bookings/" + id + "/reschedule").cookie(adminAuth())
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2028-06-10\", \"checkOut\": \"2028-06-15\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select check_in::text from bookings where id = " + id, String.class))
                .isEqualTo("2028-06-10");
        // OTP не выпускался
        assertThat(jdbc.queryForObject("select count(*) from otp_challenges", Integer.class)).isZero();
    }

    @Test
    void adminRescheduleOntoBlockedGives409() throws Exception {
        long id = confirmedBooking("+81380000004", 779404L, "2028-07-01", "2028-07-05");
        jdbc.update("insert into blocked_periods(start_date, end_date) values ('2028-07-12', '2028-07-13')");
        mvc.perform(post("/api/admin/bookings/" + id + "/reschedule").cookie(adminAuth())
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2028-07-11\", \"checkOut\": \"2028-07-14\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));
    }

    @Test
    void cancelUnknownGives404() throws Exception {
        mvc.perform(post("/api/admin/bookings/999999/cancel").cookie(adminAuth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCanCancelBookingOfGuestWithoutTelegram() throws Exception {
        // гость без telegram_chat_id (как после softDelete) с прошедшей CONFIRMED-бронью:
        // отменить некому уведомить гостя, но админское уведомление должно уйти, а не 500
        Long userId = jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values ('+81380000005', 'Без TG', null) returning id",
                Long.class);
        long id = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2026-01-10'::date, '2026-01-15'::date, 'CONFIRMED') returning id
                """, Long.class, userId);

        mvc.perform(post("/api/admin/bookings/" + id + "/cancel").cookie(adminAuth()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status || ':' || cancelled_by from bookings where id = " + id, String.class))
                .isEqualTo("CANCELLED:ADMIN");
    }
}
