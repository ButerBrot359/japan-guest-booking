package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.AbstractIntegrationTest;
import com.batowka.guestbooking.user.Role;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSeederTest extends AbstractIntegrationTest {

    @Autowired
    UserAccountRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    AdminSeeder seeder;

    @Value("${app.admin.phone}")
    String adminPhone;

    @Value("${app.admin.password}")
    String adminPassword;

    @Test
    void adminExistsAfterSeeding() {
        UserAccount admin = users.findByPhone(adminPhone).orElseThrow();

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(encoder.matches(adminPassword, admin.getPasswordHash())).isTrue();
    }

    @Test
    void seedingTwiceKeepsSingleAdmin() {
        seeder.seed();
        seeder.seed();

        assertThat(users.findAll()).filteredOn(u -> u.getRole() == Role.ADMIN).hasSize(1);
    }
}
