package com.batowka.guestbooking.calendar;

import com.batowka.guestbooking.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BlockedPeriodRepositoryTest extends AbstractIntegrationTest {

    @Autowired BlockedPeriodRepository repo;
    @Autowired JdbcTemplate jdbc;

    @Test
    void findsTouchingAndMissesDisjoint() {
        jdbc.update("insert into blocked_periods(start_date, end_date) values ('2027-05-10', '2027-05-15')");

        // касание границ включительно
        assertThat(repo.findOverlapping(LocalDate.parse("2027-05-15"), LocalDate.parse("2027-05-20"))).hasSize(1);
        assertThat(repo.findOverlapping(LocalDate.parse("2027-05-01"), LocalDate.parse("2027-05-10"))).hasSize(1);
        // внутри
        assertThat(repo.findOverlapping(LocalDate.parse("2027-05-12"), LocalDate.parse("2027-05-13"))).hasSize(1);
        // мимо с обеих сторон
        assertThat(repo.findOverlapping(LocalDate.parse("2027-05-01"), LocalDate.parse("2027-05-09"))).isEmpty();
        assertThat(repo.findOverlapping(LocalDate.parse("2027-05-16"), LocalDate.parse("2027-05-20"))).isEmpty();
    }
}
