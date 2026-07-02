package com.finbank.backend.service;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.Member;
import com.finbank.backend.dto.TransferRequest;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.MemberRepository;
import com.finbank.backend.repository.TransactionLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AccountService.transfer()는 이제 "검증 + 락 획득 + 실행 위임"만 담당한다.
 * 실제 잔액 차감/적립/로그 저장은 {@link TransferExecutor}로 분리됐으므로
 * (락 획득 이후 새 트랜잭션을 열기 위함 — REPEATABLE READ 스냅샷 문제 회피),
 * 그 쪽 책임은 {@link TransferExecutorTest}에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferRuleTest {

    private static final String TEST_EMAIL = "test@test.com";

    @Mock MemberRepository memberRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionLogRepository transactionLogRepository;

    // Redisson 분산 락 — 테스트에서는 항상 락 획득에 성공하도록 모킹
    @Mock RedissonClient redissonClient;
    @Mock RLock lock;
    @Mock RLock multiLock;

    // 실제 차감/적립/저장은 위임만 되는지 확인할 대상이므로 mock으로 대체
    @Mock TransferExecutor transferExecutor;

    @InjectMocks AccountService accountService;

    private Member currentMember;

    @BeforeEach
    void setUpAuth() throws InterruptedException {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(TEST_EMAIL, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        currentMember = mock(Member.class);
        when(currentMember.getId()).thenReturn(1L);
        when(memberRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(currentMember));

        when(redissonClient.getLock(any(String.class))).thenReturn(lock);
        when(redissonClient.getMultiLock(any(RLock.class), any(RLock.class))).thenReturn(multiLock);
        when(multiLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(multiLock.isHeldByCurrentThread()).thenReturn(true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Account mockAccount(Long id, Long ownerId, String accountNumber) {
        Account acc = mock(Account.class);
        Member owner = mock(Member.class);

        when(owner.getId()).thenReturn(ownerId);
        when(acc.getId()).thenReturn(id);
        when(acc.getMember()).thenReturn(owner);
        when(acc.getAccountNumber()).thenReturn(accountNumber);

        return acc;
    }

    @Test
    @DisplayName("받는 계좌가 없으면 NotFoundException")
    void transfer_toAccountNotFound_throwsNotFound() {
        Account from = mockAccount(10L, 1L, "111-111");

        when(accountRepository.findById(10L)).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("222-222")).thenReturn(Optional.empty());

        TransferRequest req = new TransferRequest(10L, "222-222", 10_000L);

        assertThrows(NotFoundException.class, () -> accountService.transfer(req));
        verify(transferExecutor, never()).execute(any(), any(), anyLong());
    }

    @Test
    @DisplayName("본인 계좌가 아니면 BusinessException")
    void transfer_notMyAccount_throwsBusiness() {
        Account from = mockAccount(10L, 999L, "111-111"); // ownerId != currentMemberId(1)
        Account to = mockAccount(20L, 2L, "222-222");

        when(accountRepository.findById(10L)).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("222-222")).thenReturn(Optional.of(to));

        TransferRequest req = new TransferRequest(10L, "222-222", 10_000L);

        BusinessException ex = assertThrows(BusinessException.class, () -> accountService.transfer(req));
        assertTrue(ex.getMessage().contains("본인 계좌"));

        verify(transferExecutor, never()).execute(any(), any(), anyLong());
    }

    @Test
    @DisplayName("같은 계좌번호로 이체하면 BusinessException")
    void transfer_sameAccountNumber_throwsBusiness() {
        Account from = mockAccount(10L, 1L, "111-111");
        Account to = mockAccount(10L, 2L, "111-111"); // same id/number as from

        when(accountRepository.findById(10L)).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("111-111")).thenReturn(Optional.of(to));

        TransferRequest req = new TransferRequest(10L, "111-111", 10_000L);

        BusinessException ex = assertThrows(BusinessException.class, () -> accountService.transfer(req));
        assertTrue(ex.getMessage().contains("같은 계좌"));

        verify(transferExecutor, never()).execute(any(), any(), anyLong());
    }

    @Test
    @DisplayName("이체 금액이 0 이하면 BusinessException")
    void transfer_invalidAmount_throwsBusiness() {
        Account from = mockAccount(10L, 1L, "111-111");
        Account to = mockAccount(20L, 2L, "222-222");

        when(accountRepository.findById(10L)).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("222-222")).thenReturn(Optional.of(to));

        TransferRequest req = new TransferRequest(10L, "222-222", 0L);

        BusinessException ex = assertThrows(BusinessException.class, () -> accountService.transfer(req));
        assertTrue(ex.getMessage().contains("0보다"));

        verify(transferExecutor, never()).execute(any(), any(), anyLong());
    }

    @Test
    @DisplayName("락 획득에 실패하면 BusinessException")
    void transfer_lockAcquireFails_throwsBusiness() throws InterruptedException {
        Account from = mockAccount(10L, 1L, "111-111");
        Account to = mockAccount(20L, 2L, "222-222");

        when(accountRepository.findById(10L)).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("222-222")).thenReturn(Optional.of(to));
        when(multiLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        TransferRequest req = new TransferRequest(10L, "222-222", 10_000L);

        BusinessException ex = assertThrows(BusinessException.class, () -> accountService.transfer(req));
        assertTrue(ex.getMessage().contains("다른 요청"));

        verify(transferExecutor, never()).execute(any(), any(), anyLong());
    }

    @Test
    @DisplayName("검증을 통과하면 락을 잡고 TransferExecutor에 위임한 뒤 락을 해제한다")
    void transfer_success_delegatesToExecutorAndUnlocks() {
        Account from = mockAccount(10L, 1L, "111-111");
        Account to = mockAccount(20L, 2L, "222-222");

        when(accountRepository.findById(10L)).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("222-222")).thenReturn(Optional.of(to));

        TransferRequest req = new TransferRequest(10L, "222-222", 10_000L);

        assertDoesNotThrow(() -> accountService.transfer(req));

        verify(transferExecutor, times(1)).execute(10L, 20L, 10_000L);
        verify(multiLock, times(1)).unlock();
    }
}
