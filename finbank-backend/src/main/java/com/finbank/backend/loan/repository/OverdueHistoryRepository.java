package com.finbank.backend.loan.repository;

import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.OverdueHistory;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface OverdueHistoryRepository extends JpaRepository<OverdueHistory, Long> {

    /** 진행 중(미해소) 연체 전체 */
    List<OverdueHistory> findByResolvedAtIsNull();

    /** 한 대출의 진행 중 연체 — 스케줄 조회가 회차별 연체이자를 보여 줄 때 쓴다 (일반 조회) */
    List<OverdueHistory> findByLoanAccountAndResolvedAtIsNull(LoanAccount loanAccount);

    /**
     * 특정 회차의 진행 중 연체 — <b>잠금 조회</b>. 상환이 연체이자를 걷고 이력을 닫을 때 쓴다.
     *
     * <p>잠금 조회라서 최신 커밋본(방금 가산된 연체이자까지)을 읽고, 상환이 끝날 때까지 가산 배치가
     * 이 이력을 건드리지 못한다. 일반 조회로 읽으면 그 사이 가산된 금액을 놓치고 닫아 버릴 수 있다.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from OverdueHistory h where h.schedule.id = :scheduleId and h.resolvedAt is null")
    Optional<OverdueHistory> lockOpenByScheduleId(@Param("scheduleId") Long scheduleId);

    /**
     * 조건부 가산 — 가산 배치가 쓴다. 해소되지 않았고 기준일에 아직 가산하지 않은 이력만 1행이 바뀐다.
     *
     * <p>읽어 둔 이력 엔티티를 고쳐 쓰면 행 전체가 다시 쓰여, 그 사이 상환이 닫은 이력(resolved_at)을
     * 다시 열어 버린다. 누적과 기산점 전진을 조건부 UPDATE 한 문장으로 한다.</p>
     */
    @Modifying
    @Query("""
            update OverdueHistory h
               set h.penaltyAccrued = h.penaltyAccrued + :penalty,
                   h.lastAccruedAt = :accruedAt
             where h.id = :id
               and h.resolvedAt is null
               and (h.lastAccruedAt is null or h.lastAccruedAt < :accruedAt)
            """)
    int accrueIfOpen(@Param("id") Long id,
                     @Param("penalty") BigDecimal penalty,
                     @Param("accruedAt") LocalDate accruedAt);

    /**
     * 연체이자 가산 대상 — <b>커서(id) 기반</b>.
     *
     * <p>{@code lastAccruedAt < baseDate} 조건이 곧 <b>배치 멱등</b>이다.
     * 오늘 이미 가산한 이력은 조회되지 않으므로, 같은 날 두 번 돌려도 이자가 두 번 붙지 않는다.
     * 연체 감지 배치가 status 조건으로 멱등을 얻은 것과 같은 원리 —
     * "처리 완료 표시를 데이터에 남기고, 그 표시를 읽기 조건에 넣는다".</p>
     *
     * <p>여기서도 읽으면서 {@code lastAccruedAt}을 바꾸므로 offset 페이징은 쓸 수 없다.</p>
     */
    @Query("""
            select h from OverdueHistory h
              join fetch h.loanAccount
             where h.resolvedAt is null
               and (h.lastAccruedAt is null or h.lastAccruedAt < :baseDate)
               and h.id > :lastId
             order by h.id asc
            """)
    List<OverdueHistory> findAccrualTargets(@Param("baseDate") LocalDate baseDate,
                                            @Param("lastId") Long lastId,
                                            Pageable pageable);
}
