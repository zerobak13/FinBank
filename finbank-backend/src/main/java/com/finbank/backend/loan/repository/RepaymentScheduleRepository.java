package com.finbank.backend.loan.repository;

import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.domain.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RepaymentScheduleRepository extends JpaRepository<RepaymentSchedule, Long> {

    /** 대출의 전체 스케줄 (회차순) */
    List<RepaymentSchedule> findByLoanAccountOrderByInstallmentNoAsc(LoanAccount loanAccount);

    /** 가장 빠른 미납(PENDING/OVERDUE) 회차 — "순서대로만 납부" 규칙 검증용 */
    Optional<RepaymentSchedule> findFirstByLoanAccountAndStatusInOrderByInstallmentNoAsc(
            LoanAccount loanAccount, List<ScheduleStatus> statuses);

    /**
     * 조건부 상태 전이 — 동일 회차 이중 상환 방어의 1차 방어선.
     *
     * <p>영향 행 수가 0이면 다른 요청이 먼저 처리한 것이므로 서비스에서 409로 거부한다.
     * 비관적 락과 별개로, "상태 전이 자체의 원자성"을 DB가 보장하게 하는 장치다.</p>
     */
    @Modifying
    @Query("""
            update RepaymentSchedule s
               set s.status = :paid, s.paidAt = :paidAt
             where s.id = :scheduleId
               and s.status in (:payable)
            """)
    int updateStatusIfIn(@Param("scheduleId") Long scheduleId,
                         @Param("paid") ScheduleStatus paid,
                         @Param("payable") List<ScheduleStatus> payable,
                         @Param("paidAt") LocalDateTime paidAt);

    /** 조건부 PAID 전이의 기본형 — PENDING/OVERDUE에서만 성공 */
    default int markPaidIfPayable(Long scheduleId, LocalDateTime paidAt) {
        return updateStatusIfIn(scheduleId, ScheduleStatus.PAID,
                List.of(ScheduleStatus.PENDING, ScheduleStatus.OVERDUE), paidAt);
    }
}
