package com.finbank.backend.loan.repository;

import com.finbank.backend.loan.domain.ApplicationStatus;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.loan.domain.LoanApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {

    /** 회원의 신청 이력 (최신순) */
    List<LoanApplication> findByMemberOrderByAppliedAtDesc(Member member);

    /**
     * 조건부 상태 전이 APPROVED → EXECUTED — 이중 실행 방어의 1차 방어선.
     *
     * <p>엔티티를 읽어 if로 검사한 뒤 저장하면, 두 요청이 나란히 APPROVED를 읽고
     * 둘 다 통과한다(check-then-act). 이 UPDATE는 조건과 변경이 DB에서 한 문장으로
     * 처리되므로 반드시 하나만 1을 돌려받는다. 0이면 이미 다른 요청이 실행한 것 → 409.</p>
     *
     * <p>최종 방어선은 loan_accounts.application_id UNIQUE다. 각 층이 막는 것이 다르다:
     * 이 UPDATE는 "깔끔한 409 응답"을, UNIQUE는 "어떤 경로로도 원장이 2개일 수 없음"을 보장한다.</p>
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update LoanApplication a
               set a.status = com.finbank.backend.loan.domain.ApplicationStatus.EXECUTED
             where a.id = :id
               and a.status = com.finbank.backend.loan.domain.ApplicationStatus.APPROVED
            """)
    int markExecutedIfApproved(@Param("id") Long id);

    /** 회원의 특정 상태 신청 목록 */
    List<LoanApplication> findByMemberAndStatus(Member member, ApplicationStatus status);
}
