package com.finbank.backend.loan.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.account.service.AccountService;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanAccountStatus;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.LoanTransaction;
import com.finbank.backend.loan.domain.LoanTransactionType;
import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.domain.ScheduleStatus;
import com.finbank.backend.loan.dto.LoanApplicationResponse;
import com.finbank.backend.loan.dto.LoanApplyRequest;
import com.finbank.backend.loan.dto.LoanExecutionResponse;
import com.finbank.backend.loan.dto.RepaymentResponse;
import com.finbank.backend.loan.dto.RepaymentScheduleResponse;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanTransactionRepository;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import com.finbank.backend.support.OverdueFixture;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 상환 통합 테스트 — 회차 상환 / 순서 규칙 / 멱등 / 완납 / 전액 중도상환 / 연체 회차(연체이자 함께 출금).
 *
 * <p>시나리오: 계좌 잔액 100만 + 대출 1,000만(원리금균등 12개월) 실행 → 잔액 1,100만.
 * 12회 총 상환액(약 1,032만)을 감당할 수 있는 잔액이므로 완납까지 한 테스트에서 검증할 수 있다.</p>
 */
@SpringBootTest
class LoanRepaymentServiceTest {

    @Autowired LoanApplicationService loanApplicationService;
    @Autowired LoanExecutionService loanExecutionService;
    @Autowired LoanRepaymentService loanRepaymentService;
    @Autowired LoanQueryService loanQueryService;
    @Autowired AccountService accountService;
    @Autowired LoanAccountRepository loanAccountRepository;
    @Autowired LoanTransactionRepository loanTransactionRepository;
    @Autowired OverdueFixture overdueFixture;
    @Autowired LoanProductRepository loanProductRepository;
    @Autowired RepaymentScheduleRepository repaymentScheduleRepository;
    @Autowired OverdueHistoryRepository overdueHistoryRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "repay@test.com";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000000");
    private static final BigDecimal LOAN_AMOUNT = new BigDecimal("10000000");
    private static final int TERM = 12;

    private Member member;
    private LoanProduct product;
    private Account account;
    private LoanExecutionResponse loan;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        member = memberRepository.saveAndFlush(new Member(EMAIL, "상환테스터", "password"));
        product = loanProductRepository.findByStatus(ProductStatus.ON_SALE).stream()
                .filter(p -> p.getRepaymentType() == RepaymentType.EQUAL_PAYMENT)
                .findFirst().orElseThrow();
        account = accountRepository.saveAndFlush(
                new Account(member, TestAccountNumbers.next(), INITIAL_BALANCE));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, Collections.emptyList()));

        LoanApplicationResponse application = loanApplicationService.apply(
                new LoanApplyRequest(product.getId(), account.getId(), LOAN_AMOUNT, TERM));
        loan = loanExecutionService.execute(application.id(), UUID.randomUUID().toString());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private BigDecimal accountBalance() {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    private String newKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * 1회차를 연체 상태로 만든다 — 연체 감지 배치(회차·대출 OVERDUE + 연체 이력)와
     * 가산 배치(연체이자 누적)가 해 둘 일을 직접 해 둔다.
     */
    private OverdueHistory overdueFirstInstallment(BigDecimal penalty) {
        LoanAccount loanAccount = loanAccountRepository.findById(loan.loanId()).orElseThrow();
        RepaymentSchedule first = repaymentScheduleRepository
                .findFirstByLoanAccountAndStatusInOrderByInstallmentNoAsc(loanAccount, List.of(ScheduleStatus.PENDING))
                .orElseThrow();
        overdueFixture.markScheduleOverdue(first.getId());
        overdueFixture.markLoanOverdue(loanAccount.getId());
        return overdueFixture.openHistory(first, first.getDueDate(), penalty, first.getDueDate().plusDays(10));
    }

    @Test
    @DisplayName("1회차 상환: 계좌에서 회차 총액이 빠지고 대출 잔액이 회차 원금만큼 줄어든다")
    void repay_firstInstallment() {
        RepaymentScheduleResponse first = loan.schedules().get(0);
        BigDecimal balanceBefore = accountBalance();

        RepaymentResponse res = loanRepaymentService.repay(loan.loanId(), 1, newKey());

        assertThat(res.installmentNo()).isEqualTo(1);
        assertThat(res.principalPortion()).isEqualByComparingTo(first.principalAmount());
        assertThat(res.interestPortion()).isEqualByComparingTo(first.interestAmount());
        assertThat(res.penaltyPortion()).isEqualByComparingTo(BigDecimal.ZERO); // 연체가 아니면 연체이자 0
        assertThat(res.totalPaid()).isEqualByComparingTo(first.totalAmount());

        // 계좌 잔액 = 이전 - 회차 총액
        assertThat(accountBalance()).isEqualByComparingTo(balanceBefore.subtract(first.totalAmount()));
        // 대출 잔액 = 원금 - 회차 원금
        assertThat(res.loanBalanceAfter()).isEqualByComparingTo(LOAN_AMOUNT.subtract(first.principalAmount()));

        // 스케줄은 PAID, 원장에는 상환 1건 — 연체가 아니므로 연체이자 부분은 0
        assertThat(loanQueryService.getSchedules(loan.loanId()).get(0).status()).isEqualTo("PAID");
        assertThat(loanTransactionRepository.findAll().stream()
                .filter(tx -> tx.getType() == LoanTransactionType.REPAYMENT).toList())
                .singleElement()
                .satisfies(tx -> {
                    assertThat(tx.getInterestPortion()).isEqualByComparingTo(first.interestAmount());
                    assertThat(tx.getPenaltyPortion()).isEqualByComparingTo(BigDecimal.ZERO);
                });
    }

    @Test
    @DisplayName("회차는 순서대로만: 1회차가 남았는데 2회차를 지정하면 409")
    void repay_outOfOrder_conflict() {
        assertThatThrownBy(() -> loanRepaymentService.repay(loan.loanId(), 2, newKey()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OUT_OF_ORDER_INSTALLMENT);
    }

    @Test
    @DisplayName("멱등: 같은 키로 두 번 상환 요청해도 출금은 한 번뿐이다")
    void repay_sameKey_isReplayed() {
        String key = newKey();
        BigDecimal balanceBefore = accountBalance();

        RepaymentResponse first = loanRepaymentService.repay(loan.loanId(), 1, key);
        RepaymentResponse second = loanRepaymentService.repay(loan.loanId(), 1, key);

        assertThat(second.totalPaid()).isEqualByComparingTo(first.totalPaid());
        assertThat(second.loanBalanceAfter()).isEqualByComparingTo(first.loanBalanceAfter());
        assertThat(accountBalance()).isEqualByComparingTo(balanceBefore.subtract(first.totalPaid()));
        assertThat(loanTransactionRepository.findAll().stream()
                .filter(tx -> tx.getType() == LoanTransactionType.REPAYMENT).toList()).hasSize(1);
    }

    @Test
    @DisplayName("다른 키로 같은 회차를 다시 상환하면 다음 회차가 처리된다 (진행은 하되 중복은 없다)")
    void repay_nextInstallment() {
        loanRepaymentService.repay(loan.loanId(), 1, newKey());
        RepaymentResponse second = loanRepaymentService.repay(loan.loanId(), null, newKey());

        assertThat(second.installmentNo()).isEqualTo(2);
        List<RepaymentScheduleResponse> schedules = loanQueryService.getSchedules(loan.loanId());
        assertThat(schedules.get(0).status()).isEqualTo("PAID");
        assertThat(schedules.get(1).status()).isEqualTo("PAID");
        assertThat(schedules.get(2).status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("전 회차 상환 → 대출 잔액 0 · PAID_OFF, 이후 상환 시도는 409")
    void repay_all_untilPaidOff() {
        RepaymentResponse last = null;
        for (int i = 1; i <= TERM; i++) {
            last = loanRepaymentService.repay(loan.loanId(), i, newKey());
        }

        assertThat(last).isNotNull();
        assertThat(last.loanBalanceAfter()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(last.loanStatus()).isEqualTo(LoanAccountStatus.PAID_OFF.name());
        assertThat(loanAccountRepository.findById(loan.loanId()).orElseThrow().getBalance())
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThatThrownBy(() -> loanRepaymentService.repay(loan.loanId(), null, newKey()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("계좌 잔액이 회차 총액보다 적으면 400 (스케줄은 PENDING 그대로 롤백된다)")
    void repay_insufficientBalance() {
        // 회차 총액보다 적게 남도록 계좌를 비운다
        BigDecimal keep = new BigDecimal("1000");
        accountService.withdraw(account.getId(), accountBalance().subtract(keep));

        assertThatThrownBy(() -> loanRepaymentService.repay(loan.loanId(), 1, newKey()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

        // 롤백 확인 — 조건부 UPDATE로 PAID가 됐더라도 트랜잭션이 되돌린다
        assertThat(loanQueryService.getSchedules(loan.loanId()).get(0).status()).isEqualTo("PENDING");
        assertThat(accountBalance()).isEqualByComparingTo(keep);
    }

    @Test
    @DisplayName("연체 회차 상환: 원리금 + 쌓인 연체이자를 한 번에 출금하고, 원장에는 출금액과 같은 한 줄로 남긴다")
    void repay_overdueInstallment_collectsPenalty() {
        BigDecimal penalty = new BigDecimal("12345");
        OverdueHistory history = overdueFirstInstallment(penalty);
        RepaymentScheduleResponse first = loan.schedules().get(0);
        BigDecimal balanceBefore = accountBalance();

        RepaymentResponse res = loanRepaymentService.repay(loan.loanId(), 1, newKey());

        assertThat(res.principalPortion()).isEqualByComparingTo(first.principalAmount());
        assertThat(res.interestPortion()).isEqualByComparingTo(first.interestAmount());
        assertThat(res.penaltyPortion()).isEqualByComparingTo(penalty);
        assertThat(res.totalPaid()).isEqualByComparingTo(first.totalAmount().add(penalty));
        assertThat(accountBalance()).isEqualByComparingTo(balanceBefore.subtract(res.totalPaid()));

        // 원장: 상환 한 건 = 한 줄, 금액 = 계좌 출금액. 약정 이자와 연체이자는 칸을 나눠 적는다
        List<LoanTransaction> repayments = loanTransactionRepository.findAll().stream()
                .filter(tx -> tx.getType() == LoanTransactionType.REPAYMENT).toList();
        assertThat(repayments).hasSize(1);
        LoanTransaction ledger = repayments.get(0);
        assertThat(ledger.getAmount()).isEqualByComparingTo(res.totalPaid());
        assertThat(ledger.getPrincipalPortion()).isEqualByComparingTo(first.principalAmount());
        assertThat(ledger.getInterestPortion()).isEqualByComparingTo(first.interestAmount());
        assertThat(ledger.getPenaltyPortion()).isEqualByComparingTo(penalty);

        // 연체가 정리된다 — 이력이 닫혀 가산이 멈추고, 남은 연체 회차가 없으니 대출은 정상으로
        assertThat(overdueHistoryRepository.findById(history.getId()).orElseThrow().isResolved()).isTrue();
        assertThat(loanQueryService.getSchedules(loan.loanId()).get(0).status()).isEqualTo("PAID");
        assertThat(res.loanStatus()).isEqualTo(LoanAccountStatus.ACTIVE.name());
    }

    @Test
    @DisplayName("스케줄 조회: 연체 회차에는 쌓인 연체이자가 함께 나오고, 그 회차를 갚으면 화면 금액(원리금+연체이자)이 그대로 빠진다")
    void schedules_showPenaltyAccrued_equalToChargedAmount() {
        BigDecimal penalty = new BigDecimal("12345");
        overdueFirstInstallment(penalty);

        List<RepaymentScheduleResponse> schedules = loanQueryService.getSchedules(loan.loanId());
        RepaymentScheduleResponse first = schedules.get(0);
        assertThat(first.status()).isEqualTo("OVERDUE");
        assertThat(first.penaltyAccrued()).isEqualByComparingTo(penalty);
        assertThat(schedules.get(1).penaltyAccrued()).isEqualByComparingTo(BigDecimal.ZERO);

        BigDecimal balanceBefore = accountBalance();
        loanRepaymentService.repay(loan.loanId(), 1, newKey());

        assertThat(balanceBefore.subtract(accountBalance()))
                .isEqualByComparingTo(first.totalAmount().add(first.penaltyAccrued()));
        // 갚고 나면 이력이 닫혀 0으로 돌아간다
        assertThat(loanQueryService.getSchedules(loan.loanId()).get(0).penaltyAccrued())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("연체 회차: 잔액이 원리금은 되지만 연체이자까지는 모자라면 전부 거절하고, 필요한 금액을 알려 준다")
    void repay_overdueInstallment_insufficientForPenalty_rejectsAll() {
        BigDecimal penalty = new BigDecimal("12345");
        OverdueHistory history = overdueFirstInstallment(penalty);
        RepaymentScheduleResponse first = loan.schedules().get(0);
        BigDecimal needed = first.totalAmount().add(penalty);

        // 원리금만큼만 남긴다 — 연체이자만큼 모자란 잔액
        accountService.withdraw(account.getId(), accountBalance().subtract(first.totalAmount()));

        assertThatThrownBy(() -> loanRepaymentService.repay(loan.loanId(), 1, newKey()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(String.format(Locale.KOREA, "%,d원", needed.longValue()))
                .hasMessageContaining("연체이자")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

        // 아무것도 바뀌지 않는다 — 원리금만 먼저 받는 일도 없다
        assertThat(accountBalance()).isEqualByComparingTo(first.totalAmount());
        assertThat(loanQueryService.getSchedules(loan.loanId()).get(0).status()).isEqualTo("OVERDUE");
        assertThat(loanAccountRepository.findById(loan.loanId()).orElseThrow().getStatus())
                .isEqualTo(LoanAccountStatus.OVERDUE);
        OverdueHistory reloaded = overdueHistoryRepository.findById(history.getId()).orElseThrow();
        assertThat(reloaded.isResolved()).isFalse();
        assertThat(reloaded.getPenaltyAccrued()).isEqualByComparingTo(penalty);
        assertThat(loanTransactionRepository.findAll().stream()
                .filter(tx -> tx.getType() == LoanTransactionType.REPAYMENT).toList()).isEmpty();
    }

    @Test
    @DisplayName("전액 중도상환: 잔여원금+경과이자+수수료가 출금되고 남은 회차는 CANCELED로 보존된다")
    void prepayAll() {
        BigDecimal balanceBefore = accountBalance();

        RepaymentResponse res = loanRepaymentService.prepayAll(loan.loanId(), newKey());

        assertThat(res.type()).isEqualTo("PREPAYMENT");
        assertThat(res.principalPortion()).isEqualByComparingTo(LOAN_AMOUNT);
        assertThat(res.feePortion()).isGreaterThan(BigDecimal.ZERO); // 실행 직후라 잔존기간이 거의 100%
        assertThat(res.totalPaid()).isEqualByComparingTo(
                res.principalPortion().add(res.interestPortion()).add(res.feePortion()));
        assertThat(res.loanBalanceAfter()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(res.loanStatus()).isEqualTo(LoanAccountStatus.PAID_OFF.name());

        assertThat(accountBalance()).isEqualByComparingTo(balanceBefore.subtract(res.totalPaid()));
        assertThat(loanQueryService.getSchedules(loan.loanId()))
                .allSatisfy(s -> assertThat(s.status()).isEqualTo("CANCELED"));
        assertThat(loanTransactionRepository.findAll().stream()
                .filter(tx -> tx.getType() == LoanTransactionType.PREPAYMENT).toList()).hasSize(1);
    }

    @Test
    @DisplayName("중도상환 후 다시 중도상환하면 409 (이미 완납)")
    void prepayAll_twice_conflict() {
        loanRepaymentService.prepayAll(loan.loanId(), newKey());

        assertThatThrownBy(() -> loanRepaymentService.prepayAll(loan.loanId(), newKey()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("1회차 상환 후 중도상환: 잔여원금만 상환되고 총 원금은 대출 원금과 일치한다")
    void repayOnce_thenPrepayAll() {
        RepaymentResponse first = loanRepaymentService.repay(loan.loanId(), 1, newKey());
        RepaymentResponse prepay = loanRepaymentService.prepayAll(loan.loanId(), newKey());

        assertThat(first.principalPortion().add(prepay.principalPortion()))
                .isEqualByComparingTo(LOAN_AMOUNT);
        assertThat(prepay.loanBalanceAfter()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
