package com.finbank.backend.loan.harness;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 판정 결과를 JUnit 실패로 바꾸는 얇은 어댑터.
 *
 * <p>실패 메시지가 곧 실패 리포트다: 케이스 식별자 + 위반 목록(불변식 ID · 실제 값 · 규칙 설명).
 * 위반이 많으면 앞 20건만 보여준다 — 보통 첫 몇 줄이 원인이고 나머지는 연쇄 효과다.</p>
 */
public final class HarnessAssertions {

    private static final int MAX_LINES = 20;

    private HarnessAssertions() {
    }

    public static void assertNoViolations(String caseLabel, List<Violation> violations) {
        if (violations.isEmpty()) {
            return;
        }
        String body = violations.stream()
                .limit(MAX_LINES)
                .map(v -> "  - " + v)
                .collect(Collectors.joining("\n"));
        String more = violations.size() > MAX_LINES
                ? "\n  ... 외 " + (violations.size() - MAX_LINES) + "건"
                : "";
        fail("[" + caseLabel + "] 불변식 위반 " + violations.size() + "건\n" + body + more);
    }
}
