package com.finbank.backend.account.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.dto.TransferRequest;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import com.finbank.backend.support.TestAccountNumbers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 반대 방향 동시 이체(A→B와 B→A를 같은 순간에) 데드락 회귀 테스트.
 *
 * <p>출금 계좌 → 입금 계좌 순서로 락을 걸면 두 요청이 서로 상대가 잡은 행을 기다리고, MySQL이 한쪽을 데드락으로 끊는다.
 * 지금은 두 계좌를 <b>ID 오름차순</b>으로 잠그므로 데드락이 0건이어야 하고, 두 이체가 모두 성공해야 한다.
 * 같은 금액을 주고받았으니 매번 두 잔액이 처음과 같아야 한다.</p>
 */
@SpringBootTest
class AccountServiceDeadlockTest {

    private static final int REPEAT = 100;
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100000");
    private static final BigDecimal AMOUNT = new BigDecimal("1000");

    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired DatabaseCleaner cleaner;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleaner.clean();
    }

    @Test
    @DisplayName("반대 방향 동시 이체 100번 — ID 오름차순 락이라 데드락 0건, 이체는 전부 성공")
    void oppositeTransfers_noDeadlock_allSucceed() throws Exception {
        int deadlockCount = 0;
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < REPEAT; i++) {
            Scenario scenario = createScenario(i);

            Throwable[] results = runOppositeTransfersOnce(scenario);

            for (Throwable result : results) {
                if (result == null) {
                    continue;
                }
                if (isLockFailure(result)) {
                    deadlockCount++;
                }
                failures.add("#" + i + " " + rootMessage(result));
            }
            if (results[0] == null && results[1] == null) {
                assertThat(balanceOf(scenario.accountA())).as("#%d A 잔액", i).isEqualByComparingTo(INITIAL_BALANCE);
                assertThat(balanceOf(scenario.accountB())).as("#%d B 잔액", i).isEqualByComparingTo(INITIAL_BALANCE);
            }
        }

        System.out.printf("%n[반대 방향 동시 이체] %d회 — 실패 %d건 (락 충돌·데드락 %d건)%n",
                REPEAT, failures.size(), deadlockCount);

        assertThat(deadlockCount).as("락 충돌·데드락 건수").isZero();
        assertThat(failures).as("실패한 이체").isEmpty();
    }

    private Scenario createScenario(int index) {
        cleaner.clean();

        Member member1 = memberRepository.save(new Member("user1_" + index + "@test.com", "user1", "1234"));
        Member member2 = memberRepository.save(new Member("user2_" + index + "@test.com", "user2", "1234"));

        Account accountA = accountRepository.save(new Account(member1, TestAccountNumbers.next(), INITIAL_BALANCE));
        Account accountB = accountRepository.save(new Account(member2, TestAccountNumbers.next(), INITIAL_BALANCE));

        return new Scenario(member1, member2, accountA, accountB);
    }

    private Throwable[] runOppositeTransfersOnce(Scenario scenario) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Throwable> aToB = executorService.submit(() -> transferAs(scenario.member1(),
                new TransferRequest(scenario.accountA().getId(), scenario.accountB().getAccountNumber(), AMOUNT),
                readyLatch, startLatch));
        Future<Throwable> bToA = executorService.submit(() -> transferAs(scenario.member2(),
                new TransferRequest(scenario.accountB().getId(), scenario.accountA().getAccountNumber(), AMOUNT),
                readyLatch, startLatch));

        readyLatch.await();
        startLatch.countDown();

        Throwable first = aToB.get(10, TimeUnit.SECONDS);
        Throwable second = bToA.get(10, TimeUnit.SECONDS);

        executorService.shutdown();
        executorService.awaitTermination(5, TimeUnit.SECONDS);

        return new Throwable[]{first, second};
    }

    /** 두 스레드를 같은 순간에 출발시키고, 실패하면 예외를 돌려준다 (성공은 null) */
    private Throwable transferAs(Member member, TransferRequest request,
                                 CountDownLatch readyLatch, CountDownLatch startLatch) throws InterruptedException {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(member.getEmail(), null, List.of()));
        readyLatch.countDown();
        startLatch.await();
        try {
            accountService.transfer(request);
            return null;
        } catch (Throwable e) {
            return e;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    /** 데드락·락 대기 시간 초과 — 스프링은 둘 다 PessimisticLockingFailureException 계열로 바꿔 던진다 */
    private boolean isLockFailure(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof PessimisticLockingFailureException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("deadlock") || lower.contains("lock wait timeout")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private record Scenario(Member member1, Member member2, Account accountA, Account accountB) {
    }
}
