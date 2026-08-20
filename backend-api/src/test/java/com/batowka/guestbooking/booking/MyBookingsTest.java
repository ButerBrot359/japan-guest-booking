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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class MyBookingsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private final LocalDate today = LocalDate.now(BookingService.JST);

    private Long guest(String phone) {
        return jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, 'Маша', 1) returning id",
                Long.class, phone);
    }

    private Cookie auth(Long userId) {
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(userId, Role.FRIEND));
    }

    private void booking(Long userId, LocalDate in, LocalDate out, String status, String comment) {
        jdbc.update("insert into bookings(user_id, check_in, check_out, status, comment) values (?, ?, ?, ?, ?)",
                userId, in, out, status, comment);
    }

    @Test
    void прошедшаяConfirmedУходитВИсториюИПерестаётБытьАктивной() throws Exception {
        Long id = guest("+70000000040");
        booking(id, today.minusDays(10), today.minusDays(7), "CONFIRMED", null);
        mvc.perform(get("/api/me/bookings").cookie(auth(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").isEmpty())
                .andExpect(jsonPath("$.history[0].checkIn").value(today.minusDays(10).toString()))
                .andExpect(jsonPath("$.history[0].nights").value(3));
        String status = jdbc.queryForObject(
                "select status from bookings where user_id = ?", String.class, id);
        assertThat(status).isEqualTo("COMPLETED");
    }

    @Test
    void активнаяСКомментариемИИсторияВместе() throws Exception {
        Long id = guest("+70000000041");
        booking(id, today.minusDays(30), today.minusDays(25), "COMPLETED", null);
        booking(id, today.plusDays(5), today.plusDays(8), "CONFIRMED", "Приеду с женой");
        mvc.perform(get("/api/me/bookings").cookie(auth(id)))
                .andExpect(jsonPath("$.active.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.active.comment").value("Приеду с женой"))
                .andExpect(jsonPath("$.history.length()").value(1));
    }

    @Test
    void отменённыеНеПопадаютВИсторию() throws Exception {
        Long id = guest("+70000000042");
        booking(id, today.minusDays(10), today.minusDays(7), "CANCELLED", null);
        mvc.perform(get("/api/me/bookings").cookie(auth(id)))
                .andExpect(jsonPath("$.history.length()").value(0));
    }

    @Test
    void безCookie401() throws Exception {
        mvc.perform(get("/api/me/bookings")).andExpect(status().isUnauthorized());
    }
}
