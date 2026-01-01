package com.finbank.backend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;


@Entity
@Table(name = "transaction_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

//    @Column(nullable = false, length = 20)
//    private String type; // DEPOSIT, WITHDRAW, TRANSFER
    @Enumerated(EnumType.STRING)
    //이 필드는 Enum으로 매핑하되, JDBC(DB 컬럼 타입)는 VARCHAR로 쓰게함
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

    public static TransactionLog transfer(Account from, Account to, long amount, long fromBalanceAfter) {
        TransactionLog log = new TransactionLog();
        log.type = TransactionType.TRANSFER;
        log.fromAccount = from;
        log.toAccount = to;
        log.amount = amount;
        log.balanceAfter = fromBalanceAfter;
        log.description = "Transfer";
        return log;
    }
}
