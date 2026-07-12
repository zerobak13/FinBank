package com.finbank.backend.loan.domain;

/** 상환 스케줄 회차 상태 */
public enum ScheduleStatus {
    /** 납부 대기 */
    PENDING,
    /** 납부 완료 */
    PAID,
    /** 연체 (배치가 전이) */
    OVERDUE,
    /** 전액 중도상환으로 무효화 (삭제 대신 상태 보존 — append-only 철학) */
    CANCELED
}
