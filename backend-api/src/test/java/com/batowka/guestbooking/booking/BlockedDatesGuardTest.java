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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BlockedDatesGuardTest extends AbstractIntegrationTest {

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
    void createOnBlockedDatesGives409() throws Exception {
        Long id = guest("+81350000001", 778101L);
        jdbc.update("insert into blocked_periods(start_date, end_date, reason) values ('2027-09-03', '2027-09-04', 'ремонт')");

        mvc.perform(post("/api/bookings").cookie(auth(id)).contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-09-01\", \"checkOut\": \"2027-09-04\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));
    }

    @Test
    void rescheduleOntoBlockedDatesGives409() throws Exception {
        Long id = guest("+81350000002", 778102L);
        Long bookingId = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-10-01', '2027-10-05', 'CONFIRMED') returning id
                """, Long.class, id);
        jdbc.update("insert into blocked_periods(start_date, end_date) values ('2027-11-02', '2027-11-03')");

        // перенос применяется сразу — блокировка ловится в той же транзакции
        mvc.perform(patch("/api/bookings/" + bookingId).cookie(auth(id)).contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"2027-11-01\", \"checkOut\": \"2027-11-05\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));
    }
}
