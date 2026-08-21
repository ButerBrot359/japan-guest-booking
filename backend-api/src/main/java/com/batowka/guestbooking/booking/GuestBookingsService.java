package com.batowka.guestbooking.booking;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** Активная бронь + история завершённых поездок гостя — общий сбор для /api/me и /api/bot. */
@Service
@RequiredArgsConstructor
public class GuestBookingsService {

    private final BookingService bookingService;
    private final BookingRepository bookings;

    public record PastVisit(LocalDate checkIn, LocalDate checkOut, long nights) {
    }

    public record Snapshot(Optional<Booking> active, List<PastVisit> history) {
    }

    @Transactional
    public Snapshot load(Long userId) {
        Optional<Booking> active = bookingService.activeBooking(userId);
        List<PastVisit> history = bookings
                .findByUserIdAndStatusOrderByCheckInDesc(userId, BookingStatus.COMPLETED)
                .stream()
                .map(b -> new PastVisit(b.getCheckIn(), b.getCheckOut(),
                        ChronoUnit.DAYS.between(b.getCheckIn(), b.getCheckOut())))
                .toList();
        return new Snapshot(active, history);
    }
}
