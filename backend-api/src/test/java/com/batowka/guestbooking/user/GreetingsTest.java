package com.batowka.guestbooking.user;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.auth.JwtAuthFilter;
import com.batowka.guestbooking.auth.JwtService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class GreetingsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private Long guest(String phone) {
        return jdbc.queryForObject(
                "insert into users(phone, name, telegram_chat_id) values (?, 'Маша', 1) returning id",
                Long.class, phone);
    }

    private Cookie adminAuth() {
        Long adminId = jdbc.queryForObject("select id from users where role = 'ADMIN'", Long.class);
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(adminId, Role.ADMIN));
    }

    private Cookie auth(Long userId) {
        return new Cookie(JwtAuthFilter.COOKIE_NAME, jwt.issue(userId, Role.FRIEND));
    }

    @Test
    void путьЦеликом_путЗаменяетНаборИMeОтдаётЭлемент() throws Exception {
        Long id = guest("+70000000030");
        mvc.perform(put("/api/admin/users/" + id + "/greetings").cookie(adminAuth())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"greetings": ["Привет, солнце!", "  ", "", "Ну наконец-то!"]}"""))
                .andExpect(status().isNoContent());
        Integer count = jdbc.queryForObject(
                "select count(*) from user_greetings where user_id = ?", Integer.class, id);
        assertThat(count).isEqualTo(2); // blank отброшены

        String greeting = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(mvc.perform(get("/api/me").cookie(auth(id)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("greeting").asString();
        assertThat(greeting).isIn("Привет, солнце!", "Ну наконец-то!");
    }

    @Test
    void пустойМассивСтираетИMeОтдаётNull() throws Exception {
        Long id = guest("+70000000031");
        jdbc.update("insert into user_greetings(user_id, text) values (?, 'Старое')", id);
        mvc.perform(put("/api/admin/users/" + id + "/greetings").cookie(adminAuth())
                        .contentType(APPLICATION_JSON).content("""
                                {"greetings": []}"""))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").cookie(auth(id)))
                .andExpect(jsonPath("$.greeting").isEmpty());
    }

    @Test
    void путДляУдалённого404() throws Exception {
        Long id = guest("+70000000032");
        jdbc.update("update users set deleted_at = now() where id = ?", id);
        mvc.perform(put("/api/admin/users/" + id + "/greetings").cookie(adminAuth())
                        .contentType(APPLICATION_JSON).content("""
                                {"greetings": ["Привет"]}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void путБезАдминки403() throws Exception {
        Long id = guest("+70000000033");
        mvc.perform(put("/api/admin/users/" + id + "/greetings").cookie(auth(id))
                        .contentType(APPLICATION_JSON).content("""
                                {"greetings": ["Привет"]}"""))
                .andExpect(status().isForbidden());
    }
}
