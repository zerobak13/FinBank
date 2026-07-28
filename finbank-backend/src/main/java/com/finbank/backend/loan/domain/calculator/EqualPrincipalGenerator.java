package com.finbank.backend.loan.domain.calculator;

import com.finbank.backend.common.MoneyPolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 원금균등 상환 — 매월 동일 원금 + 잔액 기준 이자. 상환액이 매월 감소한다.
 *
 * <pre>
 * 회차 원금 = P / n (원 미만 절사, 끝수는 마지막 회차가 흡수)
 * 회차 이자 = 직전잔액 × r (원 미만 절사)
 * </pre>
 */
public class EqualPrincipalGenerator implements RepaymentScheduleGenerator {

    @Override
    public List<ScheduleLine> generate(BigDecimal principal, BigDecimal annualRate,
                                       int termMonths, LocalDate executedAt) {
        validate(principal, annualRate, termMonths);

        BigDecimal r = annualRate.divide(BigDecimal.valueOf(12), MoneyPolicy.CALC_SCALE, MoneyPolicy.CALC_ROUND);
        BigDecimal basePrincipal = principal.divide(BigDecimal.valueOf(termMonths), 0, MoneyPolicy.INTEREST_ROUND);

        List<ScheduleLine> lines = new ArrayList<>(termMonths);
        BigDecimal balance = principal;

        for (int i = 1; i < termMonths; i++) {
            BigDecimal interest = MoneyPolicy.toWon(balance.multiply(r));
            balance = balance.subtract(basePrincipal);
            lines.add(new ScheduleLine(i, executedAt.plusMonths(i),
                    basePrincipal, interest, basePrincipal.add(interest), balance));
        }

        // 끝수 조정: 마지막 회차 = 잔여 원금 전액
        BigDecimal lastInterest = MoneyPolicy.toWon(balance.multiply(r));
        lines.add(new ScheduleLine(termMonths, executedAt.plusMonths(termMonths),
                balance, lastInterest, balance.add(lastInterest), BigDecimal.ZERO));
        return lines;
    }
}
