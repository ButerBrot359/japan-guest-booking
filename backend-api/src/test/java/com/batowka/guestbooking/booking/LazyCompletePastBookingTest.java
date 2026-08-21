package com.batowka.guestbooking.booking;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Гость со вчерашней вкладкой не должен уметь переписать/отменить уже
 * состоявшуюся поездку — reschedule/cancel обязаны сначала лениво завершить
 * прошедшую бронь (completePastBooking), как это уже делают create/activeBooking.
 */
@AutoConfigureMockMvc
class LazyCompletePastBookingTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Long guest(String phone, Long chatId) {
        return jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, 'Маша', ?) returning id",
                Long.class, phone, chatId);
    }

    private Cookie auth(Long userId) {
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(userId, Role.FRIEND));
    }

    private Long pastConfirmedBooking(Long userId, LocalDate checkIn, LocalDate checkOut) {
        return jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, ?, ?, 'CONFIRMED') returning id""",
                Long.class, userId, checkIn, checkOut);
    }

    private String statusOf(Long bookingId) {
        return jdbc.queryForObject("select status from bookings where id = ?", String.class, bookingId);
    }

    @Test
    void перенос_прошедшей_брони_даёт_BOOKING_EXPIRED_и_завершает_её() throws Exception {
        LocalDate today = LocalDate.now(BookingService.JST);
        Long userId = guest("+70000000101", 101L);
        // check_out «вчера» — состоявшаяся поездка
        Long bookingId = pastConfirmedBooking(userId, today.minusDays(5), today.minusDays(1));

        LocalDate newIn = today.plusDays(30);
        mvc.perform(patch("/api/bookings/" + bookingId).cookie(auth(userId))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"checkIn": "%s", "checkOut": "%s"}""".formatted(newIn, newIn.plusDays(3))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_EXPIRED"));

        assertThat(statusOf(bookingId)).isEqualTo("COMPLETED");
    }

    @Test
    void отмена_прошедшей_брони_даёт_BOOKING_EXPIRED_и_завершает_её() throws Exception {
        LocalDate today = LocalDate.now(BookingService.JST);
        Long userId = guest("+70000000102", 102L);
        // check_out «вчера» — выезд уже состоялся, поездка прошедшая
        Long bookingId = pastConfirmedBooking(userId, today.minusDays(3), today.minusDays(1));

        mvc.perform(delete("/api/bookings/" + bookingId).cookie(auth(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_EXPIRED"));

        assertThat(statusOf(bookingId)).isEqualTo("COMPLETED");
    }

    /**
     * Граница ленивого завершения (находка финального ревью этапа 6.6):
     * check_out = сегодня — дом ещё занят (инклюзивная семантика V8), бронь
     * должна ОСТАТЬСЯ CONFIRMED и держать календарь; только check_out = вчера
     * завершается. Раньше completePastBooking использовал check_out <= today,
     * из-за чего в день выезда бронь выпадала из календаря и другой гость мог
     * заехать в тот же день.
     */
    @Test
    void бронь_с_выездом_сегодня_не_завершается_а_с_выездом_вчера_завершается() throws Exception {
        LocalDate today = LocalDate.now(BookingService.JST);
        Long userId = guest("+70000000103", 103L);
        // check_out «сегодня» — дом занят весь сегодняшний день, бронь ещё активна
        Long bookingId = pastConfirmedBooking(userId, today.minusDays(3), today);

        mvc.perform(patch("/api/bookings/" + bookingId).cookie(auth(userId))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"checkIn": "%s", "checkOut": "%s"}"""
                                .formatted(today.plusDays(30), today.plusDays(33))))
                .andExpect(status().isNoContent());

        assertThat(statusOf(bookingId)).isEqualTo("CONFIRMED");
    }
}
