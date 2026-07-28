package com.finbank.backend.loan.batch;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanApplication;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.LoanTransactionType;
import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.domain.calculator.OverdueInterestCalculator;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanApplicationRepository;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.loan.repository.LoanTransactionRepository;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.loan.service.LoanRepaymentService;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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
    @Autowired LoanApplicationRepository loanApplicationRepository;
    @Autowired LoanAccountRepository loanAccountRepository;
    @Autowired RepaymentScheduleRepository repaymentScheduleRepository;
    @Autowired OverdueHistoryRepository overdueHistoryRepository;
    @Autowired LoanTransactionRepository loanTransactionRepository;
    @Autowired LoanRepaymentService loanRepaymentService;
    @Autowired DatabaseCleaner cleaner;

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

        LoanApplication application = new LoanApplication(member, product, new BigDecimal("12000000"), 12);
        application.approve();
        application.markExecuted();
        loanApplicationRepository.saveAndFlush(application);

        loan = loanAccountRepository.saveAndFlush(
                LoanAccount.execute(application, account, OVERDUE_START.minusMonths(6)));
        loan.markOverdue();
        loanAccountRepository.saveAndFlush(loan);

        RepaymentSchedule schedule = repaymentScheduleRepository.saveAndFlush(
                RepaymentSchedule.of(loan, 1, OVERDUE_START,
                        new BigDecimal("1000000"), new BigDecimal("50000")));
        schedule.markOverdue();
        repaymentScheduleRepository.saveAndFlush(schedule);

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
        assertThat(penaltyLedger()).hasSize(1);
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
    @DisplayName("연체 회차를 상환하면 이력이 해소되고 가산이 멈춘다 (상환 ↔ 배치 연결)")
    void stopsAccruingAfterOverdueInstallmentIsRepaid() throws Exception {
        runJob(OVERDUE_START.plusDays(5));
        BigDecimal beforeRepayment = reload().getPenaltyAccrued();
        assertThat(beforeRepayment).isEqualByComparingTo(expectedPenalty(5));

        // 연체된 1회차를 납부 → 이력이 닫혀야 한다
        loanRepaymentService.repay(loan.getId(), 1, UUID.randomUUID().toString());
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
