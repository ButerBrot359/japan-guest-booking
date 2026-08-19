package com.batowka.guestbooking.common;

import com.batowka.guestbooking.auth.JwtAuthFilter;
import com.batowka.guestbooking.auth.JwtService;
import com.batowka.guestbooking.auth.SecurityConfig;
import com.batowka.guestbooking.calendar.CalendarController;
import com.batowka.guestbooking.calendar.CalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CalendarController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class})
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CalendarService calendar;

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
}
