package com.finbank.backend.support;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanApplication;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import com.finbank.backend.member.domain.Member;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 실행일이 과거인 대출 — 실행 서비스는 오늘 날짜로만 실행하므로, 날짜가 걸린 테스트(중도상환 기산일·수수료 기간,
 * 납기가 지난 회차)는 대출과 스케줄을 직접 만든다.
 *
 * <p>회차는 원금 균등 + 고정 이자다. 스케줄 금액 계산은 계산기 테스트가 맡고, 여기서는 날짜와 상태만 본다.
 * 원금은 기간으로 나누어떨어지는 금액을 준다(예: 1,200만 원 · 12/60개월).</p>
 */
@Component
public class PastLoanFixture {

    private final LoanApplicationFixture applicationFixture;
    private final LoanAccountRepository loanAccountRepository;
    private final RepaymentScheduleRepository repaymentScheduleRepository;

    public PastLoanFixture(LoanApplicationFixture applicationFixture,
                           LoanAccountRepository loanAccountRepository,
                           RepaymentScheduleRepository repaymentScheduleRepository) {
        this.applicationFixture = applicationFixture;
        this.loanAccountRepository = loanAccountRepository;
        this.repaymentScheduleRepository = repaymentScheduleRepository;
    }

    public LoanAccount executed(Member member, Account account, LoanProduct product, BigDecimal principal,
                                int termMonths, LocalDate executedAt, BigDecimal installmentInterest) {
        LoanApplication application = applicationFixture.executed(member, product, account, principal, termMonths);
        LoanAccount loan = loanAccountRepository.saveAndFlush(LoanAccount.execute(application, account, executedAt));

        BigDecimal installmentPrincipal = principal.divide(BigDecimal.valueOf(termMonths)); // 나누어떨어지지 않으면 예외
        for (int i = 1; i <= termMonths; i++) {
            repaymentScheduleRepository.save(RepaymentSchedule.of(
                    loan, i, executedAt.plusMonths(i), installmentPrincipal, installmentInterest));
        }
        repaymentScheduleRepository.flush();
        return loan;
    }
}
