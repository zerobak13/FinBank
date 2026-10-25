package com.finbank.backend.loan.harness;

import com.finbank.backend.common.MoneyPolicy;
import com.finbank.backend.loan.domain.calculator.AccruedInterestCalculator;
import com.finbank.backend.loan.domain.calculator.OverdueInterestCalculator;
import com.finbank.backend.loan.domain.calculator.PrepaymentFeeCalculator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

import static com.finbank.backend.loan.harness.ScheduleInvariants.checkWon;
import static com.finbank.backend.loan.harness.ScheduleInvariants.plain;

/**
 * 일할 계산기(중도상환수수료·연체이자·경과이자) 불변식 판정기 (순수 자바).
 *
 * <p>"일수가 늘면 금액이 줄지 않는다" 같은 단조성은 한 점만 봐서는 판정할 수 없다.
 * 그래서 입력 하나(원금·금리 조합)에 대해 일수를 0부터 끝까지 <b>훑으며</b> 판정한다.</p>
 */
public final class CalculatorInvariants {

    private static final MathContext MC = MathContext.DECIMAL128;
    /** 중간 계산(scale 10) 반올림 오차 허용폭 */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.000001");

    private CalculatorInvariants() {
    }

    /** 중도상환수수료 — 잔존일수 0 ~ totalDays 전 구간 */
    public static List<Violation> prepaymentFee(PrepaymentFeeCalculator calculator, BigDecimal principal,
                                                BigDecimal feeRate, long totalDays) {
        List<Violation> v = new ArrayList<>();
        BigDecimal cap = principal.multiply(feeRate);
        BigDecimal prev = null;
        for (long d = 0; d <= totalDays; d++) {
            BigDecimal fee = calculator.calculate(principal, feeRate, d, totalDays);
            String at = "잔존 " + d + "/" + totalDays + "일: ";
            checkWon(v, at + "수수료", fee);
            if (d == 0 && fee.signum() != 0) {
                v.add(new Violation(LoanInvariant.PRE_01, at + "만기일 상환인데 수수료 " + plain(fee)));
            }
            if (prev != null && fee.compareTo(prev) < 0) {
                v.add(new Violation(LoanInvariant.PRE_02,
                        at + "수수료 " + plain(fee) + " < 잔존 " + (d - 1) + "일 수수료 " + plain(prev)));
            }
            if (fee.compareTo(cap) > 0) {
                v.add(new Violation(LoanInvariant.PRE_03,
                        at + "수수료 " + plain(fee) + " > 원금×수수료율 " + plain(cap)));
            }
            prev = fee;
        }
        return v;
    }

    /** 연체이자 — 연체일수 0 ~ maxDays 전 구간 */
    public static List<Violation> overdueInterest(OverdueInterestCalculator calculator, BigDecimal amount,
                                                  BigDecimal contractRate, BigDecimal extraRate, long maxDays) {
        List<Violation> v = new ArrayList<>();
        BigDecimal appliedRate = contractRate.add(extraRate).min(MoneyPolicy.MAX_LEGAL_RATE);
        BigDecimal prev = null;
        for (long d = 0; d <= maxDays; d++) {
            BigDecimal interest = calculator.calculate(amount, contractRate, extraRate, d);
            String at = "연체 " + d + "일: ";
            checkWon(v, at + "연체이자", interest);
            if (d == 0 && interest.signum() != 0) {
                v.add(new Violation(LoanInvariant.OVD_01, at + "연체이자 " + plain(interest)));
            }
            if (prev != null && interest.compareTo(prev) < 0) {
                v.add(new Violation(LoanInvariant.OVD_02,
                        at + "연체이자 " + plain(interest) + " < 전일 " + plain(prev)));
            }
            BigDecimal legalCap = dailyAmount(amount, MoneyPolicy.MAX_LEGAL_RATE, d);
            if (interest.compareTo(legalCap.add(TOLERANCE)) > 0) {
                v.add(new Violation(LoanInvariant.OVD_03,
                        at + "연체이자 " + plain(interest) + " > 법정최고금리 상한 " + scale4(legalCap)));
            }
            BigDecimal exact = dailyAmount(amount, appliedRate, d);
            if (outsideFloorBand(interest, exact)) {
                v.add(new Violation(LoanInvariant.OVD_04,
                        at + "연체이자 " + plain(interest) + " — 기대값 " + scale4(exact)));
            }
            prev = interest;
        }
        return v;
    }

    /** 경과이자 — 경과일수 0 ~ maxDays 전 구간 */
    public static List<Violation> accruedInterest(AccruedInterestCalculator calculator, BigDecimal remainingPrincipal,
                                                  BigDecimal annualRate, long maxDays) {
        List<Violation> v = new ArrayList<>();
        BigDecimal prev = null;
        for (long d = 0; d <= maxDays; d++) {
            BigDecimal interest = calculator.calculate(remainingPrincipal, annualRate, d);
            String at = "경과 " + d + "일: ";
            checkWon(v, at + "경과이자", interest);
            boolean mustBeZero = d == 0 || remainingPrincipal.signum() == 0 || annualRate.signum() == 0;
            if (mustBeZero && interest.signum() != 0) {
                v.add(new Violation(LoanInvariant.ACC_01, at + "경과이자 " + plain(interest)));
            }
            if (prev != null && interest.compareTo(prev) < 0) {
                v.add(new Violation(LoanInvariant.ACC_02,
                        at + "경과이자 " + plain(interest) + " < 전일 " + plain(prev)));
            }
            BigDecimal exact = dailyAmount(remainingPrincipal, annualRate, d);
            if (outsideFloorBand(interest, exact)) {
                v.add(new Violation(LoanInvariant.ACC_03,
                        at + "경과이자 " + plain(interest) + " — 기대값 " + scale4(exact)));
            }
            prev = interest;
        }
        return v;
    }

    /** 금액 × 연이율 × 일수 / 365 (정밀 계산) */
    private static BigDecimal dailyAmount(BigDecimal amount, BigDecimal annualRate, long days) {
        return amount.multiply(annualRate).multiply(BigDecimal.valueOf(days))
                .divide(MoneyPolicy.DAYS_PER_YEAR, MC);
    }

    /** 원 미만 절사 결과는 (exact − 1, exact] 안에 있어야 한다 */
    private static boolean outsideFloorBand(BigDecimal actual, BigDecimal exact) {
        return actual.compareTo(exact.add(TOLERANCE)) > 0
                || actual.compareTo(exact.subtract(BigDecimal.ONE).subtract(TOLERANCE)) <= 0;
    }

    private static String scale4(BigDecimal value) {
        return value.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
