package com.batowka.guestbooking.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByPhone(String phone);

    Optional<UserAccount> findByPhoneAndDeletedAtIsNull(String phone);
}
