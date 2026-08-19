package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.user.Role;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MeController {

    private final UserAccountRepository users;

    public record MeResponse(String phone, String name, Role role) {
    }

    @GetMapping("/api/me")
    public MeResponse me(Authentication auth) {
        UserAccount user = users.findById((Long) auth.getPrincipal()).orElseThrow();
        return new MeResponse(user.getPhone(), user.getName(), user.getRole());
    }
}
