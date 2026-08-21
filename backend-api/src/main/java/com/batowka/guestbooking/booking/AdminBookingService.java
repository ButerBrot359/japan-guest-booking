package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.calendar.BlockedPeriodRepository;
import com.batowka.guestbooking.common.DatesLock;
import com.batowka.guestbooking.user.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Операции админа над любыми бронями: без OTP, применяются сразу, гость получает уведомление. */
@Service
@RequiredArgsConstructor
public class AdminBookingService {

    private final BookingRepository bookings;
    private final BlockedPeriodRepository blockedPeriods;
    private final BookingService bookingService;
    private final DatesLock datesLock;
    private final JdbcTemplate jdbc;

    public record BookingRow(long id, String guestName, String guestPhone, LocalDate checkIn,
                             LocalDate checkOut, BookingStatus status, String comment) {
    }

    @Transactional(readOnly = true)
    public List<BookingRow> list() {
        return bookings.findAllWithUser().stream()
                .map(b -> new BookingRow(b.getId(), b.getUser().getName(), b.getUser().getPhone(),
                        b.getCheckIn(), b.getCheckOut(), b.getStatus(), b.getComment()))
                .toList();
    }

    @Transactional
    public void cancel(long bookingId) {
        Booking b = bookings.findById(bookingId).orElseThrow(BookingNotFoundException::new);
        UserAccount guest = b.getUser();
        int updated = jdbc.update("""
                update bookings set status = 'CANCELLED', cancelled_by = 'ADMIN'
                where id = ? and status = 'CONFIRMED'
                """, bookingId);
        if (updated == 0) {
            throw new BookingExpiredException(); // уже отменена
        }
        bookingService.notifyBookingEvent(guest, "BOOKING_CANCELLED",
                b.getCheckIn(), b.getCheckOut(), "ADMIN");
    }

    @Transactional
    public void reschedule(long bookingId, LocalDate checkIn, LocalDate checkOut) {
        Booking b = bookings.findById(bookingId).orElseThrow(BookingNotFoundException::new);
        if (!checkIn.isBefore(checkOut) || checkIn.isBefore(LocalDate.now(BookingService.JST))) {
            throw new InvalidBookingDatesException();
        }
        datesLock.acquire();
        if (!blockedPeriods.findOverlapping(checkIn, checkOut.minusDays(1)).isEmpty()) {
            throw new DatesTakenException();
        }
        int updated;
        try {
            updated = jdbc.update("""
                    update bookings set check_in = ?, check_out = ?
                    where id = ? and status = 'CONFIRMED'
                    """, checkIn, checkOut, bookingId);
        } catch (DataIntegrityViolationException e) {
            throw new DatesTakenException(); // exclusion constraint: пересечение с чужой бронью
        }
        if (updated == 0) {
            throw new BookingExpiredException(); // переносить можно только CONFIRMED
        }
        bookingService.notifyBookingEvent(b.getUser(), "BOOKING_RESCHEDULED",
                checkIn, checkOut, "ADMIN");
    }
}
