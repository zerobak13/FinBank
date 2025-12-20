package com.finbank.backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "members")
@Getter
@Setter
@NoArgsConstructor
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 200)
    private String password; // BCrypt hash

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Member(String email, String name, String password) {
        this.email = email;
        this.name = name;
        this.password = password;
    }
}
