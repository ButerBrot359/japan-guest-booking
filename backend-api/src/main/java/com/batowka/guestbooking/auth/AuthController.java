package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.user.Role;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    static final Duration COOKIE_TTL = Duration.ofDays(30);

    private final UserAccountRepository users;
    private final JwtService jwt;

    public record LoginRequest(@NotBlank String phone) {
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest body) {
        String phone = Phones.normalize(body.phone()).orElseThrow(InvalidPhoneException::new);
        // Роль ADMIN сюда не пускаем: беспарольный логин не должен выдавать админский токен
        UserAccount user = users.findByPhone(phone)
                .filter(u -> u.getRole() == Role.FRIEND)
                .orElseThrow(UnknownPhoneException::new);
        return noContentWithCookie(jwt.issue(user.getId(), user.getRole()), COOKIE_TTL);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return noContentWithCookie("", Duration.ZERO);
    }

    static ResponseCookie authCookie(String value, Duration maxAge) {
        return ResponseCookie.from(JwtAuthFilter.COOKIE_NAME, value)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    static ResponseEntity<Void> noContentWithCookie(String token, Duration maxAge) {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookie(token, maxAge).toString())
                .build();
    }
}
