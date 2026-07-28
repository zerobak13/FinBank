package com.finbank.backend.loan.domain;

/**
 * 대출 원장 상태.
 *
 * <pre>
 * ACTIVE ⇄ OVERDUE (연체 발생/해소)
 * ACTIVE·OVERDUE ──(완납)──▶ PAID_OFF
 * </pre>
 */
public enum LoanAccountStatus {
    ACTIVE,
    OVERDUE,
    PAID_OFF
}
