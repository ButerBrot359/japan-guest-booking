package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.user.Role;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
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
class GuestLoginTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountRepository users;

    @Value("${app.admin.phone}")
    String adminPhone;

    private void givenFriend(String phone) {
        UserAccount friend = new UserAccount();
        friend.setPhone(phone);
        friend.setName("Маша");
        friend.setRole(Role.FRIEND);
        users.save(friend);
    }

    @Test
    void knownFriendGetsAuthCookie() throws Exception {
        givenFriend("+81300000001");

        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81 300-00-0001\"}")) // с пробелом и дефисами
                .andExpect(status().isNoContent())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("auth=", "HttpOnly", "SameSite=Lax", "Path=/");
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
}
