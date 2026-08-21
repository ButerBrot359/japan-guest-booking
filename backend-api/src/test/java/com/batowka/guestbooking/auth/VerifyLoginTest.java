package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.user.Role;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class VerifyLoginTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private static final String PHONE = "+81300000010";

    private void givenFriendAndCodeSent() throws Exception {
        UserAccount friend = new UserAccount();
        friend.setPhone(PHONE);
        friend.setName("Маша");
        friend.setRole(Role.FRIEND);
        friend.setTelegramChatId(565010L);
        users.save(friend);
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"" + PHONE + "\"}"))
                .andExpect(status().isAccepted());
    }

    private String sentCode() {
        String payload = jdbc.queryForObject(
                "select payload::text from outbox where event_type = 'OTP_CODE' order by id desc limit 1",
                String.class);
        return objectMapper.readTree(payload).get("payload").get("code").asString();
    }

    private String verifyBody(String code) {
        return "{\"phone\": \"" + PHONE + "\", \"code\": \"" + code + "\"}";
    }

    @Test
    void validCodeSetsCookie() throws Exception {
        givenFriendAndCodeSent();

        MvcResult result = mvc.perform(post("/api/auth/verify")
                        .contentType(APPLICATION_JSON).content(verifyBody(sentCode())))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("auth=", "HttpOnly", "SameSite=Lax", "Path=/");
        assertThat(jdbc.queryForObject(
                "select status from otp_challenges order by id desc limit 1", String.class))
                .isEqualTo("USED");
    }

    @Test
    void wrongCodeGives400WithoutCookie() throws Exception {
        givenFriendAndCodeSent();

        MvcResult result = mvc.perform(post("/api/auth/verify")
                        .contentType(APPLICATION_JSON).content(verifyBody("000000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"))
                .andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void thirdWrongAttemptBurnsChallenge() throws Exception {
        givenFriendAndCodeSent();
        String realCode = sentCode();

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/verify")
                            .contentType(APPLICATION_JSON).content(verifyBody("000000")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        }
        mvc.perform(post("/api/auth/verify")
                        .contentType(APPLICATION_JSON).content(verifyBody("000000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
        // сгоревший челлендж не принимает даже настоящий код
        mvc.perform(post("/api/auth/verify")
                        .contentType(APPLICATION_JSON).content(verifyBody(realCode)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_CODE"));
    }

    @Test
    void verifyWithoutLoginGives400NoActiveCode() throws Exception {
        UserAccount friend = new UserAccount();
        friend.setPhone(PHONE);
        friend.setName("Маша");
        friend.setRole(Role.FRIEND);
        friend.setTelegramChatId(565010L);
        users.save(friend);

        mvc.perform(post("/api/auth/verify")
                        .contentType(APPLICATION_JSON).content(verifyBody("123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_CODE"));
    }

    @Test
    void verifyUnknownPhoneGives401() throws Exception {
        mvc.perform(post("/api/auth/verify")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81599999999\", \"code\": \"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PHONE"));
    }
}
