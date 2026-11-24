package com.finbank.backend.loan.repository;

import com.finbank.backend.member.domain.Member;
import com.finbank.backend.loan.domain.LoanApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
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

    /**
     * 조건부 취소 APPLIED/APPROVED → CANCELED.
     *
     * <p>엔티티를 읽어 취소하면, 같은 신청의 실행이 그 사이 끝났을 때 행 전체를 다시 쓰면서
     * EXECUTED를 CANCELED로 덮는다(대출금은 이미 입금된 상태). 실행 전이와 같은 이유로 한 문장으로 한다.</p>
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update LoanApplication a
               set a.status = com.finbank.backend.loan.domain.ApplicationStatus.CANCELED
             where a.id = :id
               and a.status in (com.finbank.backend.loan.domain.ApplicationStatus.APPLIED,
                                com.finbank.backend.loan.domain.ApplicationStatus.APPROVED)
            """)
    int cancelIfCancelable(@Param("id") Long id);

    /**
     * 신청의 지금 상태 — 공유 잠금 조회라 최신 커밋본을 읽는다.
     *
     * <p>조건부 전이가 0행일 때 이유를 정확히 알리는 데만 쓴다. 트랜잭션 앞에서 읽어 둔 엔티티는
     * 스냅샷이라, 그 사이 다른 요청이 바꾼 상태를 모른다.</p>
     */
    @Query(value = "select status from loan_applications where id = :id for share", nativeQuery = true)
    String findCurrentStatus(@Param("id") Long id);

    /**
     * 승인됐지만 아직 실행하지 않은 신청 금액 합계 — 한도 심사에서 "곧 나갈 돈"으로 함께 센다.
     */
    @Query("""
            select coalesce(sum(a.requestedAmount), 0)
              from LoanApplication a
             where a.member.id = :memberId
               and a.status = com.finbank.backend.loan.domain.ApplicationStatus.APPROVED
            """)
    BigDecimal sumApprovedAmountByMemberId(@Param("memberId") Long memberId);
}
