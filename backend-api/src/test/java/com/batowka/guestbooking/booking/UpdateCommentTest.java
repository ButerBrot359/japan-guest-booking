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
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class UpdateCommentTest extends AbstractIntegrationTest {

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

    @Test
    void обновляетКомментарийСвоейАктивнойБрони() throws Exception {
        Long id = guest("+70000000050");
        jdbc.update("insert into bookings(user_id, check_in, check_out, status) values (?, ?, ?, 'CONFIRMED')",
                id, today.plusDays(5), today.plusDays(8));
        mvc.perform(patch("/api/bookings/active").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content("""
                                {"comment": "Приеду с женой"}"""))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select comment from bookings where user_id = ?", String.class, id))
                .isEqualTo("Приеду с женой");
    }

    @Test
    void блankСтираетКомментарий() throws Exception {
        Long id = guest("+70000000051");
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status, comment)
                values (?, ?, ?, 'CONFIRMED', 'старый')""", id, today.plusDays(5), today.plusDays(8));
        mvc.perform(patch("/api/bookings/active").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content("""
                                {"comment": "   "}"""))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select comment from bookings where user_id = ?", String.class, id))
                .isNull();
    }

    @Test
    void нетАктивнойБрони404() throws Exception {
        Long id = guest("+70000000052");
        mvc.perform(patch("/api/bookings/active").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content("""
                                {"comment": "Привет"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void прошедшаяБроньНеРедактируется404() throws Exception {
        Long id = guest("+70000000053");
        jdbc.update("insert into bookings(user_id, check_in, check_out, status) values (?, ?, ?, 'CONFIRMED')",
                id, today.minusDays(10), today.minusDays(7));
        mvc.perform(patch("/api/bookings/active").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content("""
                                {"comment": "Поздно"}"""))
                .andExpect(status().isNotFound());
    }
}
