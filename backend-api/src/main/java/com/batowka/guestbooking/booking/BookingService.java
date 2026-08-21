package com.batowka.guestbooking.booking;

import com.batowka.guestbooking.calendar.BlockedPeriodRepository;
import com.batowka.guestbooking.common.DatesLock;
import com.batowka.guestbooking.messaging.OutboxWriter;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class BookingService {

    public static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    public static final List<BookingStatus> ACTIVE = List.of(BookingStatus.CONFIRMED);
    /** Максимальная длина брони — «не больше 2 недель» (решение владельца, этап 6.5). */
    public static final int MAX_NIGHTS = 14;

    private final BookingRepository bookings;
    private final UserAccountRepository users;
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final DatesLock datesLock;
    private final BlockedPeriodRepository blockedPeriods;
    private final TransactionTemplate requiresNew;

    public BookingService(BookingRepository bookings, UserAccountRepository users,
                          JdbcTemplate jdbc, OutboxWriter outbox, DatesLock datesLock,
                          BlockedPeriodRepository blockedPeriods, PlatformTransactionManager txManager) {
        this.bookings = bookings;
        this.users = users;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.datesLock = datesLock;
        this.blockedPeriods = blockedPeriods;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record CreateResult(long bookingId) {
    }

    @Transactional
    public CreateResult create(Long userId, LocalDate checkIn, LocalDate checkOut, String comment) {
        // до отмены старой брони — иначе новая «заменит» уже состоявшуюся поездку
        completePastBooking(userId);
        UserAccount user = requireTelegramLinked(userId);
        validateDates(checkIn, checkOut);
        datesLock.acquire();
        // блокировки админа: exclusion constraint их не видит, проверяем кодом под замком
        if (!blockedPeriods.findOverlapping(checkIn, checkOut).isEmpty()) {
            throw new DatesTakenException();
        }
        // сначала отмена старой CONFIRMED: освобождает частичный уникальный индекс
        // «одна CONFIRMED на гостя» и даты в exclusion constraint для новой брони
        bookings.findFirstByUserIdAndStatusOrderByIdDesc(userId, BookingStatus.CONFIRMED)
                .ifPresent(old -> {
                    int n = jdbc.update("""
                            update bookings set status = 'CANCELLED', cancelled_by = 'GUEST'
                            where id = ? and status = 'CONFIRMED'
                            """, old.getId());
                    // guard: если админ уже отменил старую бронь в этот же момент (гонка),
                    // UPDATE затронет 0 строк — не шлём гостю дублирующее уведомление
                    // об отмене поверх уже отправленного админским cancel
                    if (n == 1) {
                        notifyBookingEvent(user, "BOOKING_CANCELLED",
                                old.getCheckIn(), old.getCheckOut(), "GUEST");
                    }
                });
        Long bookingId;
        try {
            bookingId = jdbc.queryForObject("""
                    insert into bookings(user_id, check_in, check_out, status, comment)
                    values (?, ?, ?, 'CONFIRMED', ?) returning id
                    """, Long.class, userId, checkIn, checkOut, comment);
        } catch (DataIntegrityViolationException e) {
            // чужая бронь заняла даты — откат отменит и отмену старой брони
            throw new DatesTakenException();
        }
        notifyBookingEvent(user, "BOOKING_CONFIRMED", checkIn, checkOut, "GUEST");
        return new CreateResult(bookingId);
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
    public void reschedule(Long userId, long bookingId, LocalDate checkIn, LocalDate checkOut) {
        // гость со вчерашней вкладкой не должен переписать даты состоявшейся поездки
        completePastBooking(userId);
        UserAccount user = requireTelegramLinked(userId);
        requireOwnership(bookingId, userId);
        validateDates(checkIn, checkOut);
        datesLock.acquire();
        if (!blockedPeriods.findOverlapping(checkIn, checkOut).isEmpty()) {
            throw new DatesTakenException();
        }
        int updated;
        try {
            updated = jdbc.update("""
                    update bookings set check_in = ?, check_out = ?
                    where id = ? and status = 'CONFIRMED'
                    """, checkIn, checkOut, bookingId);
        } catch (DataIntegrityViolationException e) {
            throw new DatesTakenException();
        }
        if (updated == 0) {
            throw new BookingExpiredException();
        }
        notifyBookingEvent(user, "BOOKING_RESCHEDULED", checkIn, checkOut, "GUEST");
    }

    @Transactional
    public void cancel(Long userId, long bookingId) {
        completePastBooking(userId);
        UserAccount user = requireTelegramLinked(userId);
        requireOwnership(bookingId, userId);
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
            outboxEvent(guest.getTelegramChatId(), guest, eventType, checkIn, checkOut, by, "GUEST");
        }
        jdbc.query("""
                select telegram_chat_id from users
                where role = 'ADMIN' and telegram_chat_id is not null
                """, rs -> {
            // ADMIN: у владельца это лента всех гостей — бот не должен вытеснять прошлые
            outboxEvent(rs.getLong(1), guest, eventType, checkIn, checkOut, by, "ADMIN");
        });
    }

    private void outboxEvent(Long chatId, UserAccount guest, String eventType,
                             LocalDate checkIn, LocalDate checkOut, String by, String recipient) {
        outbox.write("notifications.outbound", eventType, Map.of(
                "chat_id", chatId,
                "guest_name", guest.getName(),
                "check_in", checkIn.toString(),
                "check_out", checkOut.toString(),
                "by", by,
                // GUEST: бот держит в чате только последний статус, старое удаляет
                "recipient", recipient));
    }

    /** Активная бронь для /api/me: единственный живой статус — CONFIRMED. */
    @Transactional
    public Optional<Booking> activeBooking(Long userId) {
        completePastBooking(userId);
        return bookings.findFirstByUserIdAndStatusOrderByIdDesc(userId, BookingStatus.CONFIRMED);
    }

    /**
     * Лениво завершает прошедшие поездки: CONFIRMED с выездом СТРОГО до сегодня → COMPLETED.
     * Граница — check_out < today, а не <=: с инклюзивной семантикой V8
     * (daterange(check_in, check_out, '[]')) день выезда всё ещё занят домом —
     * гость может остаться до конца дня, и следующий заезд в этот день запрещён
     * (правило владельца, этап 6.6). Если завершать в день выезда, бронь выпадет
     * из exclusion constraint и календаря раньше времени, и другой гость сможет
     * забронировать заезд прямо в день выезда предыдущего гостя.
     * Атомарный условный UPDATE (урок этапа 5) — без шедулера и без exists-then-update.
     * REQUIRES_NEW: коммитится независимо от вызывающего метода — иначе, например,
     * reschedule/cancel откатят завершение вместе с BookingExpiredException,
     * и устаревший клиент не увидит честный COMPLETED в БД.
     */
    public void completePastBooking(Long userId) {
        requiresNew.executeWithoutResult(status -> jdbc.update(
                "update bookings set status = 'COMPLETED' "
                        + "where user_id = ? and status = 'CONFIRMED' and check_out < ?",
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
                .orElseThrow(BookingNotFoundException::new);
        String normalized = (comment == null || comment.isBlank()) ? null : comment.trim();
        // Атомарный условный UPDATE (урок этапа 5) — не exists-then-update гонка
        int n = jdbc.update(
                "update bookings set comment = ? where id = ? and status = 'CONFIRMED'",
                normalized, active.getId());
        if (n == 0) {
            throw new BookingNotFoundException();
        }
    }
}
