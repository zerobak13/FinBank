package com.finbank.backend.loan.dto;

import com.finbank.backend.common.MoneyPolicy;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.RepaymentType;

import java.math.BigDecimal;

/** 대출 상품 응답 */
public record LoanProductResponse(
        Long id,
        String name,
        RepaymentType repaymentType,
        BigDecimal interestRate,
        BigDecimal overdueExtraRate,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        int maxTermMonths
) {
    public static LoanProductResponse from(LoanProduct p) {
        return new LoanProductResponse(
                p.getId(), p.getName(), p.getRepaymentType(),
                p.getInterestRate(), p.getOverdueExtraRate(),
                MoneyPolicy.toWon(p.getMinAmount()), MoneyPolicy.toWon(p.getMaxAmount()),
                p.getMaxTermMonths()
        );
    }
}
