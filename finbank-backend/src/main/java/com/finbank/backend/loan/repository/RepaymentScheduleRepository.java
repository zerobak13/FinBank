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
     * 조건부 연체 전이 PENDING → OVERDUE — 연체 감지 배치가 쓴다.
     *
     * <p>배치가 읽어 둔 엔티티를 고쳐 쓰면, 읽은 뒤에 끝난 상환(PAID)을 청크 끝에 옛 값으로 덮어쓴다.
     * 이 UPDATE는 최신 커밋본을 보고 판단하므로, 그 사이 상환된 회차는 0행이 되어 건너뛴다.</p>
     */
    @Modifying
    @Query("""
            update RepaymentSchedule s
               set s.status = com.finbank.backend.loan.domain.ScheduleStatus.OVERDUE
             where s.id = :scheduleId
               and s.status = com.finbank.backend.loan.domain.ScheduleStatus.PENDING
            """)
    int markOverdueIfPending(@Param("scheduleId") Long scheduleId);

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

    /**
     * 가장 마지막으로 납부 완료된 회차의 ID — <b>공유 잠금 조회</b>(최신 커밋본). 중도상환 경과이자의 기산일 계산에 쓴다.
     *
     * <p>일반 조회는 트랜잭션 첫 조회 시점의 스냅샷이다. 중도상환이 계좌 락을 기다리는 사이 같은 대출의 회차 상환이
     * 끝나면, 일반 조회로는 그 상환을 못 봐서 기산일이 한 회차 앞으로 당겨지고 이자를 더 받는다.
     * 아래 {@link #lockOverdueScheduleIds}와 같은 이유로 (대출, 회차) 유니크 인덱스를 고정한다.</p>
     */
    @Query(value = """
            select s.id from repayment_schedules s force index (uq_schedule_installment)
             where s.loan_account_id = :loanAccountId
               and s.status = 'PAID'
             order by s.installment_no desc
             limit 1
               for share
            """, nativeQuery = true)
    Optional<Long> lockLastPaidScheduleId(@Param("loanAccountId") Long loanAccountId);

    /**
     * 남은 연체 회차 ID — <b>잠금 조회</b>(최신 커밋본). 상환 뒤 연체 해소 판정에 쓴다.
     *
     * <p>일반 조회는 트랜잭션 첫 조회 시점의 스냅샷을 읽어서, 그 뒤에 연체 감지 배치가 연체로 바꾼 회차를
     * 못 볼 수 있다(그러면 연체 회차가 남았는데 대출을 정상으로 되돌린다). 잠금 조회는 최신 커밋본을 읽는다.</p>
     *
     * <p><b>인덱스를 고정한다.</b> 연체 회차가 적으면 옵티마이저가 상태 인덱스(status, due_date)를 고르는데,
     * 그러면 잠금 조회가 <b>다른 대출의 연체 회차까지</b> 훑으며 잠근다. 같은 시각 감지 배치가 다른 대출의 회차를
     * 연체로 바꾸는 중이면 상환은 그 배치를 기다리고, 배치는 상환이 쥔 이 대출의 락을 기다려 데드락이 된다.
     * (대출, 회차) 유니크 인덱스로 이 대출의 회차만 훑는다 — 이 대출은 상환이 이미 대출 락으로 지키고 있다.</p>
     */
    @Query(value = """
            select s.id from repayment_schedules s force index (uq_schedule_installment)
             where s.loan_account_id = :loanAccountId
               and s.status = 'OVERDUE'
               for update
            """, nativeQuery = true)
    List<Long> lockOverdueScheduleIds(@Param("loanAccountId") Long loanAccountId);

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
