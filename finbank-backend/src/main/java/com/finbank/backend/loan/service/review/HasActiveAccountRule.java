package com.finbank.backend.loan.service.review;

import com.finbank.backend.account.repository.AccountRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 룰 1: 잠기지 않은 활성 입출금 계좌를 보유해야 한다 (대출 실행 시 입금받을 계좌). */
@Component
@Order(1)
public class HasActiveAccountRule implements LoanReviewer {

    public static final String CODE = "NO_ACTIVE_ACCOUNT";

    private final AccountRepository accountRepository;

    public HasActiveAccountRule(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public Optional<String> reject(ReviewContext context) {
        boolean hasAccount = accountRepository.existsByMemberAndLockedFalse(context.member());
        return hasAccount ? Optional.empty() : Optional.of(CODE);
    }
}
