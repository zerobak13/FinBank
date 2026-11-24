package com.finbank.backend.loan.service.review;

import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanApplicationRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 룰 1: 기존 활성 대출 잔액 + 승인됐지만 아직 실행하지 않은 신청 + 이번 신청 금액이
 * 상품 한도(maxAmount)를 넘으면 탈락.
 *
 * <p>실제 심사는 소득 기반 DSR을 쓰지만, 소득 데이터가 없는 본 프로젝트에서는
 * "상품 한도 대비 총 노출액" 룰로 축소했다. (축소 근거를 아는 것이 포인트)</p>
 *
 * <p><b>승인만 받은 신청도 센다.</b> 실행된 대출만 더하면, 한도만큼 승인을 받아 둔 뒤 또 신청해도
 * 아직 실행 전이라 잔액이 0이어서 통과한다 — 둘 다 실행하면 한도의 두 배가 나간다. 승인은 "곧 나갈 돈"이다.
 * 남은 빈틈: 같은 회원의 신청 두 건이 정확히 동시에 오면 서로를 못 본 채 둘 다 통과할 수 있다(회원 단위 잠금 없음).</p>
 */
@Component
@Order(1)
public class CreditLimitRule implements LoanReviewer {

    public static final String CODE = "CREDIT_LIMIT_EXCEEDED";

    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationRepository loanApplicationRepository;

    public CreditLimitRule(LoanAccountRepository loanAccountRepository,
                           LoanApplicationRepository loanApplicationRepository) {
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationRepository = loanApplicationRepository;
    }

    @Override
    public Optional<String> reject(ReviewContext context) {
        Long memberId = context.member().getId();
        BigDecimal executed = loanAccountRepository.sumActiveBalanceByMemberId(memberId);
        BigDecimal approvedNotExecuted = loanApplicationRepository.sumApprovedAmountByMemberId(memberId);
        BigDecimal totalExposure = executed.add(approvedNotExecuted).add(context.requestedAmount());
        boolean exceeded = totalExposure.compareTo(context.product().getMaxAmount()) > 0;
        return exceeded ? Optional.of(CODE) : Optional.empty();
    }
}
