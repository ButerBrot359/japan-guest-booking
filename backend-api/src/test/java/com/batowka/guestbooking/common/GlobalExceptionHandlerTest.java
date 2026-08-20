package com.batowka.guestbooking.common;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.auth.JwtAuthFilter;
import com.batowka.guestbooking.auth.JwtService;
import com.batowka.guestbooking.calendar.CalendarService;
import com.batowka.guestbooking.user.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class GlobalExceptionHandlerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean
    CalendarService calendar;

    private Long guest(String phone, Long chatId) {
        return jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, 'Маша', ?) returning id",
                Long.class, phone, chatId);
    }

    private Cookie auth(Long userId) {
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(userId, Role.FRIEND));
    }

    @Test
    void unexpectedExceptionBecomes500WithoutLeakingDetails() throws Exception {
        when(calendar.getCalendar(any(), any()))
                .thenThrow(new IllegalStateException("секретная внутренняя деталь"));

        mvc.perform(get("/api/calendar").param("from", "2026-10-01").param("to", "2026-10-02"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("секретная"))));
    }

    @Test
    void unknownBookingIdGives404WithHonestText() throws Exception {
        Long id = guest("+81350000009", 778109L);
        mvc.perform(post("/api/bookings/999999/resend-code").cookie(auth(id)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Бронь не найдена"));
    }
}
