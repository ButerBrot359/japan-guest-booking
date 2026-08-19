package com.batowka.guestbooking.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Брони, пересекающие диапазон дней [from, to] включительно.
     * Бронь занимает [checkIn, checkOut), поэтому: checkIn <= to и checkOut > from.
     */
    @Query("""
            select b from Booking b join fetch b.user
            where b.status in :statuses
              and b.checkIn <= :to
              and b.checkOut > :from
            """)
    List<Booking> findOverlapping(@Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  @Param("statuses") Collection<BookingStatus> statuses);
}
