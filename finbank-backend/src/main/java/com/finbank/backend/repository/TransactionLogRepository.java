package com.finbank.backend.repository;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.TransactionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import com.finbank.backend.domain.TransactionType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 거래 로그(transaction_logs) 테이블 접근 리포지토리.
 */
public interface TransactionLogRepository extends JpaRepository<TransactionLog, Long> {

    /**
     * 특정 계좌 관점의 거래 내역을 페이징 조회한다.
     * 내가 보낸 거래(fromAccount + 출금/이체출금)와 내가 받은 거래(toAccount + 입금/이체입금)를
     * 한 번에 합쳐서 가져온다.
     */
    @Query("""
    select t
    from TransactionLog t
    where
        (
            t.fromAccount = :account
            and t.type in (:outTypes)
        )
        or
        (
            t.toAccount = :account
            and t.type in (:inTypes)
        )
""")
    Page<TransactionLog> findByAccountPerspective(
            @Param("account") Account account,
            @Param("outTypes") List<TransactionType> outTypes,
            @Param("inTypes") List<TransactionType> inTypes,
            Pageable pageable
    );
}
