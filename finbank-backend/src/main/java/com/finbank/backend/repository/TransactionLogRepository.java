package com.finbank.backend.repository;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.TransactionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import com.finbank.backend.domain.TransactionType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


import java.util.List;

public interface TransactionLogRepository extends JpaRepository<TransactionLog, Long> {

    List<TransactionLog> findByFromAccountOrderByCreatedAtDesc(Account from);
    List<TransactionLog> findByToAccountOrderByCreatedAtDesc(Account to);

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
    order by t.createdAt desc
""")
    List<TransactionLog> findByAccountPerspective(
            @Param("account") Account account,
            @Param("outTypes") List<TransactionType> outTypes,
            @Param("inTypes") List<TransactionType> inTypes
    );
}
