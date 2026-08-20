package com.batowka.guestbooking.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Одно приветствие из набора гостя; случайное показывается на каждый /api/me. */
@Entity
@Table(name = "user_greetings")
@Getter
@Setter
@NoArgsConstructor
public class UserGreeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 300)
    private String text;
}
