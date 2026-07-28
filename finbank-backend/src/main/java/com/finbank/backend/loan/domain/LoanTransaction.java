package com.finbank.backend.loan.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 대출 거래 원장 엔티티 — <b>append-only</b>.
 *
 * <p>setter가 없고 정적 팩토리로만 생성한다. UPDATE/DELETE는 코드로도 금지한다.
 * 잔액({@link LoanAccount#getBalance()})은 이 원장의 파생값이며, 원장이 진실의 원천이다.</p>
 *
 * <p>idempotencyKey에 UNIQUE 제약이 있어 같은 키의 거래가 두 번 기록될 수 없다
 * (멱등 처리의 DB 레벨 최종 방어선).</p>
 */
@Entity
@Table(name = "loan_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 대출 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_account_id", nullable = false)
    private LoanAccount loanAccount;

    /** 거래 유형 */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private LoanTransactionType type;

    /** 거래 총액 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** 원금 부분 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal principalPortion;

    /** 이자 부분 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal interestPortion;

    /** 거래 직후 미상환 원금 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    /** 멱등키 (UNIQUE) */
    @Column(name = "idempotency_key", unique = true, length = 64)
    private String idempotencyKey;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 대출 실행: 원금 전액이 나가고, 잔액은 원금과 같다. */
    public static LoanTransaction execution(LoanAccount loan, String idempotencyKey) {
        return create(loan, LoanTransactionType.EXECUTION,
                loan.getPrincipal(), loan.getPrincipal(), BigDecimal.ZERO,
                loan.getBalance(), idempotencyKey);
    }

    /** 회차 상환: 원금/이자 분리 기록. */
    public static LoanTransaction repayment(LoanAccount loan, BigDecimal principalPortion,
                                            BigDecimal interestPortion, String idempotencyKey) {
        return create(loan, LoanTransactionType.REPAYMENT,
                principalPortion.add(interestPortion), principalPortion, interestPortion,
                loan.getBalance(), idempotencyKey);
    }

    /** 전액 중도상환: 잔여원금 + 경과이자 + 수수료(이자 부분에 합산 기록). */
    public static LoanTransaction prepayment(LoanAccount loan, BigDecimal principalPortion,
                                             BigDecimal interestAndFeePortion, String idempotencyKey) {
        return create(loan, LoanTransactionType.PREPAYMENT,
                principalPortion.add(interestAndFeePortion), principalPortion, interestAndFeePortion,
                loan.getBalance(), idempotencyKey);
    }

    /** 연체이자 가산 (배치): 원금 변동 없음. */
    public static LoanTransaction overdueInterest(LoanAccount loan, BigDecimal penalty) {
        return create(loan, LoanTransactionType.OVERDUE_INTEREST,
                penalty, BigDecimal.ZERO, penalty, loan.getBalance(), null);
    }

    private static LoanTransaction create(LoanAccount loan, LoanTransactionType type,
                                          BigDecimal amount, BigDecimal principalPortion,
                                          BigDecimal interestPortion, BigDecimal balanceAfter,
                                          String idempotencyKey) {
        LoanTransaction tx = new LoanTransaction();
        tx.loanAccount = loan;
        tx.type = type;
        tx.amount = amount;
        tx.principalPortion = principalPortion;
        tx.interestPortion = interestPortion;
        tx.balanceAfter = balanceAfter;
        tx.idempotencyKey = idempotencyKey;
        return tx;
    }
}
