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
 * 연체 정산 배치 트리거 — 매일 02:00, <b>감지 → 가산</b> 순서로 실행한다.
 *
 * <p>순서가 중요하다. 오늘 새로 연체된 회차는 감지 배치가 이력을 만들어야 가산 대상이 된다.
 * 반대로 돌리면 오늘 연체된 건은 하루를 건너뛰고 내일부터 이자가 붙는다.</p>
 *
 * <p><b>한계를 먼저 적는다</b>: {@code @Scheduled}는 인스턴스마다 독립적으로 돈다.
 * 서버를 2대로 늘리면 같은 배치가 동시에 두 번 트리거된다. 지금 안전한 이유는 단일 인스턴스 배포이고,
 * 다중화한다면 분산 락(ShedLock)이나 외부 스케줄러(쿠버네티스 CronJob, Airflow)로
 * 실행 주체를 하나로 만들어야 한다. 지금 넣지 않는 이유는 다중 인스턴스를 증명할 환경이 없어서다.</p>
 *
 * <p>다만 <b>중복 실행이 사고로 이어지지는 않는다</b>. 방어가 두 겹이다:
 * ① Spring Batch가 같은 파라미터의 JobInstance 재실행을 거부하고,
 * ② 그마저 뚫려도 도메인이 멱등하다(감지는 status 조건, 가산은 lastAccruedAt).</p>
 */
@Configuration
@EnableScheduling
public class OverdueBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(OverdueBatchScheduler.class);

    private final JobLauncher jobLauncher;
    private final Job overdueDetectionJob;
    private final Job overdueInterestAccrualJob;

    public OverdueBatchScheduler(JobLauncher jobLauncher,
                                 Job overdueDetectionJob,
                                 Job overdueInterestAccrualJob) {
        this.jobLauncher = jobLauncher;
        this.overdueDetectionJob = overdueDetectionJob;
        this.overdueInterestAccrualJob = overdueInterestAccrualJob;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void runDailyOverdueSettlement() {
        LocalDate baseDate = LocalDate.now();

        // 파라미터를 기준일 하나로 두는 것은 의도적이다. Spring Batch는 "같은 파라미터 = 같은 JobInstance"로
        // 보고 이미 완료된 인스턴스의 재실행을 거부하므로, 같은 날 두 번 트리거되면 두 번째는 프레임워크가 막는다.
        // 날짜가 파라미터이므로 "놓친 어제 날짜로 다시 돌리기"도 자연스럽게 된다.
        JobParameters params = new JobParametersBuilder()
                .addString("baseDate", baseDate.toString())
                .toJobParameters();

        run(overdueDetectionJob, params, "연체 감지");
        run(overdueInterestAccrualJob, params, "연체이자 가산");
    }

    private void run(Job job, JobParameters params, String label) {
        try {
            jobLauncher.run(job, params);
        } catch (Exception e) {
            // 배치 실패가 애플리케이션을 죽이면 안 된다. 기록하고 다음 실행에 맡긴다(멱등하므로 안전).
            log.error("[{}] 배치 실행 실패", label, e);
        }
    }
}
