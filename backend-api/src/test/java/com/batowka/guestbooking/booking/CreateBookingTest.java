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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CreateBookingTest extends AbstractIntegrationTest {

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

    private String body(String in, String out) {
        return "{\"checkIn\": \"%s\", \"checkOut\": \"%s\", \"comment\": \"приеду с женой\"}"
                .formatted(in, out);
    }

    @Test
    void createHoldsDatesAndIssuesOtp() throws Exception {
        Long id = guest("+81320000001", 777101L);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2027-06-01", "2027-06-05")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").isNumber())
                .andExpect(jsonPath("$.willReplaceBooking").value(org.hamcrest.Matchers.nullValue()));

        assertThat(jdbc.queryForObject(
                "select status from bookings order by id desc limit 1", String.class))
                .isEqualTo("PENDING_OTP");
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'OTP_CODE'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void takenDatesGive409() throws Exception {
        Long masha = guest("+81320000002", 777102L);
        Long petya = guest("+81320000003", 777103L);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-07-01', '2027-07-05', 'CONFIRMED')
                """, masha);

        mvc.perform(post("/api/bookings").cookie(auth(petya))
                        .contentType(APPLICATION_JSON).content(body("2027-07-03", "2027-07-08")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));
    }

    @Test
    void overlapWithOwnBookingHintsReschedule() throws Exception {
        Long id = guest("+81320000004", 777104L);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-08-01', '2027-08-05', 'CONFIRMED')
                """, id);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2027-08-03", "2027-08-08")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERLAPS_OWN_BOOKING"));
    }

    @Test
    void existingActiveBookingIsReportedAsWillReplace() throws Exception {
        Long id = guest("+81320000005", 777105L);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-09-01', '2027-09-05', 'CONFIRMED')
                """, id);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2027-10-01", "2027-10-05")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.willReplaceBooking.checkIn").value("2027-09-01"));
    }

    @Test
    void withoutTelegramGives409() throws Exception {
        Long id = guest("+81320000006", null);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2027-11-01", "2027-11-05")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TELEGRAM_NOT_LINKED"));
    }

    @Test
    void pastOrInvertedDatesGive400() throws Exception {
        Long id = guest("+81320000007", 777107L);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2020-01-05", "2020-01-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
