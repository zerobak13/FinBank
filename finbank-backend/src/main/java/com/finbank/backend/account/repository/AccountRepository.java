package com.finbank.backend.account.repository;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.member.domain.Member;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 계좌(accounts) 테이블 접근 리포지토리.
 * 동시성 제어를 위한 비관적 락 조회와, 캐시 오염을 피하기 위한 ID 프로젝션 조회를 제공한다.
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 특정 회원의 전체 계좌 목록 */
    List<Account> findByMember(Member member);

    /** 계좌번호 존재 여부 (계좌번호 생성 시 중복 검사) */
    boolean existsByAccountNumber(String accountNumber);

    /** 잠기지 않은 활성 계좌 보유 여부 — 대출 자동심사 룰에서 사용 */
    boolean existsByMemberAndLockedFalse(Member member);

    /**
     * 계좌번호로 계좌 ID만 조회한다(프로젝션).
     * 이체 시 락 순서 결정을 위해 받는 계좌 ID가 필요하지만,
     * 엔티티를 영속성 컨텍스트에 적재하면 이후 락 조회가 낡은 값을 반환하므로
     * 엔티티 대신 ID만 읽는다.
     */
    @Query("select a.id from Account a where a.accountNumber = :accountNumber")
    Optional<Long> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

    /**
     * 회원의 잠기지 않은 계좌 ID 목록(오름차순, 프로젝션).
     * 대출 실행 시 입금받을 계좌를 고르는 데 사용한다 — 위와 같은 이유로 엔티티 대신 ID만 읽는다.
     */
    @Query("select a.id from Account a where a.member.id = :memberId and a.locked = false order by a.id asc")
    List<Long> findActiveIdsByMemberId(@Param("memberId") Long memberId);

    /**
     * 계좌 소유 회원 ID만 조회한다(프로젝션).
     * 입출금·이체에서 락을 걸기 전에 권한을 확인하는 용도 — 엔티티를 적재하지 않으므로
     * 뒤이은 락 조회가 낡은 값을 읽는 문제가 생기지 않는다.
     */
    @Query("select a.member.id from Account a where a.id = :id")
    Optional<Long> findOwnerIdById(@Param("id") Long id);

    /** 비관적 쓰기 락(SELECT ... FOR UPDATE)으로 계좌 조회 — 입출금·이체의 동시성 제어에 사용 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findWithLockingById(Long id);
}
