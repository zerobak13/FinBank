package com.finbank.backend.account.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;


/**
 * 거래 로그(원장) 엔티티.
 * 입금/출금/이체를 단일 테이블로 통합 관리하며, 거래 후 잔액(balanceAfter)을 함께 기록해
 * 시점별 잔액 이력을 추적할 수 있다. 인스턴스는 정적 팩터리 메서드(deposit/withdraw/transferOut/transferIn)로만 생성한다.
 */
@Entity
@Table(name = "transaction_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionLog {

    /** 로그 PK (자동 증가) */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 거래 유형(DEPOSIT/WITHDRAW/TRANSFER_IN/TRANSFER_OUT). Enum이지만 DB에는 VARCHAR로 저장한다. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    /** 출금(보내는) 계좌 — 입금(DEPOSIT)에서는 null */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    /** 입금(받는) 계좌 — 출금(WITHDRAW)에서는 null */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;

    /** 거래 금액 (원) — DECIMAL(19,4) */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** 이 거래 직후의 잔액 — 시점별 잔액 이력 추적용 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    /** 거래 발생 시각 */
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 거래 설명(메모) */
    @Column(length = 255)
    private String description;

    public static TransactionLog deposit(Account to, BigDecimal amount, BigDecimal balanceAfter) {
        TransactionLog log = new TransactionLog();
        log.type = TransactionType.DEPOSIT;
        log.toAccount = to;
        log.amount = amount;
        log.balanceAfter = balanceAfter;
        log.description = "Deposit";
        return log;
    }

    public static TransactionLog withdraw(Account from, BigDecimal amount, BigDecimal balanceAfter) {
        TransactionLog log = new TransactionLog();
        log.type = TransactionType.WITHDRAW;
        log.fromAccount = from;
        log.amount = amount;
        log.balanceAfter = balanceAfter;
        log.description = "Withdraw";
        return log;
    }

    public static TransactionLog transferOut(Account from, Account to, BigDecimal amount, BigDecimal fromBalanceAfter) {
        TransactionLog log = new TransactionLog();
        log.type = TransactionType.TRANSFER_OUT;
        log.fromAccount = from;
        log.toAccount = to;
        log.amount = amount;
        log.balanceAfter = fromBalanceAfter;
        log.description = "Transfer Out";
        return log;
    }

    public static TransactionLog transferIn(Account from, Account to, BigDecimal amount, BigDecimal toBalanceAfter) {
        TransactionLog log = new TransactionLog();
        log.type = TransactionType.TRANSFER_IN;
        log.fromAccount = from;
        log.toAccount = to;
        log.amount = amount;
        log.balanceAfter = toBalanceAfter;
        log.description = "Transfer In";
        return log;
    }


}
