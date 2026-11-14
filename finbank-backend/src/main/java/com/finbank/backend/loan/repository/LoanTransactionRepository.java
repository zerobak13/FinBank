package com.finbank.backend.loan.repository;

import com.finbank.backend.loan.domain.LoanTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 대출 거래 원장 리포지토리 — append-only.
 * save 외의 변경 연산(update/delete)은 사용하지 않는다.
 */
public interface LoanTransactionRepository extends JpaRepository<LoanTransaction, Long> {
}
