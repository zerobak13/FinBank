package com.finbank.backend.member.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 회원 엔티티. 로그인 주체이며 계좌(Account)와 1:N 관계다.
 * 비밀번호는 평문이 아니라 BCrypt 해시로만 저장한다.
 */
@Entity
@Table(name = "members")
@Getter
@NoArgsConstructor
public class Member {

    /** 회원 PK (자동 증가) */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이메일 — 로그인 아이디로 사용 (유니크) */
    @Column(nullable = false, unique = true, length = 100)
    private String email;

    /** 회원 이름 */
    @Column(nullable = false, length = 50)
    private String name;

    /** 비밀번호 (BCrypt 해시) */
    @Column(nullable = false, length = 200)
    private String password;

    /** 가입 시각 */
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Member(String email, String name, String password) {
        this.email = email;
        this.name = name;
        this.password = password;
    }
}
