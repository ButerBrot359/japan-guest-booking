package com.batowka.guestbooking.bot;

import com.batowka.guestbooking.booking.Booking;
import com.batowka.guestbooking.booking.BookingStatus;
import com.batowka.guestbooking.booking.GuestBookingsService;
import com.batowka.guestbooking.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/bot")
@RequiredArgsConstructor
public class BotController {

    private final UserAccountRepository users;
    private final GuestBookingsService guestBookings;

    public record ActiveBooking(LocalDate checkIn, LocalDate checkOut, BookingStatus status) {
    }

    public record PastVisit(LocalDate checkIn, LocalDate checkOut, long nights) {
    }

    public record BotBookingsResponse(boolean linked, ActiveBooking active, List<PastVisit> history) {
    }

    @GetMapping("/bookings")
    public BotBookingsResponse bookings(@RequestParam("chat_id") long chatId) {
        return users.findByTelegramChatIdAndDeletedAtIsNull(chatId)
                .map(user -> {
                    GuestBookingsService.Snapshot snap = guestBookings.load(user.getId());
                    ActiveBooking active = snap.active()
                            .map(b -> new ActiveBooking(b.getCheckIn(), b.getCheckOut(), b.getStatus()))
                            .orElse(null);
                    List<PastVisit> history = snap.history().stream()
                            .map(v -> new PastVisit(v.checkIn(), v.checkOut(), v.nights()))
                            .toList();
                    return new BotBookingsResponse(true, active, history);
                })
                .orElseGet(() -> new BotBookingsResponse(false, null, List.of()));
    }
}
