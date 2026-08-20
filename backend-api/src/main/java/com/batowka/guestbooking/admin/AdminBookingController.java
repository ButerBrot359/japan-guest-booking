package com.batowka.guestbooking.admin;

import com.batowka.guestbooking.booking.AdminBookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/bookings")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminBookingController {

    private final AdminBookingService service;

    public record RescheduleRequest(@NotNull LocalDate checkIn, @NotNull LocalDate checkOut) {
    }

    @GetMapping
    public List<AdminBookingService.BookingRow> list() {
        return service.list();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable long id) {
        service.cancel(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reschedule")
    public ResponseEntity<Void> reschedule(@PathVariable long id,
                                           @Valid @RequestBody RescheduleRequest body) {
        service.reschedule(id, body.checkIn(), body.checkOut());
        return ResponseEntity.noContent().build();
    }
}
