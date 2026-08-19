package com.batowka.guestbooking.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
}
