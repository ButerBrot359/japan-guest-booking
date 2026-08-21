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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CreateBookingTest extends AbstractIntegrationTest {

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

    private String body(String in, String out) {
        return "{\"checkIn\": \"%s\", \"checkOut\": \"%s\", \"comment\": \"приеду с женой\"}"
                .formatted(in, out);
    }

    @Test
    void createConfirmsImmediately() throws Exception {
        Long id = guest("+81320000001", 777101L);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2027-06-01", "2027-06-05")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").isNumber());

        assertThat(jdbc.queryForObject(
                "select status from bookings order by id desc limit 1", String.class))
                .isEqualTo("CONFIRMED");
        // ОТП больше не выпускается, уведомление о брони уходит сразу
        assertThat(jdbc.queryForObject(
                "select count(*) from otp_challenges", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'BOOKING_CONFIRMED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void newBookingReplacesOldConfirmed() throws Exception {
        Long id = guest("+81320000004", 777104L);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-08-01', '2027-08-05', 'CONFIRMED')
                """, id);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2027-09-01", "2027-09-03")))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject(
                "select status from bookings where check_in = '2027-08-01'", String.class))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "select status from bookings where check_in = '2027-09-01'", String.class))
                .isEqualTo("CONFIRMED");
    }

    @Test
    void takenDatesGive409() throws Exception {
        Long masha = guest("+81320000002", 777102L);
        Long petya = guest("+81320000003", 777103L);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-07-01', '2027-07-05', 'CONFIRMED')
                """, masha);

        mvc.perform(post("/api/bookings").cookie(auth(petya))
                        .contentType(APPLICATION_JSON).content(body("2027-07-03", "2027-07-08")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));
    }

    @Test
    void withoutTelegramGives409() throws Exception {
        Long id = guest("+81320000006", null);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2027-11-01", "2027-11-05")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TELEGRAM_NOT_LINKED"));
    }

    @Test
    void конфликтДатНеОткатываетСтаруюБрониИНеШлётОтменуВOutbox() throws Exception {
        // у гостя уже есть CONFIRMED-бронь; он пытается забронировать даты,
        // занятые ДРУГИМ гостем — вся транзакция (включая отмену старой брони
        // и её outbox-запись) должна откатиться
        Long masha = guest("+81320000008", 777108L);
        Long petya = guest("+81320000009", 777109L);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-10-01', '2027-10-05', 'CONFIRMED')
                """, petya);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-10-10', '2027-10-15', 'CONFIRMED')
                """, masha);

        mvc.perform(post("/api/bookings").cookie(auth(masha))
                        .contentType(APPLICATION_JSON).content(body("2027-10-02", "2027-10-04")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));

        assertThat(jdbc.queryForObject(
                "select status from bookings where check_in = '2027-10-10'", String.class))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'BOOKING_CANCELLED'", Integer.class))
                .isZero();
    }

    @Test
    void checkoutDayIsTakenForNextGuest() throws Exception {
        Long masha = guest("+81320000005", 777105L);
        Long petya = guest("+81320000006", 777106L);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-07-01', '2027-07-05', 'CONFIRMED')
                """, masha);

        // заезд в день выезда предыдущего гостя запрещён (решение владельца, этап 6.6)
        mvc.perform(post("/api/bookings").cookie(auth(petya))
                        .contentType(APPLICATION_JSON).content(body("2027-07-05", "2027-07-08")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATES_TAKEN"));
    }

    @Test
    void pastOrInvertedDatesGive400() throws Exception {
        Long id = guest("+81320000007", 777107L);

        mvc.perform(post("/api/bookings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content(body("2020-01-05", "2020-01-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
