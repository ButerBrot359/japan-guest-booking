package com.batowka.guestbooking.bot;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BotTokenFilterTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void validTokenOutsideBotPathsGrantsNothing() throws Exception {
        // токен бота не даёт доступ к админским (и любым не-bot) эндпоинтам
        mvc.perform(get("/api/admin/bookings").header("X-Bot-Token", "test-bot-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenOnBotPathStillWorks() throws Exception {
        mvc.perform(get("/api/bot/bookings").param("chat_id", "123").header("X-Bot-Token", "test-bot-token"))
                .andExpect(status().isOk());
    }
}
