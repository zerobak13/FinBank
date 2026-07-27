package com.finbank.backend.common.idempotency;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 멱등키 레코드. "이 키의 요청을 누가 처리했고, 결과가 무엇인가"를 보관한다.
 *
 * <p>idem_key에 UNIQUE 제약이 있어 <b>INSERT 성공 여부가 곧 선점 성공 여부</b>다.
 * 애플리케이션의 "조회 후 없으면 INSERT" 분기는 동시 요청에서 둘 다 통과하지만,
 * UNIQUE 제약은 반드시 하나만 통과시킨다 — 경쟁의 중재를 DB에 맡기는 이유다.</p>
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 클라이언트가 보낸 멱등키 (UNIQUE) */
    @Column(name = "idem_key", nullable = false, unique = true, length = 64)
    private String idemKey;

    /** 요청자 (감사 추적용) */
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 요청 내용의 SHA-256 — 같은 키로 다른 요청을 보내는 클라이언트 버그 탐지 */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status = IdempotencyStatus.IN_PROGRESS;

    /** 확정 응답 스냅샷(JSON) — 재시도 시 이 값을 그대로 되돌려준다 */
    @Column(name = "response_body", length = 4000)
    private String responseBody;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime completedAt;

    /** 선점(IN_PROGRESS)으로 생성 */
    public static IdempotencyRecord claim(String idemKey, Long memberId, String requestHash) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.idemKey = idemKey;
        record.memberId = memberId;
        record.requestHash = requestHash;
        return record;
    }

    /**
     * 처리 완료 확정. 본 처리와 <b>같은 트랜잭션</b>에서 호출되므로,
     * 본 처리가 롤백되면 이 확정도 함께 사라진다(= 재시도 가능한 상태로 남는다).
     */
    public void complete(String responseBody) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseBody = responseBody;
        this.completedAt = LocalDateTime.now();
    }

    public boolean isCompleted() {
        return this.status == IdempotencyStatus.COMPLETED;
    }

    public boolean matches(String requestHash) {
        return this.requestHash.equals(requestHash);
    }
}
