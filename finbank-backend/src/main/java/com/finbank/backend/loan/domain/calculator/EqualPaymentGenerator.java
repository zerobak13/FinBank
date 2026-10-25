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
 *
 * <p><b>지원하지 않는 입력 — 반올림 누적으로 잔액이 음수가 되는 조합.</b>
 * 매 회차 이자는 절사되고(실제보다 최대 1원 적게) 월 납입액은 반올림되므로(최대 0.5원 많게),
 * 원금 상환분이 매달 조금씩 과다해지고 그 차이가 이자율로 복리 누적된다. 원금이 아주 작거나
 * (예: 59원·20%·11개월) 고금리·초장기(예: 100만원·19.9%·387개월)면 만기 전에 잔액이 음수가 되어
 * 마지막 회차 원금이 음수(= 고객에게 환급)가 된다. 이런 스케줄을 조용히 만들지 않고 거부한다.
 * 현재 상품(최대 60개월, 최소 100만원)에서는 발생하지 않으며, 불변식 하네스의 무작위 탐색으로 발견했다.
 * 장기 상품(주택담보 등)을 추가할 때는 잔여 회차 재산정 방식으로 재설계가 필요하다.</p>
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
            if (balance.signum() < 0) {
                throw new IllegalArgumentException(
                        "원리금균등 스케줄을 만들 수 없는 조건입니다 (원금 " + principal.toPlainString()
                                + ", 연이율 " + annualRate.toPlainString() + ", " + termMonths + "개월): "
                                + "반올림 누적으로 " + i + "회차에 잔액이 음수가 됩니다.");
            }
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
