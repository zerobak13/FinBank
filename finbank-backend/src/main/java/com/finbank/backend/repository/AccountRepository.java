package com.finbank.backend.repository;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.Member;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByMember(Member member);

    Optional<Account> findByAccountNumber(String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findWithLockingById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findWithLockingByAccountNumber(String accountNumber);

    @Modifying
    @Transactional
    @Query("UPDATE Account a SET a.locked = true WHERE a.id = :id")
    void lockAccount(@Param("id") Long id);
}
