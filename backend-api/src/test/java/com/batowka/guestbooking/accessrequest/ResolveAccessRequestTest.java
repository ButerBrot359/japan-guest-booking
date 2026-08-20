package com.batowka.guestbooking.accessrequest;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ResolveAccessRequestTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Cookie adminAuth() {
        Long adminId = jdbc.queryForObject("select id from users where role = 'ADMIN'", Long.class);
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(adminId, Role.ADMIN));
    }

    private long pendingRequest(String phone) {
        return jdbc.queryForObject(
                "insert into access_requests(phone, name, message) values (?, 'Незнакомец', 'пустите') returning id",
                Long.class, phone);
    }

    @Test
    void approveCreatesFriendWhoCanLogin() throws Exception {
        long id = pendingRequest("+81314400001");
        mvc.perform(post("/api/admin/access-requests/" + id + "/approve").cookie(adminAuth()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status || ':' || (resolved_at is not null) from access_requests where id = " + id,
                String.class)).isEqualTo("APPROVED:true");
        assertThat(jdbc.queryForObject(
                "select role from users where phone = '+81314400001'", String.class)).isEqualTo("FRIEND");

        // новый друг может логиниться
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81314400001\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void approveReactivatesDeletedUser() throws Exception {
        jdbc.update("insert into users(phone, name, deleted_at) values ('+81314400002', 'Бывший', now())");
        long id = pendingRequest("+81314400002");
        mvc.perform(post("/api/admin/access-requests/" + id + "/approve").cookie(adminAuth()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "select count(*) from users where phone = '+81314400002' and deleted_at is null",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectOnlyMarksRequest() throws Exception {
        long id = pendingRequest("+81314400003");
        mvc.perform(post("/api/admin/access-requests/" + id + "/reject").cookie(adminAuth()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "select status from access_requests where id = " + id, String.class))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "select count(*) from users where phone = '+81314400003'", Integer.class)).isZero();
    }

    @Test
    void doubleResolveGives409() throws Exception {
        long id = pendingRequest("+81314400004");
        mvc.perform(post("/api/admin/access-requests/" + id + "/reject").cookie(adminAuth()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/access-requests/" + id + "/approve").cookie(adminAuth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RESOLVED"));
    }

    @Test
    void listDefaultsToPending() throws Exception {
        pendingRequest("+81314400005");
        long resolved = pendingRequest("+81314400006");
        jdbc.update("update access_requests set status = 'REJECTED' where id = ?", resolved);

        mvc.perform(get("/api/admin/access-requests").cookie(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].phone").value("+81314400005"));
    }

    @Test
    void unknownRequestGives404() throws Exception {
        mvc.perform(post("/api/admin/access-requests/999/approve").cookie(adminAuth()))
                .andExpect(status().isNotFound());
    }
}
