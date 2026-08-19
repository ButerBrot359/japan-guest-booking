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
class SecurityFlowTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwt;

    @Autowired
    JdbcTemplate jdbc;

    private String friendToken() {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name) values ('+81400000001', 'Маша') returning id",
                Long.class);
        return jwt.issue(id, Role.FRIEND);
    }

    @Test
    void calendarIsPublic() throws Exception {
        mvc.perform(get("/api/calendar").param("from", "2026-10-01").param("to", "2026-10-02"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedRouteWithoutTokenGives401InApiFormat() throws Exception {
        mvc.perform(get("/api/some-protected-thing"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void friendOnAdminRouteGives403InApiFormat() throws Exception {
        mvc.perform(get("/api/admin/anything")
                        .cookie(new Cookie(JwtAuthFilter.COOKIE_NAME, friendToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void garbageTokenIsJustAnonymous() throws Exception {
        mvc.perform(get("/api/some-protected-thing")
                        .cookie(new Cookie(JwtAuthFilter.COOKIE_NAME, "мусор")))
                .andExpect(status().isUnauthorized());
    }
}
