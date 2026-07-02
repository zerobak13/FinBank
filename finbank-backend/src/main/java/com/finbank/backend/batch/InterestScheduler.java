package com.finbank.backend.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 이자 정산 스케줄러.
 *
 * 매일 자정(00:00)에 interestSettlementJob을 실행한다.
 * JobParameters에 실행 날짜를 포함해 같은 날 중복 실행을 Spring Batch가 방지한다.
 * (동일 파라미터로 이미 COMPLETED된 Job은 재실행하지 않음)
 */
@Component
public class InterestScheduler {

    private static final Logger log = LoggerFactory.getLogger(InterestScheduler.class);

    private final JobLauncher jobLauncher;
    private final Job interestSettlementJob;

    public InterestScheduler(JobLauncher jobLauncher, Job interestSettlementJob) {
        this.jobLauncher = jobLauncher;
        this.interestSettlementJob = interestSettlementJob;
    }

    @Scheduled(cron = "0 0 0 * * *") // 매일 자정
    public void runInterestSettlement() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("settlementDate", LocalDate.now().toString())
                    .toJobParameters();

            log.info("[InterestScheduler] 이자 정산 배치 시작 - date={}", LocalDate.now());
            jobLauncher.run(interestSettlementJob, params);
            log.info("[InterestScheduler] 이자 정산 배치 완료");

        } catch (Exception e) {
            log.error("[InterestScheduler] 이자 정산 배치 실패", e);
        }
    }
}
