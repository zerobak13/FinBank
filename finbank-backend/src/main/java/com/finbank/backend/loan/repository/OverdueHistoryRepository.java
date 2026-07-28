package com.finbank.backend.loan.repository;

import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.OverdueHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface OverdueHistoryRepository extends JpaRepository<OverdueHistory, Long> {

    /** 진행 중(미해소) 연체 전체 */
    List<OverdueHistory> findByResolvedAtIsNull();

    /** 특정 대출의 진행 중 연체 */
    List<OverdueHistory> findByLoanAccountAndResolvedAtIsNull(LoanAccount loanAccount);

    /** 특정 회차의 진행 중 연체 (상환 시 해소 처리용) */
    Optional<OverdueHistory> findByScheduleIdAndResolvedAtIsNull(Long scheduleId);

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
