package com.finbank.backend.loan.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** 대출 신청 요청 바디 (POST /api/loans/applications) */
public record LoanApplyRequest(
        @NotNull Long productId,

        /** 대출금을 입금받을 본인 계좌 */
        @NotNull Long accountId,

        @NotNull
        @DecimalMin("1")
        @Digits(integer = 15, fraction = 0)
        BigDecimal requestedAmount,

        @Min(1) int termMonths
) {
}
