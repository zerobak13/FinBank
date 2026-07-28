package com.finbank.backend.loan.service.review;

import com.finbank.backend.loan.repository.LoanAccountRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 룰 2: 기존 활성 대출 잔액 합 + 신청 금액이 상품 한도(maxAmount)를 넘으면 탈락.
 *
 * <p>실제 심사는 소득 기반 DSR을 쓰지만, 소득 데이터가 없는 본 프로젝트에서는
 * "상품 한도 대비 총 노출액" 룰로 축소했다. (축소 근거를 아는 것이 포인트)</p>
 */
@Component
@Order(2)
public class CreditLimitRule implements LoanReviewer {

    public static final String CODE = "CREDIT_LIMIT_EXCEEDED";

    private final LoanAccountRepository loanAccountRepository;

    public CreditLimitRule(LoanAccountRepository loanAccountRepository) {
        this.loanAccountRepository = loanAccountRepository;
    }

    @Override
    public Optional<String> reject(ReviewContext context) {
        BigDecimal existing = loanAccountRepository.sumActiveBalanceByMemberId(context.member().getId());
        BigDecimal totalExposure = existing.add(context.requestedAmount());
        boolean exceeded = totalExposure.compareTo(context.product().getMaxAmount()) > 0;
        return exceeded ? Optional.of(CODE) : Optional.empty();
    }
}
