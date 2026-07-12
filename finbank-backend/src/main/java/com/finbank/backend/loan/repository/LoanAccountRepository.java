package com.finbank.backend.loan.repository;

import com.finbank.backend.loan.domain.LoanAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface LoanAccountRepository extends JpaRepository<LoanAccount, Long> {

    /**
     * 비관적 락 조회 — 상환 등 잔액이 변하는 연산의 진입점.
     *
     * <p>락 순서 규칙: 자원 유형 간 ACCOUNT → LOAN_ACCOUNT 순서로 잠근다.
     * (상환 = 연결계좌 락 → 대출계좌 락) 이 규칙은 이체의 "ID 오름차순" 규칙과 함께
     * 데드락 방지의 전역 규칙이다.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from LoanAccount l where l.id = :id")
    Optional<LoanAccount> findWithLockingById(@Param("id") Long id);

    /**
     * 회원의 활성(ACTIVE/OVERDUE) 대출 잔액 합계 — 자동심사 한도 룰에서 사용.
     */
    @Query("""
            select coalesce(sum(l.balance), 0)
            from LoanAccount l
            where l.member.id = :memberId
              and l.status in (com.finbank.backend.loan.domain.LoanAccountStatus.ACTIVE,
                               com.finbank.backend.loan.domain.LoanAccountStatus.OVERDUE)
            """)
    BigDecimal sumActiveBalanceByMemberId(@Param("memberId") Long memberId);

    /**
     * 회원의 연체(OVERDUE) 대출 보유 여부 — 자동심사 연체 룰에서 사용.
     */
    @Query("""
            select count(l) > 0
            from LoanAccount l
            where l.member.id = :memberId
              and l.status = com.finbank.backend.loan.domain.LoanAccountStatus.OVERDUE
            """)
    boolean existsOverdueByMemberId(@Param("memberId") Long memberId);
}
