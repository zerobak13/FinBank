package com.finbank.backend.loan.domain.calculator;

import com.finbank.backend.common.MoneyPolicy;

import java.math.BigDecimal;

/**
 * 경과이자 계산기 (순수 자바) — 회차 사이에 상환할 때 "쓴 날짜만큼"의 이자.
 *
 * <pre>
 * 경과이자 = 잔여원금 × 약정 연이율 × 경과일수 / 365
 * </pre>
 *
 * <p>중도상환은 납기일이 아닌 임의의 날짜에 일어나므로, 스케줄에 적힌 회차 이자를 쓸 수 없다.
 * 마지막 납부 기준일(직전 납부 회차의 납기일, 없으면 실행일)부터 상환일까지를 일할 계산한다.</p>
 *
 * <p>연체이자와 수식 모양은 같지만 <b>요율이 다르다</b>(경과이자는 약정금리, 연체이자는
 * 약정+가산 및 법정최고 상한). 정책이 다른 계산은 클래스를 나눠 각자 검증한다.</p>
 */
public class AccruedInterestCalculator {

    /**
     * @param remainingPrincipal 잔여 원금 (0 이상)
     * @param annualRate         약정 연이율
     * @param elapsedDays        기준일 ~ 상환일 경과일수 (0 이상)
     */
    public BigDecimal calculate(BigDecimal remainingPrincipal, BigDecimal annualRate, long elapsedDays) {
        if (remainingPrincipal == null || remainingPrincipal.signum() < 0) {
            throw new IllegalArgumentException("잔여 원금은 0 이상이어야 합니다.");
        }
        if (annualRate == null || annualRate.signum() < 0) {
            throw new IllegalArgumentException("연이율은 0 이상이어야 합니다.");
        }
        if (elapsedDays < 0) {
            throw new IllegalArgumentException("경과일수는 0 이상이어야 합니다.");
        }
        if (elapsedDays == 0 || remainingPrincipal.signum() == 0 || annualRate.signum() == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal interest = remainingPrincipal.multiply(annualRate)
                .multiply(BigDecimal.valueOf(elapsedDays))
                .divide(MoneyPolicy.DAYS_PER_YEAR, MoneyPolicy.CALC_SCALE, MoneyPolicy.CALC_ROUND);
        return MoneyPolicy.toWon(interest);
    }
}
