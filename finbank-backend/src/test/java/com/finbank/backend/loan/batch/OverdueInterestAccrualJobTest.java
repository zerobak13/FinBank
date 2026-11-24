package com.finbank.backend.loan.batch;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanApplication;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.LoanTransaction;
import com.finbank.backend.loan.domain.LoanTransactionType;
import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.domain.calculator.OverdueInterestCalculator;
import com.finbank.backend.loan.dto.RepaymentResponse;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.loan.repository.LoanTransactionRepository;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.loan.service.LoanRepaymentService;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import com.finbank.backend.support.LoanApplicationFixture;
import com.finbank.backend.support.OverdueFixture;
import com.finbank.backend.support.TestAccountNumbers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
 * 연체이자 가산 배치 통합 테스트.
 *
 * <p>핵심 검증: ① 계산기와 같은 금액이 누적되는가 ② <b>같은 날 두 번 돌려도 두 번 붙지 않는가</b>
 * ③ 배치가 며칠 밀렸다 돌아도 <b>구간이 겹치거나 비지 않는가</b>.</p>
 */
@SpringBootTest
class OverdueInterestAccrualJobTest {

    @Autowired JobLauncher jobLauncher;
    @Autowired Job overdueInterestAccrualJob;
    @Autowired MemberRepository memberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LoanProductRepository loanProductRepository;
    @Autowired LoanApplicationFixture applicationFixture;
    @Autowired LoanAccountRepository loanAccountRepository;
    @Autowired RepaymentScheduleRepository repaymentScheduleRepository;
    @Autowired OverdueHistoryRepository overdueHistoryRepository;
    @Autowired LoanTransactionRepository loanTransactionRepository;
    @Autowired LoanRepaymentService loanRepaymentService;
    @Autowired DatabaseCleaner cleaner;
    @Autowired OverdueFixture overdueFixture;
    @Autowired PlatformTransactionManager transactionManager;

    private static final String EMAIL = "accrual@test.com";
    private static final LocalDate OVERDUE_START = LocalDate.of(2026, 7, 1);
    private static final BigDecimal INSTALLMENT_TOTAL = new BigDecimal("1050000");

    private final OverdueInterestCalculator calculator = new OverdueInterestCalculator();

    private LoanAccount loan;
    private OverdueHistory history;

    @BeforeEach
    void setUp() {
        cleaner.clean();

        Member member = memberRepository.saveAndFlush(new Member(EMAIL, "가산테스터", "password"));
        Account account = accountRepository.saveAndFlush(
                new Account(member, TestAccountNumbers.next(), new BigDecimal("2000000")));
        LoanProduct product = loanProductRepository.findByStatus(ProductStatus.ON_SALE).get(0);

        LoanApplication application = applicationFixture.executed(member, product, account, new BigDecimal("12000000"), 12);

        loan = loanAccountRepository.saveAndFlush(
                LoanAccount.execute(application, account, OVERDUE_START.minusMonths(6)));
        overdueFixture.markLoanOverdue(loan.getId());

        RepaymentSchedule schedule = repaymentScheduleRepository.saveAndFlush(
                RepaymentSchedule.of(loan, 1, OVERDUE_START,
                        new BigDecimal("1000000"), new BigDecimal("50000")));
        overdueFixture.markScheduleOverdue(schedule.getId());

        history = overdueHistoryRepository.saveAndFlush(
                OverdueHistory.open(schedule, OVERDUE_START));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, Collections.emptyList()));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private JobExecution runJob(LocalDate baseDate) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("baseDate", baseDate.toString())
                .addString("runId", UUID.randomUUID().toString())
                .toJobParameters();
        return jobLauncher.run(overdueInterestAccrualJob, params);
    }

    private OverdueHistory reload() {
        return overdueHistoryRepository.findById(history.getId()).orElseThrow();
    }

    private BigDecimal expectedPenalty(long days) {
        return calculator.calculate(INSTALLMENT_TOTAL,
                loan.getInterestRate(), loan.getOverdueExtraRate(), days);
    }

    private List<com.finbank.backend.loan.domain.LoanTransaction> penaltyLedger() {
        return loanTransactionRepository.findAll().stream()
                .filter(tx -> tx.getType() == LoanTransactionType.OVERDUE_INTEREST)
                .toList();
    }

    @Test
    @DisplayName("연체 10일 후 실행 → 계산기와 같은 금액이 누적되고 원장에 남는다")
    void accruesInterestForOverdueDays() throws Exception {
        JobExecution execution = runJob(OVERDUE_START.plusDays(10));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        OverdueHistory result = reload();
        assertThat(result.getPenaltyAccrued()).isEqualByComparingTo(expectedPenalty(10));
        assertThat(result.getLastAccruedAt()).isEqualTo(OVERDUE_START.plusDays(10));
        // 원장에는 연체이자 부분에만 적는다 (발생). 약정 이자 칸은 비워 둔다
        assertThat(penaltyLedger()).singleElement().satisfies(tx -> {
            assertThat(tx.getPenaltyPortion()).isEqualByComparingTo(expectedPenalty(10));
            assertThat(tx.getInterestPortion()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(tx.getAmount()).isEqualByComparingTo(tx.getPenaltyPortion());
        });
    }

    @Test
    @DisplayName("멱등: 같은 기준일로 두 번 돌려도 이자가 두 번 붙지 않는다")
    void doesNotAccrueTwiceForSameDate() throws Exception {
        LocalDate baseDate = OVERDUE_START.plusDays(10);

        runJob(baseDate);
        BigDecimal afterFirst = reload().getPenaltyAccrued();

        runJob(baseDate);
        BigDecimal afterSecond = reload().getPenaltyAccrued();

        assertThat(afterFirst).isEqualByComparingTo(expectedPenalty(10));
        assertThat(afterSecond).isEqualByComparingTo(afterFirst);   // 한 푼도 늘지 않는다
        assertThat(penaltyLedger()).hasSize(1);
    }

    @Test
    @DisplayName("이어서 실행: 구간이 겹치지도 비지도 않는다 (10일 + 5일 = 15일치)")
    void accruesContinuouslyWithoutGapOrOverlap() throws Exception {
        runJob(OVERDUE_START.plusDays(10));
        runJob(OVERDUE_START.plusDays(15));

        BigDecimal total = reload().getPenaltyAccrued();

        // 10일치 + (11~15일) 5일치 — 두 구간의 합
        assertThat(total).isEqualByComparingTo(expectedPenalty(10).add(expectedPenalty(5)));
        assertThat(penaltyLedger()).hasSize(2);
    }

    @Test
    @DisplayName("배치가 밀려도 다음 실행에서 밀린 일수만큼 한 번에 가산된다")
    void catchesUpAfterMissedRuns() throws Exception {
        // 3일치 배치를 놓치고 4일째에 한 번 실행
        runJob(OVERDUE_START.plusDays(4));

        assertThat(reload().getPenaltyAccrued()).isEqualByComparingTo(expectedPenalty(4));
    }

    @Test
    @DisplayName("해소된 연체는 더 이상 가산되지 않는다")
    void skipsResolvedHistory() throws Exception {
        history.resolve(OVERDUE_START.plusDays(3));
        overdueHistoryRepository.saveAndFlush(history);

        runJob(OVERDUE_START.plusDays(10));

        assertThat(reload().getPenaltyAccrued()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(penaltyLedger()).isEmpty();
    }

    @Test
    @DisplayName("읽어 둔 이력이 그 사이 상환으로 닫혔으면 가산하지 않고, 닫힌 이력을 다시 열지도 않는다")
    void skipsHistoryResolvedAfterRead() {
        LocalDate baseDate = OVERDUE_START.plusDays(10);
        OverdueInterestAccrualProcessor processor = new OverdueInterestAccrualProcessor(
                overdueHistoryRepository, loanAccountRepository, calculator, baseDate);
        TransactionTemplate batchTx = new TransactionTemplate(transactionManager);
        TransactionTemplate repaymentTx = new TransactionTemplate(transactionManager);
        repaymentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        LoanTransaction ledger = batchTx.execute(status -> {
            // 배치가 청크를 읽어 둔 시점 — 이력이 이 트랜잭션의 1차 캐시에 올라간다
            OverdueHistory read = overdueHistoryRepository
                    .findAccrualTargets(baseDate, 0L, PageRequest.of(0, 10)).get(0);
            // 그 사이 다른 트랜잭션에서 상환이 이력을 닫는다
            repaymentTx.executeWithoutResult(s -> overdueHistoryRepository.findById(read.getId())
                    .orElseThrow().resolve(OVERDUE_START.plusDays(9)));
            return processor.process(read);
        });

        assertThat(ledger).isNull();
        assertThat(reload().isResolved()).isTrue();                                   // 다시 열리지 않았다
        assertThat(reload().getPenaltyAccrued()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(penaltyLedger()).isEmpty();
    }

    @Test
    @DisplayName("가산과 상환이 같은 순간에 겹쳐도 데드락 없이 둘 다 끝나고, 상환은 방금 가산된 연체이자까지 걷는다")
    void accrualAndRepaymentAtTheSameTime_noDeadlock() throws Exception {
        LocalDate baseDate = OVERDUE_START.plusDays(10);
        OverdueInterestAccrualProcessor processor = new OverdueInterestAccrualProcessor(
                overdueHistoryRepository, loanAccountRepository, calculator, baseDate);
        TransactionTemplate batchTx = new TransactionTemplate(transactionManager);
        ExecutorService repayer = Executors.newSingleThreadExecutor();
        try {
            Future<RepaymentResponse> repayment = batchTx.execute(status -> {
                OverdueHistory read = overdueHistoryRepository
                        .findAccrualTargets(baseDate, 0L, PageRequest.of(0, 10)).get(0);
                LoanTransaction ledger = processor.process(read);   // 대출 → 이력 순서로 잠근다

                // 이 순간 상환이 들어온다 — 계좌 → 대출 → 회차 → 이력 순서로 잠그려 한다
                Future<RepaymentResponse> started = repayer.submit(() -> repayAs(EMAIL, loan.getId(), 1));
                pause();   // 상환이 락 대기에 들어갈 시간

                // Writer가 하는 INSERT — 원장의 대출 FK를 확인하느라 대출 행의 공유 락이 필요하다.
                // 배치가 이력만 잠갔다면 여기서 대출을 쥔 상환을 기다리고, 상환은 이력을 기다려 데드락이 된다
                loanTransactionRepository.save(ledger);
                return started;
            });

            RepaymentResponse res = repayment.get(10, TimeUnit.SECONDS);
            assertThat(res.penaltyPortion()).isEqualByComparingTo(expectedPenalty(10));
        } finally {
            repayer.shutdownNow();
        }
        assertThat(reload().isResolved()).isTrue();
        assertThat(penaltyLedger()).hasSize(1);
    }

    /** 다른 스레드(= 다른 요청)에서 회원으로 로그인해 회차를 상환한다 */
    private RepaymentResponse repayAs(String email, Long loanId, int installmentNo) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList()));
        try {
            return loanRepaymentService.repay(loanId, installmentNo, UUID.randomUUID().toString());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("연체 회차를 상환하면 쌓인 연체이자가 걷히고, 이력이 해소돼 가산이 멈춘다 (상환 ↔ 배치 연결)")
    void stopsAccruingAfterOverdueInstallmentIsRepaid() throws Exception {
        runJob(OVERDUE_START.plusDays(5));
        BigDecimal beforeRepayment = reload().getPenaltyAccrued();
        assertThat(beforeRepayment).isEqualByComparingTo(expectedPenalty(5));

        // 연체된 1회차를 납부 → 배치가 쌓은 연체이자가 그대로 걷히고, 이력이 닫혀야 한다
        RepaymentResponse res = loanRepaymentService.repay(loan.getId(), 1, UUID.randomUUID().toString());
        assertThat(res.penaltyPortion()).isEqualByComparingTo(beforeRepayment);
        assertThat(reload().isResolved()).isTrue();

        // 이후 배치는 이 이력을 건드리지 않는다
        runJob(OVERDUE_START.plusDays(20));
        assertThat(reload().getPenaltyAccrued()).isEqualByComparingTo(beforeRepayment);
    }

    @Test
    @DisplayName("연체 당일 실행 → 경과일수 0이므로 가산도 원장도 없다")
    void accruesNothingOnDayZero() throws Exception {
        JobExecution execution = runJob(OVERDUE_START);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(reload().getPenaltyAccrued()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(penaltyLedger()).isEmpty();
    }
}
