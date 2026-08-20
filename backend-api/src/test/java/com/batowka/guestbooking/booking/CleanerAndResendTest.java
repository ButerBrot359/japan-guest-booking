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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CleanerAndResendTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PendingBookingCleaner cleaner;

    private Long guest(String phone, Long chatId) {
        return jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, 'Маша', ?) returning id",
                Long.class, phone, chatId);
    }

    private Cookie auth(Long userId) {
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(userId, Role.FRIEND));
    }

    private long createBooking(Long userId, String in, String out) throws Exception {
        var result = mvc.perform(post("/api/bookings").cookie(auth(userId))
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"%s\", \"checkOut\": \"%s\"}".formatted(in, out)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("bookingId").asLong();
    }

    private String lastCode() {
        String envelope = jdbc.queryForObject("""
                select payload::text from outbox where event_type = 'OTP_CODE'
                order by id desc limit 1
                """, String.class);
        return objectMapper.readTree(envelope).get("payload").get("code").asString();
    }

    @Test
    void resendTooSoonGives429ThenWorksAfterMinute() throws Exception {
        Long id = guest("+81350000001", 777401L);
        long bookingId = createBooking(id, "2027-06-01", "2027-06-05");

        mvc.perform(post("/api/bookings/" + bookingId + "/resend-code").cookie(auth(id)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RESEND_TOO_SOON"));

        jdbc.update("update otp_challenges set created_at = now() - interval '2 minutes'");
        mvc.perform(post("/api/bookings/" + bookingId + "/resend-code").cookie(auth(id)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'OTP_CODE'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void cleanerCancelsStalePendingAndFreesDates() throws Exception {
        Long masha = guest("+81350000002", 777402L);
        Long petya = guest("+81350000003", 777403L);
        long bookingId = createBooking(masha, "2027-07-01", "2027-07-05");
        // протухание: челлендж и бронь в прошлом
        jdbc.update("update otp_challenges set expires_at = now() - interval '1 minute'");
        jdbc.update("update bookings set created_at = now() - interval '10 minutes' where id = ?",
                bookingId);

        cleaner.cleanExpired();

        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, bookingId))
                .isEqualTo("CANCELLED");
        // даты освободились — Петя бронирует их же
        mvc.perform(post("/api/bookings").cookie(auth(petya))
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-07-01\", \"checkOut\": \"2027-07-05\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void cleanerLeavesFreshPendingAlone() throws Exception {
        Long id = guest("+81350000004", 777404L);
        long bookingId = createBooking(id, "2027-08-01", "2027-08-05");

        cleaner.cleanExpired();

        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, bookingId))
                .isEqualTo("PENDING_OTP");
    }
}
