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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CancelPendingTest extends AbstractIntegrationTest {

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

    @Test
    void cancelPendingFreesDatesImmediately() throws Exception {
        Long id = guest("+81312200001", 779601L);
        mvc.perform(post("/api/bookings").cookie(auth(id)).contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2028-09-01\", \"checkOut\": \"2028-09-05\"}"))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/bookings/pending").cookie(auth(id)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status || ':' || cancelled_by from bookings order by id desc limit 1",
                String.class)).isEqualTo("CANCELLED:GUEST");
        // челлендж вытеснен — старый код больше не подойдёт
        assertThat(jdbc.queryForObject(
                "select count(*) from otp_challenges where status = 'PENDING'", Integer.class)).isZero();

        // даты сразу свободны для другого гостя
        Long other = guest("+81312200002", 779602L);
        mvc.perform(post("/api/bookings").cookie(auth(other)).contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2028-09-01\", \"checkOut\": \"2028-09-05\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void cancelPendingWithoutPendingGives404() throws Exception {
        Long id = guest("+81312200003", 779603L);
        mvc.perform(delete("/api/bookings/pending").cookie(auth(id)))
                .andExpect(status().isNotFound());
    }

    @Test
    void overlapsOwnPendingHasHonestHint() throws Exception {
        Long id = guest("+81312200004", 779604L);
        mvc.perform(post("/api/bookings").cookie(auth(id)).contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2028-10-01\", \"checkOut\": \"2028-10-05\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/bookings").cookie(auth(id)).contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2028-10-03\", \"checkOut\": \"2028-10-08\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERLAPS_OWN_BOOKING"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("неподтверждённая")));
    }

    @Test
    void resendForCancelledBookingGives409() throws Exception {
        Long id = guest("+81312200005", 779605L);
        Long bookingId = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status, cancelled_by)
                values (?, '2028-11-01', '2028-11-05', 'CANCELLED', 'GUEST') returning id
                """, Long.class, id);
        mvc.perform(post("/api/bookings/" + bookingId + "/resend-code").cookie(auth(id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_EXPIRED"));
    }
}
