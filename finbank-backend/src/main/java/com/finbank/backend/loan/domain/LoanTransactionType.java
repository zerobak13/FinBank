package com.finbank.backend.loan.domain;

/**
 * 대출 거래 유형.
 *
 * <p>OVERDUE_INTEREST는 연체이자의 <b>발생</b>, REPAYMENT의 연체이자 부분은 그 <b>수납</b>이다.
 * 같은 돈이므로 두 유형의 연체이자를 더하지 않는다 ({@link LoanTransaction} 참고).</p>
 */
public enum LoanTransactionType {
    /** 대출 실행 (연결계좌 입금) */
    EXECUTION,
    /** 회차 상환 — 연체 회차면 받은 연체이자를 연체이자 부분에 (수납) */
    REPAYMENT,
    /** 전액 중도상환 — 경과이자·수수료는 이자 부분에 */
    PREPAYMENT,
    /** 연체이자 가산 (배치) — 쌓인 연체이자를 연체이자 부분에 (발생) */
    OVERDUE_INTEREST
}
