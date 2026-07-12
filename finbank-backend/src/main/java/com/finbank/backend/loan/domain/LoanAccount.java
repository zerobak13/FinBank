package com.finbank.backend.loan.domain;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.Member;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 대출 원장(계좌) 엔티티. 실행된 대출 1건의 계약 상태를 담는다.
 *
 * <p><b>금리 스냅샷</b>: 금리·가산율·상환방식은 실행 시점에 상품에서 "복사"된다.
 * 상품 요율이 바뀌어도 기존 계약은 실행 당시 조건을 유지한다.</p>
 *
 * <p><b>balance는 미상환 원금</b>이며 상환 도메인 메서드로만 감소한다.
 * 진실의 원천은 {@link LoanTransaction} 원장이고 balance는 그 파생값이다.</p>
 */
@Entity
@Table(name = "loan_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 원 신청 (1:1 — DB UNIQUE로 이중 실행 최종 방어) */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false, unique = true)
    private LoanApplication application;

    /** 채무자 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    /** 연결 입출금 계좌 (실행 시 입금, 상환 시 출금) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_account_id", nullable = false)
    private Account linkedAccount;

    /** 실행 원금 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal principal;

    /** 미상환 원금 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    /** 연이율 — 실행 시점 스냅샷 */
    @Column(nullable = false, precision = 7, scale = 6)
    private BigDecimal interestRate;

    /** 연체 가산율 — 실행 시점 스냅샷 */
    @Column(nullable = false, precision = 7, scale = 6)
    private BigDecimal overdueExtraRate;

    /** 상환 방식 — 실행 시점 스냅샷 */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private RepaymentType repaymentType;

    /** 실행일 */
    @Column(nullable = false)
    private LocalDate executedAt;

    /** 만기일 */
    @Column(nullable = false)
    private LocalDate maturityDate;

    /** 원장 상태 */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private LoanAccountStatus status = LoanAccountStatus.ACTIVE;

    /**
     * 대출 실행 팩토리. 상품의 금리·가산율·상환방식을 이 시점에 스냅샷으로 복사한다.
     */
    public static LoanAccount execute(LoanApplication application, Account linkedAccount, LocalDate executedAt) {
        LoanProduct product = application.getProduct();

        LoanAccount loan = new LoanAccount();
        loan.application = application;
        loan.member = application.getMember();
        loan.linkedAccount = linkedAccount;
        loan.principal = application.getRequestedAmount();
        loan.balance = application.getRequestedAmount();
        loan.interestRate = product.getInterestRate();          // 스냅샷
        loan.overdueExtraRate = product.getOverdueExtraRate();  // 스냅샷
        loan.repaymentType = product.getRepaymentType();        // 스냅샷
        loan.executedAt = executedAt;
        loan.maturityDate = executedAt.plusMonths(application.getTermMonths());
        return loan;
    }

    /**
     * 원금 상환: balance를 감소시키고, 전액 상환되면 PAID_OFF로 전이한다.
     */
    public void repayPrincipal(BigDecimal principalPortion) {
        if (this.status == LoanAccountStatus.PAID_OFF) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 완납된 대출입니다.");
        }
        if (principalPortion == null || principalPortion.signum() < 0) {
            throw new IllegalArgumentException("상환 원금은 0 이상이어야 합니다.");
        }
        if (this.balance.compareTo(principalPortion) < 0) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "상환 원금이 미상환 잔액을 초과합니다.");
        }
        this.balance = this.balance.subtract(principalPortion);
        if (this.balance.signum() == 0) {
            this.status = LoanAccountStatus.PAID_OFF;
        }
    }

    /** 연체 전이: ACTIVE → OVERDUE (배치가 호출) */
    public void markOverdue() {
        if (this.status == LoanAccountStatus.ACTIVE) {
            this.status = LoanAccountStatus.OVERDUE;
        }
    }

    /** 연체 해소: OVERDUE → ACTIVE (연체 회차가 모두 정리됐을 때) */
    public void resolveOverdue() {
        if (this.status == LoanAccountStatus.OVERDUE) {
            this.status = LoanAccountStatus.ACTIVE;
        }
    }
}
