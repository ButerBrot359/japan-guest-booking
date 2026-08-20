package com.batowka.guestbooking.admin;

import com.batowka.guestbooking.accessrequest.AccessRequest;
import com.batowka.guestbooking.accessrequest.AccessRequestService;
import com.batowka.guestbooking.accessrequest.AccessRequestStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/access-requests")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAccessRequestController {

    private final AccessRequestService service;

    public record RequestRow(long id, String phone, String name, String message,
                             AccessRequestStatus status, Instant createdAt, Instant resolvedAt) {
    }

    @GetMapping
    public List<RequestRow> list(@RequestParam(defaultValue = "PENDING") AccessRequestStatus status) {
        return service.list(status).stream()
                .map(r -> new RequestRow(r.getId(), r.getPhone(), r.getName(), r.getMessage(),
                        r.getStatus(), r.getCreatedAt(), r.getResolvedAt()))
                .toList();
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable long id) {
        service.approve(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable long id) {
        service.reject(id);
        return ResponseEntity.noContent().build();
    }
}
