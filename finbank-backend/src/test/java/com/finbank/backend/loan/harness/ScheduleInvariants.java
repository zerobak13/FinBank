package com.finbank.backend.loan.harness;

import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.domain.calculator.ScheduleLine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 상환 스케줄 불변식 판정기 (순수 자바 — JUnit·Spring 무의존).
 *
 * <p>assert로 첫 실패에서 멈추지 않고 <b>위반을 전부 모아 반환</b>한다. 한 케이스가 여러 규칙을
 * 동시에 깨는 경우가 많고, 검증 에이전트가 "무엇이 깨졌는지"를 한 번에 리포트해야 하기 때문이다.
 * JUnit 의존이 없으므로 테스트 밖(에이전트 스크립트 등)에서도 같은 판정을 그대로 재사용할 수 있다.</p>
 */
public final class ScheduleInvariants {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    /**
     * 월이율 중간 계산(scale 10, HALF_UP)의 반올림 오차 허용폭.
     * 월이율 오차 ≤ 5e-11 이므로 이자 오차 ≤ 잔액 × 5e-11. 여유를 두어 잔액 × 1e-10을 허용한다.
     */
    private static final BigDecimal RATE_ROUNDING_TOLERANCE = new BigDecimal("1e-10");

    private ScheduleInvariants() {
    }

    public static List<Violation> check(RepaymentType type, BigDecimal principal, BigDecimal annualRate,
                                        int termMonths, LocalDate executedAt, List<ScheduleLine> lines) {
        List<Violation> v = new ArrayList<>();

        // SCH-01 회차 수·번호
        if (lines.size() != termMonths) {
            v.add(new Violation(LoanInvariant.SCH_01,
                    "회차 수 " + lines.size() + " ≠ 기간 " + termMonths));
        }
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).installmentNo() != i + 1) {
                v.add(new Violation(LoanInvariant.SCH_01,
                        (i + 1) + "번째 줄의 회차 번호가 " + lines.get(i).installmentNo()));
            }
        }
        if (lines.isEmpty()) {
            return v;
        }

        // SCH-02 원금 합계
        BigDecimal principalSum = lines.stream()
                .map(ScheduleLine::principal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (principalSum.compareTo(principal) != 0) {
            v.add(new Violation(LoanInvariant.SCH_02,
                    "원금 합계 " + plain(principalSum) + " ≠ 대출 원금 " + plain(principal)));
        }

        // SCH-03 최종 잔액
        BigDecimal lastBalance = lines.get(lines.size() - 1).balanceAfter();
        if (lastBalance.signum() != 0) {
            v.add(new Violation(LoanInvariant.SCH_03, "마지막 회차 잔액 " + plain(lastBalance)));
        }

        // 회차별 규칙
        BigDecimal monthlyRateExact = annualRate.divide(TWELVE, MC);
        BigDecimal prevBalance = principal;
        for (ScheduleLine line : lines) {
            String at = line.installmentNo() + "회차: ";

            // MONEY-01 확정 금액 형식
            checkWon(v, at + "원금", line.principal());
            checkWon(v, at + "이자", line.interest());
            checkWon(v, at + "납입액", line.total());
            checkWon(v, at + "잔액", line.balanceAfter());

            // SCH-04 음수 금지
            if (line.principal().signum() < 0) {
                v.add(new Violation(LoanInvariant.SCH_04, at + "원금 " + plain(line.principal())));
            }
            if (line.interest().signum() < 0) {
                v.add(new Violation(LoanInvariant.SCH_04, at + "이자 " + plain(line.interest())));
            }

            // SCH-05 납입액 = 원금 + 이자
            BigDecimal expectedTotal = line.principal().add(line.interest());
            if (line.total().compareTo(expectedTotal) != 0) {
                v.add(new Violation(LoanInvariant.SCH_05,
                        at + "납입액 " + plain(line.total()) + " ≠ 원금+이자 " + plain(expectedTotal)));
            }

            // SCH-06 잔액 연쇄
            BigDecimal expectedBalance = prevBalance.subtract(line.principal());
            if (line.balanceAfter().compareTo(expectedBalance) != 0) {
                v.add(new Violation(LoanInvariant.SCH_06,
                        at + "잔액 " + plain(line.balanceAfter()) + " ≠ 직전잔액 " + plain(prevBalance)
                                + " − 원금 " + plain(line.principal())));
            }

            // SCH-07 납기일
            LocalDate expectedDue = executedAt.plusMonths(line.installmentNo());
            if (!expectedDue.equals(line.dueDate())) {
                v.add(new Violation(LoanInvariant.SCH_07,
                        at + "납기일 " + line.dueDate() + " ≠ 실행일+" + line.installmentNo() + "개월 " + expectedDue));
            }

            // SCH-08 이자 = 직전잔액 × 월이율 (원 미만 절사 → (exact − 1, exact])
            BigDecimal exact = prevBalance.multiply(monthlyRateExact, MC);
            BigDecimal tolerance = prevBalance.abs().multiply(RATE_ROUNDING_TOLERANCE);
            boolean tooHigh = line.interest().compareTo(exact.add(tolerance)) > 0;
            boolean tooLow = line.interest().compareTo(exact.subtract(BigDecimal.ONE).subtract(tolerance)) <= 0;
            if (tooHigh || tooLow) {
                v.add(new Violation(LoanInvariant.SCH_08,
                        at + "이자 " + plain(line.interest()) + " — 직전잔액 기준 월이자 "
                                + exact.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString()));
            }

            // SCH-09 무이자
            if (annualRate.signum() == 0 && line.interest().signum() != 0) {
                v.add(new Violation(LoanInvariant.SCH_09, at + "무이자인데 이자 " + plain(line.interest())));
            }

            prevBalance = line.balanceAfter();
        }

        // SCH-10 방식별 형태 (마지막 회차는 끝수 조정이 정상이므로 제외)
        checkShape(v, type, lines);
        return v;
    }

    private static void checkShape(List<Violation> v, RepaymentType type, List<ScheduleLine> lines) {
        if (lines.size() < 3) {
            return; // 비교할 "마지막 전 회차"가 2개 미만
        }
        ScheduleLine first = lines.get(0);
        for (ScheduleLine line : lines.subList(1, lines.size() - 1)) {
            String at = line.installmentNo() + "회차: ";
            switch (type) {
                case EQUAL_PAYMENT -> {
                    if (line.total().compareTo(first.total()) != 0) {
                        v.add(new Violation(LoanInvariant.SCH_10,
                                at + "원리금균등인데 납입액 " + plain(line.total()) + " ≠ 1회차 " + plain(first.total())));
                    }
                }
                case EQUAL_PRINCIPAL -> {
                    if (line.principal().compareTo(first.principal()) != 0) {
                        v.add(new Violation(LoanInvariant.SCH_10,
                                at + "원금균등인데 원금 " + plain(line.principal()) + " ≠ 1회차 " + plain(first.principal())));
                    }
                }
                case BULLET -> {
                    if (line.principal().signum() != 0 || first.principal().signum() != 0) {
                        v.add(new Violation(LoanInvariant.SCH_10, at + "만기일시인데 만기 전 원금 " + plain(line.principal())));
                    }
                    if (line.interest().compareTo(first.interest()) != 0) {
                        v.add(new Violation(LoanInvariant.SCH_10,
                                at + "만기일시인데 이자 " + plain(line.interest()) + " ≠ 1회차 " + plain(first.interest())));
                    }
                }
            }
        }
    }

    static void checkWon(List<Violation> v, String label, BigDecimal amount) {
        if (amount == null) {
            v.add(new Violation(LoanInvariant.MONEY_01, label + " 값이 null"));
            return;
        }
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 0) {
            v.add(new Violation(LoanInvariant.MONEY_01, label + " " + amount.toPlainString()));
        }
    }

    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
