package com.finbank.backend.loan.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanApplication;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.domain.calculator.AccruedInterestCalculator;
import com.finbank.backend.loan.dto.RepaymentResponse;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import com.finbank.backend.support.LoanApplicationFixture;
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
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전액 중도상환과 회차 상환이 같은 대출에 겹칠 때 — 경과이자의 기산일이 방금 끝난 상환을 반영하는가.
 *
 * <p>중도상환은 첫 조회(소유자 확인)에서 스냅샷이 잡힌 뒤 계좌 락을 기다린다. 그 사이 1회차 상환이 커밋되면,
 * "마지막 납부 회차"를 일반 조회로 읽을 때 스냅샷이라 그 상환을 못 보고 기산일을 실행일로 잡아 이자를 더 받는다.
 * 잠금 조회는 최신 커밋본을 읽는다.</p>
 */
@SpringBootTest
class LoanPrepaymentConcurrencyTest {

    @Autowired MemberRepository memberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LoanProductRepository loanProductRepository;
    @Autowired LoanApplicationFixture applicationFixture;
    @Autowired LoanAccountRepository loanAccountRepository;
    @Autowired RepaymentScheduleRepository repaymentScheduleRepository;
    @Autowired LoanRepaymentService loanRepaymentService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "prepay-race@test.com";
    private static final int TERM = 12;
    private static final BigDecimal PRINCIPAL = new BigDecimal("12000000");
    private static final BigDecimal INSTALLMENT_PRINCIPAL = new BigDecimal("1000000");
    private static final BigDecimal INSTALLMENT_INTEREST = new BigDecimal("50000");

    private LoanAccount loan;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        Member member = memberRepository.saveAndFlush(new Member(EMAIL, "중도상환테스터", "password"));
        Account account = accountRepository.saveAndFlush(
                new Account(member, TestAccountNumbers.next(), new BigDecimal("30000000")));
        LoanProduct product = loanProductRepository.findByStatus(ProductStatus.ON_SALE).get(0);

        // 40일 전에 실행한 12개월 대출 — 1회차 납기는 열흘쯤 전(아직 PENDING), 2회차부터는 앞으로
        LocalDate executedAt = LocalDate.now().minusDays(40);
        LoanApplication application = applicationFixture.executed(member, product, account, PRINCIPAL, TERM);
        loan = loanAccountRepository.saveAndFlush(LoanAccount.execute(application, account, executedAt));
        for (int i = 1; i <= TERM; i++) {
            repaymentScheduleRepository.save(RepaymentSchedule.of(
                    loan, i, executedAt.plusMonths(i), INSTALLMENT_PRINCIPAL, INSTALLMENT_INTEREST));
        }
        repaymentScheduleRepository.flush();
        login();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("1회차 상환이 끝나기를 기다린 중도상환은 경과이자를 1회차 납기일부터 계산한다 (실행일부터가 아니다)")
    void prepayWaitingForRepayment_countsInterestFromLatestPaidInstallment() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService prepayer = Executors.newSingleThreadExecutor();
        try {
            Future<RepaymentResponse> prepayment = tx.execute(status -> {
                // 1회차 상환 — 이 트랜잭션에 합류해서, 커밋 전까지 계좌·대출 락을 쥐고 있다
                loanRepaymentService.repay(loan.getId(), 1, UUID.randomUUID().toString());

                // 이 순간 중도상환이 들어온다 — 소유자 확인(첫 조회)에서 스냅샷이 잡히고 계좌 락을 기다린다
                Future<RepaymentResponse> started = prepayer.submit(this::prepayAsMember);
                pause();
                return started;
            });   // 여기서 1회차 상환이 커밋되고, 기다리던 중도상환이 이어서 진행한다

            RepaymentResponse res = prepayment.get(10, TimeUnit.SECONDS);

            BigDecimal remaining = PRINCIPAL.subtract(INSTALLMENT_PRINCIPAL);
            long sinceFirstDue = ChronoUnit.DAYS.between(loan.getExecutedAt().plusMonths(1), LocalDate.now());
            BigDecimal expected = new AccruedInterestCalculator()
                    .calculate(remaining, loan.getInterestRate(), sinceFirstDue);

            assertThat(res.principalPortion()).isEqualByComparingTo(remaining);
            assertThat(res.interestPortion()).isEqualByComparingTo(expected);   // 실행일(40일 전)부터 세면 더 크다
        } finally {
            prepayer.shutdownNow();
        }
    }

    /** 다른 스레드(= 다른 요청)에서 회원으로 로그인해 전액 중도상환한다 */
    private RepaymentResponse prepayAsMember() {
        login();
        try {
            return loanRepaymentService.prepayAll(loan.getId(), UUID.randomUUID().toString());
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
