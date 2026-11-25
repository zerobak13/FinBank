package com.finbank.backend.account.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.domain.TransactionType;
import com.finbank.backend.account.dto.CashResponse;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.account.repository.TransactionLogRepository;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import com.finbank.backend.support.TestAccountNumbers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 입금·출금 멱등성 — 이체와 같은 규칙: 같은 키로 몇 번이 오든 돈은 한 번만 움직이고,
 * 같은 키로 내용을 바꾸면 422.
 */
@SpringBootTest
class CashIdempotencyTest {

    @Autowired AccountMoneyService accountMoneyService;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "cash-idem@test.com";
    private static final BigDecimal INITIAL = new BigDecimal("1000000");
    private static final BigDecimal AMOUNT = new BigDecimal("70000");

    private Account account;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        Member member = memberRepository.saveAndFlush(new Member(EMAIL, "입출금테스터", "password"));
        account = accountRepository.saveAndFlush(new Account(member, TestAccountNumbers.next(), INITIAL));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("출금: 같은 키로 두 번 보내면 두 번째는 저장된 응답 재생, 잔액은 한 번만 줄어든다")
    void withdraw_sameKey_movesMoneyOnce() {
        String key = UUID.randomUUID().toString();

        CashResponse first = accountMoneyService.withdraw(account.getId(), AMOUNT, key);
        CashResponse second = accountMoneyService.withdraw(account.getId(), AMOUNT, key);

        assertThat(first.balanceAfter()).isEqualByComparingTo(INITIAL.subtract(AMOUNT));
        assertThat(second.balanceAfter()).isEqualByComparingTo(first.balanceAfter());
        assertThat(balance()).isEqualByComparingTo(INITIAL.subtract(AMOUNT));
        assertThat(countOf(TransactionType.WITHDRAW)).isEqualTo(1);
    }

    @Test
    @DisplayName("입금: 같은 키로 금액만 바꿔 보내면 422, 입금은 처음 한 번뿐이다")
    void deposit_sameKeyDifferentAmount_isRejected() {
        String key = UUID.randomUUID().toString();
        accountMoneyService.deposit(account.getId(), AMOUNT, key);

        assertThatThrownBy(() -> accountMoneyService.deposit(account.getId(), new BigDecimal("1"), key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISMATCH);

        assertThat(balance()).isEqualByComparingTo(INITIAL.add(AMOUNT));
        assertThat(countOf(TransactionType.DEPOSIT)).isEqualTo(1);
    }

    private BigDecimal balance() {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    private long countOf(TransactionType type) {
        return transactionLogRepository.findAll().stream().filter(t -> t.getType() == type).count();
    }
}
