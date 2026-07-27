package com.finbank.backend.loan.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.domain.TransactionType;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.account.repository.TransactionLogRepository;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.LoanTransactionType;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.dto.LoanApplicationResponse;
import com.finbank.backend.loan.dto.LoanApplyRequest;
import com.finbank.backend.loan.dto.LoanExecutionResponse;
import com.finbank.backend.loan.dto.RepaymentScheduleResponse;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.loan.repository.LoanTransactionRepository;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상환 동시성·멱등 증명 — C3의 숫자.
 *
 * <p>시나리오 A: 서로 다른 100개 요청이 같은 회차를 동시에 상환한다.<br>
 * 시나리오 B: 같은 멱등키를 가진 100개 재시도가 동시에 도착한다(네트워크 재시도 폭주).</p>
 *
 * <p>두 경우 모두 <b>돈은 정확히 한 번만</b> 움직여야 한다:
 * 원장 1건 · 출금 1건 · 계좌 잔액은 회차 총액만큼만 감소.</p>
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LoanRepaymentConcurrencyTest {

    @Autowired LoanApplicationService loanApplicationService;
    @Autowired LoanExecutionService loanExecutionService;
    @Autowired LoanRepaymentService loanRepaymentService;
    @Autowired LoanQueryService loanQueryService;
    @Autowired LoanTransactionRepository loanTransactionRepository;
    @Autowired LoanProductRepository loanProductRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "repay-concurrent@test.com";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000000");
    private static final BigDecimal LOAN_AMOUNT = new BigDecimal("10000000");
    private static final int TERM = 12;
    private static final int THREADS = 100;

    private Account account;
    private LoanExecutionResponse loan;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        Member member = memberRepository.saveAndFlush(new Member(EMAIL, "동시성테스터", "password"));
        LoanProduct product = loanProductRepository.findByStatus(ProductStatus.ON_SALE).stream()
                .filter(p -> p.getRepaymentType() == RepaymentType.EQUAL_PAYMENT)
                .findFirst().orElseThrow();
        account = accountRepository.saveAndFlush(
                new Account(member, "conc-" + System.nanoTime(), INITIAL_BALANCE));

        login();
        LoanApplicationResponse application = loanApplicationService.apply(
                new LoanApplyRequest(product.getId(), LOAN_AMOUNT, TERM));
        loan = loanExecutionService.execute(application.id(), UUID.randomUUID().toString());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, Collections.emptyList()));
    }

    @Test
    @DisplayName("100개 요청이 같은 회차를 동시 상환 → 정확히 1건 성공, 99건 409, 원장 1건, 출금 1회")
    void concurrentRepayment_onlyOneSucceeds() throws InterruptedException {
        BigDecimal balanceBefore = accountBalance();
        RepaymentScheduleResponse first = loan.schedules().get(0);

        Counters counters = runConcurrently(() -> UUID.randomUUID().toString());

        printTable("서로 다른 100개 요청 (동시 상환)", counters, balanceBefore, first.totalAmount());

        assertThat(counters.success.get()).isEqualTo(1);
        assertThat(counters.conflict.get()).isEqualTo(THREADS - 1);
        assertThat(counters.other.get()).isZero();

        assertLedgerAndBalance(balanceBefore, first.totalAmount());
    }

    @Test
    @DisplayName("같은 멱등키 100개 재시도 동시 도착 → 돈은 한 번만 움직인다 (원장 1건, 출금 1회)")
    void concurrentRetry_sameKey_movesMoneyOnce() throws InterruptedException {
        BigDecimal balanceBefore = accountBalance();
        RepaymentScheduleResponse first = loan.schedules().get(0);
        String sharedKey = UUID.randomUUID().toString();

        Counters counters = runConcurrently(() -> sharedKey);

        printTable("같은 멱등키 100개 재시도", counters, balanceBefore, first.totalAmount());

        // 성공 응답(최초 처리 + 응답 재생)은 여러 건일 수 있지만, 실제 처리는 단 한 번이다.
        assertThat(counters.success.get()).isGreaterThanOrEqualTo(1);
        assertThat(counters.other.get()).isZero();

        assertLedgerAndBalance(balanceBefore, first.totalAmount());
    }

    // ── 공통 실행/검증 ────────────────────────────────────────────

    private Counters runConcurrently(java.util.function.Supplier<String> keySupplier) throws InterruptedException {
        Counters counters = new Counters();
        // 스레드 수와 풀 크기를 같게 둔다 — 풀이 작으면 뒤쪽 작업이 큐에서 대기해
        // "동시에 출발" 자체가 성립하지 않는다(ready 래치가 영원히 안 풀린다).
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                login(); // SecurityContext는 스레드 로컬 — 각 스레드가 직접 설정한다
                ready.countDown();
                try {
                    start.await();
                    // 회차를 1로 명시한다. null(=가장 빠른 미납 회차)로 두면 승자가 커밋한 뒤
                    // 출발한 스레드가 2회차를 정상 상환해버려 "같은 회차 경합"이 되지 않는다.
                    loanRepaymentService.repay(loan.loanId(), 1, keySupplier.get());
                    counters.success.incrementAndGet();
                } catch (BusinessException e) {
                    if (isExpectedConflict(e)) {
                        counters.conflict.incrementAndGet();
                    } else {
                        counters.other.incrementAndGet();
                        counters.firstOther.compareAndSet(null, e.toString());
                    }
                } catch (Exception e) {
                    counters.other.incrementAndGet();
                    counters.firstOther.compareAndSet(null, e.toString());
                } finally {
                    SecurityContextHolder.clearContext();
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();          // 100개 스레드를 같은 순간에 출발시킨다
        done.await();
        pool.shutdown();
        return counters;
    }

    /** 정상적인 경합 결과(409)인지 — 그 외 예외는 설계 결함이므로 실패로 센다 */
    private boolean isExpectedConflict(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return code == ErrorCode.ALREADY_REPAID
                || code == ErrorCode.OUT_OF_ORDER_INSTALLMENT
                || code == ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS;
    }

    private void assertLedgerAndBalance(BigDecimal balanceBefore, BigDecimal installmentTotal) {
        long repaymentLedger = loanTransactionRepository.findAll().stream()
                .filter(tx -> tx.getType() == LoanTransactionType.REPAYMENT)
                .count();
        long withdrawLogs = transactionLogRepository.findAll().stream()
                .filter(t -> t.getType() == TransactionType.WITHDRAW)
                .count();

        assertThat(repaymentLedger).isEqualTo(1);
        assertThat(withdrawLogs).isEqualTo(1);
        assertThat(accountBalance()).isEqualByComparingTo(balanceBefore.subtract(installmentTotal));

        // 1회차만 PAID, 2회차는 손대지 않았다
        assertThat(loanQueryService.getSchedules(loan.loanId()).get(0).status()).isEqualTo("PAID");
        assertThat(loanQueryService.getSchedules(loan.loanId()).get(1).status()).isEqualTo("PENDING");
    }

    private BigDecimal accountBalance() {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    private void printTable(String scenario, Counters c, BigDecimal balanceBefore, BigDecimal installmentTotal) {
        System.out.println();
        System.out.println("=== 상환 동시성: " + scenario + " (스레드 " + THREADS + ") ===");
        System.out.printf("%-14s %10s%n", "성공(응답)", c.success.get());
        System.out.printf("%-14s %10s%n", "충돌(409)", c.conflict.get());
        System.out.printf("%-14s %10s%n", "기타 예외", c.other.get());
        System.out.printf("%-14s %10s%n", "상환 원장", loanTransactionRepository.findAll().stream()
                .filter(tx -> tx.getType() == LoanTransactionType.REPAYMENT).count());
        System.out.printf("%-14s %10s%n", "계좌 출금 로그", transactionLogRepository.findAll().stream()
                .filter(t -> t.getType() == TransactionType.WITHDRAW).count());
        System.out.printf("%-14s %10s%n", "잔액 감소", balanceBefore.subtract(accountBalance()).toPlainString());
        System.out.printf("%-14s %10s%n", "회차 총액", installmentTotal.toPlainString());
        if (c.firstOther.get() != null) {
            System.out.println("첫 기타 예외: " + c.firstOther.get());
        }
        System.out.println();
    }

    private static class Counters {
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger conflict = new AtomicInteger();
        final AtomicInteger other = new AtomicInteger();
        final AtomicReference<String> firstOther = new AtomicReference<>();
    }
}
