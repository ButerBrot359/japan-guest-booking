package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminLoginTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwt;

    @Value("${app.admin.phone}")
    String adminPhone;

    @Value("${app.admin.password}")
    String adminPassword;

    @Test
    void correctPasswordGivesAdminToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/admin-login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"" + adminPhone + "\", \"password\": \"" + adminPassword + "\"}"))
                .andExpect(status().isNoContent())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        String token = setCookie.substring(setCookie.indexOf("auth=") + 5, setCookie.indexOf(';'));
        assertThat(jwt.parse(token)).hasValueSatisfying(data ->
                assertThat(data.role()).isEqualTo(Role.ADMIN));
    }

    @Test
    void wrongPasswordGives401() throws Exception {
        mvc.perform(post("/api/auth/admin-login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"" + adminPhone + "\", \"password\": \"не-тот\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void unknownPhoneGivesSame401() throws Exception {
        mvc.perform(post("/api/auth/admin-login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81599990000\", \"password\": \"любой\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}
