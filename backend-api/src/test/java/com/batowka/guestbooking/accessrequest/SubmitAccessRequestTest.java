package com.batowka.guestbooking.accessrequest;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SubmitAccessRequestTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessRequestService service;

    private static final String BODY =
            "{\"phone\": \"+81313300001\", \"name\": \"Незнакомец\", \"message\": \"друг Миши\"}";

    @Test
    void submitCreatesPendingAndNotifiesLinkedAdmin() throws Exception {
        // админ с привязанным TG (сидер создаёт без chat_id)
        jdbc.update("update users set telegram_chat_id = 779700 where role = 'ADMIN'");

        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject(
                "select status from access_requests limit 1", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                select payload->'payload'->>'name' from outbox
                where event_type = 'ACCESS_REQUEST_RECEIVED'
                """, String.class)).isEqualTo("Незнакомец");
        assertThat(jdbc.queryForObject("""
                select (payload->'payload'->>'request_id')::bigint from outbox
                where event_type = 'ACCESS_REQUEST_RECEIVED'
                """, Long.class)).isPositive();
    }

    @Test
    void repeatWhilePendingIsIdempotent() throws Exception {
        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject(
                "select count(*) from access_requests", Integer.class)).isEqualTo(1);
    }

    @Test
    void memberPhoneGives409() throws Exception {
        jdbc.update("insert into users(phone, name) values ('+81313300002', 'Свой')");
        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81313300002\", \"name\": \"Свой\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
    }

    @Test
    void unlinkedAdminMeansNoEventButRequestSaved() throws Exception {
        // у сидерного админа telegram_chat_id = null
        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject(
                "select count(*) from access_requests", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'ACCESS_REQUEST_RECEIVED'",
                Integer.class)).isZero();
    }

    @Test
    void secondPendingWithSameNumberViolatesUniqueIndex() {
        // прямая проверка констрейнта из V4: TOCTOU-гонка двух POST закрывается на уровне БД,
        // не только чтением в сервисе (см. try/catch вокруг saveAndFlush)
        jdbc.update("""
                insert into access_requests(phone, name, status) values (?, ?, 'PENDING')
                """, "+81313300003", "Первый");

        assertThatThrownBy(() -> jdbc.update("""
                insert into access_requests(phone, name, status) values (?, ?, 'PENDING')
                """, "+81313300003", "Второй"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentSubmitsForSamePhoneNeitherFails() throws Exception {
        // проигравший гонку (TOCTOU: оба прошли pre-check «нет PENDING», оба
        // пытаются вставить) не должен получать 500 из-за rollback-only
        // транзакции после saveAndFlush+catch — оба вызова обязаны завершиться
        // без исключений, а запись в базе должна остаться ровно одна
        String phone = "+81313309001";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Void> submit = () -> {
                start.await();
                service.submit(phone, "Гонщик", null);
                return null;
            };
            Future<Void> f1 = pool.submit(submit);
            Future<Void> f2 = pool.submit(submit);
            start.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from access_requests where phone = ?", Integer.class, phone))
                .isEqualTo(1);
    }

    @Test
    void rateLimitAfterFiveAttempts() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON)
                            .content("{\"phone\": \"+8131330100" + i + "\", \"name\": \"Спамер\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/access-requests").contentType(APPLICATION_JSON)
                        .content("{\"phone\": \"+81313301009\", \"name\": \"Спамер\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
