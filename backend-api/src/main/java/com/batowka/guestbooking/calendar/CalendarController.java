package com.batowka.guestbooking.calendar;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarService calendar;

    @GetMapping
    public CalendarResponse getCalendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication auth) {
        // На permitAll-маршруте аноним приходит как AnonymousAuthenticationToken
        // с principal-строкой "anonymousUser" — поэтому обязателен instanceof Long.
        Long viewerId = (auth != null && auth.getPrincipal() instanceof Long id) ? id : null;
        return new CalendarResponse(calendar.getCalendar(from, to, viewerId));
    }
}
