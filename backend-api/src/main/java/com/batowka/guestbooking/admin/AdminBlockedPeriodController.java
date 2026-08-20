package com.batowka.guestbooking.admin;

import com.batowka.guestbooking.calendar.BlockedPeriod;
import com.batowka.guestbooking.calendar.BlockedPeriodService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/blocked-periods")
@PreAuthorize("hasRole('ADMIN')") // вторая линия обороны поверх URL-правила SecurityConfig
@RequiredArgsConstructor
public class AdminBlockedPeriodController {

    private final BlockedPeriodService service;

    public record CreateRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate,
                                @Size(max = 200) String reason) {
    }

    public record PeriodResponse(long id, LocalDate startDate, LocalDate endDate,
                                 String reason, Instant createdAt) {
    }

    @GetMapping
    public List<PeriodResponse> list() {
        return service.list().stream().map(AdminBlockedPeriodController::toResponse).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodResponse create(@Valid @RequestBody CreateRequest body) {
        return toResponse(service.create(body.startDate(), body.endDate(), body.reason()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    private static PeriodResponse toResponse(BlockedPeriod p) {
        return new PeriodResponse(p.getId(), p.getStartDate(), p.getEndDate(),
                p.getReason(), p.getCreatedAt());
    }
}
