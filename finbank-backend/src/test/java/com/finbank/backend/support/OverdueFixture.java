package com.finbank.backend.support;

import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 테스트 데이터: 회차·대출을 연체 상태로 만들고, 연체 이력을 열어 연체이자를 쌓아 둔다.
 *
 * <p>운영에서 연체 전이와 연체이자 누적은 연체 감지·가산 배치의 <b>조건부 UPDATE</b>로만 일어난다 —
 * 배치가 읽어 둔 엔티티를 고쳐 쓰면 그 사이 끝난 상환을 덮어쓰기 때문이다. 그래서 도메인에는 이 전이
 * 메서드가 없고, 테스트 데이터도 같은 UPDATE로 만든다({@link LoanApplicationFixture}와 같은 원칙).</p>
 */
@Component
public class OverdueFixture {

    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final OverdueHistoryRepository overdueHistoryRepository;
    private final TransactionTemplate tx;

    public OverdueFixture(RepaymentScheduleRepository repaymentScheduleRepository,
                          LoanAccountRepository loanAccountRepository,
                          OverdueHistoryRepository overdueHistoryRepository,
                          PlatformTransactionManager transactionManager) {
        this.repaymentScheduleRepository = repaymentScheduleRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.overdueHistoryRepository = overdueHistoryRepository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 대출을 연체 상태로 (ACTIVE → OVERDUE) */
    public void markLoanOverdue(Long loanId) {
        tx.executeWithoutResult(status -> {
            if (loanAccountRepository.markOverdueIfActive(loanId) != 1) {
                throw new IllegalStateException("대출을 연체 상태로 바꾸지 못했습니다: " + loanId);
            }
        });
    }

    /** 회차를 연체 상태로 (PENDING → OVERDUE) */
    public void markScheduleOverdue(Long scheduleId) {
        tx.executeWithoutResult(status -> {
            if (repaymentScheduleRepository.markOverdueIfPending(scheduleId) != 1) {
                throw new IllegalStateException("회차를 연체 상태로 바꾸지 못했습니다: " + scheduleId);
            }
        });
    }

    /**
     * 연체 이력을 열고 연체이자를 쌓아 둔다 (penalty가 0이면 쌓지 않는다).
     *
     * @return 다시 읽은 이력
     */
    public OverdueHistory openHistory(RepaymentSchedule schedule, LocalDate overdueStart,
                                      BigDecimal penalty, LocalDate accruedAt) {
        Long id = overdueHistoryRepository.saveAndFlush(OverdueHistory.open(schedule, overdueStart)).getId();
        if (penalty.signum() > 0) {
            tx.executeWithoutResult(status -> {
                if (overdueHistoryRepository.accrueIfOpen(id, penalty, accruedAt) != 1) {
                    throw new IllegalStateException("연체이자를 쌓지 못했습니다: " + id);
                }
            });
        }
        return overdueHistoryRepository.findById(id).orElseThrow();
    }
}
