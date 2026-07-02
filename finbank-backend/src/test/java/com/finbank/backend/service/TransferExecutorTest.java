package com.finbank.backend.service;

import com.finbank.backend.domain.Account;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.TransactionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 이체 실행(잔액 검증 + 차감/적립 + 로그 저장)의 실제 책임을 담당하는
 * {@link TransferExecutor}에 대한 규칙 테스트.
 *
 * AccountService.transfer()는 검증 + 락 획득만 하고 이 클래스로 위임하므로,
 * 잠금 계좌 / 잔액 부족 / 저장 호출 검증은 여기서 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferExecutorTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionLogRepository transactionLogRepository;

    @InjectMocks TransferExecutor transferExecutor;

    private Account mockAccount(Long id, long balance, boolean locked) {
        Account acc = mock(Account.class);
        when(acc.getId()).thenReturn(id);
        when(acc.getBalance()).thenReturn(balance);
        when(acc.isLocked()).thenReturn(locked);
        return acc;
    }

    @BeforeEach
    void setUp() {
        // no-op, 각 테스트에서 개별 스텁
    }

    @Test
    @DisplayName("잠금 계좌가 있으면 BusinessException")
    void execute_lockedAccount_throwsBusiness() {
        Account from = mockAccount(10L, 100_000L, true); // locked
        Account to = mockAccount(20L, 0L, false);

        when(accountRepository.findById(10L)).thenReturn(Optional.of(from));
        when(accountRepository.findById(20L)).thenReturn(Optional.of(to));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferExecutor.execute(10L, 20L, 10_000L));
        assertTrue(ex.getMessage().contains("잠금"));

        verify(transactionLogRepository, never()).save(any());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("잔액 부족이면 BusinessException")
    void execute_insufficientBalance_throwsBusiness() {
        Account from = mockAccount(10L, 5_000L, false);
        Account to = mockAccount(20L, 0L, false);

        when(accountRepository.findById(10L)).thenReturn(Optional.of(from));
        when(accountRepository.findById(20L)).thenReturn(Optional.of(to));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferExecutor.execute(10L, 20L, 10_000L));
        assertTrue(ex.getMessage().contains("잔액"));

        verify(transactionLogRepository, never()).save(any());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("성공 시에는 계좌 save 2번 + 로그 save 2번 호출")
    void execute_success_callsSaves() {
        Account from = mockAccount(10L, 100_000L, false);
        Account to = mockAccount(20L, 0L, false);

        when(accountRepository.findById(10L)).thenReturn(Optional.of(from));
        when(accountRepository.findById(20L)).thenReturn(Optional.of(to));

        doNothing().when(from).withdraw(10_000L);
        doNothing().when(to).deposit(10_000L);

        assertDoesNotThrow(() -> transferExecutor.execute(10L, 20L, 10_000L));

        InOrder inOrder = inOrder(accountRepository, transactionLogRepository);
        inOrder.verify(accountRepository).save(from);
        inOrder.verify(accountRepository).save(to);
        inOrder.verify(transactionLogRepository, times(2)).save(any());

        verify(accountRepository, times(2)).save(any(Account.class));
        verify(transactionLogRepository, times(2)).save(any());
    }
}
