package com.batowka.guestbooking.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserGreetingRepository extends JpaRepository<UserGreeting, Long> {
    List<UserGreeting> findByUserIdOrderByIdAsc(Long userId);
    void deleteByUserId(Long userId);
}
