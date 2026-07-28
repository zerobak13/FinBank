package com.finbank.backend.loan.domain;

/**
 * 대출 신청 상태.
 *
 * <pre>
 * APPLIED ──(심사 통과)──▶ APPROVED ──(실행)──▶ EXECUTED
 *    │                        │
 *    ├──(심사 탈락)──▶ REJECTED │
 *    └──(취소)────────▶ CANCELED ◀──(실행 전 취소)
 * </pre>
 */
public enum ApplicationStatus {
    APPLIED,
    APPROVED,
    REJECTED,
    EXECUTED,
    CANCELED
}
