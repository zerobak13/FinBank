package com.finbank.backend.loan.domain;

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
import java.time.LocalDateTime;

/**
 * 상환 스케줄 회차 엔티티. 대출 실행 시점에 이자 계산 엔진이 전 회차를 확정 생성한다.
 *
 * <p>동일 회차 이중 상환 방어는 2겹이다:
 * ① 서비스의 조건부 UPDATE(WHERE status='PENDING') ② 이 엔티티의 상태 검사.</p>
 */
@Entity
@Table(name = "repayment_schedules",
        uniqueConstraints = @UniqueConstraint(name = "uq_schedule_installment",
                columnNames = {"loan_account_id", "installment_no"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepaymentSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 대출 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_account_id", nullable = false)
    private LoanAccount loanAccount;

    /** 회차 (1부터) */
    @Column(name = "installment_no", nullable = false)
    private int installmentNo;

    /** 납부 기일 */
    @Column(nullable = false)
    private LocalDate dueDate;

    /** 회차 원금 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal principalAmount;

    /** 회차 이자 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal interestAmount;

    /** 회차 납부 총액 (원금+이자) */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    /** 회차 상태 */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status = ScheduleStatus.PENDING;

    /** 납부 완료 시각 */
    private LocalDateTime paidAt;

    public static RepaymentSchedule of(LoanAccount loanAccount, int installmentNo, LocalDate dueDate,
                                       BigDecimal principalAmount, BigDecimal interestAmount) {
        RepaymentSchedule s = new RepaymentSchedule();
        s.loanAccount = loanAccount;
        s.installmentNo = installmentNo;
        s.dueDate = dueDate;
        s.principalAmount = principalAmount;
        s.interestAmount = interestAmount;
        s.totalAmount = principalAmount.add(interestAmount);
        return s;
    }

    /** 납부 완료: PENDING/OVERDUE → PAID */
    public void markPaid() {
        if (this.status != ScheduleStatus.PENDING && this.status != ScheduleStatus.OVERDUE) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "이미 처리된 회차입니다. (현재 상태: " + this.status + ")");
        }
        this.status = ScheduleStatus.PAID;
        this.paidAt = LocalDateTime.now();
    }

    /** 연체 전이: PENDING → OVERDUE (배치가 호출) */
    public void markOverdue() {
        if (this.status == ScheduleStatus.PENDING) {
            this.status = ScheduleStatus.OVERDUE;
        }
    }

    /** 전액 중도상환으로 무효화: PENDING → CANCELED (삭제 대신 상태 보존) */
    public void cancelByPrepayment() {
        if (this.status == ScheduleStatus.PENDING) {
            this.status = ScheduleStatus.CANCELED;
        }
    }

    public boolean isOverdue() {
        return this.status == ScheduleStatus.OVERDUE;
    }
}
