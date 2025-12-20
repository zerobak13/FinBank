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

    public Long getFromAccountId() { return fromAccountId; }
    public void setFromAccountId(Long fromAccountId) { this.fromAccountId = fromAccountId; }

    public String getToAccountNumber() { return toAccountNumber; }
    public void setToAccountNumber(String toAccountNumber) { this.toAccountNumber = toAccountNumber; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
}
