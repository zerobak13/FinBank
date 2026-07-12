package com.finbank.backend.loan.repository;

import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.OverdueHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OverdueHistoryRepository extends JpaRepository<OverdueHistory, Long> {

    /** 진행 중(미해소) 연체 전체 — 연체이자 가산 배치가 사용 */
    List<OverdueHistory> findByResolvedAtIsNull();

    /** 특정 대출의 진행 중 연체 */
    List<OverdueHistory> findByLoanAccountAndResolvedAtIsNull(LoanAccount loanAccount);

    /** 특정 회차의 진행 중 연체 (상환 시 해소 처리용) */
    Optional<OverdueHistory> findByScheduleIdAndResolvedAtIsNull(Long scheduleId);
}
