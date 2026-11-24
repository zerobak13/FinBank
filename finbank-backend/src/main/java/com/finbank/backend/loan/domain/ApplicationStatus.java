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
    APPLIED("심사 중"),
    APPROVED("승인"),
    REJECTED("심사 탈락"),
    EXECUTED("실행됨"),
    CANCELED("취소");

    private final String label;

    ApplicationStatus(String label) {
        this.label = label;
    }

    /** 안내 문구용 한국어 이름 */
    public String label() {
        return label;
    }
}
