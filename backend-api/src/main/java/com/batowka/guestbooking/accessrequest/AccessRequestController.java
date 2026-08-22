package com.batowka.guestbooking.accessrequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/access-requests")
@RequiredArgsConstructor
public class AccessRequestController {

    private final AccessRequestService service;

    public record SubmitRequest(@NotBlank String phone, @NotBlank @Size(max = 100) String name,
                                @Size(max = 500) String message) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void submit(@Valid @RequestBody SubmitRequest body) {
        service.submit(body.phone(), body.name(), body.message());
    }
}
