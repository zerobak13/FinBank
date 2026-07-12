package com.finbank.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;

/**
 * 계좌 생성 요청 바디 (POST /api/accounts). 초기 입금액만 받는다.
 * 금액은 BigDecimal이지만 정책상 원 단위 정수만 허용한다(@Digits fraction=0).
 */
@Schema(description = "계좌 생성 요청")
public class AccountCreateRequest {

    @Schema(description = "초기 입금액 (0 이상, 원 단위 정수)", example = "50000")
    @DecimalMin("0")
    @Digits(integer = 15, fraction = 0)
    private BigDecimal initialDeposit = BigDecimal.ZERO;

    public BigDecimal getInitialDeposit() { return initialDeposit; }
    public void setInitialDeposit(BigDecimal initialDeposit) { this.initialDeposit = initialDeposit; }
}
