package com.finbank.backend.loan.service;

import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.account.repository.TransactionLogRepository;
import com.finbank.backend.common.idempotency.IdempotentExecutor;
import com.finbank.backend.exception.ForbiddenException;
import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.loan.domain.calculator.AccruedInterestCalculator;
import com.finbank.backend.loan.domain.calculator.PrepaymentFeeCalculator;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanTransactionRepository;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 상환 권한 확인 순서 — 남의 대출이면 <b>락을 걸기 전에</b> 거부한다.
 *
 * <p>소유자 확인이 락 뒤에 있으면, 남의 대출 ID로 요청만 보내도 그 대출과 연결 계좌의 행 락을 잡았다가 403을 돌려준다.
 * 그동안 진짜 주인의 상환·이체가 락을 기다린다. 계좌 출금({@code TransferRuleTest})과 같은 규칙이다.</p>
 */
@ExtendWith(MockitoExtension.class)
class LoanRepaymentAuthorizationTest {

    private static final Long LOAN_ID = 10L;
    private static final Long ME = 1L;
    private static final Long SOMEONE_ELSE = 2L;

    @Mock LoanAccountRepository loanAccountRepository;
    @Mock RepaymentScheduleRepository repaymentScheduleRepository;
    @Mock LoanTransactionRepository loanTransactionRepository;
    @Mock OverdueHistoryRepository overdueHistoryRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionLogRepository transactionLogRepository;
    @Mock AccruedInterestCalculator accruedInterestCalculator;
    @Mock PrepaymentFeeCalculator prepaymentFeeCalculator;
    @Mock IdempotentExecutor idempotentExecutor;

    @InjectMocks LoanRepaymentProcessor processor;

    @Test
    @DisplayName("남의 대출 회차 상환은 403 — 계좌·대출에 락을 걸지 않는다")
    void repay_othersLoan_forbiddenBeforeLock() {
        when(loanAccountRepository.findOwnerIdById(LOAN_ID)).thenReturn(Optional.of(SOMEONE_ELSE));

        assertThrows(ForbiddenException.class, () -> processor.repay(LOAN_ID, ME, null, "repay-key"));

        assertNoLockTaken();
    }

    @Test
    @DisplayName("남의 대출 전액 중도상환도 403 — 계좌·대출에 락을 걸지 않는다")
    void prepayAll_othersLoan_forbiddenBeforeLock() {
        when(loanAccountRepository.findOwnerIdById(LOAN_ID)).thenReturn(Optional.of(SOMEONE_ELSE));

        assertThrows(ForbiddenException.class, () -> processor.prepayAll(LOAN_ID, ME, "prepay-key"));

        assertNoLockTaken();
    }

    @Test
    @DisplayName("없는 대출은 404 — 마찬가지로 락 없이 끝난다")
    void repay_missingLoan_notFoundBeforeLock() {
        when(loanAccountRepository.findOwnerIdById(LOAN_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> processor.repay(LOAN_ID, ME, null, "repay-key"));

        assertNoLockTaken();
    }

    private void assertNoLockTaken() {
        verify(accountRepository, never()).findWithLockingById(any());
        verify(loanAccountRepository, never()).findWithLockingById(any());
    }
}
