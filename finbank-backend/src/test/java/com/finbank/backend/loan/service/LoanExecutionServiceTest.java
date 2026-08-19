package com.finbank.backend.loan.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.exception.ForbiddenException;
import com.finbank.backend.loan.domain.ApplicationStatus;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.LoanTransactionType;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.dto.LoanApplicationResponse;
import com.finbank.backend.loan.dto.LoanApplyRequest;
import com.finbank.backend.loan.dto.LoanExecutionResponse;
import com.finbank.backend.loan.repository.LoanApplicationRepository;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.loan.repository.LoanTransactionRepository;
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
 * 대출 실행 통합 테스트 — 실행의 정합성(스케줄·입금·원장이 한 트랜잭션)과
 * 이중 실행 방어(멱등키 / 조건부 상태 전이)를 검증한다.
 */
@SpringBootTest
class LoanExecutionServiceTest {

    @Autowired LoanApplicationService loanApplicationService;
    @Autowired LoanExecutionService loanExecutionService;
    @Autowired LoanProductRepository loanProductRepository;
    @Autowired LoanApplicationRepository loanApplicationRepository;
    @Autowired LoanTransactionRepository loanTransactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "exec@test.com";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000000");   // 계좌 초기 잔액 100만
    private static final BigDecimal LOAN_AMOUNT = new BigDecimal("10000000");      // 대출 1,000만
    private static final int TERM = 12;

    private Member member;
    private LoanProduct product;
    private Account account;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        member = memberRepository.saveAndFlush(new Member(EMAIL, "실행테스터", "password"));
        product = loanProductRepository.findByStatus(ProductStatus.ON_SALE).stream()
                .filter(p -> p.getRepaymentType() == RepaymentType.EQUAL_PAYMENT)
                .findFirst().orElseThrow();
        account = accountRepository.saveAndFlush(
                new Account(member, TestAccountNumbers.next(), INITIAL_BALANCE));

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

    private Long applyApproved() {
        LoanApplicationResponse res = loanApplicationService.apply(
                new LoanApplyRequest(product.getId(), LOAN_AMOUNT, TERM));
        assertThat(res.status()).isEqualTo(ApplicationStatus.APPROVED);
        return res.id();
    }

    @Test
    @DisplayName("실행: 스케줄 전 회차 확정 + 연결계좌 입금 + 원장 기록이 한 번에 일어난다")
    void execute_success() {
        Long applicationId = applyApproved();

        LoanExecutionResponse res = loanExecutionService.execute(applicationId, UUID.randomUUID().toString());

        // 스케줄 12회차, 원금 합계 = 대출 원금 (1원도 새지 않는다)
        assertThat(res.schedules()).hasSize(TERM);
        BigDecimal principalSum = res.schedules().stream()
                .map(s -> s.principalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(principalSum).isEqualByComparingTo(LOAN_AMOUNT);

        // 연결계좌에 원금이 입금됐다
        assertThat(res.accountBalanceAfter()).isEqualByComparingTo(INITIAL_BALANCE.add(LOAN_AMOUNT));
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(INITIAL_BALANCE.add(LOAN_AMOUNT));

        // 신청은 EXECUTED로 전이됐고, 원장에 실행 1건이 남았다
        assertThat(loanApplicationRepository.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.EXECUTED);
        assertThat(loanTransactionRepository.findAll()).hasSize(1)
                .allSatisfy(tx -> assertThat(tx.getType()).isEqualTo(LoanTransactionType.EXECUTION));
    }

    @Test
    @DisplayName("멱등: 같은 키로 재요청하면 같은 응답이 돌아오고 돈은 한 번만 움직인다")
    void execute_sameKey_isReplayed() {
        Long applicationId = applyApproved();
        String key = UUID.randomUUID().toString();

        LoanExecutionResponse first = loanExecutionService.execute(applicationId, key);
        LoanExecutionResponse second = loanExecutionService.execute(applicationId, key);

        assertThat(second.loanId()).isEqualTo(first.loanId());
        assertThat(second.accountBalanceAfter()).isEqualByComparingTo(first.accountBalanceAfter());
        // 원장 1건 · 계좌 잔액도 1회분만 증가 — 재요청은 "응답 재생"일 뿐 처리가 아니다
        assertThat(loanTransactionRepository.findAll()).hasSize(1);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(INITIAL_BALANCE.add(LOAN_AMOUNT));
    }

    @Test
    @DisplayName("이중 실행: 다른 멱등키로 같은 신청을 실행하면 409 (조건부 상태 전이가 막는다)")
    void execute_twice_conflict() {
        Long applicationId = applyApproved();
        loanExecutionService.execute(applicationId, UUID.randomUUID().toString());

        assertThatThrownBy(() -> loanExecutionService.execute(applicationId, UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_EXECUTED);

        assertThat(loanTransactionRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("같은 멱등키로 다른 신청을 실행하면 422 (키 재사용 탐지)")
    void execute_sameKey_differentRequest_rejected() {
        Long first = applyApproved();
        Long second = applyApproved();
        String key = UUID.randomUUID().toString();

        loanExecutionService.execute(first, key);

        assertThatThrownBy(() -> loanExecutionService.execute(second, key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISMATCH);
    }

    @Test
    @DisplayName("승인되지 않은 신청(취소됨)은 실행할 수 없다 → 409")
    void execute_notApproved_conflict() {
        Long applicationId = applyApproved();
        loanApplicationService.cancel(applicationId);

        assertThatThrownBy(() -> loanExecutionService.execute(applicationId, UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_EXECUTED);
    }

    @Test
    @DisplayName("타인의 신청은 실행할 수 없다 → 403")
    void execute_notMine_forbidden() {
        Long applicationId = applyApproved();

        memberRepository.saveAndFlush(new Member("other@test.com", "타인", "password"));
        login("other@test.com");

        assertThatThrownBy(() -> loanExecutionService.execute(applicationId, UUID.randomUUID().toString()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("실행 실패(멱등키 선점 후 예외)면 키가 해제되어 같은 키로 재시도할 수 있다")
    void execute_failure_releasesKey() {
        Long applicationId = applyApproved();
        loanApplicationService.cancel(applicationId); // 실행 불가 상태로 만들어 실패 유도
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> loanExecutionService.execute(applicationId, key))
                .isInstanceOf(BusinessException.class);

        // 같은 키로 다시 시도해도 "처리 중(409)"에 갇히지 않고 원래 원인(409 ALREADY_EXECUTED)이 그대로 나온다
        assertThatThrownBy(() -> loanExecutionService.execute(applicationId, key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_EXECUTED);
    }
}
