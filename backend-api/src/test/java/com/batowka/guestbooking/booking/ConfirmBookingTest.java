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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ConfirmBookingTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private Long guest(String phone, Long chatId) {
        return jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, 'Маша', ?) returning id",
                Long.class, phone, chatId);
    }

    private Cookie auth(Long userId) {
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(userId, Role.FRIEND));
    }

    private long createBooking(Long userId, String in, String out) throws Exception {
        var result = mvc.perform(post("/api/bookings").cookie(auth(userId))
                        .contentType(APPLICATION_JSON)
                        .content("{\"checkIn\": \"%s\", \"checkOut\": \"%s\"}".formatted(in, out)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("bookingId").asLong();
    }

    private String lastCode() {
        // парсим JSON, не подстроки — jsonb нормализует форматирование
        String envelope = jdbc.queryForObject("""
                select payload::text from outbox where event_type = 'OTP_CODE'
                order by id desc limit 1
                """, String.class);
        return objectMapper.readTree(envelope).get("payload").get("code").asString();
    }

    @Test
    void confirmMakesBookingConfirmedAndNotifiesGuestAndAdmin() throws Exception {
        // у сидированного админа привязываем chat_id, чтобы проверить админ-уведомление
        jdbc.update("update users set telegram_chat_id = 999000 where role = 'ADMIN'");
        Long id = guest("+81330000001", 777201L);
        long bookingId = createBooking(id, "2027-06-01", "2027-06-05");

        mvc.perform(post("/api/bookings/" + bookingId + "/confirm").cookie(auth(id))
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\": \"" + lastCode() + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, bookingId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox where event_type = 'BOOKING_CONFIRMED'
                """, Integer.class)).isEqualTo(2); // гостю и админу
    }

    @Test
    void confirmReplacesOldActiveBookingAtomically() throws Exception {
        Long id = guest("+81330000002", 777202L);
        Long oldId = jdbc.queryForObject("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2027-07-01', '2027-07-05', 'CONFIRMED') returning id
                """, Long.class, id);
        long newId = createBooking(id, "2027-08-01", "2027-08-05");

        mvc.perform(post("/api/bookings/" + newId + "/confirm").cookie(auth(id))
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\": \"" + lastCode() + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, oldId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "select cancelled_by from bookings where id = ?", String.class, oldId))
                .isEqualTo("GUEST");
        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, newId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'BOOKING_CANCELLED'",
                Integer.class)).isEqualTo(1); // админ без chat_id в этом тесте
    }

    @Test
    void wrongCodeGives400AndBookingStaysPending() throws Exception {
        Long id = guest("+81330000003", 777203L);
        long bookingId = createBooking(id, "2027-09-01", "2027-09-05");

        mvc.perform(post("/api/bookings/" + bookingId + "/confirm").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content("{\"code\": \"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));

        assertThat(jdbc.queryForObject(
                "select status from bookings where id = ?", String.class, bookingId))
                .isEqualTo("PENDING_OTP");
    }

    @Test
    void foreignBookingGives403() throws Exception {
        Long masha = guest("+81330000004", 777204L);
        Long petya = guest("+81330000005", 777205L);
        long bookingId = createBooking(masha, "2027-10-01", "2027-10-05");

        mvc.perform(post("/api/bookings/" + bookingId + "/confirm").cookie(auth(petya))
                        .contentType(APPLICATION_JSON).content("{\"code\": \"123456\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void confirmOfCleanedBookingGives409() throws Exception {
        Long id = guest("+81330000006", 777206L);
        long bookingId = createBooking(id, "2027-11-01", "2027-11-05");
        String code = lastCode();
        jdbc.update("update bookings set status = 'CANCELLED' where id = ?", bookingId);

        mvc.perform(post("/api/bookings/" + bookingId + "/confirm").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content("{\"code\": \"" + code + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_EXPIRED"));
    }
}
