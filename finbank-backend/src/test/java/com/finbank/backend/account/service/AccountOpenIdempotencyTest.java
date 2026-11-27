package com.finbank.backend.account.service;

import com.finbank.backend.account.dto.AccountCreateRequest;
import com.finbank.backend.account.dto.AccountSummaryResponse;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.account.repository.TransactionLogRepository;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
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
 * 계좌 개설 멱등성 — 초기 입금은 돈이 생기는 요청이다. 같은 키로 몇 번이 오든 계좌는 하나, 입금도 한 번.
 * (저장된 응답이 레코드로 복원되는지도 함께 본다)
 */
@SpringBootTest
class AccountOpenIdempotencyTest {

    @Autowired AccountMoneyService accountMoneyService;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "open-idem@test.com";

    private Member member;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        member = memberRepository.saveAndFlush(new Member(EMAIL, "개설테스터", "password"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("같은 키로 두 번 개설하면 두 번째는 저장된 응답 — 계좌 하나, 초기 입금 한 번")
    void open_sameKey_createsOnce() {
        String key = UUID.randomUUID().toString();

        AccountSummaryResponse first = accountMoneyService.open(request(new BigDecimal("50000")), key);
        AccountSummaryResponse second = accountMoneyService.open(request(new BigDecimal("50000")), key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.accountNumber()).isEqualTo(first.accountNumber());
        assertThat(second.balance()).isEqualByComparingTo("50000");
        assertThat(accountRepository.findByMember(member)).hasSize(1);
        assertThat(transactionLogRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("같은 키로 초기 입금액만 바꾸면 422, 계좌는 처음 하나뿐")
    void open_sameKeyDifferentDeposit_isRejected() {
        String key = UUID.randomUUID().toString();
        accountMoneyService.open(request(BigDecimal.ZERO), key);

        assertThatThrownBy(() -> accountMoneyService.open(request(new BigDecimal("1000")), key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISMATCH);
        assertThat(accountRepository.findByMember(member)).hasSize(1);
    }

    private static AccountCreateRequest request(BigDecimal initialDeposit) {
        AccountCreateRequest request = new AccountCreateRequest();
        request.setInitialDeposit(initialDeposit);
        return request;
    }
}
