package com.finbank.backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;


import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false)
    private Long balance;

    @Column(nullable = false)
    private boolean locked = false;
    //db락 아님
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Account(Member member, String accountNumber, Long initialBalance) {
        this.member = member;
        this.accountNumber = accountNumber;
        this.balance = initialBalance;
    }

    public void deposit(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Deposit amount must be positive");
        this.balance += amount;
    }

    public void withdraw(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Withdraw amount must be positive");
        if (this.balance < amount) throw new IllegalStateException("Insufficient balance");
        this.balance -= amount;
    }
}
