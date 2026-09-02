package com.batowka.guestbooking.auth;

import com.batowka.guestbooking.user.Role;
import com.batowka.guestbooking.user.UserAccount;
import com.batowka.guestbooking.user.UserAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AdminSeeder implements ApplicationRunner {

    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final String phone;
    private final String password;

    public AdminSeeder(UserAccountRepository users,
                       PasswordEncoder encoder,
                       @Value("${app.admin.phone}") String phone,
                       @Value("${app.admin.password}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.phone = phone;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** Идемпотентный upsert админа: создаёт или обновляет hash/роль по телефону из конфига. */
    public void seed() {
        UserAccount admin = users.findByPhone(phone).orElseGet(UserAccount::new);
        if (admin.getId() != null && admin.getRole() != Role.ADMIN) {
            log.warn("Сидер повышает существующего пользователя {} до ADMIN", phone);
        }
        admin.setPhone(phone);
        if (admin.getName() == null) {
            admin.setName("Админ");
        }
        admin.setRole(Role.ADMIN);
        admin.setPasswordHash(encoder.encode(password));
        users.save(admin);
    }
}
