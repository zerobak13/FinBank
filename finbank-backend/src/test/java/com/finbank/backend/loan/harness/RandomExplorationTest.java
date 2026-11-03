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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 무작위 탐색 — 사람이 정한 매트릭스가 덮지 못하는 영역을 고정 시드로 훑는다.
 *
 * <p>이 탐색(시드 42, 20,000건)이 원리금균등의 "반올림 누적 → 만기 전 잔액 음수" 버그를 처음 찾았다
 * (PR #20 당시 588건 위반). 시드가 고정이라 누가 돌려도 같은 입력이 나온다.</p>
 *
 * <p>판정:</p>
 * <ul>
 *   <li>입력 계약(INP-01~04) 밖의 입력 → 반드시 거부되어야 한다</li>
 *   <li>계약 안의 입력 → 불변식을 모두 지키거나, '잔액 음수' 사유로만 거부될 수 있다</li>
 * </ul>
 * <p>입력 계약 도입 후에는 금리 상한(20%)을 넘는 입력이 먼저 거부되므로, '잔액 음수' 거부 수는 588보다 줄어든다.</p>
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
        List<String> acceptedOutsideContract = new ArrayList<>();
        List<String> unexpectedRejections = new ArrayList<>();
        int rejectedByContract = 0;
        int rejectedByNegativeBalance = 0;

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
            List<LoanInvariant> contract = InputContract.violations(principal, rate, term, executedAt);

            try {
                List<ScheduleLine> lines = HarnessGenerators.of(type).generate(principal, rate, term, executedAt);
                if (!contract.isEmpty()) {
                    acceptedOutsideContract.add(label + " → " + ids(contract) + " 위반인데 스케줄이 만들어짐");
                    continue;
                }
                List<Violation> found = ScheduleInvariants.check(type, principal, rate, term, executedAt, lines);
                if (!found.isEmpty()) {
                    violations.add(label + " → " + found.get(0));
                }
            } catch (IllegalArgumentException e) {
                if (!contract.isEmpty()) {
                    rejectedByContract++;
                } else if (e.getMessage() != null && e.getMessage().contains("잔액이 음수")) {
                    rejectedByNegativeBalance++;
                } else {
                    unexpectedRejections.add(label + " → " + e.getMessage());
                }
            }
        }

        System.out.printf("[무작위 탐색] 시드 %d, %,d건 — 불변식 위반 %d건 / 거부: 입력 계약 %d건, 잔액 음수 %d건 / 계약 밖인데 통과 %d건%n",
                SEED, CASES, violations.size(), rejectedByContract, rejectedByNegativeBalance, acceptedOutsideContract.size());
        assertTrue(violations.isEmpty(), () -> report("불변식 위반", violations));
        assertTrue(acceptedOutsideContract.isEmpty(), () -> report("입력 계약 밖인데 거부되지 않음", acceptedOutsideContract));
        assertTrue(unexpectedRejections.isEmpty(), () -> report("계약 안의 입력이 예상 밖 사유로 거부됨", unexpectedRejections));
    }

    private static String ids(List<LoanInvariant> rules) {
        return rules.stream().map(LoanInvariant::id).collect(Collectors.joining(","));
    }

    private static String report(String title, List<String> items) {
        return title + " " + items.size() + "건\n  - " + String.join("\n  - ", items.subList(0, Math.min(20, items.size())));
    }
}
