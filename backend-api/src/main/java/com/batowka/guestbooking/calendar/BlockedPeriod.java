package com.batowka.guestbooking.calendar;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "blocked_periods")
@Getter
@Setter
@NoArgsConstructor
public class BlockedPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Включительно: блокировка «с 1 по 5» занимает и 5-е. */
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(length = 200)
    private String reason;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
