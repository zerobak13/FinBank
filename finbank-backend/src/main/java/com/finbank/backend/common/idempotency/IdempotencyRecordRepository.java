package com.finbank.backend.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdemKey(String idemKey);

    /**
     * 선점 해제 — 본 처리가 실패했을 때만 호출한다.
     *
     * <p>IN_PROGRESS인 행만 지운다. COMPLETED 행을 지우면 이미 성공한 요청이
     * 재실행 가능해지므로 조건을 반드시 건다.</p>
     */
    @Modifying
    @Query("""
            delete from IdempotencyRecord r
             where r.idemKey = :idemKey
               and r.status = com.finbank.backend.common.idempotency.IdempotencyStatus.IN_PROGRESS
            """)
    int deleteInProgress(@Param("idemKey") String idemKey);
}
