package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.common.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Лимитер до всей аутентификации и разбора тела: даже невалидный JSON расходует попытку.
 * Ответ пишем сами — @RestControllerAdvice до servlet-фильтров не дотягивается.
 */
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    static final int LIMIT = 5;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private static final Set<String> AUTH_PATHS =
            Set.of("/api/auth/login", "/api/auth/verify", "/api/auth/admin-login");
    private static final String REQUESTS_PATH = "/api/access-requests";

    private final SlidingWindowRateLimiter limiter;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String bucket = bucketFor(request);
        if (bucket == null) {
            chain.doFilter(request, response);
            return;
        }
        // за nginx getRemoteAddr() отдаёт реальный IP клиента (forward-headers-strategy: native)
        if (!limiter.tryAcquire(bucket + ":" + request.getRemoteAddr(), LIMIT, WINDOW)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    new ApiError("RATE_LIMITED", "Слишком много попыток — подождите минуту"));
            return;
        }
        chain.doFilter(request, response);
    }

    private static String bucketFor(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return null;
        }
        String uri = request.getRequestURI();
        if (AUTH_PATHS.contains(uri)) {
            return "auth";
        }
        if (REQUESTS_PATH.equals(uri)) {
            return "requests";
        }
        return null;
    }
}
