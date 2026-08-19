package com.batowka.guestbooking.calendar;

import com.batowka.guestbooking.auth.JwtAuthFilter;
import com.batowka.guestbooking.auth.JwtService;
import com.batowka.guestbooking.auth.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CalendarController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class})
class CalendarControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CalendarService calendar;

    @Test
    void returnsDays() throws Exception {
        when(calendar.getCalendar(any(), any())).thenReturn(List.of(
                new CalendarDay(LocalDate.parse("2026-10-10"), DayStatus.BOOKED, "Маша")));

        mvc.perform(get("/api/calendar")
                        .param("from", "2026-10-10")
                        .param("to", "2026-10-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].date").value("2026-10-10"))
                .andExpect(jsonPath("$.days[0].status").value("BOOKED"))
                .andExpect(jsonPath("$.days[0].guestName").value("Маша"));
    }

    @Test
    void invalidRangeBecomes400WithErrorFormat() throws Exception {
        when(calendar.getCalendar(any(), any()))
                .thenThrow(new InvalidCalendarRangeException("Дата конца раньше даты начала"));

        mvc.perform(get("/api/calendar")
                        .param("from", "2026-10-31")
                        .param("to", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Дата конца раньше даты начала"));
    }

    @Test
    void unparsableDateBecomes400() throws Exception {
        mvc.perform(get("/api/calendar")
                        .param("from", "не-дата")
                        .param("to", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void missingParamBecomes400() throws Exception {
        mvc.perform(get("/api/calendar").param("from", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
