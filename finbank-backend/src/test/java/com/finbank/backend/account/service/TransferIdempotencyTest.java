package com.finbank.backend.account.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.domain.TransactionType;
import com.finbank.backend.account.dto.TransferRequest;
import com.finbank.backend.account.dto.TransferResponse;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.account.repository.TransactionLogRepository;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import com.finbank.backend.support.TestAccountNumbers;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이체 멱등성 증명 — 상환(C3)에서 만든 멱등 인프라를 이체로 확장한 결과.
 *
 * <p>"버튼 두 번 / 네트워크 재시도"가 가장 위험한 곳은 이체다. 같은 멱등키로 몇 번이 오든
 * <b>돈은 한 번만</b> 움직여야 한다: TRANSFER_OUT·TRANSFER_IN 로그 각 1건, 잔액은 이체액만큼만 변동.</p>
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransferIdempotencyTest {

    @Autowired AccountTransferService accountTransferService;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "transfer-idem@test.com";
    private static final BigDecimal INITIAL = new BigDecimal("1000000");
    private static final BigDecimal AMOUNT = new BigDecimal("70000");
    private static final int THREADS = 100;

    private Account from;
    private Account to;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        Member member = memberRepository.saveAndFlush(new Member(EMAIL, "멱등테스터", "password"));
        from = accountRepository.saveAndFlush(new Account(member, TestAccountNumbers.next(), INITIAL));
        to = accountRepository.saveAndFlush(new Account(member, TestAccountNumbers.next(), BigDecimal.ZERO));
        login();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, Collections.emptyList()));
    }

    private TransferRequest request(BigDecimal amount) {
        return new TransferRequest(from.getId(), to.getAccountNumber(), amount);
    }

    @Test
    @DisplayName("같은 키로 순차 재시도 → 두 번째는 저장된 응답 재생, 이체는 1회")
    void sequentialRetry_replaysStoredResponse() {
        String key = UUID.randomUUID().toString();

        TransferResponse first = accountTransferService.transfer(request(AMOUNT), key);
        TransferResponse second = accountTransferService.transfer(request(AMOUNT), key);

        assertThat(second).isEqualTo(first);
        assertMovedOnce();
    }

    @Test
    @DisplayName("같은 키로 금액만 바꿔 보내면 422 (IDEMPOTENCY_KEY_MISMATCH), 두 번째 이체는 없음")
    void sameKeyDifferentAmount_rejected() {
        String key = UUID.randomUUID().toString();
        accountTransferService.transfer(request(AMOUNT), key);

        assertThatThrownBy(() -> accountTransferService.transfer(request(new BigDecimal("1")), key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISMATCH);

        assertMovedOnce();
    }

    @Test
    @DisplayName("금액 표기만 다른 같은 요청(70000 vs 70000.00)은 같은 요청으로 본다")
    void sameAmountDifferentScale_isSameRequest() {
        String key = UUID.randomUUID().toString();
        TransferResponse first = accountTransferService.transfer(request(AMOUNT), key);
        TransferResponse second = accountTransferService.transfer(request(new BigDecimal("70000.00")), key);

        assertThat(second).isEqualTo(first);
        assertMovedOnce();
    }

    @Test
    @DisplayName("실패한 요청(잔액 부족)의 키는 해제된다 → 같은 키로 정상 재시도 가능")
    void failedRequest_releasesKey() {
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> accountTransferService.transfer(request(new BigDecimal("99999999")), key))
                .isInstanceOf(BusinessException.class);
        // 내용이 다르면 422가 나야 정상이지만, 실패한 키는 지워졌으므로 새 요청처럼 처리된다
        accountTransferService.transfer(request(AMOUNT), key);

        assertMovedOnce();
    }

    @Test
    @DisplayName("같은 멱등키 100개 재시도 동시 도착 → 이체는 정확히 1회")
    void concurrentRetry_sameKey_movesMoneyOnce() throws InterruptedException {
        String sharedKey = UUID.randomUUID().toString();

        AtomicInteger success = new AtomicInteger();
        AtomicInteger inProgress = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        AtomicReference<String> firstOther = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                login(); // SecurityContext는 스레드 로컬
                ready.countDown();
                try {
                    start.await();
                    accountTransferService.transfer(request(AMOUNT), sharedKey);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS) {
                        inProgress.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                        firstOther.compareAndSet(null, e.toString());
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                    firstOther.compareAndSet(null, e.toString());
                } finally {
                    SecurityContextHolder.clearContext();
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        pool.shutdown();

        System.out.println();
        System.out.println("=== 이체 멱등: 같은 키 " + THREADS + "개 동시 재시도 ===");
        System.out.printf("%-14s %8d%n", "성공(응답)", success.get());
        System.out.printf("%-14s %8d%n", "처리 중(409)", inProgress.get());
        System.out.printf("%-14s %8d%n", "기타 예외", other.get());
        System.out.printf("%-14s %8d%n", "출금 로그", count(TransactionType.TRANSFER_OUT));
        System.out.printf("%-14s %8d%n", "입금 로그", count(TransactionType.TRANSFER_IN));
        if (firstOther.get() != null) System.out.println("첫 기타 예외: " + firstOther.get());
        System.out.println();

        assertThat(success.get()).isGreaterThanOrEqualTo(1);
        assertThat(success.get() + inProgress.get()).isEqualTo(THREADS);
        assertThat(other.get()).isZero();
        assertMovedOnce();
    }

    private long count(TransactionType type) {
        return transactionLogRepository.findAll().stream().filter(t -> t.getType() == type).count();
    }

    private void assertMovedOnce() {
        assertThat(count(TransactionType.TRANSFER_OUT)).isEqualTo(1);
        assertThat(count(TransactionType.TRANSFER_IN)).isEqualTo(1);
        assertThat(accountRepository.findById(from.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(INITIAL.subtract(AMOUNT));
        assertThat(accountRepository.findById(to.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(AMOUNT);
    }
}
