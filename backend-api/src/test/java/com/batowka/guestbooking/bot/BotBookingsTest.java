package com.batowka.guestbooking.bot;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BotBookingsTest extends AbstractIntegrationTest {

    // значение зеркалит app.bot.api-token из application.yml (тестовый профиль)
    static final String TOKEN = "test-bot-token";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private Long guest(String phone, Long chatId) {
        return jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, 'Маша', ?) returning id",
                Long.class, phone, chatId);
    }

    @Test
    void linkedGuestGetsActiveAndHistory() throws Exception {
        Long id = guest("+81330000001", 800001L);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-06-01', '2027-06-05', 'CONFIRMED')
                """, id);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2025-05-01', '2025-05-04', 'COMPLETED')
                """, id);

        mvc.perform(get("/api/bot/bookings?chat_id=800001").header("X-Bot-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.active.checkIn").value("2027-06-01"))
                .andExpect(jsonPath("$.active.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.history[0].checkIn").value("2025-05-01"))
                .andExpect(jsonPath("$.history[0].nights").value(3));
    }

    @Test
    void unknownChatIdIsNotLinked() throws Exception {
        mvc.perform(get("/api/bot/bookings?chat_id=999999").header("X-Bot-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.active").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.history").isEmpty());
    }

    @Test
    void missingTokenGets401() throws Exception {
        mvc.perform(get("/api/bot/bookings?chat_id=800001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongTokenGets401() throws Exception {
        mvc.perform(get("/api/bot/bookings?chat_id=800001").header("X-Bot-Token", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingChatIdGets400() throws Exception {
        mvc.perform(get("/api/bot/bookings").header("X-Bot-Token", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
