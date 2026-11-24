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
 *
 * <p><b>금액 칸</b>: 금액 = 원금 부분 + 이자 부분 + 연체이자 부분 (생성할 때 계산하므로 어긋날 수 없다).
 * 이자 부분은 약정 이자만 담고(중도상환이면 경과이자 + 수수료), 연체이자는 연체이자 부분에만 적는다.</p>
 *
 * <p><b>발생과 수납을 더하지 않는다</b>: OVERDUE_INTEREST는 연체이자가 <i>쌓인</i> 기록(발생)이고,
 * REPAYMENT의 연체이자 부분은 그 돈을 <i>받은</i> 기록(수납)이다. 같은 돈을 두 번 적은 것이므로
 * 연체이자 합계는 목적에 맞는 한쪽만 더한다 — 받은 금액은 REPAYMENT, 쌓인 금액은 OVERDUE_INTEREST.</p>
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

    /** 이자 부분 — 약정 이자 (중도상환이면 경과이자 + 수수료). 연체이자는 들어가지 않는다 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal interestPortion;

    /** 연체이자 부분 — OVERDUE_INTEREST면 쌓인 금액(발생), REPAYMENT면 받은 금액(수납) */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal penaltyPortion;

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
                loan.getPrincipal(), BigDecimal.ZERO, BigDecimal.ZERO,
                loan.getBalance(), idempotencyKey);
    }

    /** 회차 상환: 원금·이자·연체이자를 칸마다 나눠 적는다. 금액 = 계좌 출금액 (연체가 아니면 연체이자 0). */
    public static LoanTransaction repayment(LoanAccount loan, BigDecimal principalPortion,
                                            BigDecimal interestPortion, BigDecimal penaltyPortion,
                                            String idempotencyKey) {
        return create(loan, LoanTransactionType.REPAYMENT,
                principalPortion, interestPortion, penaltyPortion,
                loan.getBalance(), idempotencyKey);
    }

    /** 전액 중도상환: 잔여원금 + 경과이자 + 수수료(이자 부분에 합산 기록). 정상 대출만 가능해 연체이자는 없다. */
    public static LoanTransaction prepayment(LoanAccount loan, BigDecimal principalPortion,
                                             BigDecimal interestAndFeePortion, String idempotencyKey) {
        return create(loan, LoanTransactionType.PREPAYMENT,
                principalPortion, interestAndFeePortion, BigDecimal.ZERO,
                loan.getBalance(), idempotencyKey);
    }

    /** 연체이자 가산 (배치): 쌓인 연체이자를 연체이자 부분에 적는다(발생). 원금 변동 없음. */
    public static LoanTransaction overdueInterest(LoanAccount loan, BigDecimal penalty) {
        return create(loan, LoanTransactionType.OVERDUE_INTEREST,
                BigDecimal.ZERO, BigDecimal.ZERO, penalty, loan.getBalance(), null);
    }

    private static LoanTransaction create(LoanAccount loan, LoanTransactionType type,
                                          BigDecimal principalPortion, BigDecimal interestPortion,
                                          BigDecimal penaltyPortion, BigDecimal balanceAfter,
                                          String idempotencyKey) {
        LoanTransaction tx = new LoanTransaction();
        tx.loanAccount = loan;
        tx.type = type;
        tx.amount = principalPortion.add(interestPortion).add(penaltyPortion);
        tx.principalPortion = principalPortion;
        tx.interestPortion = interestPortion;
        tx.penaltyPortion = penaltyPortion;
        tx.balanceAfter = balanceAfter;
        tx.idempotencyKey = idempotencyKey;
        return tx;
    }
}
