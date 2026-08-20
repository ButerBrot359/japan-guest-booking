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
class AdminBlockedPeriodTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    /** Засеянный AdminSeeder'ом админ (телефон из application.yml). */
    private Cookie adminAuth() {
        Long adminId = jdbc.queryForObject(
                "select id from users where role = 'ADMIN'", Long.class);
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(adminId, Role.ADMIN));
    }

    private Cookie friendAuth() {
        Long id = jdbc.queryForObject(
                "insert into users(phone, name) values ('+81360000001', 'Петя') returning id", Long.class);
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(id, Role.FRIEND));
    }

    @Test
    void crudFlow() throws Exception {
        mvc.perform(post("/api/admin/blocked-periods").cookie(adminAuth()).contentType(APPLICATION_JSON)
                        .content("{\"startDate\": \"2027-12-01\", \"endDate\": \"2027-12-10\", \"reason\": \"ремонт\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());

        mvc.perform(get("/api/admin/blocked-periods").cookie(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reason").value("ремонт"));

        Long id = jdbc.queryForObject("select id from blocked_periods limit 1", Long.class);
        mvc.perform(delete("/api/admin/blocked-periods/" + id).cookie(adminAuth()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from blocked_periods", Integer.class)).isZero();
    }

    @Test
    void overlapWithBookingGives409WithConflictList() throws Exception {
        Long guestId = jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values ('+81360000002', 'Маша', 779201) returning id",
                Long.class);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, '2028-01-05', '2028-01-10', 'CONFIRMED')
                """, guestId);

        mvc.perform(post("/api/admin/blocked-periods").cookie(adminAuth()).contentType(APPLICATION_JSON)
                        .content("{\"startDate\": \"2028-01-08\", \"endDate\": \"2028-01-12\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERLAPS_BOOKING"))
                .andExpect(jsonPath("$.conflicts[0].guestName").value("Маша"))
                .andExpect(jsonPath("$.conflicts[0].checkIn").value("2028-01-05"));
        assertThat(jdbc.queryForObject("select count(*) from blocked_periods", Integer.class)).isZero();
    }

    @Test
    void deleteUnknownGives404() throws Exception {
        mvc.perform(delete("/api/admin/blocked-periods/999").cookie(adminAuth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Блокировка не найдена"));
    }

    @Test
    void friendGets403() throws Exception {
        mvc.perform(get("/api/admin/blocked-periods").cookie(friendAuth()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void invalidRangeGives400() throws Exception {
        mvc.perform(post("/api/admin/blocked-periods").cookie(adminAuth()).contentType(APPLICATION_JSON)
                        .content("{\"startDate\": \"2028-02-10\", \"endDate\": \"2028-02-01\"}"))
                .andExpect(status().isBadRequest());
    }
}
