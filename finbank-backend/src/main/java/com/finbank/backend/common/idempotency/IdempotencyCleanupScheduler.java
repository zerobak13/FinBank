package com.finbank.backend.common.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 보관 기간이 지난 멱등키 정리 — 매일 새벽 한 번.
 *
 * <p>정리하지 않으면 돈이 움직이는 요청마다 한 행씩 쌓여 테이블이 끝없이 커진다. 클라이언트 재시도는 몇 초~몇 분 안의
 * 일이라 응답 스냅샷을 오래 들고 있을 이유가 없다. 보관 기간({@code app.idempotency.retention-days})이 지난 키는
 * 상태와 관계없이 지운다 — 그만큼 오래된 IN_PROGRESS는 이미 죽은 선점이다.</p>
 *
 * <p><b>보관 기간이 곧 멱등의 유효 기간이다.</b> 지운 키로 다시 요청이 오면 새 요청으로 처리된다. 다만 대출 실행·상환은
 * 원장({@code loan_transactions.idempotency_key} UNIQUE)이 같은 키를 한 번 더 막는다.</p>
 *
 * <p>한 번에 {@value #BATCH_SIZE}행씩 짧은 트랜잭션으로 나눠 지운다 — 한 문장으로 수십만 행을 지우면 그동안
 * 선점 INSERT가 갭 락에 막힐 수 있다.</p>
 */
@Component
public class IdempotencyCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupScheduler.class);

    static final int BATCH_SIZE = 1000;

    private final IdempotencyRecordRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Duration retention;

    public IdempotencyCleanupScheduler(IdempotencyRecordRepository repository,
                                       TransactionTemplate transactionTemplate,
                                       @Value("${app.idempotency.retention-days:7}") long retentionDays) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.retention = Duration.ofDays(retentionDays);
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    public void runDaily() {
        try {
            int deleted = purgeCreatedBefore(LocalDateTime.now().minus(retention));
            log.info("[멱등키 정리] {}건 삭제", deleted);
        } catch (RuntimeException e) {
            // 정리 실패가 애플리케이션을 죽이면 안 된다. 다음 실행이 남은 것을 지운다
            log.error("[멱등키 정리] 실패", e);
        }
    }

    /** {@code before}보다 먼저 만든 키를 모두 지우고 지운 행 수를 돌려준다 */
    public int purgeCreatedBefore(LocalDateTime before) {
        int total = 0;
        int deleted;
        do {
            Integer n = transactionTemplate.execute(status -> repository.deleteCreatedBefore(before, BATCH_SIZE));
            deleted = n == null ? 0 : n;
            total += deleted;
        } while (deleted == BATCH_SIZE);
        return total;
    }
}
