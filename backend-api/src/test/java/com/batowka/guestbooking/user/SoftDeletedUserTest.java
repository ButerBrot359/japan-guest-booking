package com.batowka.guestbooking.user;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.auth.JwtAuthFilter;
import com.batowka.guestbooking.auth.JwtService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class SoftDeletedUserTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Long deletedUser(String phone) {
        return jdbc.queryForObject(
                "insert into users(phone, name, deleted_at) values (?, 'Бывший', now()) returning id",
                Long.class, phone);
    }

    @Test
    void deletedUserCannotLogin() throws Exception {
        deletedUser("+81390000001");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81390000001\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PHONE"));
    }

    @Test
    void staleJwtOfDeletedUserGives401AndClearsCookie() throws Exception {
        Long id = deletedUser("+81390000002");
        Cookie stale = new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(id, Role.FRIEND));

        mvc.perform(get("/api/me").cookie(stale))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge(JwtAuthFilter.COOKIE_NAME, 0));
    }
}
