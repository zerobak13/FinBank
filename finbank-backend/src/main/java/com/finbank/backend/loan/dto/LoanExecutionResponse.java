package com.finbank.backend.loan.dto;

import com.finbank.backend.common.MoneyPolicy;
import com.finbank.backend.loan.domain.LoanAccount;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 대출 실행 응답.
 *
 * <p>멱등 재요청 시 이 JSON이 그대로 복원되어 반환되므로,
 * Jackson이 역직렬화할 수 있는 형태(record + 기본 타입)로만 구성한다.</p>
 *
 * <p><b>전 회차 스케줄은 담지 않는다.</b> 이 응답은 멱등키 표의 응답 칸(VARCHAR 4000)에 저장되는데,
 * 스케줄까지 넣으면 22개월부터 4,000자를 넘어 실행 전체가 롤백됐다(상품 최대 기간은 60개월).
 * 스케줄은 {@code GET /api/loans/{loanId}/schedules}로 조회한다.</p>
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
        BigDecimal accountBalanceAfter
) {
    public static LoanExecutionResponse of(LoanAccount loan, BigDecimal accountBalanceAfter) {
        return new LoanExecutionResponse(
                loan.getId(),
                loan.getApplication().getId(),
                loan.getLinkedAccount().getId(),
                MoneyPolicy.toWon(loan.getPrincipal()),
                loan.getInterestRate(),
                loan.getRepaymentType().name(),
                loan.getExecutedAt(),
                loan.getMaturityDate(),
                MoneyPolicy.toWon(accountBalanceAfter)
        );
    }
}
