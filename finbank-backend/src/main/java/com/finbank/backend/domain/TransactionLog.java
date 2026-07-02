package com.finbank.backend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Enum으로 매핑하되 DB 컬럼 타입은 VARCHAR로 저장한다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false)
    private Long balanceAfter;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(length = 255)
    private String description;

    public static TransactionLog deposit(Account to, long amount, long balanceAfter) {
        TransactionLog log = new TransactionLog();
        log.type = TransactionType.DEPOSIT;
        log.toAccount = to;
        log.amount = amount;
        log.balanceAfter = balanceAfter;
        log.description = "Deposit";
        return log;
    }

    public static TransactionLog withdraw(Account from, long amount, long balanceAfter) {
        TransactionLog log = new TransactionLog();
        log.type = TransactionType.WITHDRAW;
        log.fromAccount = from;
        log.amount = amount;
        log.balanceAfter = balanceAfter;
        log.description = "Withdraw";
        return log;
    }

    public static TransactionLog transferOut(Account from, Account to, long amount, long fromBalanceAfter) {
        TransactionLog log = new TransactionLog();
        log.type = TransactionType.TRANSFER_OUT;
        log.fromAccount = from;
        log.toAccount = to;
        log.amount = amount;
        log.balanceAfter = fromBalanceAfter;
        log.description = "Transfer Out";
        return log;
    }

    public static TransactionLog transferIn(Account from, Account to, long amount, long toBalanceAfter) {
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
