package com.finbank.backend.loan.repository;

import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.domain.ScheduleStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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

    /**
     * 연체 감지 배치의 Reader 쿼리 — <b>커서(id) 기반</b>.
     *
     * <p>페이지 번호(offset) 방식을 쓰지 않는 이유: 이 배치는 읽은 행의 {@code status}를
     * PENDING → OVERDUE로 <b>바꾼다</b>. 조건에 걸리는 행이 처리와 동시에 줄어들기 때문에,
     * offset 10부터 다음 페이지를 읽으면 앞에서 빠진 만큼 <b>건너뛴 행이 생긴다</b>.
     * "마지막으로 읽은 id 다음부터"를 읽으면 결과 집합이 줄어도 밀리지 않는다.</p>
     *
     * <p>덤으로 offset이 커질수록 느려지는 문제도 없다(10만 번째 페이지도 첫 페이지와 같은 비용).</p>
     */
    @Query("""
            select s from RepaymentSchedule s
              join fetch s.loanAccount
             where s.status = com.finbank.backend.loan.domain.ScheduleStatus.PENDING
               and s.dueDate < :today
               and s.id > :lastId
             order by s.id asc
            """)
    List<RepaymentSchedule> findOverdueCandidates(@Param("today") LocalDate today,
                                                  @Param("lastId") Long lastId,
                                                  Pageable pageable);

    /** 가장 마지막으로 납부 완료된 회차 — 중도상환 경과이자의 기산일 계산에 사용 */
    Optional<RepaymentSchedule> findFirstByLoanAccountAndStatusOrderByInstallmentNoDesc(
            LoanAccount loanAccount, ScheduleStatus status);

    /** 특정 상태의 남은 회차 수 — 연체 해소 판정 등에 사용 */
    int countByLoanAccountAndStatusIn(LoanAccount loanAccount, List<ScheduleStatus> statuses);

    /**
     * 전액 중도상환으로 남은 회차 일괄 무효화 (PENDING → CANCELED).
     *
     * <p>삭제하지 않는 이유: "계약이 원래 어떤 계획이었는지"는 사후 검증에 필요한 사실이다.
     * 원장 append-only 철학의 연장선.</p>
     */
    // clearAutomatically는 쓰지 않는다 — 영속성 컨텍스트를 비우면 같은 트랜잭션에서
    // 락을 걸어둔 계좌·대출 엔티티가 준영속이 되어 이후 변경이 유실된다.
    @Modifying(flushAutomatically = true)
    @Query("""
            update RepaymentSchedule s
               set s.status = com.finbank.backend.loan.domain.ScheduleStatus.CANCELED
             where s.loanAccount.id = :loanAccountId
               and s.status = com.finbank.backend.loan.domain.ScheduleStatus.PENDING
            """)
    int cancelPendingByLoanAccountId(@Param("loanAccountId") Long loanAccountId);
}
