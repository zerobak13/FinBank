package com.finbank.backend.loan.harness;

import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.domain.calculator.RepaymentScheduleGenerator;
import com.finbank.backend.loan.domain.calculator.ScheduleLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.finbank.backend.loan.harness.HarnessAssertions.assertNoViolations;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 개별 케이스 파일 × 불변식 — 매트릭스가 덮지 못하는 "사유가 있는 입력"을 검사한다.
 *
 * <ul>
 *   <li>{@code schedule-cases.csv} — 사람이 관리. {@code manual}(경계값), {@code regression}(실제로 버그를 잡은 입력)</li>
 *   <li>{@code llm-cases.csv} — 검증 에이전트가 끝에 추가만 한다. {@code llm}</li>
 * </ul>
 *
 * <p>{@code expect=ok}면 스케줄이 만들어지고 모든 불변식을 지켜야 한다.
 * {@code expect=reject}면 엔진이 입력을 거부해야 한다(예: 반올림 누적으로 잔액이 음수가 되는 조합).
 * 거부 사유도 본다 — 입력 계약(INP-01~04) 밖이면 어떤 {@link IllegalArgumentException}이든 정당한 거부이고,
 * 계약 안이면 '잔액 음수' 사유만 정당하다({@link RandomExplorationTest}와 같은 기준, verify-2.md 4번).
 * 판정 기준은 {@link LoanInvariant}에 있으므로 케이스를 아무리 추가해도 기준은 느슨해지지 않는다.
 * 파일 형식은 {@link CaseFileFormatTest}가 따로 검사한다.</p>
 */
class ScheduleCaseFileTest {

    @ParameterizedTest(name = "[{0}] {1} P={2} r={3} n={4} 실행={5} 기대={6} ({7}) {8}")
    @CsvFileSource(resources = {"/harness/schedule-cases.csv", "/harness/llm-cases.csv"}, numLinesToSkip = 1)
    void invariants(String id, RepaymentType type, BigDecimal principal, BigDecimal rate,
                    int termMonths, LocalDate executedAt, String expect, String source, String note) {
        RepaymentScheduleGenerator generator = HarnessGenerators.of(type);
        String label = id + " (" + source + ")";

        if ("reject".equals(expect)) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> generator.generate(principal, rate, termMonths, executedAt),
                    label + ": 거부되어야 하는 입력으로 스케줄이 만들어졌다");
            // 계약 안의 입력이 '잔액 음수'가 아닌 사유로 거부됐다면 엉뚱한 이유로 통과한 것이다
            if (InputContract.violations(principal, rate, termMonths, executedAt).isEmpty()) {
                String reason = rejected.getMessage();
                assertTrue(reason != null && reason.contains("잔액이 음수"),
                        label + ": 계약 안의 입력인데 '잔액 음수'가 아닌 사유로 거부됐다 — " + reason);
            }
            return;
        }
        if (!"ok".equals(expect)) {
            fail(label + ": expect는 ok 또는 reject만 쓸 수 있다 — " + expect);
        }

        List<ScheduleLine> lines;
        try {
            lines = generator.generate(principal, rate, termMonths, executedAt);
        } catch (IllegalArgumentException e) {
            fail(label + ": 정상이어야 하는 입력이 거부됐다 — " + e.getMessage());
            return;
        }
        assertNoViolations(label, ScheduleInvariants.check(type, principal, rate, termMonths, executedAt, lines));
    }
}
