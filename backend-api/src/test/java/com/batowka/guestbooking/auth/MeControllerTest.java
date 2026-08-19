package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.user.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class MeControllerTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwt;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void authenticatedGuestSeesOwnProfile() throws Exception {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name) values ('+81600000001', 'Маша') returning id",
                Long.class);

        mvc.perform(get("/api/me")
                        .cookie(new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(id, Role.FRIEND))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+81600000001"))
                .andExpect(jsonPath("$.name").value("Маша"))
                .andExpect(jsonPath("$.role").value("FRIEND"))
                .andExpect(jsonPath("$.telegramLinked").value(false))
                .andExpect(jsonPath("$.activeBooking").doesNotExist());
    }

    @Test
    void guestWithConfirmedBookingSeeActiveBookingInProfile() throws Exception {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, ?, ?) returning id",
                Long.class, "+81600000002", "Петя", 777601L);
        jdbc.update(
                "insert into bookings(user_id, check_in, check_out, status) values (?, '2027-09-01', '2027-09-05', 'CONFIRMED')",
                id);

        mvc.perform(get("/api/me")
                        .cookie(new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(id, Role.FRIEND))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBooking.checkIn").value("2027-09-01"))
                .andExpect(jsonPath("$.activeBooking.checkOut").value("2027-09-05"))
                .andExpect(jsonPath("$.activeBooking.status").value("CONFIRMED"));
    }

    @Test
    void anonymousGets401() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
