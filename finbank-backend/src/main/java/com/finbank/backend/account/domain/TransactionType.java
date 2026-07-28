package com.finbank.backend.account.domain;

/**
 * 거래 유형. 입금/출금/이체를 단일 거래 로그 테이블에서 구분하는 값이다.
 * 이체 1건은 보내는 쪽 TRANSFER_OUT + 받는 쪽 TRANSFER_IN 두 로그로 기록된다.
 */
public enum TransactionType {
    DEPOSIT,       // 입금
    WITHDRAW,      // 출금
    TRANSFER_IN,   // 이체 - 받는 쪽
    TRANSFER_OUT   // 이체 - 보내는 쪽
}
