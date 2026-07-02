package com.finbank.backend.service;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.Member;
import com.finbank.backend.domain.TransactionLog;
import com.finbank.backend.domain.TransactionType;
import com.finbank.backend.dto.TransferRequest;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.IdempotencyKeyRepository;
import com.finbank.backend.repository.MemberRepository;
import com.finbank.backend.repository.RefreshTokenRepository;
import com.finbank.backend.repository.TransactionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    @Autowired IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    private static final String EMAIL = "test@test.com";

    @BeforeEach
    void setUp() {
        // members를 참조하는 테이블을 먼저 정리 (FK 제약)
        transactionLogRepository.deleteAllInBatch();
        idempotencyKeyRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();

        Member member = new Member(EMAIL, "테스터", "password");
        memberRepository.saveAndFlush(member);
    }

    @Test
    void concurrent_transfer_only_one_should_succeed() throws InterruptedException {

        Member member = memberRepository.findByEmail(EMAIL).orElseThrow();

        Account from = accountRepository.saveAndFlush(
                new Account(member, "111-111", 100_000L)
        );

        Account to = accountRepository.saveAndFlush(
                new Account(member, "222-222", 0L)
        );

        TransferRequest request =
                new TransferRequest(from.getId(), to.getAccountNumber(), 70_000L);

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

        assertThat(finalFrom.getBalance()).isEqualTo(30_000L);
        assertThat(finalTo.getBalance()).isEqualTo(70_000L);

        List<TransactionLog> logs = transactionLogRepository.findAll();

        assertThat(logs)
                .filteredOn(l -> l.getType() == TransactionType.TRANSFER_OUT)
                .hasSize(1);

        assertThat(logs)
                .filteredOn(l -> l.getType() == TransactionType.TRANSFER_IN)
                .hasSize(1);
    }
}
