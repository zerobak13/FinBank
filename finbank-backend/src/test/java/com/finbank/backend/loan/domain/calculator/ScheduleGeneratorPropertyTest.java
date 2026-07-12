package com.finbank.backend.loan.domain.calculator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상환 스케줄 생성기 — 속성(불변식) 테스트.
 *
 * <p>금액 4종 × 금리 4종(0% 포함) × 기간 5종 × 상환방식 3종 = <b>240개 조합</b>에 대해
 * "어떤 입력이 와도 반드시 성립해야 하는 성질"을 검증한다:</p>
 * <ul>
 *   <li>Σ(회차 원금) == 대출 원금 — 반올림으로 1원도 새거나 늘지 않는다</li>
 *   <li>마지막 회차 잔액 == 0 — 완납 시 끝수가 남지 않는다</li>
 *   <li>회차 수 == 기간, 이자 ≥ 0, 잔액 단조 감소(음수 불가)</li>
 * </ul>
 */
class ScheduleGeneratorPropertyTest {

    private static final LocalDate EXECUTED = LocalDate.of(2026, 1, 15);

    static Stream<Arguments> allCombinations() {
        List<RepaymentScheduleGenerator> generators = List.of(
                new EqualPaymentGenerator(), new EqualPrincipalGenerator(), new BulletGenerator());
        List<String> amounts = List.of("1000000", "3333333", "10000000", "50000000");
        List<String> rates = List.of("0", "0.055", "0.068", "0.199");
        List<Integer> terms = List.of(1, 6, 12, 36, 60);

        return generators.stream().flatMap(g ->
                amounts.stream().flatMap(p ->
                        rates.stream().flatMap(r ->
                                terms.stream().map(n -> Arguments.of(
                                        g.getClass().getSimpleName(), g,
                                        new BigDecimal(p), new BigDecimal(r), n)))));
    }

    @ParameterizedTest(name = "[{index}] {0} P={2} r={3} n={4}")
    @MethodSource("allCombinations")
    void invariants(String name, RepaymentScheduleGenerator generator,
                    BigDecimal principal, BigDecimal rate, int termMonths) {
        List<ScheduleLine> lines = generator.generate(principal, rate, termMonths, EXECUTED);

        // 회차 수 == 기간
        assertThat(lines).hasSize(termMonths);

        // Σ원금 == 대출 원금 (1원도 새지 않는다)
        BigDecimal principalSum = lines.stream()
                .map(ScheduleLine::principal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(principalSum).isEqualByComparingTo(principal);

        // 최종 잔액 == 0
        assertThat(lines.get(termMonths - 1).balanceAfter()).isEqualByComparingTo(BigDecimal.ZERO);

        // 회차별: 이자·원금 ≥ 0, total = 원금+이자, 잔액 단조 감소·음수 불가
        BigDecimal prevBalance = principal;
        for (ScheduleLine line : lines) {
            assertThat(line.interest().signum()).isGreaterThanOrEqualTo(0);
            assertThat(line.principal().signum()).isGreaterThanOrEqualTo(0);
            assertThat(line.total()).isEqualByComparingTo(line.principal().add(line.interest()));
            assertThat(line.balanceAfter().signum()).isGreaterThanOrEqualTo(0);
            assertThat(line.balanceAfter()).isLessThanOrEqualTo(prevBalance);
            prevBalance = line.balanceAfter();
        }
    }
}
