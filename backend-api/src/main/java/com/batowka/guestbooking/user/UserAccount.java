package com.batowka.guestbooking.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String phone;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.FRIEND;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
