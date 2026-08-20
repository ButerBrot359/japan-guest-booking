package com.batowka.guestbooking.admin;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminUserTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Cookie adminAuth() {
        Long adminId = jdbc.queryForObject("select id from users where role = 'ADMIN'", Long.class);
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(adminId, Role.ADMIN));
    }

    @Test
    void addListDelete() throws Exception {
        mvc.perform(post("/api/admin/users").cookie(adminAuth()).contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81311100001\", \"name\": \"Новый друг\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/admin/users").cookie(adminAuth()))
                .andExpect(status().isOk())
                // [0] — админ из сидера, [1] — добавленный
                .andExpect(jsonPath("$[1].name").value("Новый друг"))
                .andExpect(jsonPath("$[1].deletedAt").isEmpty());

        Long id = jdbc.queryForObject(
                "select id from users where phone = '+81311100001'", Long.class);
        mvc.perform(delete("/api/admin/users/" + id).cookie(adminAuth()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from users where id = " + id, Boolean.class)).isTrue();
    }

    @Test
    void deleteRevokesTelegramLink() throws Exception {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values ('+81311100005', 'Со связкой', 779599) returning id",
                Long.class);
        mvc.perform(delete("/api/admin/users/" + id).cookie(adminAuth()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "select telegram_chat_id is null from users where id = " + id, Boolean.class)).isTrue();
    }

    @Test
    void duplicateLivePhoneGives409() throws Exception {
        jdbc.update("insert into users(phone, name) values ('+81311100002', 'Есть')");
        mvc.perform(post("/api/admin/users").cookie(adminAuth()).contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81311100002\", \"name\": \"Дубль\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
    }

    @Test
    void deleteWithActiveBookingGives409() throws Exception {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values ('+81311100003', 'Маша', 779501) returning id",
                Long.class);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2028-08-01', '2028-08-05', 'CONFIRMED')
                """, id);
        mvc.perform(delete("/api/admin/users/" + id).cookie(adminAuth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_BOOKING_EXISTS"));
    }

    @Test
    void deleteAdminGives409() throws Exception {
        Long adminId = jdbc.queryForObject("select id from users where role = 'ADMIN'", Long.class);
        mvc.perform(delete("/api/admin/users/" + adminId).cookie(adminAuth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_DELETE_ADMIN"));
    }

    @Test
    void patchSetsAndClearsGreeting() throws Exception {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name) values ('+81311100005', 'Миша') returning id", Long.class);

        mvc.perform(patch("/api/admin/users/" + id).cookie(adminAuth())
                        .contentType(APPLICATION_JSON)
                        .content("{\"greeting\": \"Мишаня! Футон проветрен\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "select greeting from users where id = " + id, String.class))
                .isEqualTo("Мишаня! Футон проветрен");

        mvc.perform(patch("/api/admin/users/" + id).cookie(adminAuth())
                        .contentType(APPLICATION_JSON).content("{\"greeting\": null}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "select greeting from users where id = " + id, String.class)).isNull();
    }

    @Test
    void patchDeletedUserGives404() throws Exception {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name, deleted_at) values ('+81311100006', 'Бывший', now()) returning id",
                Long.class);
        mvc.perform(patch("/api/admin/users/" + id).cookie(adminAuth())
                        .contentType(APPLICATION_JSON).content("{\"greeting\": \"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reAddingDeletedReactivatesWithHistory() throws Exception {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name, deleted_at) values ('+81311100004', 'Бывший', now()) returning id",
                Long.class);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status, cancelled_by)
                values (?, '2026-01-01', '2026-01-05', 'CANCELLED', 'GUEST')
                """, id);

        mvc.perform(post("/api/admin/users").cookie(adminAuth()).contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81311100004\", \"name\": \"Вернулся\"}"))
                .andExpect(status().isCreated());

        // та же запись, история броней на месте
        assertThat(jdbc.queryForObject(
                "select count(*) from users where phone = '+81311100004'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select deleted_at is null from users where id = " + id, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from bookings where user_id = " + id, Integer.class)).isEqualTo(1);
    }
}
