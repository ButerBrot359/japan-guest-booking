package com.batowka.guestbooking.calendar;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.auth.JwtAuthFilter;
import com.batowka.guestbooking.auth.JwtService;
import com.batowka.guestbooking.user.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CalendarPrivacyTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private final LocalDate in = LocalDate.now(com.batowka.guestbooking.booking.BookingService.JST).plusDays(10);

    private Long guest(String phone, String name) {
        return jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, ?, 1) returning id",
                Long.class, phone, name);
    }

    private void confirmedBooking(Long userId) {
        jdbc.update("insert into bookings(user_id, check_in, check_out, status) values (?, ?, ?, 'CONFIRMED')",
                userId, in, in.plusDays(3));
    }

    private Cookie auth(Long userId) {
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(userId, Role.FRIEND));
    }

    private String url() {
        return "/api/calendar?from=%s&to=%s".formatted(in, in.plusDays(4));
    }

    @Test
    void анонимНеВидитИмён() throws Exception {
        confirmedBooking(guest("+70000000010", "Миша"));
        mvc.perform(get(url()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].status").value("BOOKED"))
                .andExpect(jsonPath("$.days[0].guestName").isEmpty())
                .andExpect(jsonPath("$.days[0].mine").value(false));
    }

    @Test
    void залогиненныйВидитИмяИСвоёMine() throws Exception {
        Long misha = guest("+70000000011", "Миша");
        confirmedBooking(misha);
        Long viewer = guest("+70000000012", "Катя");
        mvc.perform(get(url()).cookie(auth(viewer)))
                .andExpect(jsonPath("$.days[0].guestName").value("Миша"))
                .andExpect(jsonPath("$.days[0].mine").value(false));
        mvc.perform(get(url()).cookie(auth(misha)))
                .andExpect(jsonPath("$.days[0].mine").value(true));
    }

    @Test
    void удалённыйПользовательСЖивойCookieКакАноним() throws Exception {
        confirmedBooking(guest("+70000000013", "Миша"));
        Long deleted = guest("+70000000014", "Бывший");
        jdbc.update("update users set deleted_at = now() where id = ?", deleted);
        mvc.perform(get(url()).cookie(auth(deleted)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].guestName").isEmpty());
    }
}
