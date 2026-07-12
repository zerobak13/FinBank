package com.finbank.backend.loan.domain;

/** 대출 거래 유형 */
public enum LoanTransactionType {
    /** 대출 실행 (연결계좌 입금) */
    EXECUTION,
    /** 회차 상환 */
    REPAYMENT,
    /** 전액 중도상환 */
    PREPAYMENT,
    /** 연체이자 가산 (배치) */
    OVERDUE_INTEREST
}
