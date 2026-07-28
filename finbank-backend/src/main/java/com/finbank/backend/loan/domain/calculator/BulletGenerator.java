package com.finbank.backend.loan.domain.calculator;

import com.finbank.backend.common.MoneyPolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 만기일시 상환 — 매월 이자만 내고, 만기에 원금 전액을 상환한다.
 *
 * <pre>
 * 1 ~ n-1회차: 이자 = P × r (원 미만 절사), 원금 0
 * n회차:       원금 P + 이자
 * </pre>
 */
public class BulletGenerator implements RepaymentScheduleGenerator {

    @Override
    public List<ScheduleLine> generate(BigDecimal principal, BigDecimal annualRate,
                                       int termMonths, LocalDate executedAt) {
        validate(principal, annualRate, termMonths);

        BigDecimal r = annualRate.divide(BigDecimal.valueOf(12), MoneyPolicy.CALC_SCALE, MoneyPolicy.CALC_ROUND);
        BigDecimal monthlyInterest = MoneyPolicy.toWon(principal.multiply(r));

        List<ScheduleLine> lines = new ArrayList<>(termMonths);
        for (int i = 1; i < termMonths; i++) {
            lines.add(new ScheduleLine(i, executedAt.plusMonths(i),
                    BigDecimal.ZERO, monthlyInterest, monthlyInterest, principal));
        }
        lines.add(new ScheduleLine(termMonths, executedAt.plusMonths(termMonths),
                principal, monthlyInterest, principal.add(monthlyInterest), BigDecimal.ZERO));
        return lines;
    }
}
