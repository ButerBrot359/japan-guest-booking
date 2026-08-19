package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.messaging.OutboxWriter;
import com.batowka.guestbooking.otp.OtpService;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

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
    private final OutboxWriter outbox;

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

    @Transactional
    public void confirm(Long userId, long bookingId, String code) {
        UserAccount user = requireTelegramLinked(userId);
        requireOwnership(bookingId, userId);
        OtpService.ChallengeResult ch = otp.verify(userId, bookingId, code);
        switch (ch.action()) {
            case "CREATE_BOOKING" -> confirmCreate(user, bookingId);
            case "RESCHEDULE" -> applyReschedule(user, bookingId, ch.payload());
            case "CANCEL" -> applyCancel(user, bookingId);
            default -> throw new IllegalStateException("Неизвестный action: " + ch.action());
        }
    }

    private void confirmCreate(UserAccount user, long bookingId) {
        // порядок обязателен: частичный уникальный индекс «одна CONFIRMED на гостя»
        bookings.findFirstByUserIdAndStatusOrderByIdDesc(user.getId(), BookingStatus.CONFIRMED)
                .ifPresent(old -> {
                    int n = jdbc.update("""
                            update bookings set status = 'CANCELLED', cancelled_by = 'GUEST'
                            where id = ? and status = 'CONFIRMED'
                            """, old.getId());
                    if (n == 1) {
                        notifyBookingEvent(user, "BOOKING_CANCELLED",
                                old.getCheckIn(), old.getCheckOut());
                    }
                });
        int updated = jdbc.update("""
                update bookings set status = 'CONFIRMED'
                where id = ? and status = 'PENDING_OTP'
                """, bookingId);
        if (updated == 0) {
            throw new BookingExpiredException();
        }
        Map<String, Object> dates = jdbc.queryForMap(
                "select check_in, check_out from bookings where id = ?", bookingId);
        notifyBookingEvent(user, "BOOKING_CONFIRMED",
                ((java.sql.Date) dates.get("check_in")).toLocalDate(),
                ((java.sql.Date) dates.get("check_out")).toLocalDate());
    }

    // заглушки — реализуются в Task 6; до тех пор недостижимы (челленджи этих
    // action появятся только в Task 6)
    private void applyReschedule(UserAccount user, long bookingId, JsonNode payload) {
        throw new UnsupportedOperationException("Task 6");
    }

    private void applyCancel(UserAccount user, long bookingId) {
        throw new UnsupportedOperationException("Task 6");
    }

    /** Событие гостю + админу (если у админа привязан Telegram). */
    void notifyBookingEvent(UserAccount guest, String eventType,
                            LocalDate checkIn, LocalDate checkOut) {
        outboxEvent(guest.getTelegramChatId(), guest, eventType, checkIn, checkOut);
        jdbc.query("""
                select telegram_chat_id from users
                where role = 'ADMIN' and telegram_chat_id is not null
                """, rs -> {
            outboxEvent(rs.getLong(1), guest, eventType, checkIn, checkOut);
        });
    }

    private void outboxEvent(Long chatId, UserAccount guest, String eventType,
                             LocalDate checkIn, LocalDate checkOut) {
        outbox.write("notifications.outbound", eventType, Map.of(
                "chat_id", chatId,
                "guest_name", guest.getName(),
                "check_in", checkIn.toString(),
                "check_out", checkOut.toString()));
    }
}
