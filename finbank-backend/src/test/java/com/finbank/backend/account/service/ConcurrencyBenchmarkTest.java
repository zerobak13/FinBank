package com.finbank.backend.account.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.account.domain.TransactionLog;
import com.finbank.backend.account.domain.TransactionType;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.auth.repository.RefreshTokenRepository;
import com.finbank.backend.account.repository.TransactionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 벤치마크 — "락 없음(버그 재현)" vs "비관적 락(현재 구현)"을 동일 조건으로 돌려
 * 성공 건수 / 최종 잔액 / 잔액 정합성 / 처리시간을 표로 출력한다.
 *
 * 시나리오: 잔액 200,000원 계좌에 50개 스레드가 동시에 10,000원씩 출금 시도.
 *   - 올바르게 처리되면 정확히 20건만 성공하고 잔액은 0이어야 한다.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcurrencyBenchmarkTest {

    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired com.finbank.backend.support.DatabaseCleaner cleaner;

    private static final String EMAIL = "bench@test.com";
    private static final long INITIAL = 200_000L;   // 초기 잔액
    private static final long AMOUNT  = 10_000L;    // 1회 출금액
    private static final BigDecimal INITIAL_BD = BigDecimal.valueOf(INITIAL);
    private static final BigDecimal AMOUNT_BD  = BigDecimal.valueOf(AMOUNT);
    private static final int  THREADS = 50;         // 동시 요청 수
    private static final long EXPECTED_SUCCESS = INITIAL / AMOUNT; // 20건

    @BeforeEach
    void setUp() {
        cleaner.clean();
        memberRepository.saveAndFlush(new Member(EMAIL, "벤치", "password"));
    }

    @Test
    @DisplayName("동시 출금 벤치마크: 락 없음(버그) vs 비관적 락(현재)")
    void benchmark() throws InterruptedException {
        Result noLock = runNoLock();   // 락 없이 read-modify-write (버그 재현)
        Result locked = runLocked();   // 현재 구현 (비관적 락)

        printTable(noLock, locked);

        // 현재 구현(비관적 락)은 반드시 정합해야 한다.
        assertThat(locked.finalBalance()).isEqualTo(0L);
        assertThat(locked.withdrawLogs()).isEqualTo((int) EXPECTED_SUCCESS);
    }

    // ── 시나리오 A: 락 없음 (버그 재현) ─────────────────────────────
    private Result runNoLock() throws InterruptedException {
        Long accountId = freshAccount();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        long start = System.currentTimeMillis();
        runConcurrently(() -> tx.executeWithoutResult(status -> {
            Account a = accountRepository.findById(accountId).orElseThrow(); // 락 없음
            if (a.getBalance().compareTo(AMOUNT_BD) >= 0) {                  // 낡은 값 기준 판단
                a.withdraw(AMOUNT_BD);
                accountRepository.save(a);
                transactionLogRepository.save(TransactionLog.withdraw(a, AMOUNT_BD, a.getBalance()));
            }
        }));
        long elapsed = System.currentTimeMillis() - start;
        return collect("락 없음 (read-modify-write)", accountId, elapsed);
    }

    // ── 시나리오 B: 비관적 락 (현재 구현) ───────────────────────────
    private Result runLocked() throws InterruptedException {
        Long accountId = freshAccount();

        long start = System.currentTimeMillis();
        runConcurrently(() -> {
            try {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(EMAIL, null, Collections.emptyList()));
                accountService.withdraw(accountId, AMOUNT_BD); // findWithLockingById
            } catch (Exception ignored) {
                // 잔액 부족 실패는 정상 시나리오
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        long elapsed = System.currentTimeMillis() - start;
        return collect("비관적 락 (현재 구현)", accountId, elapsed);
    }

    // ── 공통 유틸 ──────────────────────────────────────────────────
    private void runConcurrently(Runnable action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch latch = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try { action.run(); }
                catch (Exception ignored) { }
                finally { latch.countDown(); }
            });
        }
        latch.await();
        pool.shutdown();
    }

    private Long freshAccount() {
        transactionLogRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        Member member = memberRepository.findByEmail(EMAIL).orElseThrow();
        Account acc = accountRepository.saveAndFlush(new Account(member, "bench-" + System.nanoTime(), INITIAL_BD));
        return acc.getId();
    }

    private Result collect(String label, Long accountId, long elapsedMs) {
        // DECIMAL(19,4)로 조회되지만 값은 항상 원 단위 정수이므로 long으로 안전 변환한다.
        long finalBalance = accountRepository.findById(accountId).orElseThrow().getBalance().longValueExact();
        int withdrawLogs = (int) transactionLogRepository.findAll().stream()
                .filter(l -> l.getType() == TransactionType.WITHDRAW).count();
        // "로그상 빠져나간 금액" vs "실제 줄어든 잔액"의 차이 = lost update 로 새어나간 금액
        long loggedOut = (long) withdrawLogs * AMOUNT;
        long actuallyReduced = INITIAL - finalBalance;
        long lostUpdate = loggedOut - actuallyReduced;
        return new Result(label, withdrawLogs, finalBalance, lostUpdate, elapsedMs);
    }

    private void printTable(Result... rows) {
        String line = "  " + "-".repeat(94);
        System.out.println();
        System.out.println("  ==== 동시 출금 벤치마크 (초기잔액 " + fmt(INITIAL) + "원, " + THREADS
                + "스레드 x " + fmt(AMOUNT) + "원 출금, 정상이면 " + EXPECTED_SUCCESS + "건만 성공) ====");
        System.out.println(line);
        System.out.printf("  %-26s %10s %14s %14s %18s %8s%n",
                "방식", "성공(로그)", "최종잔액", "기대잔액", "잔액오차(lost)", "시간");
        System.out.println(line);
        for (Result r : rows) {
            System.out.printf("  %-24s %10d %14s %14s %18s %6dms%n",
                    r.label(), r.withdrawLogs(), fmt(r.finalBalance()), "0",
                    fmt(r.lostUpdate()) + (r.lostUpdate() == 0 ? " (정합)" : " (증발)"), r.elapsedMs());
        }
        System.out.println(line);
        System.out.println("  * 잔액오차(lost) = 로그상 빠져나간 금액 - 실제 줄어든 잔액. 0이면 lost update 없음.");
        System.out.println();
    }

    private static String fmt(long v) { return String.format("%,d", v); }

    private record Result(String label, int withdrawLogs, long finalBalance, long lostUpdate, long elapsedMs) {}
}
