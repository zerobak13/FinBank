package com.finbank.backend.loan.batch;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanApplication;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanApplicationRepository;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import com.finbank.backend.support.TestAccountNumbers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청크 사이즈별 배치 처리시간 비교 — D단계의 숫자.
 *
 * <p>청크 크기는 "한 트랜잭션에 몇 건을 묶을 것인가"다. 크게 잡으면 커밋 횟수가 줄어 빨라지지만,
 * 실패 시 되돌아가는 범위가 커지고 메모리에 올려두는 엔티티도 늘어난다.
 * <b>정답이 아니라 트레이드오프이므로 재보고 정한다.</b></p>
 *
 * <p>측정마다 데이터를 새로 만든다 — 앞선 실행이 회차를 이미 OVERDUE로 바꿔놓으면
 * 다음 측정은 0건을 처리하고 "빠르다"고 나온다.</p>
 */
@SpringBootTest
@Tag("perf")
class BatchChunkSizePerformanceTest {

    @Autowired JobLauncher jobLauncher;
    @Autowired Job overdueDetectionJob;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LoanProductRepository loanProductRepository;
    @Autowired LoanApplicationRepository loanApplicationRepository;
    @Autowired LoanAccountRepository loanAccountRepository;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "chunk-perf@test.com";
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 7, 1);
    private static final int ROWS = 50_000;
    private static final int[] CHUNK_SIZES = {100, 500, 1000};

    private Long loanId;

    @BeforeEach
    void setUp() {
        cleaner.clean();
    }

    @Test
    @DisplayName("청크 100 / 500 / 1000 — 5만 회차 연체 감지 처리시간 비교")
    void compareChunkSizes() throws Exception {
        Map<Integer, Long> elapsedByChunk = new LinkedHashMap<>();

        for (int chunkSize : CHUNK_SIZES) {
            cleaner.clean();
            prepareLoanWithSchedules();

            long start = System.currentTimeMillis();
            JobExecution execution = runDetection(chunkSize);
            long elapsed = System.currentTimeMillis() - start;

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(overdueCount()).isEqualTo(ROWS);   // 어떤 청크 크기에서도 전량 처리
            elapsedByChunk.put(chunkSize, elapsed);
        }

        printTable(elapsedByChunk);
    }

    private JobExecution runDetection(int chunkSize) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("baseDate", BASE_DATE.toString())
                .addLong("chunkSize", (long) chunkSize)
                .addString("runId", UUID.randomUUID().toString())
                .toJobParameters();
        return jobLauncher.run(overdueDetectionJob, params);
    }

    private int overdueCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repayment_schedules WHERE status = 'OVERDUE'", Integer.class);
        return count == null ? 0 : count;
    }

    private void printTable(Map<Integer, Long> elapsedByChunk) {
        System.out.println();
        System.out.println("=== 청크 사이즈별 처리시간 (연체 감지 " + String.format("%,d", ROWS) + "건) ===");
        System.out.printf("%-12s %12s %14s %14s%n", "청크 크기", "처리시간(ms)", "커밋 횟수", "건당(µs)");
        elapsedByChunk.forEach((chunk, ms) -> System.out.printf("%-12d %,12d %,14d %,14d%n",
                chunk, ms, (long) Math.ceil(ROWS / (double) chunk), ms * 1000 / ROWS));
        System.out.println();
    }

    /** 대출 1건 + 납기 지난 회차 5만 건 (회차 번호는 1..N, 납기일은 기준일 이전으로 분산) */
    private void prepareLoanWithSchedules() {
        Member member = memberRepository.saveAndFlush(new Member(EMAIL, "청크테스터", "password"));
        Account account = accountRepository.saveAndFlush(
                new Account(member, TestAccountNumbers.next(), new BigDecimal("1000000")));
        LoanProduct product = loanProductRepository.findByStatus(ProductStatus.ON_SALE).get(0);

        LoanApplication application = new LoanApplication(member, product, new BigDecimal("12000000"), 12);
        application.approve();
        application.markExecuted();
        loanApplicationRepository.saveAndFlush(application);

        LoanAccount loan = loanAccountRepository.saveAndFlush(
                LoanAccount.execute(application, account, BASE_DATE.minusYears(1)));
        loanId = loan.getId();

        // 5만 건을 JPA로 넣으면 준비에만 수 분이 걸린다. 측정 대상은 배치이므로 적재는 DB에서 끝낸다.
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET SESSION cte_max_recursion_depth = " + (ROWS * 2));
                st.executeUpdate("""
                        INSERT INTO repayment_schedules
                            (loan_account_id, installment_no, due_date,
                             principal_amount, interest_amount, total_amount, status)
                        WITH RECURSIVE seq AS (
                            SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < %d
                        )
                        SELECT %d, n, DATE_SUB('%s', INTERVAL (n %% 300 + 1) DAY),
                               1000000, 50000, 1050000, 'PENDING'
                          FROM seq
                        """.formatted(ROWS, loanId, BASE_DATE));
                st.execute("ANALYZE TABLE repayment_schedules");
            }
            return null;
        });
    }
}
