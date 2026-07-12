package com.finbank.backend.loan.domain;

/** 상환 방식 */
public enum RepaymentType {
    /** 원리금균등 — 매월 동일 금액(원금+이자) 상환 */
    EQUAL_PAYMENT,
    /** 원금균등 — 매월 동일 원금 + 잔액 기준 이자 (상환액이 매월 감소) */
    EQUAL_PRINCIPAL,
    /** 만기일시 — 매월 이자만, 만기에 원금 전액 */
    BULLET
}
