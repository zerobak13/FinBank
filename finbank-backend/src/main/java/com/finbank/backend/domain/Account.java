package com.finbank.backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

import java.time.LocalDateTime;

/**
 * 계좌 엔티티. 회원(Member) 1 : N 계좌 관계이며 잔액(balance)을 원 단위 정수로 보관한다.
 * 잔액 변경은 도메인 메서드(deposit/withdraw)로만 이뤄지며 음수 잔액을 방지한다.
 * (locked는 계좌 잠금 상태 플래그로, DB의 비관적 락과는 무관하다.)
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    /** 계좌 PK (자동 증가) */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 12자리 계좌번호 (유니크) */
    @Column(nullable = false, unique = true, length = 20)
    private String accountNumber;

    /** 계좌 소유 회원 (N:1) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    /** 잔액 (원 단위 정수) */
    @Column(nullable = false)
    private Long balance;

    /** 계좌 잠금 여부 — 거래 정지 상태 플래그. DB의 비관적 락과는 무관하다. */
    @Column(nullable = false)
    private boolean locked = false;

    /** 계좌 개설 시각 */
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Account(Member member, String accountNumber, Long initialBalance) {
        this.member = member;
        this.accountNumber = accountNumber;
        this.balance = initialBalance;
    }

    /** 입금: 잔액을 증가시킨다. 0 이하 금액은 거부한다. */
    public void deposit(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Deposit amount must be positive");
        this.balance += amount;
    }

    /** 출금: 잔액을 감소시킨다. 0 이하이거나 잔액이 부족하면 거부한다(음수 잔액 방지). */
    public void withdraw(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Withdraw amount must be positive");
        if (this.balance < amount) throw new IllegalStateException("Insufficient balance");
        this.balance -= amount;
    }
}
