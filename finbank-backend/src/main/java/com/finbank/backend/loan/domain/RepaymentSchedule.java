package com.finbank.backend.loan.domain;

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
 * <p><b>상태 전이는 전부 리포지토리의 조건부 UPDATE로만 한다</b> — PAID(상환), OVERDUE(연체 감지 배치),
 * CANCELED(전액 중도상환). 엔티티를 읽어 상태를 바꾸면 REPEATABLE READ 스냅샷 때문에 동시 요청 둘 다
 * 통과하거나(check-then-act), 배치가 읽어 둔 옛 값으로 다른 요청의 결과를 덮어쓴다.</p>
 *
 * <p>동일 회차 이중 상환 방어는 2겹이다: ① 조건부 UPDATE({@code WHERE status IN (PENDING, OVERDUE)})
 * ② 대출 원장의 멱등키 UNIQUE.</p>
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

    // 상태 전이 메서드는 두지 않는다 — markPaidIfPayable, markOverdueIfPending, cancelPendingByLoanAccountId
    // (RepaymentScheduleRepository)의 조건부 UPDATE로만 바꾼다. 위 클래스 설명 참고.

}
