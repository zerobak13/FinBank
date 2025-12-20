package com.finbank.backend.dto;

import jakarta.validation.constraints.Min;

public class AccountCreateRequest {

    @Min(0)
    private long initialDeposit;

    public long getInitialDeposit() { return initialDeposit; }
    public void setInitialDeposit(long initialDeposit) { this.initialDeposit = initialDeposit; }
}
