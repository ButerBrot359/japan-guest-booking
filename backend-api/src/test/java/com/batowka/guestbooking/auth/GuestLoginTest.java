package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.user.Role;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class GuestLoginTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${app.admin.phone}")
    String adminPhone;

    private Long givenFriend(String phone, Long chatId) {
        UserAccount friend = new UserAccount();
        friend.setPhone(phone);
        friend.setName("Маша");
        friend.setRole(Role.FRIEND);
        friend.setTelegramChatId(chatId);
        return users.save(friend).getId();
    }

    @Test
    void loginSendsCodeInsteadOfCookie() throws Exception {
        givenFriend("+81300000001", 565001L);

        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81 300-00-0001\"}"))
                .andExpect(status().isAccepted())
                .andReturn();

        // куки нет — вход завершается только на /auth/verify
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(jdbc.queryForObject(
                "select action from otp_challenges where status = 'PENDING'", String.class))
                .isEqualTo("LOGIN");
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'OTP_CODE'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void loginWithoutTelegramGets409() throws Exception {
        givenFriend("+81300000002", null);

        mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81300000002\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TELEGRAM_NOT_LINKED"));
    }

    @Test
    void repeatedLoginReplacesChallenge() throws Exception {
        givenFriend("+81300000003", 565003L);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/login")
                            .contentType(APPLICATION_JSON)
                            .content("{\"phone\": \"+81300000003\"}"))
                    .andExpect(status().isAccepted());
        }
        // старый челлендж вытеснен, активен ровно один
        assertThat(jdbc.queryForObject(
                "select count(*) from otp_challenges where status = 'PENDING'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void unknownPhoneGets401() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81599999999\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PHONE"));
    }

    @Test
    void adminPhoneCannotUsePasswordlessLogin() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"" + adminPhone + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PHONE"));
    }

    @Test
    void malformedPhoneGets400() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"восемь-девятьсот\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void blankBodyGets400() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void logoutClearsCookie() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("auth=;", "Max-Age=0");
    }

    @Test
    void sixthLoginAttemptInARowGets429() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login")
                            .contentType(APPLICATION_JSON)
                            .content("{\"phone\": \"+81599999999\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81599999999\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
