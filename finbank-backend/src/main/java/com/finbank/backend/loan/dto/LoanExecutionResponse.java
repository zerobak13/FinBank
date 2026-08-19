package com.finbank.backend.loan.dto;

import com.finbank.backend.common.MoneyPolicy;
import com.finbank.backend.loan.domain.LoanAccount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 대출 실행 응답.
 *
 * <p>멱등 재요청 시 이 JSON이 그대로 복원되어 반환되므로,
 * Jackson이 역직렬화할 수 있는 형태(record + 기본 타입)로만 구성한다.</p>
 */
public record LoanExecutionResponse(
        Long loanId,
        Long applicationId,
        Long linkedAccountId,
        BigDecimal principal,
        BigDecimal interestRate,
        String repaymentType,
        LocalDate executedAt,
        LocalDate maturityDate,
        BigDecimal accountBalanceAfter,
        List<RepaymentScheduleResponse> schedules
) {
    public static LoanExecutionResponse of(LoanAccount loan, BigDecimal accountBalanceAfter,
                                           List<RepaymentScheduleResponse> schedules) {
        return new LoanExecutionResponse(
                loan.getId(),
                loan.getApplication().getId(),
                loan.getLinkedAccount().getId(),
                MoneyPolicy.toWon(loan.getPrincipal()),
                loan.getInterestRate(),
                loan.getRepaymentType().name(),
                loan.getExecutedAt(),
                loan.getMaturityDate(),
                MoneyPolicy.toWon(accountBalanceAfter),
                schedules
        );
    }
}
