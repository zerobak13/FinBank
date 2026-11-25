package com.finbank.backend.loan.batch;

import com.finbank.backend.loan.domain.LoanTransaction;
import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.domain.calculator.OverdueInterestCalculator;
import com.finbank.backend.loan.repository.LoanAccountRepository;
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
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;

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
 * 같은 날 두 번 돌려도 두 번째는 대상이 0건이다. 누적 자체도 같은 조건을 건 조건부 UPDATE라
 * 상환과 겹쳐도 닫힌 이력을 다시 열지 않는다({@link OverdueInterestAccrualProcessor}).</p>
 */
@Configuration
public class OverdueInterestAccrualJobConfig {

    private static final Logger log = LoggerFactory.getLogger(OverdueInterestAccrualJobConfig.class);

    public static final String JOB_NAME = "overdueInterestAccrualJob";
    public static final String STEP_NAME = "overdueInterestAccrualStep";

    private final OverdueHistoryRepository overdueHistoryRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanTransactionRepository loanTransactionRepository;
    private final OverdueInterestCalculator overdueInterestCalculator;

    public OverdueInterestAccrualJobConfig(OverdueHistoryRepository overdueHistoryRepository,
                                           LoanAccountRepository loanAccountRepository,
                                           LoanTransactionRepository loanTransactionRepository,
                                           OverdueInterestCalculator overdueInterestCalculator) {
        this.overdueHistoryRepository = overdueHistoryRepository;
        this.loanAccountRepository = loanAccountRepository;
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
                .processor(new OverdueInterestAccrualProcessor(
                        overdueHistoryRepository, loanAccountRepository, overdueInterestCalculator, date))
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
