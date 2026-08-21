package com.batowka.guestbooking.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    public record RescheduleRequest(@NotNull LocalDate checkIn, @NotNull LocalDate checkOut) {
    }

    public record CommentRequest(@Size(max = 500) String comment) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingService.CreateResult create(@Valid @RequestBody CreateRequest body,
                                              Authentication auth) {
        return bookingService.create((Long) auth.getPrincipal(),
                body.checkIn(), body.checkOut(), body.comment());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> reschedule(@PathVariable long id,
                                           @Valid @RequestBody RescheduleRequest body,
                                           Authentication auth) {
        bookingService.reschedule((Long) auth.getPrincipal(), id,
                body.checkIn(), body.checkOut());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateComment(Authentication auth, @Valid @RequestBody CommentRequest body) {
        bookingService.updateComment((Long) auth.getPrincipal(), body.comment());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable long id, Authentication auth) {
        bookingService.cancel((Long) auth.getPrincipal(), id);
        return ResponseEntity.noContent().build();
    }
}
