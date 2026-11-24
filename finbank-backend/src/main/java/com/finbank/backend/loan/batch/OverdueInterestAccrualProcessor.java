package com.finbank.backend.loan.batch;

import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanTransaction;
import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.domain.calculator.OverdueInterestCalculator;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 연체이자 가산 배치의 처리 단계 — 이자를 계산해 이력에 누적하고, 원장에 남길 거래를 만든다.
 *
 * <p><b>경과일수의 기산점</b>: 이전에 가산한 적이 있으면 {@code lastAccruedAt}부터, 없으면 납기일부터.
 * 구간이 정확히 이어져 겹치지도 비지도 않는다.</p>
 *
 * <p><b>누적은 조건부 UPDATE로 한다.</b> 읽어 둔 이력 엔티티를 고쳐 쓰면 행 전체가 다시 쓰여, 그 사이 상환이
 * 닫은 이력을 다시 열어 버린다. 해소되지 않았고 기준일에 아직 가산하지 않은 이력만 1행이 바뀌고,
 * 0행이면(상환으로 닫혔거나 같은 날 이미 가산) 원장도 남기지 않는다.</p>
 *
 * <p><b>대출 행을 먼저 잠근다.</b> 상환과 같은 순서(대출 → 연체 이력)여야 서로가 쥔 락을 기다리지 않는다.
 * 원장에 적는 잔액도 이때 읽은 최신 값이다.</p>
 *
 * <p>가산액이 0원이어도 기산점은 전진시킨다 — 다음 실행에서 같은 구간을 다시 계산하지 않게.
 * 다만 0원짜리 원장은 남기지 않는다({@code null}이면 Writer로 넘어가지 않는다).</p>
 */
public class OverdueInterestAccrualProcessor implements ItemProcessor<OverdueHistory, LoanTransaction> {

    private final OverdueHistoryRepository overdueHistoryRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final OverdueInterestCalculator overdueInterestCalculator;
    private final LocalDate baseDate;

    public OverdueInterestAccrualProcessor(OverdueHistoryRepository overdueHistoryRepository,
                                           LoanAccountRepository loanAccountRepository,
                                           OverdueInterestCalculator overdueInterestCalculator,
                                           LocalDate baseDate) {
        this.overdueHistoryRepository = overdueHistoryRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.overdueInterestCalculator = overdueInterestCalculator;
        this.baseDate = baseDate;
    }

    @Override
    public LoanTransaction process(OverdueHistory history) {
        LocalDate from = (history.getLastAccruedAt() != null)
                ? history.getLastAccruedAt()
                : history.getOverdueStart();
        long days = ChronoUnit.DAYS.between(from, baseDate);
        if (days <= 0) {
            return null;   // 가산할 구간이 없다
        }

        LoanAccount loan = history.getLoanAccount();
        BigDecimal currentBalance = loanAccountRepository.lockBalanceById(loan.getId());   // 락 순서: 대출 → 이력
        BigDecimal penalty = overdueInterestCalculator.calculate(
                history.getOverdueAmount(),
                loan.getInterestRate(),
                loan.getOverdueExtraRate(),
                days);

        if (overdueHistoryRepository.accrueIfOpen(history.getId(), penalty, baseDate) == 0) {
            return null;   // 그 사이 상환으로 닫혔거나 같은 날 이미 가산했다
        }
        return penalty.signum() == 0 ? null : LoanTransaction.overdueInterest(loan, penalty, currentBalance);
    }
}
