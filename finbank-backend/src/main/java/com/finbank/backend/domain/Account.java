package com.finbank.backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 계좌 엔티티. 회원(Member) 1 : N 계좌 관계이며 잔액(balance)을 BigDecimal로 보관한다.
 * (DB는 DECIMAL(19,4) — 확정 금액은 정책상 원 단위 정수, MoneyPolicy 참고)
 * 잔액 변경은 도메인 메서드(deposit/withdraw)로만 이뤄지며 음수 잔액을 방지한다.
 * (locked는 계좌 잠금 상태 플래그로, DB의 비관적 락과는 무관하다.)
 *
 * <p>주의: BigDecimal 비교는 equals가 아니라 compareTo를 쓴다.
 * equals는 스케일까지 비교하므로 1000 != 1000.0000 으로 판정된다.</p>
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

    /** 잔액 (원) — DECIMAL(19,4) */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    /** 계좌 잠금 여부 — 거래 정지 상태 플래그. DB의 비관적 락과는 무관하다. */
    @Column(nullable = false)
    private boolean locked = false;

    /** 계좌 개설 시각 */
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Account(Member member, String accountNumber, BigDecimal initialBalance) {
        this.member = member;
        this.accountNumber = accountNumber;
        this.balance = initialBalance;
    }

    /** 입금: 잔액을 증가시킨다. null 또는 0 이하 금액은 거부한다. */
    public void deposit(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        this.balance = this.balance.add(amount);
    }

    /** 출금: 잔액을 감소시킨다. null·0 이하이거나 잔액이 부족하면 거부한다(음수 잔액 방지). */
    public void withdraw(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Withdraw amount must be positive");
        }
        if (this.balance.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance");
        }
        this.balance = this.balance.subtract(amount);
    }
}
