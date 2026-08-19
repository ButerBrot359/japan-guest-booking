package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.otp.OtpService;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BookingService {

    static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    static final List<BookingStatus> ACTIVE =
            List.of(BookingStatus.PENDING_OTP, BookingStatus.CONFIRMED);

    private final BookingRepository bookings;
    private final UserAccountRepository users;
    private final OtpService otp;
    private final JdbcTemplate jdbc;

    public record WillReplace(long id, LocalDate checkIn, LocalDate checkOut) {
    }

    public record CreateResult(long bookingId, WillReplace willReplaceBooking) {
    }

    @Transactional
    public CreateResult create(Long userId, LocalDate checkIn, LocalDate checkOut, String comment) {
        UserAccount user = requireTelegramLinked(userId);
        validateDates(checkIn, checkOut);
        // пересечение с СОБСТВЕННОЙ активной бронью — подсказываем перенос
        boolean overlapsOwn = bookings
                .findOverlapping(checkIn, checkOut.minusDays(1), ACTIVE).stream()
                .anyMatch(b -> b.getUser().getId().equals(userId));
        if (overlapsOwn) {
            throw new OverlapsOwnBookingException();
        }
        Long bookingId;
        try {
            bookingId = jdbc.queryForObject("""
                    insert into bookings(user_id, check_in, check_out, status, comment)
                    values (?, ?, ?, 'PENDING_OTP', ?) returning id
                    """, Long.class, userId, checkIn, checkOut, comment);
        } catch (DataIntegrityViolationException e) {
            throw new DatesTakenException();
        }
        otp.issue(user, "CREATE_BOOKING", Map.of("booking_id", bookingId));
        WillReplace willReplace = bookings
                .findFirstByUserIdAndStatusOrderByIdDesc(userId, BookingStatus.CONFIRMED)
                .map(b -> new WillReplace(b.getId(), b.getCheckIn(), b.getCheckOut()))
                .orElse(null);
        return new CreateResult(bookingId, willReplace);
    }

    UserAccount requireTelegramLinked(Long userId) {
        UserAccount user = users.findById(userId).orElseThrow();
        if (user.getTelegramChatId() == null) {
            throw new TelegramNotLinkedException();
        }
        return user;
    }

    void requireOwnership(long bookingId, Long userId) {
        Long ownerId = jdbc.queryForObject(
                "select user_id from bookings where id = ?", Long.class, bookingId);
        if (ownerId == null || !ownerId.equals(userId)) {
            throw new NotYourBookingException();
        }
    }

    private void validateDates(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null || !checkIn.isBefore(checkOut)
                || checkIn.isBefore(LocalDate.now(JST))) {
            throw new InvalidBookingDatesException();
        }
    }
}
