package com.batowka.guestbooking.bot;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BotApprovalTest extends AbstractIntegrationTest {

    // токен зеркалит app.bot.api-token (в тестах задан через @DynamicPropertySource)
    static final String TOKEN = "test-bot-token";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long adminChatId() {
        // сид-админ существует; привяжем ему telegram_chat_id
        jdbc.update("update users set telegram_chat_id = 900900 where role = 'ADMIN'");
        return 900900L;
    }

    private long pendingRequest(String phone) {
        return jdbc.queryForObject(
                "insert into access_requests(phone, name, message) values (?, 'Незнакомец', 'пустите') returning id",
                Long.class, phone);
    }

    private String body(long chatId) {
        return "{\"adminChatId\": " + chatId + "}";
    }

    @Test
    void approveAddsToWhitelistAndResolves() throws Exception {
        long chat = adminChatId();
        long reqId = pendingRequest("+81340000001");

        mvc.perform(post("/api/bot/access-requests/" + reqId + "/approve")
                        .header("X-Bot-Token", TOKEN).contentType(APPLICATION_JSON).content(body(chat)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status from access_requests where id = ?", String.class, reqId)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                "select count(*) from users where phone = '+81340000001'", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectResolvesWithoutWhitelist() throws Exception {
        long chat = adminChatId();
        long reqId = pendingRequest("+81340000002");

        mvc.perform(post("/api/bot/access-requests/" + reqId + "/reject")
                        .header("X-Bot-Token", TOKEN).contentType(APPLICATION_JSON).content(body(chat)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select status from access_requests where id = ?", String.class, reqId)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "select count(*) from users where phone = '+81340000002'", Integer.class)).isZero();
    }

    @Test
    void nonAdminChatIdGets403() throws Exception {
        long reqId = pendingRequest("+81340000003");
        // 555 — не привязан ни к какому ADMIN
        mvc.perform(post("/api/bot/access-requests/" + reqId + "/approve")
                        .header("X-Bot-Token", TOKEN).contentType(APPLICATION_JSON).content(body(555)))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestChatIdGets403() throws Exception {
        // привязанный ГОСТЬ (не ADMIN) не может одобрять
        jdbc.update("insert into users(phone, name, telegram_chat_id) values ('+81340000099', 'Гость', 700700)");
        long reqId = pendingRequest("+81340000004");
        mvc.perform(post("/api/bot/access-requests/" + reqId + "/approve")
                        .header("X-Bot-Token", TOKEN).contentType(APPLICATION_JSON).content(body(700700)))
                .andExpect(status().isForbidden());
    }

    @Test
    void alreadyResolvedGets409() throws Exception {
        long chat = adminChatId();
        long reqId = pendingRequest("+81340000005");
        jdbc.update("update access_requests set status = 'APPROVED' where id = ?", reqId);

        mvc.perform(post("/api/bot/access-requests/" + reqId + "/approve")
                        .header("X-Bot-Token", TOKEN).contentType(APPLICATION_JSON).content(body(chat)))
                .andExpect(status().isConflict());
    }

    @Test
    void missingTokenGets401() throws Exception {
        long reqId = pendingRequest("+81340000006");
        mvc.perform(post("/api/bot/access-requests/" + reqId + "/approve")
                        .contentType(APPLICATION_JSON).content(body(900900)))
                .andExpect(status().isUnauthorized());
    }
}
