package com.batowka.guestbooking.calendar;

import com.batowka.guestbooking.booking.BookingService;
import com.batowka.guestbooking.booking.BookingRepository;
import com.batowka.guestbooking.common.DatesLock;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BlockedPeriodService {

    private final BlockedPeriodRepository blockedPeriods;
    private final BookingRepository bookings;
    private final DatesLock datesLock;

    @Transactional(readOnly = true)
    public List<BlockedPeriod> list() {
        return blockedPeriods.findAll(Sort.by("startDate"));
    }

    @Transactional
    public BlockedPeriod create(LocalDate startDate, LocalDate endDate, String reason) {
        if (endDate.isBefore(startDate)) {
            throw new InvalidCalendarRangeException("Конец периода раньше начала");
        }
        datesLock.acquire();
        // бронь занимает [checkIn, checkOut] включительно (V8), блокировка — включительно:
        // конфликт, если checkIn <= end && checkOut >= start
        List<OverlapsBookingException.Conflict> conflicts = bookings
                .findOverlapping(startDate, endDate, BookingService.ACTIVE).stream()
                .map(b -> new OverlapsBookingException.Conflict(
                        b.getId(), b.getUser().getName(), b.getCheckIn(), b.getCheckOut()))
                .toList();
        if (!conflicts.isEmpty()) {
            throw new OverlapsBookingException(conflicts);
        }
        BlockedPeriod p = new BlockedPeriod();
        p.setStartDate(startDate);
        p.setEndDate(endDate);
        p.setReason(reason);
        return blockedPeriods.save(p);
    }

    @Transactional
    public void delete(long id) {
        if (!blockedPeriods.existsById(id)) {
            throw new BlockedPeriodNotFoundException();
        }
        blockedPeriods.deleteById(id);
    }
}
