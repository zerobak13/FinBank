package com.finbank.backend.loan.repository;

import com.finbank.backend.loan.domain.LoanAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface LoanAccountRepository extends JpaRepository<LoanAccount, Long>, LoanAccountRepositoryCustom {

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
     * 연결 입출금 계좌의 ID만 조회한다(프로젝션).
     *
     * <p>상환은 Account → LoanAccount 순으로 잠가야 하는데, 계좌 ID를 알려면 대출을 먼저 읽어야 한다.
     * 이때 대출 <b>엔티티</b>를 읽으면 1차 캐시에 적재되어 뒤이은 락 조회가 락 이전의 낡은 값을
     * 돌려준다(= 비관적 락 무력화, 계좌 동시성 테스트에서 확인한 함정). 그래서 엔티티 대신 ID만 읽는다.</p>
     */
    @Query("select l.linkedAccount.id from LoanAccount l where l.id = :id")
    Optional<Long> findLinkedAccountIdById(@Param("id") Long id);

    /**
     * 대출 소유 회원 ID만 조회한다(프로젝션) — 상환에서 <b>락을 걸기 전에</b> 권한을 확인하는 용도.
     * 남의 대출 번호로 온 요청이 그 사람의 계좌·대출에 락을 거는 일을 막는다(이체와 같은 원칙).
     */
    @Query("select l.member.id from LoanAccount l where l.id = :id")
    Optional<Long> findOwnerIdById(@Param("id") Long id);

    /**
     * 대출 행에 쓰기 락만 건다 — 엔티티를 읽지 않는다.
     *
     * <p>연체 감지 배치가 회차를 연체로 바꾸기 전에 호출한다. 상환과 같은 순서(대출 → 회차)로 잠가야
     * 두 작업이 서로가 쥔 락을 기다리지 않는다.</p>
     */
    @Query(value = "select id from loan_accounts where id = :id for update", nativeQuery = true)
    Long lockById(@Param("id") Long id);

    /**
     * 대출 행에 쓰기 락을 걸고 <b>최신 잔액</b>을 읽는다 — 연체이자 가산 배치가 쓴다.
     *
     * <p>가산 배치도 상환과 같은 순서(대출 → 연체 이력)로 잠가야 한다. 이력을 먼저 잠그면, 대출을 쥔 상환은 이력을
     * 기다리고 배치는 원장을 쓰다가(원장의 대출 FK 확인에 대출 행 공유 락이 필요하다) 상환을 기다려 데드락이 된다.
     * 잔액은 Reader가 읽어 둔 대출 엔티티가 아니라 여기서 읽는다 — 그 사이 다른 회차의 상환이 줄였을 수 있다.</p>
     */
    @Query(value = "select balance from loan_accounts where id = :id for update", nativeQuery = true)
    BigDecimal lockBalanceById(@Param("id") Long id);

    /**
     * 조건부 연체 전이 ACTIVE → OVERDUE — 연체 감지 배치가 쓴다.
     *
     * <p>대출 엔티티를 고쳐 쓰면 행 전체(잔액 포함)가 배치가 읽었던 옛 값으로 다시 쓰여,
     * 그 사이 상환이 줄인 잔액이 되돌아간다. 상태 한 칸만 조건부로 바꾼다.</p>
     */
    @Modifying
    @Query("""
            update LoanAccount l
               set l.status = com.finbank.backend.loan.domain.LoanAccountStatus.OVERDUE
             where l.id = :id
               and l.status = com.finbank.backend.loan.domain.LoanAccountStatus.ACTIVE
            """)
    int markOverdueIfActive(@Param("id") Long id);

    /**
     * 회원의 대출 목록 (최근 실행순). 목록 한 줄에 상품명이 들어가므로 신청·상품을 함께 읽는다 —
     * 따로 읽으면 대출마다 쿼리가 2번씩 더 나간다(N+1). 둘 다 단일 연관이라 페치 조인해도 행이 불어나지 않는다.
     */
    @Query("""
            select l from LoanAccount l
              join fetch l.application a
              join fetch a.product
             where l.member.id = :memberId
             order by l.id desc
            """)
    List<LoanAccount> findByMemberIdOrderByIdDesc(@Param("memberId") Long memberId);

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
