package com.finbank.backend.service;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.Member;
import com.finbank.backend.domain.TransactionLog;
import com.finbank.backend.domain.TransactionType;
import com.finbank.backend.dto.TransferRequest;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.MemberRepository;
import com.finbank.backend.repository.RefreshTokenRepository;
import com.finbank.backend.repository.TransactionLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TransactionLogTest {

    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    private static final String TEST_EMAIL = "log@test.com";

    @BeforeEach
    void setUp() {
        // FK 순서 고려해서 정리 (refresh_tokens는 members 참조 → members보다 먼저)
        transactionLogRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();

        // 로그인 세팅
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(TEST_EMAIL, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Member createMember() {
        Member m = new Member(TEST_EMAIL, "로그테스터", "password");
        return memberRepository.saveAndFlush(m);
    }

    private Account createAccount(Member member, String number, long balance) {
        Account acc = new Account(member, number, balance);
        return accountRepository.saveAndFlush(acc);
    }

    @Test
    @DisplayName("입금 성공 시 DEPOSIT 로그가 1개 남고 balanceAfter가 반영된다")
    void deposit_createsDepositLog() {
        Member member = createMember();
        Account a = createAccount(member, "111-111", 10_000L);

        accountService.deposit(a.getId(), 3_000L);

        List<TransactionLog> logs = transactionLogRepository.findAll();
        assertEquals(1, logs.size());

        TransactionLog log = logs.get(0);
        assertEquals(TransactionType.DEPOSIT, log.getType());
        assertEquals(3_000L, log.getAmount());
        assertEquals(13_000L, log.getBalanceAfter());

        Account updated = accountRepository.findById(a.getId()).orElseThrow();
        assertEquals(13_000L, updated.getBalance());
    }

    @Test
    @DisplayName("출금 성공 시 WITHDRAW 로그가 1개 남고 balanceAfter가 반영된다")
    void withdraw_createsWithdrawLog() {
        Member member = createMember();
        Account a = createAccount(member, "111-111", 10_000L);

        accountService.withdraw(a.getId(), 4_000L);

        List<TransactionLog> logs = transactionLogRepository.findAll();
        assertEquals(1, logs.size());

        TransactionLog log = logs.get(0);
        assertEquals(TransactionType.WITHDRAW, log.getType());
        assertEquals(4_000L, log.getAmount());
        assertEquals(6_000L, log.getBalanceAfter());

        Account updated = accountRepository.findById(a.getId()).orElseThrow();
        assertEquals(6_000L, updated.getBalance());
    }

    @Test
    @DisplayName("이체 성공 시 TRANSFER_OUT/TRANSFER_IN 로그가 각각 1개씩 총 2개 남는다")
    void transfer_createsTwoLogs() {
        Member member = createMember();
        Account from = createAccount(member, "111-111", 100_000L);
        Account to = createAccount(member, "222-222", 0L);

        accountService.transfer(new TransferRequest(from.getId(), to.getAccountNumber(), 70_000L));

        List<TransactionLog> logs = transactionLogRepository.findAll();
        assertEquals(2, logs.size());

        Map<TransactionType, TransactionLog> byType = logs.stream()
                .collect(Collectors.toMap(TransactionLog::getType, l -> l));

        assertTrue(byType.containsKey(TransactionType.TRANSFER_OUT));
        assertTrue(byType.containsKey(TransactionType.TRANSFER_IN));

        TransactionLog out = byType.get(TransactionType.TRANSFER_OUT);
        TransactionLog in = byType.get(TransactionType.TRANSFER_IN);

        assertEquals(70_000L, out.getAmount());
        assertEquals(30_000L, out.getBalanceAfter());

        assertEquals(70_000L, in.getAmount());
        assertEquals(70_000L, in.getBalanceAfter());

        Account updatedFrom = accountRepository.findById(from.getId()).orElseThrow();
        Account updatedTo = accountRepository.findById(to.getId()).orElseThrow();
        assertEquals(30_000L, updatedFrom.getBalance());
        assertEquals(70_000L, updatedTo.getBalance());
    }
}
