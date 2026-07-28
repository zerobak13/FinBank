package com.finbank.backend.loan.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;

/**
 * 배치 실행 트리거.
 *
 * <p><b>한계를 먼저 적는다</b>: {@code @Scheduled}는 인스턴스마다 독립적으로 돈다.
 * 서버를 2대로 늘리는 순간 같은 배치가 동시에 두 번 실행된다. 지금 구조에서 안전한 이유는
 * 단일 인스턴스 배포이기 때문이고, 다중화한다면 분산 락(ShedLock 등)이나 외부 스케줄러
 * (쿠버네티스 CronJob, Airflow)로 실행 주체를 하나로 만들어야 한다.
 * 지금 도입하지 않는 이유는 다중 인스턴스를 증명할 환경이 없어서다 — 증명 못 하는 기술은 넣지 않는다.</p>
 *
 * <p>다만 <b>중복 실행이 사고로 이어지지는 않는다</b>. 연체 감지는 status 조건으로,
 * 연체이자 가산은 lastAccruedAt으로 각각 멱등이 확보되어 있다.
 * 분산 락은 "중복을 막는 장치"가 아니라 "낭비를 줄이는 장치"에 가깝다.</p>
 */
@Configuration
@EnableScheduling
public class OverdueBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(OverdueBatchScheduler.class);

    private final JobLauncher jobLauncher;
    private final Job overdueDetectionJob;

    public OverdueBatchScheduler(JobLauncher jobLauncher, Job overdueDetectionJob) {
        this.jobLauncher = jobLauncher;
        this.overdueDetectionJob = overdueDetectionJob;
    }

    /** 매일 02:00 — 당일 납기까지 반영된 시점에 돌린다 */
    @Scheduled(cron = "0 0 2 * * *")
    public void runOverdueDetection() {
        try {
            // 같은 잡 파라미터로는 재실행이 거부되므로 기준일을 파라미터로 넘긴다.
            // 날짜가 파라미터이므로 "어제 날짜로 다시 돌리기"도 자연스럽게 가능하다.
            JobParameters params = new JobParametersBuilder()
                    .addString("baseDate", LocalDate.now().toString())
                    .toJobParameters();
            jobLauncher.run(overdueDetectionJob, params);
        } catch (Exception e) {
            // 배치 실패가 애플리케이션을 죽이면 안 된다. 기록하고 다음 실행에 맡긴다(멱등하므로 안전).
            log.error("[연체 감지] 배치 실행 실패", e);
        }
    }
}
