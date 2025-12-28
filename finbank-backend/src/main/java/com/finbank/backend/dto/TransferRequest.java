package com.finbank.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class TransferRequest {

    @NotNull
    private Long fromAccountId;

    @NotBlank
    private String toAccountNumber;

    @Min(1)
    private long amount;

    // 1. 기본 생성자
    public TransferRequest() {}

    // 2. 모든 필드를 받는 생성자 (테스트 코드에서 사용)
    public TransferRequest(Long fromAccountId, String toAccountNumber, long amount) {
        this.fromAccountId = fromAccountId;
        this.toAccountNumber = toAccountNumber;
        this.amount = amount;
    }

    public Long getFromAccountId() { return fromAccountId; }
    public void setFromAccountId(Long fromAccountId) { this.fromAccountId = fromAccountId; }

    public String getToAccountNumber() { return toAccountNumber; }
    public void setToAccountNumber(String toAccountNumber) { this.toAccountNumber = toAccountNumber; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
}
