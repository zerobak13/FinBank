package com.finbank.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

/**
 * 계좌 생성 요청 바디 (POST /api/accounts). 초기 입금액만 받는다.
 */
@Schema(description = "계좌 생성 요청")
public class AccountCreateRequest {

    @Schema(description = "초기 입금액 (0 이상)", example = "50000")
    @Min(0)
    private long initialDeposit;

    public long getInitialDeposit() { return initialDeposit; }
    public void setInitialDeposit(long initialDeposit) { this.initialDeposit = initialDeposit; }
}
