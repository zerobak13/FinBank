package com.finbank.backend.loan.domain.calculator;

import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.harness.HarnessGenerators;
import com.finbank.backend.loan.harness.ScheduleInvariants;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static com.finbank.backend.loan.harness.HarnessAssertions.assertNoViolations;

/**
 * 상환 스케줄 생성기 — 조합 매트릭스 × 불변식.
 *
 * <p>상환방식 3종 × 금액 4종 × 금리 4종(0% 포함) × 기간 5종 = <b>240개 조합</b>.
 * 매트릭스는 차원만 코드로 정의하고 조합은 자동 생성한다(차원 하나를 늘려도 한 줄 수정).
 * 판정은 {@link ScheduleInvariants}에 모인 불변식 카탈로그로 한다 — 매트릭스 밖의 개별 케이스는
 * {@code ScheduleCaseFileTest}(CSV)가 같은 판정기로 검사한다.</p>
 */
class ScheduleGeneratorPropertyTest {

    private static final LocalDate EXECUTED = LocalDate.of(2026, 1, 15);

    private static final List<String> AMOUNTS = List.of("1000000", "3333333", "10000000", "50000000");
    private static final List<String> RATES = List.of("0", "0.055", "0.068", "0.199");
    private static final List<Integer> TERMS = List.of(1, 6, 12, 36, 60);

    static Stream<Arguments> allCombinations() {
        return Arrays.stream(RepaymentType.values()).flatMap(type ->
                AMOUNTS.stream().flatMap(p ->
                        RATES.stream().flatMap(r ->
                                TERMS.stream().map(n -> Arguments.of(
                                        type, new BigDecimal(p), new BigDecimal(r), n)))));
    }

    @ParameterizedTest(name = "[{index}] {0} P={1} r={2} n={3}")
    @MethodSource("allCombinations")
    void invariants(RepaymentType type, BigDecimal principal, BigDecimal rate, int termMonths) {
        List<ScheduleLine> lines = HarnessGenerators.of(type).generate(principal, rate, termMonths, EXECUTED);

        assertNoViolations(type + " P=" + principal + " r=" + rate + " n=" + termMonths,
                ScheduleInvariants.check(type, principal, rate, termMonths, EXECUTED, lines));
    }
}
