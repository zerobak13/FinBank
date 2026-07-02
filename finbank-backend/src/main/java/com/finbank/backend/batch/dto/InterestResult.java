package com.finbank.backend.batch.dto;

/**
 * Processor에서 계산된 이자 결과.
 * Reader → Processor → Writer 흐름에서 Writer가 받는 객체.
 */
public class InterestResult {

    private final Long accountId;
    private final long interestAmount;

    public InterestResult(Long accountId, long interestAmount) {
        this.accountId = accountId;
        this.interestAmount = interestAmount;
    }

    public Long getAccountId() { return accountId; }
    public long getInterestAmount() { return interestAmount; }
}
