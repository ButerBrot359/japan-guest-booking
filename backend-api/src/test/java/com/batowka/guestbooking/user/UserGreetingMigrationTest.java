package com.batowka.guestbooking.user;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class UserGreetingMigrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired UserGreetingRepository greetings;

    @Test
    void таблицаЕстьКолонкиНет() {
        Integer greetingsTable = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'user_greetings'",
                Integer.class);
        Integer oldColumn = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'users' and column_name = 'greeting'""", Integer.class);
        assertThat(greetingsTable).isEqualTo(1);
        assertThat(oldColumn).isZero();
    }

    @Test
    void репозиторийПишетИЧитает() {
        Long userId = jdbc.queryForObject(
                "insert into users(phone, name) values ('+70000000020', 'Маша') returning id", Long.class);
        var g = new UserGreeting();
        g.setUserId(userId);
        g.setText("Привет, солнце!");
        greetings.save(g);
        assertThat(greetings.findByUserId(userId)).hasSize(1);
    }

    @Test
    void статусCompletedПроходитCheck() {
        Long userId = jdbc.queryForObject(
                "insert into users(phone, name) values ('+70000000021', 'Маша') returning id", Long.class);
        jdbc.update("""
                insert into bookings(user_id, check_in, check_out, status)
                values (?, date '2026-01-10', date '2026-01-12', 'COMPLETED')""", userId);
    }
}
