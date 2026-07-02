package com.finbank.backend.repository;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.Member;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByMember(Member member);

    boolean existsByAccountNumber(String accountNumber);

    /**
     * 계좌번호로 계좌 ID만 조회한다(프로젝션).
     * 이체 시 락 순서 결정을 위해 받는 계좌 ID가 필요하지만,
     * 엔티티를 영속성 컨텍스트에 적재하면 이후 락 조회가 낡은 값을 반환하므로
     * 엔티티 대신 ID만 읽는다.
     */
    @Query("select a.id from Account a where a.accountNumber = :accountNumber")
    Optional<Long> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findWithLockingById(Long id);
}
