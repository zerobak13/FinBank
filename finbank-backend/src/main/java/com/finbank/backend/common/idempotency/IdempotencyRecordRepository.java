package com.finbank.backend.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdemKey(String idemKey);

    /**
     * 응답 확정 IN_PROGRESS → COMPLETED — <b>정확히 한 번의 마지막 판정</b>.
     *
     * <p>본 처리 트랜잭션 안에서 돈의 이동과 함께 커밋된다. UPDATE는 스냅샷이 아니라 최신 커밋본을 보고 조건을 평가하므로,
     * 같은 키의 본 처리가 둘 돌더라도(오래된 선점을 넘겨받은 재시도와 아직 살아 있던 원래 요청) 먼저 확정한 쪽만 1행을 받는다.
     * 늦은 쪽은 0행 → 예외 → 돈의 이동까지 롤백된다.</p>
     */
    @Modifying
    @Query("""
            update IdempotencyRecord r
               set r.status = com.finbank.backend.common.idempotency.IdempotencyStatus.COMPLETED,
                   r.responseBody = :responseBody,
                   r.completedAt = :completedAt
             where r.idemKey = :idemKey
               and r.status = com.finbank.backend.common.idempotency.IdempotencyStatus.IN_PROGRESS
            """)
    int completeIfInProgress(@Param("idemKey") String idemKey,
                             @Param("responseBody") String responseBody,
                             @Param("completedAt") LocalDateTime completedAt);

    /**
     * 선점 해제 — 본 처리가 실패했을 때만 호출한다.
     *
     * <p>IN_PROGRESS인 행만 지운다. COMPLETED 행을 지우면 이미 성공한 요청이
     * 재실행 가능해지므로 조건을 반드시 건다. 행 ID로 지우는 이유: 내 선점이 오래돼 다른 재시도가 넘겨받았다면
     * 그 새 선점을 내가 지우면 안 된다.</p>
     */
    @Modifying
    @Query("""
            delete from IdempotencyRecord r
             where r.id = :id
               and r.status = com.finbank.backend.common.idempotency.IdempotencyStatus.IN_PROGRESS
            """)
    int deleteInProgressById(@Param("id") Long id);

    /**
     * 오래된 선점 회수 — 넘겨받기 직전에 부른다. 그 사이 원래 요청이 확정했거나 다른 재시도가 먼저 회수했으면 0행.
     */
    @Modifying
    @Query("""
            delete from IdempotencyRecord r
             where r.id = :id
               and r.status = com.finbank.backend.common.idempotency.IdempotencyStatus.IN_PROGRESS
               and r.createdAt < :staleBefore
            """)
    int deleteStaleInProgress(@Param("id") Long id, @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 보관 기간이 지난 키 정리 — 한 번에 {@code limit}행씩 지워 큰 삭제가 테이블을 오래 잠그지 않게 한다.
     * 지운 행 수를 돌려준다 (0이면 끝).
     */
    @Modifying
    @Query(value = "delete from idempotency_keys where created_at < :before limit :limit", nativeQuery = true)
    int deleteCreatedBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
