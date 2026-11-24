package com.finbank.backend.loan.batch;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanAccountStatus;
import com.finbank.backend.loan.domain.LoanApplication;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.domain.ScheduleStatus;
import com.finbank.backend.loan.dto.RepaymentResponse;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import com.finbank.backend.loan.service.LoanRepaymentService;
import com.finbank.backend.member.domain.Member;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    @Autowired LoanApplicationFixture applicationFixture;
    @Autowired LoanAccountRepository loanAccountRepository;
    @Autowired RepaymentScheduleRepository repaymentScheduleRepository;
    @Autowired OverdueHistoryRepository overdueHistoryRepository;
    @Autowired DatabaseCleaner cleaner;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OverdueFixture overdueFixture;
    @Autowired LoanRepaymentService loanRepaymentService;

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
        return givenLoanWithSchedules(account, dueDates);
    }

    private LoanAccount givenLoanWithSchedules(Account account, LocalDate... dueDates) {
        LoanApplication application = applicationFixture.executed(
                member, product, account, new BigDecimal("12000000"), dueDates.length);

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

    /**
     * <b>잡 파라미터가 같으면 재실행이 거부된다</b>({@code JobInstanceAlreadyCompleteException}).
     * Spring Batch는 "같은 파라미터 = 같은 JobInstance"로 보고, 한 번 COMPLETED된 인스턴스는
     * 두 번 완료시키지 않는다 — 프레임워크가 제공하는 중복 실행 방지다.
     *
     * <p>이 방어는 배치 메타 테이블에 <b>영구히</b> 남기 때문에, 테스트를 다시 돌리기만 해도
     * 지난 실행과 충돌한다. 그래서 실행마다 유니크한 구분자를 파라미터에 넣는다
     * (운영에서 {@code JobParametersIncrementer}나 run.id를 쓰는 것과 같은 이유).</p>
     */
    private JobExecution runJob(LocalDate baseDate) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("baseDate", baseDate.toString())
                .addString("runId", UUID.randomUUID().toString())
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

        JobExecution execution = runJob(BASE_DATE);

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
    @DisplayName("읽어 둔 회차가 그 사이 상환됐으면 연체로 바꾸지 않고, 상환이 줄인 대출 잔액도 덮어쓰지 않는다")
    void skipsScheduleRepaidAfterRead() {
        LoanAccount loan = givenLoanWithSchedules(BASE_DATE.minusDays(5));
        OverdueDetectionProcessor processor =
                new OverdueDetectionProcessor(repaymentScheduleRepository, loanAccountRepository);
        TransactionTemplate batchTx = new TransactionTemplate(transactionManager);
        TransactionTemplate repaymentTx = new TransactionTemplate(transactionManager);
        repaymentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        OverdueHistory opened = batchTx.execute(status -> {
            // 배치가 청크를 읽어 둔 시점 — 회차·대출이 이 트랜잭션의 1차 캐시에 올라간다
            RepaymentSchedule read = repaymentScheduleRepository
                    .findOverdueCandidates(BASE_DATE, 0L, PageRequest.of(0, 10)).get(0);
            // 그 사이 다른 트랜잭션에서 그 회차의 상환이 끝난다 (회차 PAID + 대출 잔액 감소)
            repaymentTx.executeWithoutResult(s -> {
                repaymentScheduleRepository.markPaidIfPayable(read.getId(), LocalDateTime.now());
                loanAccountRepository.findById(loan.getId()).orElseThrow()
                        .repayPrincipal(read.getPrincipalAmount());
            });
            return processor.process(read);
        });

        assertThat(opened).isNull();
        RepaymentSchedule schedule = schedulesOf(loan).get(0);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.PAID);
        LoanAccount after = loanAccountRepository.findById(loan.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(LoanAccountStatus.ACTIVE);
        assertThat(after.getBalance())
                .isEqualByComparingTo(loan.getPrincipal().subtract(schedule.getPrincipalAmount()));
        assertThat(overdueHistoryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("감지와 상환이 같은 순간에 겹쳐도 데드락 없이 둘 다 끝난다 — 상환은 남은 연체 회차를 볼 때 자기 대출의 회차만 잠근다")
    void detectionAndRepaymentAtTheSameTime_noDeadlock() throws Exception {
        LoanAccount other = givenLoanWithSchedules(BASE_DATE.minusDays(5));   // 감지가 먼저 처리할 다른 대출
        Account repayAccount = accountRepository.saveAndFlush(
                new Account(member, TestAccountNumbers.next(), new BigDecimal("5000000")));
        LoanAccount repaying = givenLoanWithSchedules(repayAccount,
                BASE_DATE.minusDays(40), BASE_DATE.minusDays(10),             // 1회차는 이미 연체, 2회차는 오늘 연체가 될 차례
                BASE_DATE.plusDays(20), BASE_DATE.plusDays(50), BASE_DATE.plusDays(80), BASE_DATE.plusDays(110));
        RepaymentSchedule overdueFirst = schedulesOf(repaying).get(0);
        overdueFixture.markScheduleOverdue(overdueFirst.getId());
        overdueFixture.markLoanOverdue(repaying.getId());
        overdueFixture.openHistory(overdueFirst, overdueFirst.getDueDate(), BigDecimal.ZERO, null);

        OverdueDetectionProcessor processor =
                new OverdueDetectionProcessor(repaymentScheduleRepository, loanAccountRepository);
        TransactionTemplate batchTx = new TransactionTemplate(transactionManager);
        ExecutorService repayer = Executors.newSingleThreadExecutor();
        try {
            Future<RepaymentResponse> repayment = batchTx.execute(status -> {
                List<RepaymentSchedule> chunk = repaymentScheduleRepository
                        .findOverdueCandidates(BASE_DATE, 0L, PageRequest.of(0, 10));   // [다른 대출 1회차, 상환 중인 대출 2회차]
                OverdueHistory first = processor.process(chunk.get(0));               // 다른 대출의 회차를 연체로 — 아직 커밋 전

                // 이 순간 1회차 상환이 들어온다. 남은 연체 회차를 상태 인덱스로 훑으면 방금 바뀐 다른 대출의 회차에서 배치를 기다린다
                Future<RepaymentResponse> started = repayer.submit(() -> repayAs(repaying.getId(), 1));
                pause();   // 상환이 끝나거나 락 대기에 들어갈 시간

                // 배치는 상환 중인 대출을 잠그려 한다 — 상환이 배치를 기다리고 있다면 여기서 데드락
                OverdueHistory second = processor.process(chunk.get(1));
                overdueHistoryRepository.saveAll(List.of(first, second));               // Writer
                return started;
            });

            RepaymentResponse res = repayment.get(10, TimeUnit.SECONDS);
            assertThat(res.installmentNo()).isEqualTo(1);
        } finally {
            repayer.shutdownNow();
        }

        // 어느 쪽이 먼저 끝났든 결과는 같다: 1회차 상환 완료, 2회차는 연체, 대출은 연체
        List<RepaymentSchedule> schedules = schedulesOf(repaying);
        assertThat(schedules.get(0).getStatus()).isEqualTo(ScheduleStatus.PAID);
        assertThat(schedules.get(1).getStatus()).isEqualTo(ScheduleStatus.OVERDUE);
        assertThat(loanAccountRepository.findById(repaying.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanAccountStatus.OVERDUE);
        assertThat(schedulesOf(other).get(0).getStatus()).isEqualTo(ScheduleStatus.OVERDUE);
        assertThat(overdueHistoryRepository.findByResolvedAtIsNull()).hasSize(2);   // 다른 대출 1회차 + 2회차
    }

    /** 다른 스레드(= 다른 요청)에서 회원으로 로그인해 회차를 상환한다 */
    private RepaymentResponse repayAs(Long loanId, int installmentNo) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, Collections.emptyList()));
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
    @DisplayName("멱등: 같은 기준일로 두 번 돌려도 연체이력이 늘지 않는다")
    void isIdempotentAcrossReruns() throws Exception {
        givenLoanWithSchedules(BASE_DATE.minusDays(10), BASE_DATE.minusDays(5));

        runJob(BASE_DATE);
        int afterFirst = overdueHistoryRepository.findByResolvedAtIsNull().size();

        runJob(BASE_DATE);
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

        JobExecution execution = runJob(BASE_DATE);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(overdueHistoryRepository.findByResolvedAtIsNull()).hasSize(count);
        assertThat(schedulesOf(loan))
                .allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(ScheduleStatus.OVERDUE));
    }

    @Test
    @DisplayName("연체 대상이 없으면 아무 일도 일어나지 않는다 (정상 종료)")
    void completesWithNothingToDo() throws Exception {
        givenLoanWithSchedules(BASE_DATE.plusDays(10), BASE_DATE.plusDays(40));

        JobExecution execution = runJob(BASE_DATE);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(overdueHistoryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("기준일을 과거로 주면 그날 기준으로 판정한다 (놓친 배치 재실행)")
    void respectsBaseDateParameter() throws Exception {
        LoanAccount loan = givenLoanWithSchedules(BASE_DATE.minusDays(10), BASE_DATE.minusDays(2));

        // 기준일을 5일 전으로 → 10일 전 회차만 연체
        runJob(BASE_DATE.minusDays(5));

        List<RepaymentSchedule> schedules = schedulesOf(loan);
        assertThat(schedules.get(0).getStatus()).isEqualTo(ScheduleStatus.OVERDUE);
        assertThat(schedules.get(1).getStatus()).isEqualTo(ScheduleStatus.PENDING);
    }
}
