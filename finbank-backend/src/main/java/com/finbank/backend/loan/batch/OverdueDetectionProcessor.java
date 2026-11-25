package com.finbank.backend.loan.batch;

import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import org.springframework.batch.item.ItemProcessor;

/**
 * 연체 감지 배치의 처리 단계 — 납기 지난 회차 하나를 연체로 바꾸고 연체 이력을 만든다.
 *
 * <p><b>읽어 둔 엔티티를 고쳐 쓰지 않는다.</b> Reader가 읽은 회차·대출 엔티티를 바꾸면 청크가 끝날 때
 * 행 전체가 다시 쓰인다. 그 사이(읽은 뒤 ~ 청크 커밋 전)에 같은 회차가 상환되면, 상환이 줄인 대출 잔액과
 * PAID가 배치가 읽었던 옛 값으로 되돌아간다 — 돈은 빠졌는데 잔액은 그대로, 회차는 다시 연체.
 * 그래서 상태 전이는 상환·실행과 같은 원칙으로 <b>조건부 UPDATE</b>로만 한다.</p>
 *
 * <ol>
 *   <li>대출 행을 먼저 잠근다 — 상환과 같은 순서(대출 → 회차)여야 서로가 쥔 락을 기다리지 않는다.</li>
 *   <li>회차를 PENDING일 때만 OVERDUE로. 읽은 뒤 상환됐으면 0행 → 건너뛴다(이력도 만들지 않는다).</li>
 *   <li>대출을 ACTIVE일 때만 OVERDUE로 (이미 연체면 그대로).</li>
 * </ol>
 */
public class OverdueDetectionProcessor implements ItemProcessor<RepaymentSchedule, OverdueHistory> {

    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final LoanAccountRepository loanAccountRepository;

    public OverdueDetectionProcessor(RepaymentScheduleRepository repaymentScheduleRepository,
                                     LoanAccountRepository loanAccountRepository) {
        this.repaymentScheduleRepository = repaymentScheduleRepository;
        this.loanAccountRepository = loanAccountRepository;
    }

    @Override
    public OverdueHistory process(RepaymentSchedule schedule) {
        Long loanId = schedule.getLoanAccount().getId();
        loanAccountRepository.lockById(loanId);

        if (repaymentScheduleRepository.markOverdueIfPending(schedule.getId()) == 0) {
            return null;   // 읽은 뒤 상환됐다 — 연체가 아니다 (null이면 Writer로 넘어가지 않는다)
        }
        loanAccountRepository.markOverdueIfActive(loanId);
        return OverdueHistory.open(schedule, schedule.getDueDate());
    }
}
