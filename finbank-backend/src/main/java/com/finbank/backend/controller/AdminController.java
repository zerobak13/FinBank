package com.finbank.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "어드민 (Admin)", description = """
        관리자 전용 API. JWT 인증 필요.

        **이자 정산 배치**
        - 운영 환경: 매일 자정 자동 실행 (cron: 0 0 0 * * *)
        - 데모/테스트: 이 API로 즉시 수동 실행 가능
        - Spring Batch가 실행 이력을 관리하므로 중복 정산 없음
          (단, 수동 실행은 타임스탬프를 파라미터로 사용해 매번 새 Job으로 실행)
        """)
@SecurityRequirement(name = "BearerAuth")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final JobLauncher jobLauncher;
    private final Job interestSettlementJob;

    public AdminController(JobLauncher jobLauncher, Job interestSettlementJob) {
        this.jobLauncher = jobLauncher;
        this.interestSettlementJob = interestSettlementJob;
    }

    @Operation(
            summary = "이자 정산 배치 수동 실행",
            description = """
                    SAVINGS(적금) 계좌에 일일 이자를 즉시 정산합니다.

                    **이자 계산 공식**
                    일일 이자 = 잔액 × (연이율 / 365) → 소수점 이하 버림

                    **처리 흐름**
                    1. MyBatis로 SAVINGS 계좌를 100건씩 페이징 조회
                    2. Processor: 일일 이자 계산 (0원이면 skip)
                    3. Writer: 계좌 잔액 반영 + INTEREST 거래 로그 기록

                    **주의**: 수동 실행은 타임스탬프를 Job 파라미터로 사용하므로
                    자정 자동 실행(날짜 파라미터)과는 별개의 Job으로 처리됩니다.
                    하루에 여러 번 수동 실행 시 이자가 중복 지급될 수 있습니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "배치 실행 완료")
    @ApiResponse(responseCode = "500", description = "배치 실행 실패")
    @PostMapping("/batch/interest")
    public ResponseEntity<Map<String, Object>> runInterestBatch() {
        try {
            String runId = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

            JobParameters params = new JobParametersBuilder()
                    .addString("settlementDate", runId)  // 매번 다른 파라미터 → 새 Job 실행
                    .toJobParameters();

            log.info("[AdminController] 이자 정산 배치 수동 실행 - runId={}", runId);

            JobExecution execution = jobLauncher.run(interestSettlementJob, params);

            log.info("[AdminController] 배치 완료 - status={}, jobId={}",
                    execution.getStatus(), execution.getJobId());

            return ResponseEntity.ok(Map.of(
                    "status",    execution.getStatus().toString(),
                    "jobId",     execution.getJobId(),
                    "startTime", execution.getStartTime() != null ? execution.getStartTime().toString() : "",
                    "endTime",   execution.getEndTime()   != null ? execution.getEndTime().toString()   : "",
                    "message",   "이자 정산이 완료되었습니다."
            ));

        } catch (Exception e) {
            log.error("[AdminController] 배치 실행 실패", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status",  "FAILED",
                    "message", e.getMessage()
            ));
        }
    }
}
