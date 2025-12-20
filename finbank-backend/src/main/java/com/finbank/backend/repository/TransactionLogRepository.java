package com.finbank.backend.repository;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.TransactionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionLogRepository extends JpaRepository<TransactionLog, Long> {

    List<TransactionLog> findByFromAccountOrToAccountOrderByCreatedAtDesc(Account from, Account to);
}
