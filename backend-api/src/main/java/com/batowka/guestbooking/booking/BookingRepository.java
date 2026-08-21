package com.batowka.guestbooking.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Брони, пересекающие диапазон дней [from, to] включительно.
     * Бронь занимает [checkIn, checkOut] ВКЛЮЧИТЕЛЬНО (V8): checkIn <= to и checkOut >= from.
     */
    @Query("""
            select b from Booking b join fetch b.user
            where b.status in :statuses
              and b.checkIn <= :to
              and b.checkOut >= :from
            """)
    List<Booking> findOverlapping(@Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  @Param("statuses") Collection<BookingStatus> statuses);

    Optional<Booking> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, BookingStatus status);

    /** История посещений: завершённые брони гостя, свежие сверху. */
    List<Booking> findByUserIdAndStatusOrderByCheckInDesc(Long userId, BookingStatus status);

    /** Все брони с данными гостя — для админ-списка. */
    @Query("select b from Booking b join fetch b.user order by b.checkIn desc")
    List<Booking> findAllWithUser();
}
