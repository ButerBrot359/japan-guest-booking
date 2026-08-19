package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.user.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void issuedTokenParsesBack() {
        JwtService jwt = new JwtService(SECRET, 30);

        String token = jwt.issue(42L, Role.FRIEND);

        assertThat(jwt.parse(token)).hasValueSatisfying(data -> {
            assertThat(data.userId()).isEqualTo(42L);
            assertThat(data.role()).isEqualTo(Role.FRIEND);
        });
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService jwt = new JwtService(SECRET, -1); // exp в прошлом

        String token = jwt.issue(42L, Role.FRIEND);

        assertThat(jwt.parse(token)).isEmpty();
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        JwtService alice = new JwtService(SECRET, 30);
        JwtService mallory = new JwtService("ffffffffffffffffffffffffffffffff", 30);

        assertThat(alice.parse(mallory.issue(42L, Role.ADMIN))).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        JwtService jwt = new JwtService(SECRET, 30);

        assertThat(jwt.parse("не-jwt-вообще")).isEmpty();
    }
}
