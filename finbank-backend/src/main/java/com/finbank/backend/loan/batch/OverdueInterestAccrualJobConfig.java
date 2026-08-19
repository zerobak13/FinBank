package com.finbank.backend.loan.batch;

import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanTransaction;
import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.domain.calculator.OverdueInterestCalculator;
import com.finbank.backend.loan.repository.LoanTransactionRepository;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 연체이자 가산 배치 — 매일 밤, 진행 중인 연체에 하루치 이자를 누적한다.
 *
 * <pre>
 * 연체이자 = 연체 원리금 × min(약정금리 + 연체가산율, 법정최고 20%) × 경과일수 / 365
 * </pre>
 *
 * <p><b>경과일수의 기산점</b>: 이전에 가산한 적이 있으면 {@code lastAccruedAt}부터,
 * 없으면 {@code overdueStart}(납기일)부터 계산한다. 이렇게 하면 구간이 정확히 이어져
 * <b>겹치지도 비지도 않는다</b>. 배치가 이틀 밀렸다면 다음 실행에서 2일치가 한 번에 가산된다.</p>
 *
 * <p><b>멱등</b>: Reader가 {@code lastAccruedAt < 기준일}인 이력만 읽으므로
 * 같은 날 두 번 돌려도 두 번째는 대상이 0건이다. C1에서 이 컬럼을 미리 깔아둔 이유가 여기 있다.</p>
 */
@Configuration
public class OverdueInterestAccrualJobConfig {

    private static final Logger log = LoggerFactory.getLogger(OverdueInterestAccrualJobConfig.class);

    public static final String JOB_NAME = "overdueInterestAccrualJob";
    public static final String STEP_NAME = "overdueInterestAccrualStep";

    private final OverdueHistoryRepository overdueHistoryRepository;
    private final LoanTransactionRepository loanTransactionRepository;
    private final OverdueInterestCalculator overdueInterestCalculator;

    public OverdueInterestAccrualJobConfig(OverdueHistoryRepository overdueHistoryRepository,
                                           LoanTransactionRepository loanTransactionRepository,
                                           OverdueInterestCalculator overdueInterestCalculator) {
        this.overdueHistoryRepository = overdueHistoryRepository;
        this.loanTransactionRepository = loanTransactionRepository;
        this.overdueInterestCalculator = overdueInterestCalculator;
    }

    @Bean
    public Job overdueInterestAccrualJob(JobRepository jobRepository, Step overdueInterestAccrualStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(overdueInterestAccrualStep)
                .build();
    }

    @Bean
    @JobScope
    public Step overdueInterestAccrualStep(JobRepository jobRepository,
                                           PlatformTransactionManager transactionManager,
                                           OverdueHistoryReader overdueHistoryReader,
                                           @Value("#{jobParameters['chunkSize'] ?: 100}") int chunkSize,
                                           @Value("#{jobParameters['baseDate']}") String baseDate) {
        LocalDate date = parseOrToday(baseDate);
        return new StepBuilder(STEP_NAME, jobRepository)
                .<OverdueHistory, LoanTransaction>chunk(chunkSize, transactionManager)
                .reader(overdueHistoryReader)
                .processor(accrualProcessor(date))
                .writer(accrualWriter())
                .build();
    }

    @Bean
    @StepScope
    public OverdueHistoryReader overdueHistoryReader(
            @Value("#{jobParameters['baseDate']}") String baseDate,
            @Value("#{jobParameters['chunkSize'] ?: 100}") int chunkSize) {
        return new OverdueHistoryReader(overdueHistoryRepository, parseOrToday(baseDate), chunkSize);
    }

    /**
     * 이자를 계산해 이력에 누적하고, 원장에 남길 거래를 만든다.
     *
     * <p>가산액이 0원이면(연체 첫날이거나 금액이 아주 작을 때) 원장을 남기지 않는다 —
     * {@code null}을 반환하면 Writer로 넘어가지 않는다. 다만 {@code lastAccruedAt}은 갱신되므로
     * 다음 실행에서 같은 구간을 다시 계산하지 않는다.</p>
     */
    private ItemProcessor<OverdueHistory, LoanTransaction> accrualProcessor(LocalDate baseDate) {
        return history -> {
            LocalDate from = (history.getLastAccruedAt() != null)
                    ? history.getLastAccruedAt()
                    : history.getOverdueStart();
            long days = ChronoUnit.DAYS.between(from, baseDate);
            if (days <= 0) {
                return null;   // 가산할 구간이 없다
            }

            LoanAccount loan = history.getLoanAccount();
            BigDecimal penalty = overdueInterestCalculator.calculate(
                    history.getOverdueAmount(),
                    loan.getInterestRate(),
                    loan.getOverdueExtraRate(),
                    days);

            history.accrue(penalty, baseDate);   // 0원이어도 기산점은 전진시킨다

            return penalty.signum() == 0 ? null : LoanTransaction.overdueInterest(loan, penalty);
        };
    }

    /** 원장은 append-only — 연체이자도 거래로 남는다 (잔액은 변하지 않는다) */
    private ItemWriter<LoanTransaction> accrualWriter() {
        return transactions -> {
            loanTransactionRepository.saveAll(transactions.getItems());
            log.info("[연체이자 가산] {}건 기록", transactions.size());
        };
    }

    private LocalDate parseOrToday(String baseDate) {
        return (baseDate == null) ? LocalDate.now() : LocalDate.parse(baseDate);
    }
}
