package com.finbank.backend.loan.harness;

import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.domain.calculator.ScheduleLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.finbank.backend.loan.harness.HarnessAssertions.assertNoViolations;

/**
 * 개별 케이스 파일 × 불변식 — 매트릭스가 덮지 못하는 "사유가 있는 입력"을 검사한다.
 *
 * <p>케이스 파일: {@code src/test/resources/harness/schedule-cases.csv}</p>
 * <ul>
 *   <li>{@code source=manual} — 사람이 고른 경계값</li>
 *   <li>{@code source=regression} — 실제로 버그를 잡은 입력 (고친 뒤에도 영구 보존)</li>
 *   <li>{@code source=llm} — 검증 에이전트가 코드를 읽고 생성한 엣지케이스</li>
 * </ul>
 *
 * <p>케이스 파일에는 <b>행 추가만</b> 한다. 판정 기준(불변식)은 여기가 아니라
 * {@link LoanInvariant}에 있으므로, 케이스를 아무리 추가해도 기준이 느슨해지지 않는다.</p>
 */
class ScheduleCaseFileTest {

    @ParameterizedTest(name = "[{0}] {1} P={2} r={3} n={4} 실행={5} ({6}) {7}")
    @CsvFileSource(resources = "/harness/schedule-cases.csv", numLinesToSkip = 1)
    void invariants(String id, RepaymentType type, BigDecimal principal, BigDecimal rate,
                    int termMonths, LocalDate executedAt, String source, String note) {
        List<ScheduleLine> lines = HarnessGenerators.of(type).generate(principal, rate, termMonths, executedAt);

        assertNoViolations(id + " (" + source + ")",
                ScheduleInvariants.check(type, principal, rate, termMonths, executedAt, lines));
    }
}
