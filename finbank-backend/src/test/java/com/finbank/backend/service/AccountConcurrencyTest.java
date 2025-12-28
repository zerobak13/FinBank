package com.finbank.backend.service;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.Member;
import com.finbank.backend.dto.TransferRequest;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
@SpringBootTest
class AccountConcurrencyTest {

    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private MemberRepository memberRepository;

    private final String TEST_EMAIL = "test@test.com";

    @BeforeEach
    void clean() {
        accountRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void whenTwoConcurrentTransfers_thenOnlyOneSucceeds() throws InterruptedException {

        // given
        Member member = new Member(TEST_EMAIL, "테스터", "password");
        memberRepository.saveAndFlush(member);

        Account from = new Account(member, "111-111", 100000L);
        Long fromId = accountRepository.saveAndFlush(from).getId();

        Account to = new Account(member, "222-222", 0L);
        String toNum = accountRepository.saveAndFlush(to).getAccountNumber();

        TransferRequest request = new TransferRequest(fromId, toNum, 70000L);

        int threadCount = 2;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.execute(() -> {
                try {
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(
                                    TEST_EMAIL, null, Collections.emptyList()
                            );
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    accountService.transfer(request);
                } catch (Exception e) {
                    System.out.println("기대된 실패: " + e.getMessage());
                } finally {
                    SecurityContextHolder.clearContext();
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // then
        Account finalAccount = accountRepository.findById(fromId).get();
        assertEquals(30000L, finalAccount.getBalance());
    }
}
