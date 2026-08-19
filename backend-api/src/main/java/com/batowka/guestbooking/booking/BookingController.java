package com.batowka.guestbooking.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    public record CreateRequest(@NotNull LocalDate checkIn, @NotNull LocalDate checkOut,
                                @Size(max = 500) String comment) {
    }

    public record ConfirmRequest(@NotBlank String code) {
    }

    public record RescheduleRequest(@NotNull LocalDate checkIn, @NotNull LocalDate checkOut) {
    }

    @PostMapping
    public BookingService.CreateResult create(@Valid @RequestBody CreateRequest body,
                                              Authentication auth) {
        return bookingService.create((Long) auth.getPrincipal(),
                body.checkIn(), body.checkOut(), body.comment());
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable long id,
                                        @Valid @RequestBody ConfirmRequest body,
                                        Authentication auth) {
        bookingService.confirm((Long) auth.getPrincipal(), id, body.code());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> reschedule(@PathVariable long id,
                                           @Valid @RequestBody RescheduleRequest body,
                                           Authentication auth) {
        bookingService.requestReschedule((Long) auth.getPrincipal(), id,
                body.checkIn(), body.checkOut());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable long id, Authentication auth) {
        bookingService.requestCancel((Long) auth.getPrincipal(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resend-code")
    public ResponseEntity<Void> resendCode(@PathVariable long id, Authentication auth) {
        bookingService.resendCode((Long) auth.getPrincipal(), id);
        return ResponseEntity.noContent().build();
    }
}
