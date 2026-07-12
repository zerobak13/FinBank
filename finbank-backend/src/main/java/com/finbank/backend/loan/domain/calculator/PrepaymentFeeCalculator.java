package com.finbank.backend.loan.domain.calculator;

import com.finbank.backend.common.MoneyPolicy;

import java.math.BigDecimal;

/**
 * 중도상환수수료 계산기 — 국내 은행의 <b>잔존기간 체감 방식</b> (순수 자바).
 *
 * <pre>
 * 수수료 = 중도상환 원금 × 수수료율 × 잔존일수 / 총 대출일수
 * </pre>
 *
 * <p>만기에 가까울수록 수수료가 줄어들어 만기일 상환 시 0이 된다.
 * 결과는 원 미만 절사.</p>
 */
public class PrepaymentFeeCalculator {

    /**
     * @param prepayPrincipal 중도상환 원금
     * @param feeRate         중도상환수수료율 (예: 0.015 = 1.5%)
     * @param remainingDays   상환일 ~ 만기일 잔존일수 (0 이상)
     * @param totalDays       실행일 ~ 만기일 총 대출일수 (1 이상)
     */
    public BigDecimal calculate(BigDecimal prepayPrincipal, BigDecimal feeRate,
                                long remainingDays, long totalDays) {
        if (prepayPrincipal == null || prepayPrincipal.signum() < 0) {
            throw new IllegalArgumentException("중도상환 원금은 0 이상이어야 합니다.");
        }
        if (totalDays < 1) {
            throw new IllegalArgumentException("총 대출일수는 1 이상이어야 합니다.");
        }
        if (remainingDays < 0 || remainingDays > totalDays) {
            throw new IllegalArgumentException("잔존일수는 0 이상, 총 대출일수 이하여야 합니다.");
        }
        if (remainingDays == 0) {
            return BigDecimal.ZERO; // 만기일 상환 — 수수료 없음
        }

        BigDecimal fee = prepayPrincipal.multiply(feeRate)
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(totalDays), MoneyPolicy.CALC_SCALE, MoneyPolicy.CALC_ROUND);
        return MoneyPolicy.toWon(fee);
    }
}
