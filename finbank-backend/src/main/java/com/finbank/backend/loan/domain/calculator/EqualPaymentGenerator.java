package com.finbank.backend.loan.domain.calculator;

import com.finbank.backend.common.MoneyPolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 원리금균등 상환 — 매월 동일 금액 A를 갚는다.
 *
 * <pre>
 * 월이율  r = 연이율 / 12                       (scale 10, HALF_UP)
 * 월상환금 A = P·r(1+r)ⁿ / ((1+r)ⁿ − 1)          (최초 1회만 원 단위 반올림 후 고정)
 * 매회:   이자 = 직전잔액 × r (원 미만 절사) / 원금 = A − 이자
 * 마지막 회차: 원금 = 잔여 잔액 전액 (끝수 조정 — A와 ±수 원 차이가 정상)
 * </pre>
 *
 * <p>경계: r = 0이면 공식의 분모가 0이므로, 원금 P/n 균등 분할로 별도 처리한다.</p>
 */
public class EqualPaymentGenerator implements RepaymentScheduleGenerator {

    @Override
    public List<ScheduleLine> generate(BigDecimal principal, BigDecimal annualRate,
                                       int termMonths, LocalDate executedAt) {
        validate(principal, annualRate, termMonths);

        BigDecimal r = annualRate.divide(BigDecimal.valueOf(12), MoneyPolicy.CALC_SCALE, MoneyPolicy.CALC_ROUND);
        BigDecimal payment = monthlyPayment(principal, r, termMonths);

        List<ScheduleLine> lines = new ArrayList<>(termMonths);
        BigDecimal balance = principal;

        for (int i = 1; i < termMonths; i++) {
            BigDecimal interest = MoneyPolicy.toWon(balance.multiply(r));
            BigDecimal principalPortion = payment.subtract(interest);
            balance = balance.subtract(principalPortion);
            lines.add(new ScheduleLine(i, executedAt.plusMonths(i), principalPortion, interest, payment, balance));
        }

        // 끝수 조정: 마지막 회차는 잔여 원금 전액을 흡수한다.
        BigDecimal lastInterest = MoneyPolicy.toWon(balance.multiply(r));
        lines.add(new ScheduleLine(termMonths, executedAt.plusMonths(termMonths),
                balance, lastInterest, balance.add(lastInterest), BigDecimal.ZERO));
        return lines;
    }

    private BigDecimal monthlyPayment(BigDecimal principal, BigDecimal monthlyRate, int termMonths) {
        if (monthlyRate.signum() == 0) {
            // 무이자: 원금 균등 분할 (끝수는 마지막 회차가 흡수)
            return principal.divide(BigDecimal.valueOf(termMonths), 0, MoneyPolicy.INTEREST_ROUND);
        }
        BigDecimal onePlusRPowN = BigDecimal.ONE.add(monthlyRate).pow(termMonths);
        BigDecimal numerator = principal.multiply(monthlyRate).multiply(onePlusRPowN);
        BigDecimal denominator = onePlusRPowN.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 0, MoneyPolicy.PAYMENT_ROUND);
    }
}
