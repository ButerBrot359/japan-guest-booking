package com.batowka.guestbooking.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface BlockedPeriodRepository extends JpaRepository<BlockedPeriod, Long> {

    @Query("""
            select p from BlockedPeriod p
            where p.startDate <= :to and p.endDate >= :from
            """)
    List<BlockedPeriod> findOverlapping(@Param("from") LocalDate from,
                                        @Param("to") LocalDate to);
}
