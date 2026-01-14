package com.finbank.backend.service;

import com.finbank.backend.domain.Account;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.MemberRepository;
import com.finbank.backend.repository.TransactionLogRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AccountLedgerValidationTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionLogRepository transactionLogRepository;

    @InjectMocks
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("출금 로그 검증: from 계좌가 없으면 예외 발생")
    void withdraw_ledger_rule_violation() {
        assertThatThrownBy(() ->
                accountService.validateWithdrawLedger(null)
        ).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("입금 로그 검증: to 계좌가 없으면 예외 발생")
    void deposit_ledger_rule_violation() {
        assertThatThrownBy(() ->
                accountService.validateDepositLedger(null)
        ).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("이체 로그 검증: to 계좌가 없으면 예외 발생")
    void transfer_ledger_rule_violation() {
        Account from = mock(Account.class);

        assertThatThrownBy(() ->
                accountService.validateTransferLedger(from, null)
        ).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("이체 로그 검증: 정상적인 from/to 계좌는 통과")
    void transfer_ledger_rule_success() {
        Account from = mock(Account.class);
        Account to = mock(Account.class);

        accountService.validateTransferLedger(from, to);
    }
}
