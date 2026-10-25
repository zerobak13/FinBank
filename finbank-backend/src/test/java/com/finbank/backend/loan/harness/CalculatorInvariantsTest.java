package com.finbank.backend.loan.harness;

import com.finbank.backend.loan.domain.calculator.AccruedInterestCalculator;
import com.finbank.backend.loan.domain.calculator.OverdueInterestCalculator;
import com.finbank.backend.loan.domain.calculator.PrepaymentFeeCalculator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static com.finbank.backend.loan.harness.HarnessAssertions.assertNoViolations;

/**
 * 일할 계산기 3종 — 조합 매트릭스 × 불변식.
 * 각 조합마다 일수를 0부터 끝까지 훑으므로(단조성 판정), 실제 계산 횟수는 조합 수 × 일수다.
 */
class CalculatorInvariantsTest {

    private final PrepaymentFeeCalculator prepaymentFee = new PrepaymentFeeCalculator();
    private final OverdueInterestCalculator overdueInterest = new OverdueInterestCalculator();
    private final AccruedInterestCalculator accruedInterest = new AccruedInterestCalculator();

    // ── 중도상환수수료: 원금 4 × 수수료율 4 × 총 대출일수 5 = 80조합 ──────────
    static Stream<Arguments> prepaymentCases() {
        return combine(List.of("1", "3333333", "10000000", "500000000"),
                List.of("0", "0.012", "0.015", "0.02"),
                List.of("1", "31", "365", "366", "1826"));
    }

    @ParameterizedTest(name = "[{index}] 원금={0} 수수료율={1} 총일수={2}")
    @MethodSource("prepaymentCases")
    void prepaymentFee(String principal, String feeRate, String totalDays) {
        assertNoViolations("중도상환수수료 P=" + principal + " fee=" + feeRate + " total=" + totalDays,
                CalculatorInvariants.prepaymentFee(prepaymentFee,
                        new BigDecimal(principal), new BigDecimal(feeRate), Long.parseLong(totalDays)));
    }

    // ── 연체이자: 원리금 3 × 약정금리 4 × 가산율 4 = 48조합, 0~400일 ─────────
    static Stream<Arguments> overdueCases() {
        return combine(List.of("1", "860205", "10000000"),
                List.of("0", "0.055", "0.15", "0.199"),
                List.of("0", "0.03", "0.05", "0.10"));
    }

    @ParameterizedTest(name = "[{index}] 원리금={0} 약정={1} 가산={2}")
    @MethodSource("overdueCases")
    void overdueInterest(String amount, String contractRate, String extraRate) {
        assertNoViolations("연체이자 A=" + amount + " c=" + contractRate + " e=" + extraRate,
                CalculatorInvariants.overdueInterest(overdueInterest, new BigDecimal(amount),
                        new BigDecimal(contractRate), new BigDecimal(extraRate), 400));
    }

    // ── 경과이자: 잔여원금 5(0 포함) × 금리 3(0 포함) = 15조합, 0~400일 ──────
    static Stream<Arguments> accruedCases() {
        return Stream.of("0", "1", "3333333", "10000000", "1000000000").flatMap(p ->
                Stream.of("0", "0.055", "0.199").map(r -> Arguments.of(p, r)));
    }

    @ParameterizedTest(name = "[{index}] 잔여원금={0} 금리={1}")
    @MethodSource("accruedCases")
    void accruedInterest(String remainingPrincipal, String annualRate) {
        assertNoViolations("경과이자 P=" + remainingPrincipal + " r=" + annualRate,
                CalculatorInvariants.accruedInterest(accruedInterest,
                        new BigDecimal(remainingPrincipal), new BigDecimal(annualRate), 400));
    }

    private static Stream<Arguments> combine(List<String> a, List<String> b, List<String> c) {
        return a.stream().flatMap(x -> b.stream().flatMap(y -> c.stream().map(z -> Arguments.of(x, y, z))));
    }
}
