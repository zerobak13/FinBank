package com.finbank.backend.loan.harness;

import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.domain.calculator.ScheduleLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 무작위 탐색 — 사람이 정한 매트릭스가 덮지 못하는 영역을 고정 시드로 훑는다.
 *
 * <p>이 탐색(시드 42, 20,000건)이 원리금균등의 "반올림 누적 → 만기 전 잔액 음수" 버그를 찾았다(588건 위반).
 * 수정 후에는 같은 입력이 전부 거부되어야 하고, 위반은 0건, 거부 사유는 '잔액 음수'뿐이어야 한다.
 * 시드가 고정이라 누가 돌려도 같은 입력·같은 결과가 나온다 — README 수치의 재현 근거.</p>
 */
class RandomExplorationTest {

    private static final long SEED = 42L;
    private static final int CASES = 20_000;

    private static final long[] PRINCIPALS = {1, 2, 7, 11, 59, 99, 100, 101, 999, 1000, 12345, 999999,
            1000001, 3333333, 99999999, 999999999999L};
    private static final String[] RATES = {"0", "0.0000001", "0.01", "0.055", "0.068", "0.199", "0.2", "0.5", "1", "2.5"};
    private static final LocalDate[] DATES = {LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 31),
            LocalDate.of(2028, 2, 29), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 12, 31)};

    @Test
    void randomInputsNeverViolateInvariants() {
        Random rnd = new Random(SEED);
        List<String> violations = new ArrayList<>();
        List<String> unexpectedRejections = new ArrayList<>();
        int rejected = 0;

        for (int i = 0; i < CASES; i++) {
            // 난수 소비 순서가 바뀌면 다른 입력이 나온다 — 재현을 위해 순서를 고정한다
            RepaymentType type = RepaymentType.values()[rnd.nextInt(3)];
            BigDecimal principal = BigDecimal.valueOf(rnd.nextBoolean()
                    ? PRINCIPALS[rnd.nextInt(PRINCIPALS.length)]
                    : 1 + (long) (rnd.nextDouble() * 1e9));
            BigDecimal rate = new BigDecimal(rnd.nextBoolean()
                    ? RATES[rnd.nextInt(RATES.length)]
                    : String.format(Locale.ROOT, "%.4f", rnd.nextDouble() * 0.3));
            int term = 1 + rnd.nextInt(rnd.nextBoolean() ? 12 : 480);
            LocalDate executedAt = DATES[rnd.nextInt(DATES.length)];
            String label = type + " P=" + principal + " r=" + rate + " n=" + term + " 실행=" + executedAt;

            try {
                List<ScheduleLine> lines = HarnessGenerators.of(type).generate(principal, rate, term, executedAt);
                List<Violation> found = ScheduleInvariants.check(type, principal, rate, term, executedAt, lines);
                if (!found.isEmpty()) {
                    violations.add(label + " → " + found.get(0));
                }
            } catch (IllegalArgumentException e) {
                rejected++;
                if (e.getMessage() == null || !e.getMessage().contains("잔액이 음수")) {
                    unexpectedRejections.add(label + " → " + e.getMessage());
                }
            }
        }

        System.out.printf("[무작위 탐색] 시드 %d, %,d건 — 위반 %d건, 거부 %d건%n",
                SEED, CASES, violations.size(), rejected);
        assertTrue(violations.isEmpty(), () -> "불변식 위반 " + violations.size() + "건\n  - "
                + String.join("\n  - ", violations.subList(0, Math.min(20, violations.size()))));
        assertTrue(unexpectedRejections.isEmpty(), () -> "예상 밖 사유로 거부 " + unexpectedRejections.size() + "건\n  - "
                + String.join("\n  - ", unexpectedRejections.subList(0, Math.min(20, unexpectedRejections.size()))));
    }
}
