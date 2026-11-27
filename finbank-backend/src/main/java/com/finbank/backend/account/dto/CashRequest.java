package com.finbank.backend.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 입금·출금 요청 바디 (POST /api/accounts/{id}/deposit · /withdraw).
 * 금액 규칙은 이체와 같다 — 1원 이상, 원 단위 정수, 15자리 이하.
 */
@Schema(description = "입금·출금 요청")
public record CashRequest(
        @Schema(description = "금액 (1 이상, 원 단위 정수)", example = "10000")
        @NotNull
        @DecimalMin("1")
        @Digits(integer = 15, fraction = 0)
        BigDecimal amount
) {
}
