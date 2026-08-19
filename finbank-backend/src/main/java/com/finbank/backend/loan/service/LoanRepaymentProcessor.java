package com.finbank.backend.loan.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.domain.TransactionLog;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.account.repository.TransactionLogRepository;
import com.finbank.backend.common.idempotency.IdempotentExecutor;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.exception.ForbiddenException;
import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanAccountStatus;
import com.finbank.backend.loan.domain.LoanTransaction;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.domain.ScheduleStatus;
import com.finbank.backend.loan.domain.calculator.AccruedInterestCalculator;
import com.finbank.backend.loan.domain.calculator.PrepaymentFeeCalculator;
import com.finbank.backend.loan.dto.RepaymentResponse;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanTransactionRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import com.finbank.backend.common.MoneyPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 상환의 트랜잭션 본체 — 회차 상환과 전액 중도상환.
 *
 * <p><b>락 순서 규칙(전역)</b>: 자원 유형 간에는 항상 {@code Account → LoanAccount} 순으로 잠근다.
 * 이체의 "ID 오름차순" 규칙과 같은 목적이다 — 두 요청이 서로가 쥔 락을 기다리는 상황을 원천 차단한다.
 * 그래서 대출을 먼저 읽어야 알 수 있는 연결계좌 ID는 <b>엔티티가 아니라 ID만</b> 프로젝션으로 읽는다
 * (엔티티를 먼저 읽으면 1차 캐시 때문에 뒤이은 락 조회가 낡은 값을 돌려준다 — Phase A의 교훈).</p>
 *
 * <p><b>이중 상환 방어의 핵심은 조건부 UPDATE다.</b> MySQL 기본 격리수준(REPEATABLE READ)에서
 * 일반 SELECT는 트랜잭션 시작 시점의 스냅샷을 읽는다. 락을 기다렸다 통과한 두 번째 요청이
 * "아직 PENDING인 회차"를 스냅샷에서 볼 수 있다는 뜻이다. 반면 UPDATE는 항상 최신 커밋본을 보고
 * 조건을 평가하므로, {@code WHERE status IN (PENDING, OVERDUE)}가 0행을 반환하며 정확히 막힌다.</p>
 */
@Service
public class LoanRepaymentProcessor {

    private final LoanAccountRepository loanAccountRepository;
    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final LoanTransactionRepository loanTransactionRepository;
    private final AccountRepository accountRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final AccruedInterestCalculator accruedInterestCalculator;
    private final PrepaymentFeeCalculator prepaymentFeeCalculator;
    private final IdempotentExecutor idempotentExecutor;

    public LoanRepaymentProcessor(LoanAccountRepository loanAccountRepository,
                                  RepaymentScheduleRepository repaymentScheduleRepository,
                                  LoanTransactionRepository loanTransactionRepository,
                                  AccountRepository accountRepository,
                                  TransactionLogRepository transactionLogRepository,
                                  AccruedInterestCalculator accruedInterestCalculator,
                                  PrepaymentFeeCalculator prepaymentFeeCalculator,
                                  IdempotentExecutor idempotentExecutor) {
        this.loanAccountRepository = loanAccountRepository;
        this.repaymentScheduleRepository = repaymentScheduleRepository;
        this.loanTransactionRepository = loanTransactionRepository;
        this.accountRepository = accountRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.accruedInterestCalculator = accruedInterestCalculator;
        this.prepaymentFeeCalculator = prepaymentFeeCalculator;
        this.idempotentExecutor = idempotentExecutor;
    }

    // ── 회차 상환 ─────────────────────────────────────────────────
    @Transactional
    public RepaymentResponse repay(Long loanId, Long memberId, Integer requestedInstallmentNo, String idempotencyKey) {
        Account account = lockLinkedAccount(loanId);
        LoanAccount loan = lockLoan(loanId, memberId);

        if (loan.getStatus() == LoanAccountStatus.PAID_OFF) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 완납된 대출입니다.");
        }

        RepaymentSchedule target = repaymentScheduleRepository
                .findFirstByLoanAccountAndStatusInOrderByInstallmentNoAsc(
                        loan, List.of(ScheduleStatus.PENDING, ScheduleStatus.OVERDUE))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE, "상환할 회차가 없습니다."));

        // 클라이언트가 회차를 지정했다면 "가장 빠른 미납 회차"와 일치해야 한다 (순서대로만 상환)
        if (requestedInstallmentNo != null && requestedInstallmentNo != target.getInstallmentNo()) {
            throw new BusinessException(ErrorCode.OUT_OF_ORDER_INSTALLMENT,
                    "다음 상환 회차는 " + target.getInstallmentNo() + "회차입니다.");
        }

        // 이중 상환 차단의 결정적 한 줄 — 최신 커밋본을 기준으로 조건을 평가한다.
        if (repaymentScheduleRepository.markPaidIfPayable(target.getId(), LocalDateTime.now()) == 0) {
            throw new BusinessException(ErrorCode.ALREADY_REPAID,
                    target.getInstallmentNo() + "회차는 이미 상환되었습니다.");
        }

        BigDecimal principal = target.getPrincipalAmount();
        BigDecimal interest = target.getInterestAmount();
        BigDecimal total = principal.add(interest);

        withdrawFromAccount(account, total);
        loan.repayPrincipal(principal);
        resolveOverdueIfCleared(loan);

        loanTransactionRepository.save(LoanTransaction.repayment(loan, principal, interest, idempotencyKey));

        RepaymentResponse response = new RepaymentResponse(
                loan.getId(),
                "REPAYMENT",
                target.getInstallmentNo(),
                MoneyPolicy.toWon(principal),
                MoneyPolicy.toWon(interest),
                BigDecimal.ZERO,
                MoneyPolicy.toWon(total),
                MoneyPolicy.toWon(loan.getBalance()),
                MoneyPolicy.toWon(account.getBalance()),
                loan.getStatus().name());

        idempotentExecutor.complete(idempotencyKey, response);
        return response;
    }

    // ── 전액 중도상환 ──────────────────────────────────────────────
    @Transactional
    public RepaymentResponse prepayAll(Long loanId, Long memberId, String idempotencyKey) {
        Account account = lockLinkedAccount(loanId);
        LoanAccount loan = lockLoan(loanId, memberId);

        if (loan.getStatus() != LoanAccountStatus.ACTIVE) {
            // 연체 중 대출은 연체이자 정산(D단계 배치)이 선행되어야 한다 — 지금은 명시적으로 거부한다.
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "전액 중도상환은 정상(ACTIVE) 대출만 가능합니다. (현재 상태: " + loan.getStatus() + ")");
        }

        BigDecimal remainingPrincipal = loan.getBalance();
        if (remainingPrincipal.signum() == 0) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 완납된 대출입니다.");
        }

        LocalDate today = LocalDate.now();
        BigDecimal accruedInterest = accruedInterestCalculator.calculate(
                remainingPrincipal, loan.getInterestRate(), elapsedDaysSinceLastPayment(loan, today));
        BigDecimal fee = prepaymentFeeCalculator.calculate(
                remainingPrincipal, loan.getPrepaymentFeeRate(),
                remainingDays(loan, today), totalDays(loan));

        BigDecimal total = remainingPrincipal.add(accruedInterest).add(fee);

        withdrawFromAccount(account, total);
        loan.repayPrincipal(remainingPrincipal); // 잔액 0 → PAID_OFF

        // 남은 회차는 삭제하지 않고 CANCELED로 남긴다 (계약이 어떤 계획이었는지는 사실이다)
        repaymentScheduleRepository.cancelPendingByLoanAccountId(loan.getId());

        loanTransactionRepository.save(
                LoanTransaction.prepayment(loan, remainingPrincipal, accruedInterest.add(fee), idempotencyKey));

        RepaymentResponse response = new RepaymentResponse(
                loan.getId(),
                "PREPAYMENT",
                null,
                MoneyPolicy.toWon(remainingPrincipal),
                MoneyPolicy.toWon(accruedInterest),
                MoneyPolicy.toWon(fee),
                MoneyPolicy.toWon(total),
                MoneyPolicy.toWon(loan.getBalance()),
                MoneyPolicy.toWon(account.getBalance()),
                loan.getStatus().name());

        idempotentExecutor.complete(idempotencyKey, response);
        return response;
    }

    // ── 공통 ─────────────────────────────────────────────────────

    /** 락 순서 ①: 연결 입출금 계좌 (ID만 먼저 읽고, 엔티티는 락 조회가 첫 조회가 되게 한다) */
    private Account lockLinkedAccount(Long loanId) {
        Long accountId = loanAccountRepository.findLinkedAccountIdById(loanId)
                .orElseThrow(() -> new NotFoundException("대출을 찾을 수 없습니다: " + loanId));
        return accountRepository.findWithLockingById(accountId)
                .orElseThrow(() -> new NotFoundException("연결 계좌를 찾을 수 없습니다: " + accountId));
    }

    /** 락 순서 ②: 대출 원장 */
    private LoanAccount lockLoan(Long loanId, Long memberId) {
        LoanAccount loan = loanAccountRepository.findWithLockingById(loanId)
                .orElseThrow(() -> new NotFoundException("대출을 찾을 수 없습니다: " + loanId));
        if (!loan.getMember().getId().equals(memberId)) {
            throw new ForbiddenException("본인의 대출만 상환할 수 있습니다.");
        }
        return loan;
    }

    private void withdrawFromAccount(Account account, BigDecimal amount) {
        if (account.isLocked()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "잠금된 계좌에서는 상환할 수 없습니다.");
        }
        if (account.getBalance().compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE,
                    "상환 금액에 비해 계좌 잔액이 부족합니다.");
        }
        account.withdraw(amount);
        transactionLogRepository.save(TransactionLog.withdraw(account, amount, account.getBalance()));
    }

    /** 연체 회차가 모두 정리되면 대출을 정상으로 되돌린다 */
    private void resolveOverdueIfCleared(LoanAccount loan) {
        if (loan.getStatus() != LoanAccountStatus.OVERDUE) {
            return;
        }
        if (repaymentScheduleRepository.countByLoanAccountAndStatusIn(loan, List.of(ScheduleStatus.OVERDUE)) == 0) {
            loan.resolveOverdue();
        }
    }

    /** 경과이자 기산일 = 직전 납부 회차의 납기일, 없으면 실행일 */
    private long elapsedDaysSinceLastPayment(LoanAccount loan, LocalDate today) {
        LocalDate base = repaymentScheduleRepository
                .findFirstByLoanAccountAndStatusOrderByInstallmentNoDesc(loan, ScheduleStatus.PAID)
                .map(RepaymentSchedule::getDueDate)
                .orElse(loan.getExecutedAt());
        return Math.max(0, ChronoUnit.DAYS.between(base, today));
    }

    private long totalDays(LoanAccount loan) {
        return Math.max(1, ChronoUnit.DAYS.between(loan.getExecutedAt(), loan.getMaturityDate()));
    }

    private long remainingDays(LoanAccount loan, LocalDate today) {
        long remaining = ChronoUnit.DAYS.between(today, loan.getMaturityDate());
        return Math.min(Math.max(0, remaining), totalDays(loan));
    }
}
