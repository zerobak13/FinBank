package com.finbank.backend.loan.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.exception.ForbiddenException;
import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.loan.domain.*;
import com.finbank.backend.loan.dto.LoanApplicationResponse;
import com.finbank.backend.loan.dto.LoanApplyRequest;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanApplicationRepository;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.loan.service.review.CreditLimitRule;
import com.finbank.backend.loan.service.review.NoActiveOverdueRule;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.auth.repository.RefreshTokenRepository;
import com.finbank.backend.account.repository.TransactionLogRepository;
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
import java.time.LocalDate;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 대출 신청·자동심사 통합 테스트 (실제 MySQL — 심사 룰이 실데이터 기준으로 동작하는지 검증).
 * 상품은 V4 시드(원리금균등, 한도 5,000만)를 사용한다.
 */
@SpringBootTest
class LoanApplicationServiceTest {

    @Autowired LoanApplicationService service;
    @Autowired LoanProductRepository loanProductRepository;
    @Autowired LoanApplicationRepository loanApplicationRepository;
    @Autowired LoanAccountRepository loanAccountRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired com.finbank.backend.support.DatabaseCleaner cleaner;

    private static final String EMAIL = "loan@test.com";
    private LoanProduct product; // 원리금균등, 한도 5,000만
    private Member member;

    @BeforeEach
    void setUp() {
        cleaner.clean();

        member = memberRepository.saveAndFlush(new Member(EMAIL, "대출테스터", "password"));
        product = loanProductRepository.findByStatus(ProductStatus.ON_SALE).stream()
                .filter(p -> p.getRepaymentType() == RepaymentType.EQUAL_PAYMENT)
                .findFirst().orElseThrow();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Account createActiveAccount() {
        return accountRepository.saveAndFlush(
                new Account(member, TestAccountNumbers.next(), new BigDecimal("1000000")));
    }

    /** 잔액 balance의 실행된 대출 1건 생성 (한도/연체 룰 테스트용) */
    private LoanAccount createExecutedLoan(Account linked, BigDecimal amount) {
        LoanApplication app = new LoanApplication(member, product, linked, amount, 12);
        app.approve();
        app.markExecuted();
        loanApplicationRepository.saveAndFlush(app);
        return loanAccountRepository.saveAndFlush(LoanAccount.execute(app, linked, LocalDate.now()));
    }

    @Test
    @DisplayName("활성 계좌 보유 + 한도 내 신청 → 즉시 APPROVED")
    void apply_approved() {
        Account account = createActiveAccount();

        LoanApplicationResponse res = service.apply(
                new LoanApplyRequest(product.getId(), account.getId(), new BigDecimal("10000000"), 12));

        assertThat(res.status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(res.rejectReason()).isNull();
        assertThat(res.reviewedAt()).isNotNull();
    }

    @Test
    @DisplayName("신청한 계좌가 응답에 그대로 실린다 (어느 계좌로 받는지 사용자가 확인 가능)")
    void apply_linkedAccountInResponse() {
        Account account = createActiveAccount();

        LoanApplicationResponse res = service.apply(
                new LoanApplyRequest(product.getId(), account.getId(), new BigDecimal("10000000"), 12));

        assertThat(res.accountId()).isEqualTo(account.getId());
        assertThat(res.accountNumber()).isEqualTo(account.getAccountNumber());
    }

    @Test
    @DisplayName("존재하지 않는 계좌 지정 → 404 NotFound")
    void apply_accountNotFound() {
        createActiveAccount();

        assertThatThrownBy(() -> service.apply(
                new LoanApplyRequest(product.getId(), 999_999L, new BigDecimal("10000000"), 12)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("타인의 계좌를 입금 계좌로 지정 → 403 Forbidden")
    void apply_othersAccount_forbidden() {
        createActiveAccount();

        // 타인 소유 계좌를 만들어 그 id로 신청을 시도한다
        Member other = memberRepository.saveAndFlush(new Member("other@test.com", "타인", "password"));
        Account othersAccount = accountRepository.saveAndFlush(
                new Account(other, TestAccountNumbers.next(), new BigDecimal("1000000")));

        assertThatThrownBy(() -> service.apply(new LoanApplyRequest(
                product.getId(), othersAccount.getId(), new BigDecimal("10000000"), 12)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("잠긴 계좌를 입금 계좌로 지정 → 400 ACCOUNT_LOCKED")
    void apply_lockedAccount() {
        Account locked = createActiveAccount();
        locked.lock();
        accountRepository.saveAndFlush(locked);

        assertThatThrownBy(() -> service.apply(new LoanApplyRequest(
                product.getId(), locked.getId(), new BigDecimal("10000000"), 12)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    @DisplayName("기존 대출 3,000만 + 신청 2,500만 = 5,500만 > 한도 5,000만 → REJECTED (CREDIT_LIMIT_EXCEEDED)")
    void apply_rejected_creditLimit() {
        Account account = createActiveAccount();
        createExecutedLoan(account, new BigDecimal("30000000"));

        LoanApplicationResponse res = service.apply(
                new LoanApplyRequest(product.getId(), account.getId(), new BigDecimal("25000000"), 12));

        assertThat(res.status()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(res.rejectReason()).isEqualTo(CreditLimitRule.CODE);
    }

    @Test
    @DisplayName("연체 중인 대출이 있으면 → REJECTED (ACTIVE_OVERDUE_EXISTS)")
    void apply_rejected_overdue() {
        Account account = createActiveAccount();
        LoanAccount overdueLoan = createExecutedLoan(account, new BigDecimal("30000000"));
        overdueLoan.markOverdue();
        loanAccountRepository.saveAndFlush(overdueLoan);

        // 한도 룰(룰1)은 통과하도록 소액(1,000만) 신청 → 룰2에서 탈락해야 함
        LoanApplicationResponse res = service.apply(
                new LoanApplyRequest(product.getId(), account.getId(), new BigDecimal("10000000"), 12));

        assertThat(res.status()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(res.rejectReason()).isEqualTo(NoActiveOverdueRule.CODE);
    }

    @Test
    @DisplayName("상품 최대 한도 초과 금액 신청 → 422 AMOUNT_OUT_OF_RANGE (심사 이전 검증)")
    void apply_amountOutOfRange() {
        Account account = createActiveAccount();
        BigDecimal overMax = product.getMaxAmount().add(BigDecimal.ONE);

        assertThatThrownBy(() -> service.apply(new LoanApplyRequest(product.getId(), account.getId(), overMax, 12)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AMOUNT_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("상품 최대 기간 초과 신청 → 422 TERM_OUT_OF_RANGE")
    void apply_termOutOfRange() {
        Account account = createActiveAccount();

        assertThatThrownBy(() -> service.apply(
                new LoanApplyRequest(product.getId(), account.getId(), new BigDecimal("10000000"), product.getMaxTermMonths() + 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TERM_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("타인의 신청 조회 → 403 Forbidden")
    void getApplication_notMine_forbidden() {
        Account account = createActiveAccount();
        LoanApplicationResponse mine = service.apply(
                new LoanApplyRequest(product.getId(), account.getId(), new BigDecimal("10000000"), 12));

        // 다른 사용자로 전환
        memberRepository.saveAndFlush(new Member("other@test.com", "타인", "password"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("other@test.com", null, Collections.emptyList()));

        assertThatThrownBy(() -> service.getApplication(mine.id()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("승인된 신청은 취소 가능, 취소 후 이력에 CANCELED로 남는다")
    void cancel_approvedApplication() {
        Account account = createActiveAccount();
        LoanApplicationResponse res = service.apply(
                new LoanApplyRequest(product.getId(), account.getId(), new BigDecimal("10000000"), 12));

        service.cancel(res.id());

        assertThat(service.getApplication(res.id()).status()).isEqualTo(ApplicationStatus.CANCELED);
    }
}
