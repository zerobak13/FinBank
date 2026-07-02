package com.finbank.backend.dto;

import com.finbank.backend.domain.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

@Schema(description = "계좌 생성 요청")
public class AccountCreateRequest {

    @Schema(description = "초기 입금액 (0 이상)", example = "50000")
    @Min(0)
    private long initialDeposit;

    @Schema(description = "계좌 타입 (REGULAR: 일반, SAVINGS: 적금). 기본값: REGULAR", example = "REGULAR")
    private AccountType accountType = AccountType.REGULAR;

    public long getInitialDeposit() { return initialDeposit; }
    public void setInitialDeposit(long initialDeposit) { this.initialDeposit = initialDeposit; }

    public AccountType getAccountType() { return accountType; }
    public void setAccountType(AccountType accountType) { this.accountType = accountType; }
}
