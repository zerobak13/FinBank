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

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
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

    private Account createAccount(Member member, String number, BigDecimal balance) {
        Account acc = new Account(member, number, balance);
        return accountRepository.saveAndFlush(acc);
    }

    @Test
    @DisplayName("입금 성공 시 DEPOSIT 로그가 1개 남고 balanceAfter가 반영된다")
    void deposit_createsDepositLog() {
        Member member = createMember();
        Account a = createAccount(member, "111-111", new BigDecimal("10000"));

        accountService.deposit(a.getId(), new BigDecimal("3000"));

        List<TransactionLog> logs = transactionLogRepository.findAll();
        assertEquals(1, logs.size());

        TransactionLog log = logs.get(0);
        assertEquals(TransactionType.DEPOSIT, log.getType());
        // BigDecimal은 equals가 스케일까지 비교하므로(3000 != 3000.0000) isEqualByComparingTo를 쓴다.
        assertThat(log.getAmount()).isEqualByComparingTo("3000");
        assertThat(log.getBalanceAfter()).isEqualByComparingTo("13000");

        Account updated = accountRepository.findById(a.getId()).orElseThrow();
        assertThat(updated.getBalance()).isEqualByComparingTo("13000");
    }

    @Test
    @DisplayName("출금 성공 시 WITHDRAW 로그가 1개 남고 balanceAfter가 반영된다")
    void withdraw_createsWithdrawLog() {
        Member member = createMember();
        Account a = createAccount(member, "111-111", new BigDecimal("10000"));

        accountService.withdraw(a.getId(), new BigDecimal("4000"));

        List<TransactionLog> logs = transactionLogRepository.findAll();
        assertEquals(1, logs.size());

        TransactionLog log = logs.get(0);
        assertEquals(TransactionType.WITHDRAW, log.getType());
        assertThat(log.getAmount()).isEqualByComparingTo("4000");
        assertThat(log.getBalanceAfter()).isEqualByComparingTo("6000");

        Account updated = accountRepository.findById(a.getId()).orElseThrow();
        assertThat(updated.getBalance()).isEqualByComparingTo("6000");
    }

    @Test
    @DisplayName("이체 성공 시 TRANSFER_OUT/TRANSFER_IN 로그가 각각 1개씩 총 2개 남는다")
    void transfer_createsTwoLogs() {
        Member member = createMember();
        Account from = createAccount(member, "111-111", new BigDecimal("100000"));
        Account to = createAccount(member, "222-222", BigDecimal.ZERO);

        accountService.transfer(new TransferRequest(from.getId(), to.getAccountNumber(), new BigDecimal("70000")));

        List<TransactionLog> logs = transactionLogRepository.findAll();
        assertEquals(2, logs.size());

        Map<TransactionType, TransactionLog> byType = logs.stream()
                .collect(Collectors.toMap(TransactionLog::getType, l -> l));

        assertTrue(byType.containsKey(TransactionType.TRANSFER_OUT));
        assertTrue(byType.containsKey(TransactionType.TRANSFER_IN));

        TransactionLog out = byType.get(TransactionType.TRANSFER_OUT);
        TransactionLog in = byType.get(TransactionType.TRANSFER_IN);

        assertThat(out.getAmount()).isEqualByComparingTo("70000");
        assertThat(out.getBalanceAfter()).isEqualByComparingTo("30000");

        assertThat(in.getAmount()).isEqualByComparingTo("70000");
        assertThat(in.getBalanceAfter()).isEqualByComparingTo("70000");

        Account updatedFrom = accountRepository.findById(from.getId()).orElseThrow();
        Account updatedTo = accountRepository.findById(to.getId()).orElseThrow();
        assertThat(updatedFrom.getBalance()).isEqualByComparingTo("30000");
        assertThat(updatedTo.getBalance()).isEqualByComparingTo("70000");
    }
}
