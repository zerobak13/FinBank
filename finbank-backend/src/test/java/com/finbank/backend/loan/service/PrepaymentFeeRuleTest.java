package com.finbank.backend.loan.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.domain.TransactionLog;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.account.repository.TransactionLogRepository;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.domain.calculator.PrepaymentFeeCalculator;
import com.finbank.backend.loan.dto.PrepaymentQuoteResponse;
import com.finbank.backend.loan.dto.RepaymentResponse;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import com.finbank.backend.support.PastLoanFixture;
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
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 중도상환수수료 — 대출일부터 3년 안에 갚을 때만 받는다 (금소법 §20①4호나목), 견적과 실제 금액이 같다.
 *
 * <p>수수료를 매기는 기간은 "실행일부터 만기일과 실행일 + 3년 중 빠른 날까지"다. 3년 이하 대출은 예전 계산
 * (잔존일수 ÷ 대출일수)과 같고, 3년 넘는 대출은 (3년 − 경과일) ÷ 3년, 3년이 지나면 0원.</p>
 */
@SpringBootTest
class PrepaymentFeeRuleTest {

    @Autowired MemberRepository memberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LoanProductRepository loanProductRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired PastLoanFixture pastLoanFixture;
    @Autowired LoanQueryService loanQueryService;
    @Autowired LoanRepaymentService loanRepaymentService;
    @Autowired LoanApplicationService loanApplicationService;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "prepay-fee@test.com";
    private static final BigDecimal PRINCIPAL = new BigDecimal("12000000");
    private static final BigDecimal INSTALLMENT_INTEREST = new BigDecimal("50000");

    private final PrepaymentFeeCalculator feeCalculator = new PrepaymentFeeCalculator();

    private Member member;
    private Account account;
    private LoanProduct product;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        member = memberRepository.saveAndFlush(new Member(EMAIL, "수수료테스터", "password"));
        account = accountRepository.saveAndFlush(
                new Account(member, TestAccountNumbers.next(), new BigDecimal("50000000")));
        product = loanProductRepository.findByStatus(ProductStatus.ON_SALE).get(0);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("3년이 지난 중도상환은 수수료 0원 — 60개월 대출을 40개월째 갚는다")
    void afterThreeYears_noFee() {
        LoanAccount loan = loanExecuted(60, LocalDate.now().minusMonths(40));

        PrepaymentQuoteResponse quote = loanQueryService.quotePrepayment(loan.getId());
        RepaymentResponse res = loanRepaymentService.prepayAll(loan.getId(), UUID.randomUUID().toString());

        assertThat(quote.feePortion()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quote.feeFreeFrom()).isEqualTo(loan.getExecutedAt().plusYears(3));
        assertThat(res.feePortion()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("3년 안이면 (3년 − 경과일) ÷ 3년 비율 — 60개월 대출을 12개월째 갚는다")
    void withinThreeYears_feeOverThreeYearWindow() {
        LoanAccount loan = loanExecuted(60, LocalDate.now().minusMonths(12));
        LocalDate feeEnd = loan.getExecutedAt().plusYears(3);

        PrepaymentQuoteResponse quote = loanQueryService.quotePrepayment(loan.getId());

        BigDecimal expected = feeCalculator.calculate(PRINCIPAL, loan.getPrepaymentFeeRate(),
                ChronoUnit.DAYS.between(LocalDate.now(), feeEnd),
                ChronoUnit.DAYS.between(loan.getExecutedAt(), feeEnd));
        assertThat(quote.feePortion()).isPositive().isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("3년 이하 대출은 예전과 같다 — 잔존일수 ÷ 대출일수 (12개월 대출을 3개월째 갚는다)")
    void shortLoan_feeOverLoanTerm() {
        LoanAccount loan = loanExecuted(12, LocalDate.now().minusMonths(3));

        PrepaymentQuoteResponse quote = loanQueryService.quotePrepayment(loan.getId());

        BigDecimal expected = feeCalculator.calculate(PRINCIPAL, loan.getPrepaymentFeeRate(),
                ChronoUnit.DAYS.between(LocalDate.now(), loan.getMaturityDate()),
                ChronoUnit.DAYS.between(loan.getExecutedAt(), loan.getMaturityDate()));
        assertThat(quote.feePortion()).isPositive().isEqualByComparingTo(expected);
        assertThat(quote.feeFreeFrom()).isEqualTo(loan.getMaturityDate());
    }

    @Test
    @DisplayName("견적은 같은 날 실제 중도상환 금액과 같고, 계좌 거래 내역에는 어느 대출의 중도상환인지 남는다")
    void quote_matchesActualPrepayment() {
        LoanAccount loan = loanExecuted(60, LocalDate.now().minusMonths(12));

        PrepaymentQuoteResponse quote = loanQueryService.quotePrepayment(loan.getId());
        RepaymentResponse res = loanRepaymentService.prepayAll(loan.getId(), UUID.randomUUID().toString());

        assertThat(res.principalPortion()).isEqualByComparingTo(quote.principalPortion());
        assertThat(res.interestPortion()).isEqualByComparingTo(quote.interestPortion());
        assertThat(res.feePortion()).isEqualByComparingTo(quote.feePortion());
        assertThat(res.totalPaid()).isEqualByComparingTo(quote.totalAmount());
        assertThat(transactionLogRepository.findAll())
                .extracting(TransactionLog::getDescription)
                .contains("전액 중도상환 · 대출 #" + loan.getId());
    }

    @Test
    @DisplayName("중도상환할 수 없는 대출(완납)은 견적도 409, 상품 목록에는 중도상환수수료율이 나온다")
    void quote_paidOffLoan_conflict_and_productsShowFeeRate() {
        LoanAccount loan = loanExecuted(12, LocalDate.now().minusMonths(3));
        loanRepaymentService.prepayAll(loan.getId(), UUID.randomUUID().toString());

        assertThatThrownBy(() -> loanQueryService.quotePrepayment(loan.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
        assertThat(loanApplicationService.getProducts())
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.prepaymentFeeRate()).isNotNull());
    }

    private LoanAccount loanExecuted(int termMonths, LocalDate executedAt) {
        return pastLoanFixture.executed(member, account, product, PRINCIPAL, termMonths, executedAt, INSTALLMENT_INTEREST);
    }
}
