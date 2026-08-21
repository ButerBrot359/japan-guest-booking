package com.batowka.guestbooking.calendar;

import com.batowka.guestbooking.booking.Booking;
import com.batowka.guestbooking.booking.BookingRepository;
import com.batowka.guestbooking.booking.BookingStatus;
import com.batowka.guestbooking.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CalendarService {

    private static final int MAX_RANGE_DAYS = 366;
    private static final List<BookingStatus> OCCUPYING_STATUSES = List.of(BookingStatus.CONFIRMED);

    private final BookingRepository bookings;
    private final BlockedPeriodRepository blockedPeriods;
    private final UserAccountRepository users;

    @Transactional(readOnly = true)
    public List<CalendarDay> getCalendar(LocalDate from, LocalDate to, Long viewerId) {
        if (to.isBefore(from)) {
            throw new InvalidCalendarRangeException("Дата конца раньше даты начала");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new InvalidCalendarRangeException(
                    "Диапазон больше " + MAX_RANGE_DAYS + " дней");
        }

        // Живость зрителя (правило этапа 5): удалённый с валидной cookie — аноним.
        if (viewerId != null && users.findById(viewerId)
                .filter(u -> u.getDeletedAt() == null).isEmpty()) {
            viewerId = null;
        }
        Long viewer = viewerId;

        Map<LocalDate, CalendarDay> days = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.put(d, new CalendarDay(d, DayStatus.FREE, null, false));
        }

        for (Booking b : bookings.findOverlapping(from, to, OCCUPYING_STATUSES)) {
            boolean mine = viewer != null && viewer.equals(b.getUser().getId());
            String name = (viewer != null && b.getStatus() == BookingStatus.CONFIRMED)
                    ? b.getUser().getName() : null;
            LocalDate start = b.getCheckIn().isBefore(from) ? from : b.getCheckIn();
            for (LocalDate d = start;
                 !d.isAfter(b.getCheckOut()) && !d.isAfter(to);
                 d = d.plusDays(1)) {
                days.put(d, new CalendarDay(d, DayStatus.BOOKED, name, mine));
            }
        }

        // блокировки поверх броней: если периоды наложились, показываем BLOCKED
        for (BlockedPeriod p : blockedPeriods.findOverlapping(from, to)) {
            LocalDate start = p.getStartDate().isBefore(from) ? from : p.getStartDate();
            LocalDate end = p.getEndDate().isAfter(to) ? to : p.getEndDate();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                days.put(d, new CalendarDay(d, DayStatus.BLOCKED, null, false));
            }
        }

        return List.copyOf(days.values());
    }
}
