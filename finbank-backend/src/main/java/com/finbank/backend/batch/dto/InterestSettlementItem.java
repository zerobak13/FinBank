package com.finbank.backend.batch.dto;

import java.math.BigDecimal;

/**
 * MyBatis로 읽어온 적금 계좌 1건.
 * 배치 Reader → Processor 에서 사용.
 */
public class InterestSettlementItem {

    private Long id;
    private Long balance;
    private BigDecimal interestRate;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getBalance() { return balance; }
    public void setBalance(Long balance) { this.balance = balance; }

    public BigDecimal getInterestRate() { return interestRate; }
    public void setInterestRate(BigDecimal interestRate) { this.interestRate = interestRate; }
}
