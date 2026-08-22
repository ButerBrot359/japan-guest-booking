package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class RateLimitFilterTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private ResultActions postLogin(String body) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body));
    }

    @Test
    void sixthLoginAttemptIsRateLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            postLogin("{\"phone\": \"+79990000001\"}");
        }
        postLogin("{\"phone\": \"+79990000001\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void invalidBodyAlsoConsumesAttempt() throws Exception {
        // раньше @Valid срабатывал до rateLimiter.check — мусорные тела не считались
        for (int i = 0; i < 5; i++) {
            postLogin("{}").andExpect(status().isBadRequest());
        }
        postLogin("{}").andExpect(status().isTooManyRequests());
    }

    @Test
    void bucketsAreIndependent() throws Exception {
        for (int i = 0; i < 6; i++) {
            postLogin("{\"phone\": \"+79990000001\"}");
        }
        // бакет auth исчерпан — у формы заявок свой лимит
        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+79990000002\", \"name\": \"Гость\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void nonLimitedRequestsAreNotCounted() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/calendar?from=2026-09-01&to=2026-09-30"));
        }
        // календарь не расходует бакет: логин проходит фильтр (401 = неизвестный номер, не 429)
        postLogin("{\"phone\": \"+79990000001\"}").andExpect(status().isUnauthorized());
    }
}
