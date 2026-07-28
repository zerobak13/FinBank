package com.finbank.backend.loan.batch;

import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
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

import java.time.LocalDate;

/**
 * 연체 감지 배치 — 매일 밤, 납기가 지났는데 아직 안 낸 회차를 찾아 연체로 전이한다.
 *
 * <pre>
 * PENDING & due_date &lt; 기준일  ──▶  회차 OVERDUE + 연체이력(open) + 대출 OVERDUE
 * </pre>
 *
 * <p><b>배치 멱등</b>: 읽는 조건이 {@code status = PENDING}이므로, 이미 전이된 회차는
 * 다음 실행에서 아예 조회되지 않는다. 하루에 두 번 돌려도 이력이 두 건 생기지 않는다.
 * "실행 횟수와 무관하게 결과가 같다"를 상태 조건 하나로 확보한 것이다.</p>
 *
 * <p><b>청크 단위 커밋</b>: 10만 건을 한 트랜잭션으로 묶으면 마지막 1건이 실패할 때
 * 앞의 99,999건이 되돌아간다. 청크로 끊으면 실패 지점까지는 커밋되어 있고,
 * 재실행 시 남은 것만 처리된다(위의 멱등 덕분에 안전하게 재실행할 수 있다).</p>
 */
@Configuration
public class OverdueDetectionJobConfig {

    private static final Logger log = LoggerFactory.getLogger(OverdueDetectionJobConfig.class);

    public static final String JOB_NAME = "overdueDetectionJob";
    public static final String STEP_NAME = "overdueDetectionStep";

    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final OverdueHistoryRepository overdueHistoryRepository;

    public OverdueDetectionJobConfig(RepaymentScheduleRepository repaymentScheduleRepository,
                                     OverdueHistoryRepository overdueHistoryRepository) {
        this.repaymentScheduleRepository = repaymentScheduleRepository;
        this.overdueHistoryRepository = overdueHistoryRepository;
    }

    @Bean
    public Job overdueDetectionJob(JobRepository jobRepository, Step overdueDetectionStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(overdueDetectionStep)
                .build();
    }

    @Bean
    public Step overdueDetectionStep(JobRepository jobRepository,
                                     PlatformTransactionManager transactionManager,
                                     OverdueScheduleReader overdueScheduleReader,
                                     @Value("${finbank.batch.chunk-size:100}") int chunkSize) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<RepaymentSchedule, OverdueHistory>chunk(chunkSize, transactionManager)
                .reader(overdueScheduleReader)
                .processor(overdueProcessor())
                .writer(overdueWriter())
                .build();
    }

    /**
     * 기준일은 잡 파라미터로 받는다. "오늘"을 코드가 직접 읽으면 테스트에서 시간을 조작할 수 없고,
     * 장애로 하루치 배치를 놓쳤을 때 어제 날짜로 재실행할 방법도 없어진다.
     *
     * <p><b>Reader의 조회 단위를 청크 크기와 같게 맞추는 것이 중요하다.</b>
     * 더 크게 잡으면 앞 청크의 트랜잭션에서 읽은 엔티티가 다음 청크에서는 준영속 상태가 되어,
     * 프로세서가 바꾼 값이 변경 감지로 반영되지 않는다(조용히 누락된다).</p>
     */
    @Bean
    @StepScope
    public OverdueScheduleReader overdueScheduleReader(
            @Value("#{jobParameters['baseDate']}") String baseDate,
            @Value("${finbank.batch.chunk-size:100}") int chunkSize) {
        LocalDate date = (baseDate == null) ? LocalDate.now() : LocalDate.parse(baseDate);
        return new OverdueScheduleReader(repaymentScheduleRepository, date, chunkSize);
    }

    /** 회차·대출 상태를 바꾸고 연체이력을 만든다 (상태 전이의 판단은 도메인이 한다) */
    private ItemProcessor<RepaymentSchedule, OverdueHistory> overdueProcessor() {
        return schedule -> {
            schedule.markOverdue();
            schedule.getLoanAccount().markOverdue();
            return OverdueHistory.open(schedule, schedule.getDueDate());
        };
    }

    /**
     * 이력만 저장한다. 회차·대출은 이미 영속 상태라 청크 트랜잭션이 커밋될 때
     * 변경 감지로 함께 반영된다 — save를 호출할 필요가 없다.
     */
    private ItemWriter<OverdueHistory> overdueWriter() {
        return histories -> {
            overdueHistoryRepository.saveAll(histories.getItems());
            log.info("[연체 감지] {}건 전이", histories.size());
        };
    }
}
