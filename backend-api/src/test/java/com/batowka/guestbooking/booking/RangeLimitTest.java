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

import java.time.LocalDate;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class RangeLimitTest extends AbstractIntegrationTest {

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

    private String body(LocalDate in, LocalDate out) {
        return """
                {"checkIn": "%s", "checkOut": "%s"}""".formatted(in, out);
    }

    @Test
    void ровно14НочейМожно() throws Exception {
        Long id = guest("+70000000001", 111L);
        LocalDate in = LocalDate.now(BookingService.JST).plusDays(30);
        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body(in, in.plusDays(14))))
                .andExpect(status().isCreated());
    }

    @Test
    void пятнадцатьНочейВCreate400() throws Exception {
        Long id = guest("+70000000002", 222L);
        LocalDate in = LocalDate.now(BookingService.JST).plusDays(30);
        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body(in, in.plusDays(15))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
    }

    @Test
    void пятнадцатьНочейВReschedule400() throws Exception {
        Long id = guest("+70000000003", 333L);
        LocalDate in = LocalDate.now(BookingService.JST).plusDays(30);
        Long bookingId = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, ?, ?, 'CONFIRMED') returning id""",
                Long.class, id, in, in.plusDays(3));
        mvc.perform(patch("/api/bookings/" + bookingId).cookie(auth(id))
                        .contentType(APPLICATION_JSON)
                        .content(body(in.plusDays(40), in.plusDays(55))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
    }
}
