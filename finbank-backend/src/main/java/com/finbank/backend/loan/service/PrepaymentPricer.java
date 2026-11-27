package com.finbank.backend.loan.service;

import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanAccountStatus;
import com.finbank.backend.loan.domain.calculator.AccruedInterestCalculator;
import com.finbank.backend.loan.domain.calculator.PrepaymentFeeCalculator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 전액 중도상환 금액 — 잔여원금 + 경과이자 + 중도상환수수료.
 *
 * <p>실제 중도상환({@link LoanRepaymentProcessor#prepayAll})과 견적({@link LoanQueryService#quotePrepayment})이
 * 같은 계산을 쓰도록 한 곳에 둔다. 따로 계산하면 견적과 실제 출금액이 어긋난다.</p>
 *
 * <p><b>중도상환수수료는 대출일부터 3년 안에 갚을 때만 받는다.</b> 금융소비자보호법 §20①4호나목은 중도상환수수료를
 * 원칙적으로 금지하고 3년 이내 상환만 예외로 둔다. 그래서 수수료를 매기는 기간은 "실행일부터 만기일과 실행일 + 3년 중
 * 빠른 날까지"이고, 수수료 = 잔여원금 × 수수료율 × (그 기간 중 남은 날 ÷ 그 기간)이다.</p>
 * <ul>
 *   <li>3년 이하 대출: 잔존일수 ÷ 대출일수 (예전 계산 그대로)</li>
 *   <li>3년 넘는 대출: (3년 − 경과일수) ÷ 3년, 3년이 지나면 0원</li>
 * </ul>
 * <p>계산기({@link PrepaymentFeeCalculator})는 그대로 두고 넘기는 기간만 바꾼다 — 계산기 테스트는 판정 기준이다.</p>
 */
@Component
public class PrepaymentPricer {

    /** 중도상환수수료를 받을 수 있는 기간 — 대출일부터 3년 (금소법 §20①4호나목) */
    static final int FEE_YEARS = 3;

    private final AccruedInterestCalculator accruedInterestCalculator;
    private final PrepaymentFeeCalculator prepaymentFeeCalculator;

    public PrepaymentPricer(AccruedInterestCalculator accruedInterestCalculator,
                            PrepaymentFeeCalculator prepaymentFeeCalculator) {
        this.accruedInterestCalculator = accruedInterestCalculator;
        this.prepaymentFeeCalculator = prepaymentFeeCalculator;
    }

    /** 중도상환 금액 세 칸. 경과이자·수수료는 계산기가 원 단위로 확정해 돌려준 값이다 */
    public record Amounts(BigDecimal principal, BigDecimal accruedInterest, BigDecimal fee) {
        public BigDecimal total() {
            return principal.add(accruedInterest).add(fee);
        }
    }

    /** 전액 중도상환할 수 있는 대출인지 — 정상(ACTIVE)이고 잔액이 남아 있어야 한다 */
    public static void requirePrepayable(LoanAccount loan) {
        if (loan.getStatus() != LoanAccountStatus.ACTIVE) {
            // 연체 중 전액 중도상환은 아직 지원하지 않는다 — 원금·이자·연체이자 중 무엇부터 채울지(충당 순서)를
            // 먼저 정해야 해서, 연체 회차를 먼저 상환하도록 명시적으로 거부한다.
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "전액 중도상환은 정상(ACTIVE) 대출만 가능합니다. (현재 상태: " + loan.getStatus() + ")");
        }
        if (loan.getBalance().signum() == 0) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 완납된 대출입니다.");
        }
    }

    /**
     * @param interestFrom 경과이자 기산일 — 직전 납부 회차의 납기일, 없으면 실행일
     * @param today        상환일
     */
    public Amounts price(LoanAccount loan, LocalDate interestFrom, LocalDate today) {
        BigDecimal principal = loan.getBalance();
        BigDecimal interest = accruedInterestCalculator.calculate(
                principal, loan.getInterestRate(), Math.max(0, ChronoUnit.DAYS.between(interestFrom, today)));

        long feeDays = feeDays(loan);
        long feeDaysLeft = Math.min(Math.max(0, ChronoUnit.DAYS.between(today, feeFreeFrom(loan))), feeDays);
        BigDecimal fee = prepaymentFeeCalculator.calculate(principal, loan.getPrepaymentFeeRate(), feeDaysLeft, feeDays);

        return new Amounts(principal, interest, fee);
    }

    /** 이 날부터는 중도상환수수료가 없다 — 만기일과 실행일 + 3년 중 빠른 날 */
    public LocalDate feeFreeFrom(LoanAccount loan) {
        LocalDate threeYears = loan.getExecutedAt().plusYears(FEE_YEARS);
        return loan.getMaturityDate().isBefore(threeYears) ? loan.getMaturityDate() : threeYears;
    }

    /** 수수료를 매기는 기간(일) — 실행일부터 {@link #feeFreeFrom}까지 */
    private long feeDays(LoanAccount loan) {
        return Math.max(1, ChronoUnit.DAYS.between(loan.getExecutedAt(), feeFreeFrom(loan)));
    }
}
