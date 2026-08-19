package com.finbank.backend.loan.dto;

import com.finbank.backend.common.MoneyPolicy;
import com.finbank.backend.loan.domain.LoanAccount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 대출 상세 — 계약 조건 스냅샷 + 전 회차 스케줄 */
public record LoanDetailResponse(
        Long loanId,
        Long applicationId,
        String productName,
        Long linkedAccountId,
        BigDecimal principal,
        BigDecimal balance,
        BigDecimal interestRate,
        BigDecimal overdueExtraRate,
        BigDecimal prepaymentFeeRate,
        String repaymentType,
        LocalDate executedAt,
        LocalDate maturityDate,
        String status,
        List<RepaymentScheduleResponse> schedules
) {
    public static LoanDetailResponse of(LoanAccount loan, List<RepaymentScheduleResponse> schedules) {
        return new LoanDetailResponse(
                loan.getId(),
                loan.getApplication().getId(),
                loan.getApplication().getProduct().getName(),
                loan.getLinkedAccount().getId(),
                MoneyPolicy.toWon(loan.getPrincipal()),
                MoneyPolicy.toWon(loan.getBalance()),
                loan.getInterestRate(),
                loan.getOverdueExtraRate(),
                loan.getPrepaymentFeeRate(),
                loan.getRepaymentType().name(),
                loan.getExecutedAt(),
                loan.getMaturityDate(),
                loan.getStatus().name(),
                schedules
        );
    }
}
