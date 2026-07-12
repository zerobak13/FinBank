package com.finbank.backend.loan.service.review;

import com.finbank.backend.member.domain.Member;
import com.finbank.backend.loan.domain.LoanProduct;

import java.math.BigDecimal;

/** 자동심사에 필요한 입력 묶음 */
public record ReviewContext(Member member, LoanProduct product, BigDecimal requestedAmount) {
}
