package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.calendar.BlockedPeriodRepository;
import com.batowka.guestbooking.common.DatesLock;
import com.batowka.guestbooking.messaging.OutboxWriter;
import com.batowka.guestbooking.otp.OtpService;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import com.batowka.guestbooking.user.UserGoneException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class BookingService {

    public static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    public static final List<BookingStatus> ACTIVE =
            List.of(BookingStatus.PENDING_OTP, BookingStatus.CONFIRMED);
    /** Максимальная длина брони — «не больше 2 недель» (решение владельца, этап 6.5). */
    public static final int MAX_NIGHTS = 14;

    private final BookingRepository bookings;
    private final UserAccountRepository users;
    private final OtpService otp;
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final DatesLock datesLock;
    private final BlockedPeriodRepository blockedPeriods;
    private final TransactionTemplate requiresNew;

    public BookingService(BookingRepository bookings, UserAccountRepository users, OtpService otp,
                          JdbcTemplate jdbc, OutboxWriter outbox, DatesLock datesLock,
                          BlockedPeriodRepository blockedPeriods, PlatformTransactionManager txManager) {
        this.bookings = bookings;
        this.users = users;
        this.otp = otp;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.datesLock = datesLock;
        this.blockedPeriods = blockedPeriods;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record WillReplace(long id, LocalDate checkIn, LocalDate checkOut) {
    }

    public record CreateResult(long bookingId, WillReplace willReplaceBooking) {
    }

    @Transactional
    public CreateResult create(Long userId, LocalDate checkIn, LocalDate checkOut, String comment) {
        // до вычисления willReplaceBooking — иначе новая бронь «заменит» уже
        // состоявшуюся поездку и история потеряется
        completePastBooking(userId);
        UserAccount user = requireTelegramLinked(userId);
        validateDates(checkIn, checkOut);
        // пересечение с СОБСТВЕННОЙ активной бронью — подсказываем перенос
        List<Booking> own = bookings
                .findOverlapping(checkIn, checkOut.minusDays(1), ACTIVE).stream()
                .filter(b -> b.getUser().getId().equals(userId))
                .toList();
        if (!own.isEmpty()) {
            boolean pending = own.stream()
                    .anyMatch(b -> b.getStatus() == BookingStatus.PENDING_OTP);
            if (pending) {
                throw new OverlapsOwnBookingException(
                        "Эти даты держит ваша неподтверждённая бронь — подтвердите её кодом или отмените");
            }
            throw new OverlapsOwnBookingException();
        }
        datesLock.acquire();
        // блокировки админа: exclusion constraint их не видит, проверяем кодом под замком
        if (!blockedPeriods.findOverlapping(checkIn, checkOut.minusDays(1)).isEmpty()) {
            throw new DatesTakenException();
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
        UserAccount user = users.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(UserGoneException::new);
        if (user.getTelegramChatId() == null) {
            throw new TelegramNotLinkedException();
        }
        return user;
    }

    void requireOwnership(long bookingId, Long userId) {
        Long ownerId;
        try {
            ownerId = jdbc.queryForObject(
                    "select user_id from bookings where id = ?", Long.class, bookingId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new BookingNotFoundException();
        }
        if (ownerId == null || !ownerId.equals(userId)) {
            throw new NotYourBookingException();
        }
    }

    private void validateDates(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null || !checkIn.isBefore(checkOut)
                || checkIn.isBefore(LocalDate.now(JST))) {
            throw new InvalidBookingDatesException();
        }
        if (ChronoUnit.DAYS.between(checkIn, checkOut) > MAX_NIGHTS) {
            throw new RangeTooLongException();
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
                                old.getCheckIn(), old.getCheckOut(), "GUEST");
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
                ((java.sql.Date) dates.get("check_out")).toLocalDate(), "GUEST");
    }

    @Transactional
    public void requestReschedule(Long userId, long bookingId,
                                  LocalDate checkIn, LocalDate checkOut) {
        // гость со вчерашней вкладкой не должен переписать даты уже состоявшейся
        // поездки — сначала лениво завершаем прошедшую бронь (как в create/activeBooking)
        completePastBooking(userId);
        UserAccount user = requireTelegramLinked(userId);
        requireOwnership(bookingId, userId);
        validateDates(checkIn, checkOut);
        requireStatus(bookingId, "CONFIRMED");
        otp.issue(user, "RESCHEDULE", Map.of(
                "booking_id", bookingId,
                "check_in", checkIn.toString(),
                "check_out", checkOut.toString()));
    }

    @Transactional
    public void requestCancel(Long userId, long bookingId) {
        // тот же лаг: не дать стереть отменой уже состоявшуюся поездку
        completePastBooking(userId);
        UserAccount user = requireTelegramLinked(userId);
        requireOwnership(bookingId, userId);
        requireStatus(bookingId, "CONFIRMED");
        otp.issue(user, "CANCEL", Map.of("booking_id", bookingId));
    }

    private void requireStatus(long bookingId, String expected) {
        String status;
        try {
            status = jdbc.queryForObject(
                    "select status from bookings where id = ?", String.class, bookingId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new BookingNotFoundException();
        }
        if (!expected.equals(status)) {
            throw new BookingExpiredException();
        }
    }

    private void applyReschedule(UserAccount user, long bookingId, JsonNode payload) {
        LocalDate in = LocalDate.parse(payload.get("check_in").asString());
        LocalDate out = LocalDate.parse(payload.get("check_out").asString());
        datesLock.acquire();
        if (!blockedPeriods.findOverlapping(in, out.minusDays(1)).isEmpty()) {
            throw new DatesTakenException();
        }
        int updated;
        try {
            updated = jdbc.update("""
                    update bookings set check_in = ?, check_out = ?
                    where id = ? and status = 'CONFIRMED'
                    """, in, out, bookingId);
        } catch (DataIntegrityViolationException e) {
            // Даты заняли за 5 минут — вариант A. Исключение откатывает ВСЮ
            // транзакцию confirm, включая пометку челленджа USED: челлендж
            // остаётся PENDING, гость может запросить новый перенос (новый
            // PATCH вытеснит челлендж) или повторить confirm.
            throw new DatesTakenException();
        }
        if (updated == 0) {
            throw new BookingExpiredException();
        }
        notifyBookingEvent(user, "BOOKING_RESCHEDULED", in, out, "GUEST");
    }

    private void applyCancel(UserAccount user, long bookingId) {
        Map<String, Object> dates = jdbc.queryForMap(
                "select check_in, check_out from bookings where id = ?", bookingId);
        int updated = jdbc.update("""
                update bookings set status = 'CANCELLED', cancelled_by = 'GUEST'
                where id = ? and status = 'CONFIRMED'
                """, bookingId);
        if (updated == 0) {
            throw new BookingExpiredException();
        }
        notifyBookingEvent(user, "BOOKING_CANCELLED",
                ((java.sql.Date) dates.get("check_in")).toLocalDate(),
                ((java.sql.Date) dates.get("check_out")).toLocalDate(), "GUEST");
    }

    /** Событие гостю + админу (если у админа привязан Telegram). by: GUEST | ADMIN. */
    void notifyBookingEvent(UserAccount guest, String eventType,
                            LocalDate checkIn, LocalDate checkOut, String by) {
        // гость без Telegram (например, soft-удалённый — WhitelistService.softDelete
        // обнуляет telegram_chat_id) — событие некому слать, но админское уведомление
        // всё равно должно уйти
        if (guest.getTelegramChatId() != null) {
            outboxEvent(guest.getTelegramChatId(), guest, eventType, checkIn, checkOut, by);
        }
        jdbc.query("""
                select telegram_chat_id from users
                where role = 'ADMIN' and telegram_chat_id is not null
                """, rs -> {
            outboxEvent(rs.getLong(1), guest, eventType, checkIn, checkOut, by);
        });
    }

    private void outboxEvent(Long chatId, UserAccount guest, String eventType,
                             LocalDate checkIn, LocalDate checkOut, String by) {
        outbox.write("notifications.outbound", eventType, Map.of(
                "chat_id", chatId,
                "guest_name", guest.getName(),
                "check_in", checkIn.toString(),
                "check_out", checkOut.toString(),
                "by", by));
    }

    @Transactional
    public void resendCode(Long userId, long bookingId) {
        UserAccount user = requireTelegramLinked(userId);
        requireOwnership(bookingId, userId);
        requireNotCancelled(bookingId);
        otp.resend(user, bookingId);
    }

    private void requireNotCancelled(long bookingId) {
        String status;
        try {
            status = jdbc.queryForObject(
                    "select status from bookings where id = ?", String.class, bookingId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new BookingNotFoundException();
        }
        if ("CANCELLED".equals(status)) {
            throw new BookingExpiredException();
        }
    }

    /** Явная отмена своей неподтверждённой брони: даты свободны сразу, чистильщика не ждём. */
    @Transactional
    public void cancelPending(Long userId) {
        Booking pending = bookings
                .findFirstByUserIdAndStatusOrderByIdDesc(userId, BookingStatus.PENDING_OTP)
                .orElseThrow(BookingNotFoundException::new);
        jdbc.update("""
                update bookings set status = 'CANCELLED', cancelled_by = 'GUEST'
                where id = ? and status = 'PENDING_OTP'
                """, pending.getId());
        otp.expireActive(userId, pending.getId());
    }

    /** Активная бронь для /api/me: CONFIRMED, иначе свежайшая PENDING_OTP. */
    @Transactional
    public Optional<Booking> activeBooking(Long userId) {
        completePastBooking(userId);
        return bookings.findFirstByUserIdAndStatusOrderByIdDesc(userId, BookingStatus.CONFIRMED)
                .or(() -> bookings.findFirstByUserIdAndStatusOrderByIdDesc(
                        userId, BookingStatus.PENDING_OTP));
    }

    /**
     * Лениво завершает прошедшие поездки: CONFIRMED с выездом сегодня или раньше → COMPLETED.
     * Атомарный условный UPDATE (урок этапа 5) — без шедулера и без exists-then-update.
     * REQUIRES_NEW: коммитится независимо от вызывающего метода — иначе, например,
     * requestReschedule/requestCancel откатят завершение вместе с BookingExpiredException,
     * и устаревший клиент не увидит честный COMPLETED в БД.
     */
    public void completePastBooking(Long userId) {
        requiresNew.executeWithoutResult(status -> jdbc.update(
                "update bookings set status = 'COMPLETED' "
                        + "where user_id = ? and status = 'CONFIRMED' and check_out <= ?",
                userId, LocalDate.now(JST)));
    }

    /** Гость меняет комментарий своей активной брони; без OTP — поле не критичное. */
    @Transactional
    public void updateComment(Long userId, String comment) {
        users.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(UserGoneException::new);
        completePastBooking(userId);
        Booking active = bookings.findFirstByUserIdAndStatusOrderByIdDesc(userId, BookingStatus.CONFIRMED)
                .or(() -> bookings.findFirstByUserIdAndStatusOrderByIdDesc(
                        userId, BookingStatus.PENDING_OTP))
                .orElseThrow(BookingNotFoundException::new);
        String normalized = (comment == null || comment.isBlank()) ? null : comment.trim();
        // Атомарный условный UPDATE (урок этапа 5) — не exists-then-update гонка
        int n = jdbc.update(
                "update bookings set comment = ? where id = ? and status in ('CONFIRMED', 'PENDING_OTP')",
                normalized, active.getId());
        if (n == 0) {
            throw new BookingNotFoundException();
        }
    }
}
