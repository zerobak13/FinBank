package com.finbank.backend.loan.batch;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanAccountStatus;
import com.finbank.backend.loan.domain.LoanApplication;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.domain.ScheduleStatus;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanApplicationRepository;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import com.finbank.backend.member.domain.Member;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연체 감지 배치 통합 테스트.
 *
 * <p>검증 관점: ① 납기 지난 회차만 정확히 전이되는가 ② <b>두 번 돌려도 결과가 같은가</b>(배치 멱등)
 * ③ 청크 경계를 넘는 건수에서도 <b>빠뜨리는 행이 없는가</b>.</p>
 *
 * <p>③이 이 배치의 핵심이다. 읽으면서 읽기 조건을 바꾸는 배치는 페이징 방식에 따라
 * 조용히 절반만 처리하고도 정상 종료한다.</p>
 */
@SpringBootTest
class OverdueDetectionJobTest {

    @Autowired JobLauncher jobLauncher;
    @Autowired Job overdueDetectionJob;
    @Autowired MemberRepository memberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LoanProductRepository loanProductRepository;
    @Autowired LoanApplicationRepository loanApplicationRepository;
    @Autowired LoanAccountRepository loanAccountRepository;
    @Autowired RepaymentScheduleRepository repaymentScheduleRepository;
    @Autowired OverdueHistoryRepository overdueHistoryRepository;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "batch@test.com";
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 7, 1);

    private Member member;
    private Account account;
    private LoanProduct product;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        member = memberRepository.saveAndFlush(new Member(EMAIL, "배치테스터", "password"));
        account = accountRepository.saveAndFlush(
                new Account(member, TestAccountNumbers.next(), new BigDecimal("1000000")));
        product = loanProductRepository.findByStatus(ProductStatus.ON_SALE).get(0);
    }

    /** 회차 납기일을 직접 지정해 대출 1건을 만든다 */
    private LoanAccount givenLoanWithSchedules(LocalDate... dueDates) {
        LoanApplication application = new LoanApplication(
                member, product, new BigDecimal("12000000"), dueDates.length);
        application.approve();
        application.markExecuted();
        loanApplicationRepository.saveAndFlush(application);

        LoanAccount loan = loanAccountRepository.saveAndFlush(
                LoanAccount.execute(application, account, BASE_DATE.minusMonths(6)));

        for (int i = 0; i < dueDates.length; i++) {
            repaymentScheduleRepository.save(RepaymentSchedule.of(
                    loan, i + 1, dueDates[i],
                    new BigDecimal("1000000"), new BigDecimal("50000")));
        }
        repaymentScheduleRepository.flush();
        return loan;
    }

    private JobExecution runJob(LocalDate baseDate, String runId) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("baseDate", baseDate.toString())
                .addString("runId", runId)     // 같은 파라미터로는 재실행이 거부되므로 구분자를 둔다
                .toJobParameters();
        return jobLauncher.run(overdueDetectionJob, params);
    }

    private List<RepaymentSchedule> schedulesOf(LoanAccount loan) {
        return repaymentScheduleRepository.findByLoanAccountOrderByInstallmentNoAsc(loan);
    }

    @Test
    @DisplayName("납기 지난 미납 회차만 OVERDUE로 전이되고 연체이력이 생긴다")
    void detectsOnlyPastDueSchedules() throws Exception {
        LoanAccount loan = givenLoanWithSchedules(
                BASE_DATE.minusDays(10),   // 연체 대상
                BASE_DATE.minusDays(1),    // 연체 대상
                BASE_DATE,                 // 오늘 납기 — 아직 연체 아님
                BASE_DATE.plusDays(30));   // 미도래

        JobExecution execution = runJob(BASE_DATE, "run-1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<RepaymentSchedule> schedules = schedulesOf(loan);
        assertThat(schedules.get(0).getStatus()).isEqualTo(ScheduleStatus.OVERDUE);
        assertThat(schedules.get(1).getStatus()).isEqualTo(ScheduleStatus.OVERDUE);
        assertThat(schedules.get(2).getStatus()).isEqualTo(ScheduleStatus.PENDING);
        assertThat(schedules.get(3).getStatus()).isEqualTo(ScheduleStatus.PENDING);

        // 연체이력 2건, 대출은 OVERDUE
        assertThat(overdueHistoryRepository.findByResolvedAtIsNull()).hasSize(2);
        assertThat(loanAccountRepository.findById(loan.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanAccountStatus.OVERDUE);
    }

    @Test
    @DisplayName("멱등: 같은 기준일로 두 번 돌려도 연체이력이 늘지 않는다")
    void isIdempotentAcrossReruns() throws Exception {
        givenLoanWithSchedules(BASE_DATE.minusDays(10), BASE_DATE.minusDays(5));

        runJob(BASE_DATE, "run-1");
        int afterFirst = overdueHistoryRepository.findByResolvedAtIsNull().size();

        runJob(BASE_DATE, "run-2");
        int afterSecond = overdueHistoryRepository.findByResolvedAtIsNull().size();

        assertThat(afterFirst).isEqualTo(2);
        assertThat(afterSecond).isEqualTo(2);   // 이미 OVERDUE인 회차는 조회 자체가 안 된다
    }

    @Test
    @DisplayName("청크 경계를 넘어도 한 건도 빠뜨리지 않는다 (커서 Reader 검증)")
    void processesEveryRowAcrossChunkBoundaries() throws Exception {
        // 청크 크기(기본 100)의 2.5배 — 페이지 방식이었다면 뒤쪽이 통째로 밀렸을 규모
        int count = 250;
        LocalDate[] dueDates = new LocalDate[count];
        for (int i = 0; i < count; i++) {
            dueDates[i] = BASE_DATE.minusDays(i + 1);
        }
        LoanAccount loan = givenLoanWithSchedules(dueDates);

        JobExecution execution = runJob(BASE_DATE, "run-1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(overdueHistoryRepository.findByResolvedAtIsNull()).hasSize(count);
        assertThat(schedulesOf(loan))
                .allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(ScheduleStatus.OVERDUE));
    }

    @Test
    @DisplayName("연체 대상이 없으면 아무 일도 일어나지 않는다 (정상 종료)")
    void completesWithNothingToDo() throws Exception {
        givenLoanWithSchedules(BASE_DATE.plusDays(10), BASE_DATE.plusDays(40));

        JobExecution execution = runJob(BASE_DATE, "run-1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(overdueHistoryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("기준일을 과거로 주면 그날 기준으로 판정한다 (놓친 배치 재실행)")
    void respectsBaseDateParameter() throws Exception {
        LoanAccount loan = givenLoanWithSchedules(BASE_DATE.minusDays(10), BASE_DATE.minusDays(2));

        // 기준일을 5일 전으로 → 10일 전 회차만 연체
        runJob(BASE_DATE.minusDays(5), "run-1");

        List<RepaymentSchedule> schedules = schedulesOf(loan);
        assertThat(schedules.get(0).getStatus()).isEqualTo(ScheduleStatus.OVERDUE);
        assertThat(schedules.get(1).getStatus()).isEqualTo(ScheduleStatus.PENDING);
    }
}
