package com.finbank.backend.loan.domain.calculator;

import com.finbank.backend.common.MoneyPolicy;

import java.math.BigDecimal;

/**
 * 연체이자 계산기 (순수 자바).
 *
 * <pre>
 * 연체이자 = 연체 원리금 × min(약정금리 + 연체가산율, 법정최고금리 20%) × 연체일수 / 365
 * </pre>
 *
 * <ul>
 *   <li>일할 계산은 연 365일 고정 (윤년 무시 — MoneyPolicy 컨벤션)</li>
 *   <li>결과는 원 미만 절사</li>
 *   <li>법정최고금리(연 20%) 상한 적용 — 대부업법상 초과 약정은 무효</li>
 * </ul>
 */
public class OverdueInterestCalculator {

    /**
     * @param overdueAmount 연체 원리금 (연체된 회차의 납부액)
     * @param contractRate  약정 연이율
     * @param extraRate     연체 가산율
     * @param overdueDays   연체 일수 (0 이상)
     */
    public BigDecimal calculate(BigDecimal overdueAmount, BigDecimal contractRate,
                                BigDecimal extraRate, long overdueDays) {
        if (overdueAmount == null || overdueAmount.signum() < 0) {
            throw new IllegalArgumentException("연체 원리금은 0 이상이어야 합니다.");
        }
        if (overdueDays < 0) {
            throw new IllegalArgumentException("연체 일수는 0 이상이어야 합니다.");
        }
        if (overdueDays == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal overdueRate = contractRate.add(extraRate).min(MoneyPolicy.MAX_LEGAL_RATE);
        BigDecimal daily = overdueAmount.multiply(overdueRate)
                .multiply(BigDecimal.valueOf(overdueDays))
                .divide(MoneyPolicy.DAYS_PER_YEAR, MoneyPolicy.CALC_SCALE, MoneyPolicy.CALC_ROUND);
        return MoneyPolicy.toWon(daily);
    }
}
