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
import com.finbank.backend.loan.domain.LoanApplication;
import com.finbank.backend.loan.domain.LoanTransaction;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.domain.calculator.RepaymentScheduleGenerator;
import com.finbank.backend.loan.domain.calculator.ScheduleLine;
import com.finbank.backend.loan.dto.LoanExecutionResponse;
import com.finbank.backend.loan.dto.RepaymentScheduleResponse;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.LoanApplicationRepository;
import com.finbank.backend.loan.repository.LoanTransactionRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 대출 실행의 트랜잭션 본체.
 *
 * <p><b>전부 한 트랜잭션이다.</b> 스케줄만 저장되고 입금이 안 되거나, 입금은 됐는데
 * 원장이 안 남는 중간 상태가 존재해서는 안 된다.</p>
 *
 * <p><b>이중 실행 방어 2겹</b></p>
 * <ol>
 *   <li>조건부 상태 전이 (APPROVED → EXECUTED) — 두 요청 중 하나만 1행을 갱신한다. 진 쪽은 409.</li>
 *   <li>loan_accounts.application_id UNIQUE — 어떤 경로로도 신청 1건의 원장이 2개가 될 수 없다.</li>
 * </ol>
 * 여기에 멱등키(요청 단위 중복 차단)가 더해져, "같은 버튼 두 번"과 "동시 두 요청"을 각각 막는다.
 */
@Service
public class LoanExecutionProcessor {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final LoanTransactionRepository loanTransactionRepository;
    private final AccountRepository accountRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final Map<RepaymentType, RepaymentScheduleGenerator> scheduleGenerators;
    private final IdempotentExecutor idempotentExecutor;

    public LoanExecutionProcessor(LoanApplicationRepository loanApplicationRepository,
                                  LoanAccountRepository loanAccountRepository,
                                  RepaymentScheduleRepository repaymentScheduleRepository,
                                  LoanTransactionRepository loanTransactionRepository,
                                  AccountRepository accountRepository,
                                  TransactionLogRepository transactionLogRepository,
                                  Map<RepaymentType, RepaymentScheduleGenerator> scheduleGenerators,
                                  IdempotentExecutor idempotentExecutor) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.repaymentScheduleRepository = repaymentScheduleRepository;
        this.loanTransactionRepository = loanTransactionRepository;
        this.accountRepository = accountRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.scheduleGenerators = scheduleGenerators;
        this.idempotentExecutor = idempotentExecutor;
    }

    @Transactional
    public LoanExecutionResponse execute(Long applicationId, Long memberId, String idempotencyKey) {
        LoanApplication application = loanApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new NotFoundException("대출 신청을 찾을 수 없습니다: " + applicationId));

        if (!application.getMember().getId().equals(memberId)) {
            throw new ForbiddenException("본인의 대출 신청만 실행할 수 있습니다.");
        }

        // ① 조건부 전이: APPROVED인 지금 이 순간에만 1행이 갱신된다.
        //    (엔티티를 읽어 if로 검사하면 동시 요청 둘 다 통과한다 — check-then-act 함정)
        if (loanApplicationRepository.markExecutedIfApproved(applicationId) == 0) {
            throw new BusinessException(ErrorCode.ALREADY_EXECUTED,
                    "실행할 수 없는 신청입니다. (현재 상태: " + application.getStatus() + ")");
        }

        // 입금 계좌는 락 조회를 첫 조회로 — 락 이전의 낡은 잔액을 읽지 않게 한다.
        Account linkedAccount = accountRepository
                .findWithLockingById(resolveLinkedAccountId(application, memberId))
                .orElseThrow(() -> new NotFoundException("입금할 계좌를 찾을 수 없습니다."));

        if (linkedAccount.isLocked()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "잠금된 계좌에는 대출을 실행할 수 없습니다.");
        }

        // ② 계약 생성 — 금리·가산율·수수료율·상환방식을 이 시점에 스냅샷으로 복사한다.
        LocalDate executedAt = LocalDate.now();
        LoanAccount loan = loanAccountRepository.save(
                LoanAccount.execute(application, linkedAccount, executedAt));

        // ③ 상환 스케줄 전 회차 확정 생성 (C2 이자 엔진)
        List<RepaymentSchedule> schedules = generateSchedules(loan, application.getTermMonths(), executedAt);
        repaymentScheduleRepository.saveAll(schedules);

        // ④ 연결계좌 입금 (기존 계좌 도메인 재사용)
        linkedAccount.deposit(loan.getPrincipal());
        transactionLogRepository.save(
                TransactionLog.deposit(linkedAccount, loan.getPrincipal(), linkedAccount.getBalance()));

        // ⑤ 대출 원장 기록 (append-only)
        loanTransactionRepository.save(LoanTransaction.execution(loan, idempotencyKey));

        LoanExecutionResponse response = LoanExecutionResponse.of(
                loan,
                linkedAccount.getBalance(),
                schedules.stream().map(RepaymentScheduleResponse::from).toList());

        // ⑥ 멱등 응답 확정 — 위의 모든 변경과 같은 커밋에 묶인다.
        idempotentExecutor.complete(idempotencyKey, response);
        return response;
    }

    /**
     * 입금받을 계좌 확정 — 신청 시 지정한 계좌를 쓴다.
     *
     * <p>지정 계좌가 없는 신청(V7 이전 데이터)은 종전 동작대로 회원의 첫 활성 계좌로
     * 폴백한다. 소유권은 신청 시점에 검증했고, 신청 소유자 == 실행 요청자임을
     * 이 메서드 호출 전에 이미 확인했으므로 여기서 다시 검사하지 않는다.</p>
     */
    private Long resolveLinkedAccountId(LoanApplication application, Long memberId) {
        if (application.getLinkedAccount() != null) {
            return application.getLinkedAccount().getId();
        }
        return accountRepository.findActiveIdsByMemberId(memberId).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("입금할 활성 계좌가 없습니다."));
    }

    private List<RepaymentSchedule> generateSchedules(LoanAccount loan, int termMonths, LocalDate executedAt) {
        RepaymentScheduleGenerator generator = scheduleGenerators.get(loan.getRepaymentType());
        if (generator == null) {
            throw new IllegalStateException("지원하지 않는 상환 방식: " + loan.getRepaymentType());
        }
        List<ScheduleLine> lines = generator.generate(
                loan.getPrincipal(), loan.getInterestRate(), termMonths, executedAt);

        return lines.stream()
                .map(line -> RepaymentSchedule.of(loan, line.installmentNo(), line.dueDate(),
                        line.principal(), line.interest()))
                .toList();
    }
}
