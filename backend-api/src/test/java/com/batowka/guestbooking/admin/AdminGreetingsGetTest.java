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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminGreetingsGetTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Cookie adminAuth() {
        Long adminId = jdbc.queryForObject("select id from users where role = 'ADMIN'", Long.class);
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(adminId, Role.ADMIN));
    }

    private long guest(String phone) {
        return jdbc.queryForObject(
                "insert into users(phone, name) values (?, 'Маша') returning id", Long.class, phone);
    }

    @Test
    void returnsGreetingsInStorageOrder() throws Exception {
        long id = guest("+81350000001");
        jdbc.update("insert into user_greetings(user_id, text) values (?, 'Привет!'), (?, 'С приездом!')", id, id);

        mvc.perform(get("/api/admin/users/" + id + "/greetings").cookie(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("Привет!"))
                .andExpect(jsonPath("$[1]").value("С приездом!"));
    }

    @Test
    void guestWithoutGreetingsGetsEmptyList() throws Exception {
        long id = guest("+81350000002");
        mvc.perform(get("/api/admin/users/" + id + "/greetings").cookie(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void withoutAdminCookieGets401() throws Exception {
        mvc.perform(get("/api/admin/users/1/greetings"))
                .andExpect(status().isUnauthorized());
    }
}
