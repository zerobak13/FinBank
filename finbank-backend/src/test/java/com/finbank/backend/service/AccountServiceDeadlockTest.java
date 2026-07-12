package com.finbank.backend.service;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.Member;
import com.finbank.backend.dto.TransferRequest;

import java.math.BigDecimal;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.MemberRepository;
import com.finbank.backend.repository.RefreshTokenRepository;
import com.finbank.backend.repository.TransactionLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AccountServiceDeadlockTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TransactionLogRepository transactionLogRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("현재 transfer 구현은 반대 방향 동시 이체에서 데드락이 발생할 수 있다")
    void deadlock_can_happen_with_current_transfer() throws Exception {
        int repeat = 100;
        int deadlockCount = 0;
        int successCount = 0;
        List<String> deadlockMessages = new ArrayList<>();

        for (int i = 0; i < repeat; i++) {
            Scenario scenario = createScenario(i);

            Throwable[] results = runOppositeTransfersOnce(scenario);

            boolean deadlockOccurred = false;

            if (isDeadlock(results[0])) {
                deadlockOccurred = true;
                deadlockMessages.add(extractRootMessage(results[0]));
            }
            if (isDeadlock(results[1])) {
                deadlockOccurred = true;
                deadlockMessages.add(extractRootMessage(results[1]));
            }

            if (deadlockOccurred) {
                deadlockCount++;
            } else {
                successCount++;
            }

            clearSecurityContext();
            cleanup();
        }

        printSummary(repeat, successCount, deadlockCount, deadlockMessages);

        //assertThat(deadlockCount).isGreaterThan(0);
        assertThat(deadlockCount).isZero();
    }

    private Scenario createScenario(int index) {
        cleanup();

        Member member1 = memberRepository.save(
                new Member("user1_" + index + "@test.com", "1234", "user1")
        );

        Member member2 = memberRepository.save(
                new Member("user2_" + index + "@test.com", "1234", "user2")
        );

        Account accountA = accountRepository.save(
                new Account(member1, "11111111111" + index, new BigDecimal("100000"))
        );

        Account accountB = accountRepository.save(
                new Account(member2, "22222222222" + index, new BigDecimal("100000"))
        );

        return new Scenario(member1, member2, accountA, accountB);
    }

    private Throwable[] runOppositeTransfersOnce(Scenario scenario) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Throwable> future1 = executorService.submit(() -> {
            setAuthentication(scenario.member1().getEmail());
            readyLatch.countDown();
            startLatch.await();

            try {
                accountService.transfer(createTransferRequest(
                        scenario.accountA().getId(),
                        scenario.accountB().getAccountNumber(),
                        1000L
                ));
                return null;
            } catch (Throwable e) {
                return e;
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        Future<Throwable> future2 = executorService.submit(() -> {
            setAuthentication(scenario.member2().getEmail());
            readyLatch.countDown();
            startLatch.await();

            try {
                accountService.transfer(createTransferRequest(
                        scenario.accountB().getId(),
                        scenario.accountA().getAccountNumber(),
                        1000L
                ));
                return null;
            } catch (Throwable e) {
                return e;
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        readyLatch.await();
        startLatch.countDown();

        Throwable ex1 = future1.get(10, TimeUnit.SECONDS);
        Throwable ex2 = future2.get(10, TimeUnit.SECONDS);

        executorService.shutdown();
        executorService.awaitTermination(5, TimeUnit.SECONDS);

        return new Throwable[]{ex1, ex2};
    }

    private TransferRequest createTransferRequest(Long fromAccountId, String toAccountNumber, long amount) {
        return new TransferRequest(fromAccountId, toAccountNumber, BigDecimal.valueOf(amount));
    }

    private void setAuthentication(String email) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private boolean isDeadlock(Throwable e) {
        while (e != null) {
            if (e instanceof DeadlockLoserDataAccessException ||
                    e instanceof CannotAcquireLockException) {
                return true;
            }

            String message = e.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("deadlock")
                        || lower.contains("lock wait timeout")
                        || lower.contains("could not acquire lock")
                        || lower.contains("try restarting transaction")) {
                    return true;
                }
            }

            e = e.getCause();
        }
        return false;
    }

    private String extractRootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    private void printSummary(int repeat, int successCount, int deadlockCount, List<String> deadlockMessages) {
        System.out.println();
        System.out.println("==================================================");
        System.out.println("DEADLOCK TEST SUMMARY");
        System.out.println("==================================================");
        System.out.println("Total Iterations : " + repeat);
        System.out.println("Success Count    : " + successCount);
        System.out.println("Deadlock Count   : " + deadlockCount);
        System.out.println("Deadlock Rate    : " + String.format("%.2f", (deadlockCount * 100.0) / repeat) + "%");

        if (!deadlockMessages.isEmpty()) {
            System.out.println("Sample Message   : " + deadlockMessages.get(0));
        }

        System.out.println("Result           : " + (deadlockCount > 0 ? "DEADLOCK DETECTED" : "NO DEADLOCK"));
        System.out.println("==================================================");
        System.out.println();
    }

    private void cleanup() {
        // refresh_tokens는 members를 참조하므로 members보다 먼저 정리한다.
        transactionLogRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        accountRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private record Scenario(
            Member member1,
            Member member2,
            Account accountA,
            Account accountB
    ) {
    }
}