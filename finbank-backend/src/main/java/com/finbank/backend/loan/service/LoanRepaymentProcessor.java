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
import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.domain.ScheduleStatus;
import com.finbank.backend.loan.domain.calculator.AccruedInterestCalculator;
import com.finbank.backend.loan.domain.calculator.PrepaymentFeeCalculator;
import com.finbank.backend.loan.dto.RepaymentResponse;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanTransactionRepository;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import com.finbank.backend.common.MoneyPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 상환의 트랜잭션 본체 — 회차 상환과 전액 중도상환.
 *
 * <p><b>락 순서 규칙(전역)</b>: 자원 유형 간에는 항상 {@code Account → LoanAccount} 순으로 잠근다.
 * 이체의 "ID 오름차순" 규칙과 같은 목적이다 — 두 요청이 서로가 쥔 락을 기다리는 상황을 원천 차단한다.
 * 그래서 대출을 먼저 읽어야 알 수 있는 연결계좌 ID는 <b>엔티티가 아니라 ID만</b> 프로젝션으로 읽는다
 * (엔티티를 먼저 읽으면 1차 캐시 때문에 뒤이은 락 조회가 낡은 값을 돌려준다 — 계좌 동시성 테스트에서 확인한 함정).
 * 락보다 먼저 하는 일은 소유자 확인이다 — 남의 대출 번호로 온 요청이 그 사람의 계좌·대출을 잠그지 못하게.</p>
 *
 * <p><b>연체 배치와 겹칠 때</b>: 연체 이력과 남은 연체 회차는 잠금 조회로 읽는다. 일반 조회는 트랜잭션
 * 첫 조회 시점의 스냅샷이라, 그 뒤에 배치가 가산한 연체이자나 연체로 바꾼 회차를 못 볼 수 있다.</p>
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
    private final OverdueHistoryRepository overdueHistoryRepository;
    private final AccountRepository accountRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final AccruedInterestCalculator accruedInterestCalculator;
    private final PrepaymentFeeCalculator prepaymentFeeCalculator;
    private final IdempotentExecutor idempotentExecutor;

    public LoanRepaymentProcessor(LoanAccountRepository loanAccountRepository,
                                  RepaymentScheduleRepository repaymentScheduleRepository,
                                  LoanTransactionRepository loanTransactionRepository,
                                  OverdueHistoryRepository overdueHistoryRepository,
                                  AccountRepository accountRepository,
                                  TransactionLogRepository transactionLogRepository,
                                  AccruedInterestCalculator accruedInterestCalculator,
                                  PrepaymentFeeCalculator prepaymentFeeCalculator,
                                  IdempotentExecutor idempotentExecutor) {
        this.loanAccountRepository = loanAccountRepository;
        this.repaymentScheduleRepository = repaymentScheduleRepository;
        this.loanTransactionRepository = loanTransactionRepository;
        this.overdueHistoryRepository = overdueHistoryRepository;
        this.accountRepository = accountRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.accruedInterestCalculator = accruedInterestCalculator;
        this.prepaymentFeeCalculator = prepaymentFeeCalculator;
        this.idempotentExecutor = idempotentExecutor;
    }

    // ── 회차 상환 ─────────────────────────────────────────────────
    @Transactional
    public RepaymentResponse repay(Long loanId, Long memberId, Integer requestedInstallmentNo, String idempotencyKey) {
        assertLoanOwner(loanId, memberId);
        Account account = lockLinkedAccount(loanId);
        LoanAccount loan = lockLoan(loanId);

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

        // 연체 회차면 가산 배치가 쌓아 둔 연체이자까지 한 번에 받는다 (연체가 아니면 이력이 없어 0원).
        // 잠금 조회 — 방금 가산된 금액까지 읽고, 상환이 끝날 때까지 가산 배치가 이 이력을 건드리지 못한다
        Optional<OverdueHistory> overdue = overdueHistoryRepository.lockOpenByScheduleId(target.getId());
        BigDecimal penalty = overdue.map(OverdueHistory::getPenaltyAccrued)
                .map(MoneyPolicy::toWon)
                .orElse(BigDecimal.ZERO);
        BigDecimal total = principal.add(interest).add(penalty);

        withdrawFromAccount(account, total, penalty);   // 모자라면 여기서 전부 거절 — 원리금만 받는 일은 없다
        loan.repayPrincipal(principal);
        // 이력을 닫아야 가산 배치가 멈춘다 — 안 닫으면 다 갚은 회차에 이자가 계속 붙는다 (배치를 만들며 드러난 빈틈)
        overdue.ifPresent(history -> history.resolve(LocalDate.now()));
        resolveOverdueIfCleared(loan);

        // 원장은 상환 한 건 = 한 줄 = 계좌 출금액. 정상이자와 연체이자는 칸을 나눠 적는다
        // (가산 배치가 남긴 OVERDUE_INTEREST는 발생, 여기 연체이자 부분은 수납 — 둘을 더하지 않는다)
        loanTransactionRepository.save(
                LoanTransaction.repayment(loan, principal, interest, penalty, idempotencyKey));

        RepaymentResponse response = new RepaymentResponse(
                loan.getId(),
                "REPAYMENT",
                target.getInstallmentNo(),
                MoneyPolicy.toWon(principal),
                MoneyPolicy.toWon(interest),
                penalty,
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
        assertLoanOwner(loanId, memberId);
        Account account = lockLinkedAccount(loanId);
        LoanAccount loan = lockLoan(loanId);

        if (loan.getStatus() != LoanAccountStatus.ACTIVE) {
            // 연체 중 전액 중도상환은 아직 지원하지 않는다 — 원금·이자·연체이자 중 무엇부터 채울지(충당 순서)를
            // 먼저 정해야 해서, 연체 회차를 먼저 상환하도록 명시적으로 거부한다.
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

        withdrawFromAccount(account, total, BigDecimal.ZERO);
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
                BigDecimal.ZERO,
                MoneyPolicy.toWon(fee),
                MoneyPolicy.toWon(total),
                MoneyPolicy.toWon(loan.getBalance()),
                MoneyPolicy.toWon(account.getBalance()),
                loan.getStatus().name());

        idempotentExecutor.complete(idempotencyKey, response);
        return response;
    }

    // ── 공통 ─────────────────────────────────────────────────────

    /** 락보다 먼저: 소유자 확인 (프로젝션 — 엔티티를 1차 캐시에 올리지 않는다). 대출이 없으면 404, 남의 것이면 403 */
    private void assertLoanOwner(Long loanId, Long memberId) {
        Long ownerId = loanAccountRepository.findOwnerIdById(loanId)
                .orElseThrow(() -> new NotFoundException("대출을 찾을 수 없습니다: " + loanId));
        if (!ownerId.equals(memberId)) {
            throw new ForbiddenException("본인의 대출만 상환할 수 있습니다.");
        }
    }

    /** 락 순서 ①: 연결 입출금 계좌 (ID만 먼저 읽고, 엔티티는 락 조회가 첫 조회가 되게 한다) */
    private Account lockLinkedAccount(Long loanId) {
        Long accountId = loanAccountRepository.findLinkedAccountIdById(loanId)
                .orElseThrow(() -> new NotFoundException("대출을 찾을 수 없습니다: " + loanId));
        return accountRepository.findWithLockingById(accountId)
                .orElseThrow(() -> new NotFoundException("연결 계좌를 찾을 수 없습니다: " + accountId));
    }

    /** 락 순서 ②: 대출 (소유자는 락 전에 확인했다) */
    private LoanAccount lockLoan(Long loanId) {
        return loanAccountRepository.findWithLockingById(loanId)
                .orElseThrow(() -> new NotFoundException("대출을 찾을 수 없습니다: " + loanId));
    }

    /**
     * 연결계좌에서 상환 금액을 출금한다.
     *
     * <p>잔액이 모자라면 일부만 빼 가지 않고 <b>전부 거절</b>한다 — 앞서 실행한 회차 PAID 전이도 같은 트랜잭션이라 함께 롤백된다.
     * 거절 메시지에는 얼마가 있어야 하는지(연체이자가 들어 있으면 그 금액까지) 적어, 입금하고 다시 시도할 수 있게 한다.</p>
     *
     * @param penaltyIncluded {@code amount} 안의 연체이자 (안내 문구용, 없으면 0)
     */
    private void withdrawFromAccount(Account account, BigDecimal amount, BigDecimal penaltyIncluded) {
        if (account.isLocked()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "잠금된 계좌에서는 상환할 수 없습니다.");
        }
        if (account.getBalance().compareTo(amount) < 0) {
            String penaltyNote = penaltyIncluded.signum() > 0 ? "(연체이자 " + won(penaltyIncluded) + " 포함)" : "";
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE,
                    "계좌 잔액이 부족합니다. 상환에 필요한 금액은 " + won(amount) + penaltyNote
                            + "이고, 현재 잔액은 " + won(account.getBalance()) + "입니다.");
        }
        account.withdraw(amount);
        transactionLogRepository.save(TransactionLog.withdraw(account, amount, account.getBalance()));
    }

    /** 안내 문구용 금액 표기 — 1051150 → "1,051,150원" */
    private static String won(BigDecimal amount) {
        return String.format(Locale.KOREA, "%,d원", MoneyPolicy.toWon(amount).toBigInteger());
    }

    /** 연체 회차가 모두 정리되면 대출을 정상으로 되돌린다 (남은 연체 회차는 잠금 조회 — 최신 커밋본으로 판정) */
    private void resolveOverdueIfCleared(LoanAccount loan) {
        if (loan.getStatus() != LoanAccountStatus.OVERDUE) {
            return;
        }
        if (repaymentScheduleRepository.lockOverdueScheduleIds(loan.getId()).isEmpty()) {
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
