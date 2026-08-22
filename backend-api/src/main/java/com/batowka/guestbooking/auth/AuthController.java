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
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final PasswordEncoder encoder;
    private final LoginService loginService;

    public record LoginRequest(@NotBlank String phone) {
    }

    public record VerifyRequest(@NotBlank String phone, @NotBlank String code) {
    }

    public record AdminLoginRequest(@NotBlank String phone, @NotBlank String password) {
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest body) {
        loginService.requestCode(body.phone());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/verify")
    public ResponseEntity<Void> verify(@Valid @RequestBody VerifyRequest body) {
        return noContentWithCookie(loginService.verify(body.phone(), body.code()), COOKIE_TTL);
    }

    @PostMapping("/admin-login")
    public ResponseEntity<Void> adminLogin(@Valid @RequestBody AdminLoginRequest body) {
        // Единый 401 на любой провал: не раскрываем, что именно не совпало
        UserAccount admin = Phones.normalize(body.phone())
                .flatMap(users::findByPhoneAndDeletedAtIsNull)
                .filter(u -> u.getRole() == Role.ADMIN)
                .filter(u -> u.getPasswordHash() != null
                        && encoder.matches(body.password(), u.getPasswordHash()))
                .orElseThrow(InvalidCredentialsException::new);
        return noContentWithCookie(jwt.issue(admin.getId(), admin.getRole()), COOKIE_TTL);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return noContentWithCookie("", Duration.ZERO);
    }

    public static ResponseCookie authCookie(String value, Duration maxAge) {
        return ResponseCookie.from(JwtAuthFilter.COOKIE_NAME, value)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    public static ResponseEntity<Void> noContentWithCookie(String token, Duration maxAge) {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookie(token, maxAge).toString())
                .build();
    }
}
