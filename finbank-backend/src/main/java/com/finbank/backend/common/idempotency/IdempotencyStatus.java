package com.finbank.backend.common.idempotency;

/**
 * 멱등키 레코드 상태.
 *
 * <pre>
 * (없음) ──선점(INSERT)──▶ IN_PROGRESS ──본 처리 성공──▶ COMPLETED
 *                              │
 *                              └──본 처리 실패──▶ (삭제 = 재시도 허용)
 * </pre>
 */
public enum IdempotencyStatus {
    /** 처리 주체가 키를 선점했고 본 처리가 진행 중 — 같은 키의 동시 요청은 409 */
    IN_PROGRESS,
    /** 처리 완료 + 응답 스냅샷 확정 — 같은 키의 재요청은 저장된 응답을 그대로 반환 */
    COMPLETED
}
