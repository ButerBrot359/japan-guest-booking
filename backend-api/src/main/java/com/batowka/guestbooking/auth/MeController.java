package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.booking.BookingService;
import com.batowka.guestbooking.booking.BookingStatus;
import com.batowka.guestbooking.booking.GuestBookingsService;
import com.batowka.guestbooking.user.Role;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import com.batowka.guestbooking.user.UserGoneException;
import com.batowka.guestbooking.user.WhitelistService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class MeController {

    private final UserAccountRepository users;
    private final BookingService bookingService;
    private final GuestBookingsService guestBookings;
    private final WhitelistService whitelist;

    public record ActiveBooking(long id, LocalDate checkIn, LocalDate checkOut, BookingStatus status) {
    }

    public record MeResponse(String phone, String name, Role role, boolean telegramLinked,
                             String greeting, ActiveBooking activeBooking) {
    }

    public record ActiveBookingDetails(long id, LocalDate checkIn, LocalDate checkOut,
                                       BookingStatus status, String comment) {
    }

    public record PastVisit(LocalDate checkIn, LocalDate checkOut, long nights) {
    }

    public record MyBookingsResponse(ActiveBookingDetails active, List<PastVisit> history) {
    }

    @GetMapping("/api/me")
    public MeResponse me(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        UserAccount user = users.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(UserGoneException::new);
        ActiveBooking activeBooking = bookingService.activeBooking(userId)
                .map(b -> new ActiveBooking(b.getId(), b.getCheckIn(), b.getCheckOut(), b.getStatus()))
                .orElse(null);
        return new MeResponse(user.getPhone(), user.getName(), user.getRole(),
                user.getTelegramChatId() != null, whitelist.randomGreeting(userId).orElse(null), activeBooking);
    }

    /** Активная бронь с комментарием + история завершённых поездок. */
    @GetMapping("/api/me/bookings")
    public MyBookingsResponse myBookings(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        users.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(UserGoneException::new);
        GuestBookingsService.Snapshot snap = guestBookings.load(userId);
        ActiveBookingDetails active = snap.active()
                .map(b -> new ActiveBookingDetails(b.getId(), b.getCheckIn(), b.getCheckOut(),
                        b.getStatus(), b.getComment()))
                .orElse(null);
        List<PastVisit> history = snap.history().stream()
                .map(v -> new PastVisit(v.checkIn(), v.checkOut(), v.nights()))
                .toList();
        return new MyBookingsResponse(active, history);
    }
}
