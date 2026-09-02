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

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** Хеш строки "password" — публичный BCrypt-тест-вектор. Результат сравнения игнорируется:
        нужен только прогон BCrypt, чтобы время ответа не выдавало, существует ли номер. */
    static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserAccountRepository users;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final LoginService loginService;
    private final AuthCookies cookies;

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
        return noContent(cookies.session(loginService.verify(body.phone(), body.code())));
    }

    @PostMapping("/admin-login")
    public ResponseEntity<Void> adminLogin(@Valid @RequestBody AdminLoginRequest body) {
        UserAccount admin = Phones.normalize(body.phone())
                .flatMap(users::findByPhoneAndDeletedAtIsNull)
                .filter(u -> u.getRole() == Role.ADMIN)
                .filter(u -> u.getPasswordHash() != null)
                .orElse(null);
        // Единый 401 и одинаковое время на любой провал: BCrypt прогоняется всегда
        boolean matches = encoder.matches(body.password(),
                admin != null ? admin.getPasswordHash() : DUMMY_HASH);
        if (admin == null || !matches) {
            throw new InvalidCredentialsException();
        }
        return noContent(cookies.session(jwt.issue(admin.getId(), admin.getRole())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return noContent(cookies.expired());
    }

    private ResponseEntity<Void> noContent(ResponseCookie cookie) {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }
}
