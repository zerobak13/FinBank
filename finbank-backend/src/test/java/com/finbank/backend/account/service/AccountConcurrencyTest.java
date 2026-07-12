package com.finbank.backend.account.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.account.domain.TransactionLog;
import com.finbank.backend.account.domain.TransactionType;
import com.finbank.backend.account.dto.TransferRequest;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.auth.repository.RefreshTokenRepository;
import com.finbank.backend.account.repository.TransactionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountConcurrencyTest {

    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired com.finbank.backend.support.DatabaseCleaner cleaner;

    private static final String EMAIL = "test@test.com";

    @BeforeEach
    void setUp() {
        cleaner.clean();

        Member member = new Member(EMAIL, "테스터", "password");
        memberRepository.saveAndFlush(member);
    }

    @Test
    void concurrent_transfer_only_one_should_succeed() throws InterruptedException {

        Member member = memberRepository.findByEmail(EMAIL).orElseThrow();

        Account from = accountRepository.saveAndFlush(
                new Account(member, "111-111", new BigDecimal("100000"))
        );

        Account to = accountRepository.saveAndFlush(
                new Account(member, "222-222", BigDecimal.ZERO)
        );

        TransferRequest request =
                new TransferRequest(from.getId(), to.getAccountNumber(), new BigDecimal("70000"));

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    EMAIL, null, Collections.emptyList()
                            )
                    );
                    accountService.transfer(request);
                } catch (Exception ignored) {
                    //동시성 실패는 정상 시나리오
                } finally {
                    SecurityContextHolder.clearContext();
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // then - 상태 검증 (핵심)
        Account finalFrom = accountRepository.findById(from.getId()).orElseThrow();
        Account finalTo = accountRepository.findById(to.getId()).orElseThrow();

        // BigDecimal은 equals가 스케일까지 비교하므로(30000 != 30000.0000) isEqualByComparingTo를 쓴다.
        assertThat(finalFrom.getBalance()).isEqualByComparingTo("30000");
        assertThat(finalTo.getBalance()).isEqualByComparingTo("70000");

        List<TransactionLog> logs = transactionLogRepository.findAll();

        assertThat(logs)
                .filteredOn(l -> l.getType() == TransactionType.TRANSFER_OUT)
                .hasSize(1);

        assertThat(logs)
                .filteredOn(l -> l.getType() == TransactionType.TRANSFER_IN)
                .hasSize(1);
    }
}
