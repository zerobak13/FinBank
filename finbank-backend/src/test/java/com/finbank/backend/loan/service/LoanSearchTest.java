package com.finbank.backend.loan.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.common.PageResponse;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanAccountStatus;
import com.finbank.backend.loan.domain.LoanApplication;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.dto.LoanSearchCondition;
import com.finbank.backend.loan.dto.LoanSummaryResponse;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanApplicationRepository;
import com.finbank.backend.loan.repository.LoanProductRepository;
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
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대출 동적 검색 통합 테스트.
 *
 * <p>검증 관점은 두 가지다: ① 조건이 정확히 적용되는가 ② <b>조건을 조합해도</b> 정확한가.
 * 동적 쿼리의 버그는 대부분 "조건 하나일 땐 되는데 두 개 겹치면 이상해지는" 형태로 나타난다.</p>
 */
@SpringBootTest
class LoanSearchTest {

    @Autowired LoanQueryService loanQueryService;
    @Autowired LoanProductRepository loanProductRepository;
    @Autowired LoanApplicationRepository loanApplicationRepository;
    @Autowired LoanAccountRepository loanAccountRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "search@test.com";
    private static final String OTHER_EMAIL = "search-other@test.com";

    private Member member;
    private Account account;
    private LoanProduct equalPayment;   // 원리금균등
    private LoanProduct bullet;         // 만기일시

    @BeforeEach
    void setUp() {
        cleaner.clean();
        member = memberRepository.saveAndFlush(new Member(EMAIL, "검색테스터", "password"));
        account = accountRepository.saveAndFlush(
                new Account(member, TestAccountNumbers.next(), new BigDecimal("1000000")));

        List<LoanProduct> products = loanProductRepository.findByStatus(ProductStatus.ON_SALE);
        equalPayment = products.stream()
                .filter(p -> p.getRepaymentType() == RepaymentType.EQUAL_PAYMENT).findFirst().orElseThrow();
        bullet = products.stream()
                .filter(p -> p.getRepaymentType() == RepaymentType.BULLET).findFirst().orElseThrow();

        login(EMAIL);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList()));
    }

    /** 검색 대상 데이터를 직접 만든다 (실행 API를 태우면 계좌 잔액·심사 룰에 묶여 조합을 만들기 어렵다) */
    private LoanAccount givenLoan(Member owner, Account linked, LoanProduct product,
                                  BigDecimal amount, LocalDate executedAt, LoanAccountStatus status) {
        LoanApplication application = new LoanApplication(owner, product, linked, amount, 12);
        application.approve();
        application.markExecuted();
        loanApplicationRepository.saveAndFlush(application);

        LoanAccount loan = LoanAccount.execute(application, linked, executedAt);
        if (status == LoanAccountStatus.OVERDUE) {
            loan.markOverdue();
        } else if (status == LoanAccountStatus.PAID_OFF) {
            loan.repayPrincipal(loan.getBalance());
        }
        return loanAccountRepository.saveAndFlush(loan);
    }

    private PageResponse<LoanSummaryResponse> search(LoanSearchCondition condition) {
        return loanQueryService.search(condition, 0, 20);
    }

    private LoanSearchCondition condition(LoanAccountStatus status, RepaymentType type, Long productId,
                                          LocalDate from, LocalDate to, BigDecimal min, BigDecimal max) {
        return new LoanSearchCondition(status, type, productId, from, to, min, max);
    }

    @Test
    @DisplayName("조건 없음 → 내 대출 전체가 최신순으로 나온다")
    void search_noCondition() {
        givenLoan(member, account, equalPayment, new BigDecimal("10000000"), LocalDate.now(), LoanAccountStatus.ACTIVE);
        givenLoan(member, account, bullet, new BigDecimal("5000000"), LocalDate.now(), LoanAccountStatus.ACTIVE);

        PageResponse<LoanSummaryResponse> result = search(
                condition(null, null, null, null, null, null, null));

        assertThat(result.getTotalElements()).isEqualTo(2);
        // 최신순(id desc) — 나중에 만든 만기일시 대출이 먼저
        assertThat(result.getContent().get(0).repaymentType()).isEqualTo(RepaymentType.BULLET.name());
    }

    @Test
    @DisplayName("상태 조건: OVERDUE만 골라낸다")
    void search_byStatus() {
        givenLoan(member, account, equalPayment, new BigDecimal("10000000"), LocalDate.now(), LoanAccountStatus.ACTIVE);
        givenLoan(member, account, equalPayment, new BigDecimal("20000000"), LocalDate.now(), LoanAccountStatus.OVERDUE);

        PageResponse<LoanSummaryResponse> result = search(
                condition(LoanAccountStatus.OVERDUE, null, null, null, null, null, null));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).status()).isEqualTo("OVERDUE");
    }

    @Test
    @DisplayName("상환방식 + 상품 조건 조합")
    void search_byTypeAndProduct() {
        givenLoan(member, account, equalPayment, new BigDecimal("10000000"), LocalDate.now(), LoanAccountStatus.ACTIVE);
        givenLoan(member, account, bullet, new BigDecimal("5000000"), LocalDate.now(), LoanAccountStatus.ACTIVE);

        PageResponse<LoanSummaryResponse> result = search(
                condition(null, RepaymentType.BULLET, bullet.getId(), null, null, null, null));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).productName()).isEqualTo(bullet.getName());
    }

    @Test
    @DisplayName("실행일 구간: 경계일 포함 (goe / loe)")
    void search_byExecutedRange() {
        LocalDate base = LocalDate.now();
        givenLoan(member, account, equalPayment, new BigDecimal("10000000"), base.minusDays(10), LoanAccountStatus.ACTIVE);
        givenLoan(member, account, equalPayment, new BigDecimal("11000000"), base.minusDays(5), LoanAccountStatus.ACTIVE);
        givenLoan(member, account, equalPayment, new BigDecimal("12000000"), base, LoanAccountStatus.ACTIVE);

        PageResponse<LoanSummaryResponse> result = search(
                condition(null, null, null, base.minusDays(10), base.minusDays(5), null, null));

        assertThat(result.getTotalElements()).isEqualTo(2); // 경계 양쪽 모두 포함
    }

    @Test
    @DisplayName("원금 구간 + 상태 조합 — 조건이 겹쳐도 정확하다")
    void search_byPrincipalRangeAndStatus() {
        givenLoan(member, account, equalPayment, new BigDecimal("5000000"), LocalDate.now(), LoanAccountStatus.ACTIVE);
        givenLoan(member, account, equalPayment, new BigDecimal("15000000"), LocalDate.now(), LoanAccountStatus.ACTIVE);
        givenLoan(member, account, equalPayment, new BigDecimal("15000000"), LocalDate.now(), LoanAccountStatus.OVERDUE);
        givenLoan(member, account, equalPayment, new BigDecimal("30000000"), LocalDate.now(), LoanAccountStatus.ACTIVE);

        PageResponse<LoanSummaryResponse> result = search(
                condition(LoanAccountStatus.ACTIVE, null, null, null, null,
                        new BigDecimal("10000000"), new BigDecimal("20000000")));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).principal()).isEqualByComparingTo(new BigDecimal("15000000"));
    }

    @Test
    @DisplayName("타인의 대출은 어떤 조건으로도 나오지 않는다")
    void search_neverLeaksOtherMembersLoans() {
        Member other = memberRepository.saveAndFlush(new Member(OTHER_EMAIL, "타인", "password"));
        Account otherAccount = accountRepository.saveAndFlush(
                new Account(other, TestAccountNumbers.next(), new BigDecimal("1000000")));

        givenLoan(other, otherAccount, equalPayment, new BigDecimal("10000000"), LocalDate.now(), LoanAccountStatus.ACTIVE);
        givenLoan(member, account, equalPayment, new BigDecimal("20000000"), LocalDate.now(), LoanAccountStatus.ACTIVE);

        PageResponse<LoanSummaryResponse> result = search(
                condition(null, null, null, null, null, null, null));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).principal()).isEqualByComparingTo(new BigDecimal("20000000"));
    }

    @Test
    @DisplayName("페이징: 전체 건수와 페이지 정보가 정확하다")
    void search_paging() {
        for (int i = 0; i < 5; i++) {
            givenLoan(member, account, equalPayment,
                    new BigDecimal("10000000").add(BigDecimal.valueOf(i)), LocalDate.now(), LoanAccountStatus.ACTIVE);
        }

        PageResponse<LoanSummaryResponse> firstPage = loanQueryService.search(
                condition(null, null, null, null, null, null, null), 0, 2);

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(firstPage.isFirst()).isTrue();
        assertThat(firstPage.isLast()).isFalse();

        PageResponse<LoanSummaryResponse> lastPage = loanQueryService.search(
                condition(null, null, null, null, null, null, null), 2, 2);

        assertThat(lastPage.getContent()).hasSize(1);
        assertThat(lastPage.isLast()).isTrue();
    }

    @Test
    @DisplayName("결과가 없으면 빈 페이지 (예외가 아니다)")
    void search_empty() {
        givenLoan(member, account, equalPayment, new BigDecimal("10000000"), LocalDate.now(), LoanAccountStatus.ACTIVE);

        PageResponse<LoanSummaryResponse> result = search(
                condition(LoanAccountStatus.PAID_OFF, null, null, null, null, null, null));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }
}
