package com.finbank.backend.service;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.Member;
import com.finbank.backend.domain.TransactionLog;
import com.finbank.backend.domain.TransactionType;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.MemberRepository;
import com.finbank.backend.repository.RefreshTokenRepository;
import com.finbank.backend.repository.TransactionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
class WithdrawConcurrencyTest {

    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    private static final String EMAIL = "withdraw-concurrency@test.com";

    @BeforeEach
    void setUp() {
        // FK 순서에 맞춰 자식 테이블부터 정리 (refresh_tokens는 members 참조).
        transactionLogRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();

        Member member = new Member(EMAIL, "테스터", "password");
        memberRepository.saveAndFlush(member);
    }

    @Test
    @DisplayName("잔액 10만원 계좌에 7만원 출금을 동시에 2번 시도하면 1번만 성공한다")
    void concurrent_withdraw_only_one_should_succeed() throws InterruptedException {

        Member member = memberRepository.findByEmail(EMAIL).orElseThrow();

        Account account = accountRepository.saveAndFlush(
                new Account(member, "333-333", new BigDecimal("100000"))
        );

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
                    accountService.withdraw(account.getId(), new BigDecimal("70000"));
                } catch (Exception ignored) {
                    // 동시성 실패(잔액 부족)는 정상 시나리오
                } finally {
                    SecurityContextHolder.clearContext();
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // then - 잔액은 정확히 한 번만 차감되어야 한다
        Account finalAccount = accountRepository.findById(account.getId()).orElseThrow();
        // BigDecimal은 equals가 스케일까지 비교하므로 isEqualByComparingTo를 쓴다.
        assertThat(finalAccount.getBalance()).isEqualByComparingTo("30000");

        // 그리고 WITHDRAW 로그도 정확히 1건만 남아야 한다 (lost update가 없었다는 증거)
        List<TransactionLog> logs = transactionLogRepository.findAll();
        assertThat(logs)
                .filteredOn(l -> l.getType() == TransactionType.WITHDRAW)
                .hasSize(1);
    }
}
