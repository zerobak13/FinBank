package com.finbank.backend.loan.dto;

import com.finbank.backend.common.MoneyPolicy;
import com.finbank.backend.loan.domain.LoanAccount;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 내 대출 목록의 한 줄 */
public record LoanSummaryResponse(
        Long loanId,
        String productName,
        BigDecimal principal,
        BigDecimal balance,
        BigDecimal interestRate,
        String repaymentType,
        LocalDate executedAt,
        LocalDate maturityDate,
        String status
) {
    public static LoanSummaryResponse from(LoanAccount loan) {
        return new LoanSummaryResponse(
                loan.getId(),
                loan.getApplication().getProduct().getName(),
                MoneyPolicy.toWon(loan.getPrincipal()),
                MoneyPolicy.toWon(loan.getBalance()),
                loan.getInterestRate(),
                loan.getRepaymentType().name(),
                loan.getExecutedAt(),
                loan.getMaturityDate(),
                loan.getStatus().name()
        );
    }
}
