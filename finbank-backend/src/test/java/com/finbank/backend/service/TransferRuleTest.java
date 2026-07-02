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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;


@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferRuleTest {

    private static final String TEST_EMAIL = "test@test.com";

    @Mock MemberRepository memberRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionLogRepository transactionLogRepository;

    @InjectMocks AccountService accountService;

    private Member currentMember;

    @BeforeEach
    void setUpAuth() {
        // SecurityContext 세팅
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(TEST_EMAIL, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // 현재 로그인 사용자(Member) Mock
        currentMember = mock(Member.class);
        when(currentMember.getId()).thenReturn(1L);
        when(memberRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(currentMember));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Account mockAccount(Long id, Long ownerId, String accountNumber, long balance, boolean locked) {
        Account acc = mock(Account.class);
        Member owner = mock(Member.class);

        when(owner.getId()).thenReturn(ownerId);
        when(acc.getId()).thenReturn(id);
        when(acc.getMember()).thenReturn(owner);
        when(acc.getAccountNumber()).thenReturn(accountNumber);
        when(acc.getBalance()).thenReturn(balance);
        when(acc.isLocked()).thenReturn(locked);

        return acc;
    }

    /**
     * 현재 transfer() 구현의 계좌 조회 흐름에 맞춘 공통 스텁.
     * 1) 받는 계좌 ID 프로젝션: findIdByAccountNumber(toNumber)
     * 2) 락 획득(각 행의 첫 조회): findWithLockingById(fromId), findWithLockingById(toId)
     */
    private void stubTransferLookups(Account from, Account to) {
        // mock 메서드 호출을 미리 지역변수로 추출한다.
        // when(...)/thenReturn(...) 내부에서 다른 mock을 호출하면
        // Mockito가 미완성 스터빙으로 오인해 UnfinishedStubbingException이 발생한다.
        Long fromId = from.getId();
        Long toId = to.getId();
        String toNumber = to.getAccountNumber();

        when(accountRepository.findIdByAccountNumber(toNumber)).thenReturn(Optional.of(toId));
        when(accountRepository.findWithLockingById(fromId)).thenReturn(Optional.of(from));
        when(accountRepository.findWithLockingById(toId)).thenReturn(Optional.of(to));
    }

    @Test
    @DisplayName("받는 계좌가 없으면 NotFoundException")
    void transfer_toAccountNotFound_throwsNotFound() {
        when(accountRepository.findIdByAccountNumber("222-222")).thenReturn(Optional.empty());

        TransferRequest req = new TransferRequest(10L, "222-222", 10_000L);

        assertThrows(NotFoundException.class, () -> accountService.transfer(req));
        verify(transactionLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("본인 계좌가 아니면 BusinessException")
    void transfer_notMyAccount_throwsBusiness() {
        Account from = mockAccount(10L, 999L, "111-111", 100_000L, false); // ownerId != currentMemberId(1)
        Account to = mockAccount(20L, 2L, "222-222", 0L, false);

        stubTransferLookups(from, to);

        TransferRequest req = new TransferRequest(10L, "222-222", 10_000L);

        BusinessException ex = assertThrows(BusinessException.class, () -> accountService.transfer(req));
        assertTrue(ex.getMessage().contains("본인 계좌"));

        verify(transactionLogRepository, never()).save(any());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("같은 계좌로 이체하면 BusinessException (from/to 동일 ID)")
    void transfer_sameAccount_throwsBusiness() {
        // 현재 구현은 계좌번호가 아니라 ID 동일성으로 같은 계좌 여부를 판단한다.
        when(accountRepository.findIdByAccountNumber("111-111")).thenReturn(Optional.of(10L));

        TransferRequest req = new TransferRequest(10L, "111-111", 10_000L);

        BusinessException ex = assertThrows(BusinessException.class, () -> accountService.transfer(req));
        assertTrue(ex.getMessage().contains("같은 계좌"));

        verify(transactionLogRepository, never()).save(any());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("잠금 계좌가 있으면 BusinessException")
    void transfer_lockedAccount_throwsBusiness() {
        Account from = mockAccount(10L, 1L, "111-111", 100_000L, true); // locked
        Account to = mockAccount(20L, 2L, "222-222", 0L, false);

        stubTransferLookups(from, to);

        TransferRequest req = new TransferRequest(10L, "222-222", 10_000L);

        BusinessException ex = assertThrows(BusinessException.class, () -> accountService.transfer(req));
        assertTrue(ex.getMessage().contains("잠금"));

        verify(transactionLogRepository, never()).save(any());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("잔액 부족이면 BusinessException")
    void transfer_insufficientBalance_throwsBusiness() {
        Account from = mockAccount(10L, 1L, "111-111", 5_000L, false);
        Account to = mockAccount(20L, 2L, "222-222", 0L, false);

        stubTransferLookups(from, to);

        TransferRequest req = new TransferRequest(10L, "222-222", 10_000L);

        BusinessException ex = assertThrows(BusinessException.class, () -> accountService.transfer(req));
        assertTrue(ex.getMessage().contains("잔액"));

        verify(transactionLogRepository, never()).save(any());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("성공 시에는 계좌 save 2번 + 로그 save 2번 호출")
    void transfer_success_callsSaves() {
        Account from = mockAccount(10L, 1L, "111-111", 100_000L, false);
        Account to = mockAccount(20L, 2L, "222-222", 0L, false);

        stubTransferLookups(from, to);

        // withdraw/deposit는 실제 잔액 변경 대신 void 처리
        doNothing().when(from).withdraw(10_000L);
        doNothing().when(to).deposit(10_000L);

        TransferRequest req = new TransferRequest(10L, "222-222", 10_000L);

        assertDoesNotThrow(() -> accountService.transfer(req));

        // 호출 순서 검증: from 저장 → to 저장 → 로그 2건
        InOrder inOrder = inOrder(accountRepository, transactionLogRepository);
        inOrder.verify(accountRepository).save(from);
        inOrder.verify(accountRepository).save(to);
        inOrder.verify(transactionLogRepository, times(2)).save(any());

        verify(accountRepository, times(2)).save(any(Account.class));
        verify(transactionLogRepository, times(2)).save(any());
    }
}
