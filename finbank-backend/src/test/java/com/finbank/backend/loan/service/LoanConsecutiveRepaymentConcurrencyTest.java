package com.finbank.backend.loan.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.common.idempotency.IdempotencyStore;
import com.finbank.backend.common.idempotency.RequestHash;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.domain.ScheduleStatus;
import com.finbank.backend.loan.dto.RepaymentResponse;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import com.finbank.backend.support.PastLoanFixture;
import com.finbank.backend.support.TestAccountNumbers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 대출의 1회차·2회차 상환이 겹칠 때 — 2회차 상환이 방금 끝난 1회차를 보고 2회차를 갚는가.
 *
 * <p>상환은 첫 조회(소유자 확인)에서 스냅샷이 잡힌 뒤 계좌 락을 기다린다. 그 사이 1회차 상환이 커밋되면, 다음 회차를
 * 일반 조회로 고를 때 스냅샷이라 1회차를 아직 미납으로 보고 "다음 상환 회차는 1회차입니다"로 거절한다.
 * 잠금 조회는 최신 커밋본을 읽는다. (돈은 어느 쪽이든 안전하다 — 이중 상환은 조건부 UPDATE가 막는다)</p>
 *
 * <p>1회차 상환은 처리기를 이 테스트의 트랜잭션에서 직접 부르고 키는 미리 선점한다 — 이유는 {@link LoanPrepaymentConcurrencyTest}.</p>
 */
@SpringBootTest
class LoanConsecutiveRepaymentConcurrencyTest {

    @Autowired MemberRepository memberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LoanProductRepository loanProductRepository;
    @Autowired LoanAccountRepository loanAccountRepository;
    @Autowired RepaymentScheduleRepository repaymentScheduleRepository;
    @Autowired PastLoanFixture pastLoanFixture;
    @Autowired LoanRepaymentService loanRepaymentService;
    @Autowired LoanRepaymentProcessor loanRepaymentProcessor;
    @Autowired IdempotencyStore idempotencyStore;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "repay-seq@test.com";
    private static final int TERM = 12;
    private static final BigDecimal PRINCIPAL = new BigDecimal("12000000");
    private static final BigDecimal INSTALLMENT_PRINCIPAL = new BigDecimal("1000000");
    private static final BigDecimal INSTALLMENT_INTEREST = new BigDecimal("50000");

    private Long memberId;
    private LoanAccount loan;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        Member member = memberRepository.saveAndFlush(new Member(EMAIL, "연속상환테스터", "password"));
        memberId = member.getId();
        Account account = accountRepository.saveAndFlush(
                new Account(member, TestAccountNumbers.next(), new BigDecimal("30000000")));
        LoanProduct product = loanProductRepository.findByStatus(ProductStatus.ON_SALE).get(0);
        loan = pastLoanFixture.executed(member, account, product, PRINCIPAL, TERM,
                LocalDate.now().minusDays(40), INSTALLMENT_INTEREST);
        login();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("1회차 상환이 끝나기를 기다린 2회차 상환은 2회차를 갚는다 (스냅샷의 '다음은 1회차'로 거절하지 않는다)")
    void secondInstallmentWaitingForFirst_paysSecond() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService payer = Executors.newSingleThreadExecutor();
        try {
            // 1회차 상환의 멱등키는 실제 흐름처럼 먼저 선점해 커밋해 둔다 (별도 트랜잭션)
            String firstKey = UUID.randomUUID().toString();
            idempotencyStore.claim(firstKey, memberId, RequestHash.of("loan-repay", memberId, loan.getId(), 1));

            Future<RepaymentResponse> second = tx.execute(status -> {
                // 1회차 상환 본체 — 이 트랜잭션에서 돌아서, 커밋 전까지 계좌·대출·회차 락을 쥐고 있다
                loanRepaymentProcessor.repay(loan.getId(), memberId, 1, firstKey);

                // 이 순간 2회차 상환이 들어온다 — 소유자 확인(첫 조회)에서 스냅샷이 잡히고 계좌 락을 기다린다
                Future<RepaymentResponse> started = payer.submit(() -> repayAsMember(2));
                pause();
                return started;
            });   // 여기서 1회차 상환이 커밋되고, 기다리던 2회차 상환이 이어서 진행한다

            RepaymentResponse res = second.get(10, TimeUnit.SECONDS);

            assertThat(res.installmentNo()).isEqualTo(2);
            assertThat(loanAccountRepository.findById(loan.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo(PRINCIPAL.subtract(INSTALLMENT_PRINCIPAL.multiply(BigDecimal.valueOf(2))));
            List<RepaymentSchedule> schedules = repaymentScheduleRepository.findByLoanAccountOrderByInstallmentNoAsc(loan);
            assertThat(schedules).extracting(RepaymentSchedule::getStatus).startsWith(
                    ScheduleStatus.PAID, ScheduleStatus.PAID, ScheduleStatus.PENDING);
        } finally {
            payer.shutdownNow();
        }
    }

    /** 다른 스레드(= 다른 요청)에서 회원으로 로그인해 회차 상환한다 */
    private RepaymentResponse repayAsMember(int installmentNo) {
        login();
        try {
            return loanRepaymentService.repay(loan.getId(), installmentNo, UUID.randomUUID().toString());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, Collections.emptyList()));
    }

    private static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
