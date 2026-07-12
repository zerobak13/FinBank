package com.finbank.backend.loan.service.review;

import com.finbank.backend.loan.repository.LoanAccountRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 룰 3: 진행 중인 연체(OVERDUE) 대출이 있으면 신규 대출 불가. */
@Component
@Order(3)
public class NoActiveOverdueRule implements LoanReviewer {

    public static final String CODE = "ACTIVE_OVERDUE_EXISTS";

    private final LoanAccountRepository loanAccountRepository;

    public NoActiveOverdueRule(LoanAccountRepository loanAccountRepository) {
        this.loanAccountRepository = loanAccountRepository;
    }

    @Override
    public Optional<String> reject(ReviewContext context) {
        boolean hasOverdue = loanAccountRepository.existsOverdueByMemberId(context.member().getId());
        return hasOverdue ? Optional.of(CODE) : Optional.empty();
    }
}
