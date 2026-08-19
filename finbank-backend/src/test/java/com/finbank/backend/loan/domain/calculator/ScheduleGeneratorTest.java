package com.finbank.backend.loan.domain.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 상환 스케줄 생성기 — 확정 기대값 검증 (손계산 + 은행 대출계산기와 대조한 수치).
 *
 * 기준 케이스: 원금 10,000,000원 / 연 6% (월 0.5%) / 12개월 / 실행일 2026-01-15
 */
class ScheduleGeneratorTest {

    private static final BigDecimal P = new BigDecimal("10000000");
    private static final BigDecimal RATE = new BigDecimal("0.06");
    private static final LocalDate EXECUTED = LocalDate.of(2026, 1, 15);

    private void assertLine(ScheduleLine line, String principal, String interest, String balanceAfter) {
        assertThat(line.principal()).isEqualByComparingTo(principal);
        assertThat(line.interest()).isEqualByComparingTo(interest);
        assertThat(line.balanceAfter()).isEqualByComparingTo(balanceAfter);
    }

    @Nested
    @DisplayName("원리금균등 (EqualPayment)")
    class EqualPayment {

        private final EqualPaymentGenerator generator = new EqualPaymentGenerator();
        private final List<ScheduleLine> lines = generator.generate(P, RATE, 12, EXECUTED);

        @Test
        @DisplayName("월 상환금은 860,664원 (A = P·r(1+r)ⁿ/((1+r)ⁿ−1), 원 단위 반올림)")
        void monthlyPayment() {
            assertThat(lines.get(0).total()).isEqualByComparingTo("860664");
        }

        @Test
        @DisplayName("1~3회차: 이자 절사 → 원금 = A − 이자 → 잔액 감소")
        void earlyInstallments() {
            assertLine(lines.get(0), "810664", "50000", "9189336");
            assertLine(lines.get(1), "814718", "45946", "8374618");
            assertLine(lines.get(2), "818791", "41873", "7555827");
        }

        @Test
        @DisplayName("11회차 후 잔액 856,381원 — 공식대로면 12회차에 끝수가 남는다")
        void eleventhInstallment() {
            assertLine(lines.get(10), "852122", "8542", "856381");
        }

        @Test
        @DisplayName("끝수 조정: 12회차 원금은 잔액 전액(856,381), 상환액은 A보다 2원 작은 860,662원")
        void lastInstallment_absorbsRoundingDrift() {
            ScheduleLine last = lines.get(11);
            assertLine(last, "856381", "4281", "0");
            assertThat(last.total()).isEqualByComparingTo("860662"); // A(860,664) − 2원: 반올림 누적 오차
        }

        @Test
        @DisplayName("무이자(0%) 경계: 공식 분모가 0 → 원금 균등분할로 처리")
        void zeroRate() {
            List<ScheduleLine> zero = generator.generate(P, BigDecimal.ZERO, 12, EXECUTED);
            assertLine(zero.get(0), "833333", "0", "9166667");
            assertLine(zero.get(11), "833337", "0", "0"); // 끝수 4원은 마지막 회차가 흡수
        }

        @Test
        @DisplayName("1개월 대출: 회차 1개 — 원금 전액 + 한 달 이자")
        void singleInstallment() {
            List<ScheduleLine> one = generator.generate(P, RATE, 1, EXECUTED);
            assertThat(one).hasSize(1);
            assertLine(one.get(0), "10000000", "50000", "0");
        }
    }

    @Nested
    @DisplayName("원금균등 (EqualPrincipal)")
    class EqualPrincipal {

        private final List<ScheduleLine> lines =
                new EqualPrincipalGenerator().generate(P, RATE, 12, EXECUTED);

        @Test
        @DisplayName("매회 원금 833,333원 고정, 이자는 잔액 기준으로 매월 감소")
        void decreasingInterest() {
            assertLine(lines.get(0), "833333", "50000", "9166667");
            assertLine(lines.get(1), "833333", "45833", "8333334");
            assertThat(lines.get(0).total()).isEqualByComparingTo("883333");
            assertThat(lines.get(1).total()).isEqualByComparingTo("879166");
        }

        @Test
        @DisplayName("끝수 조정: 12회차 원금은 833,337원 (P/12 절사분 4원 흡수)")
        void lastInstallment() {
            assertLine(lines.get(11), "833337", "4166", "0");
        }
    }

    @Nested
    @DisplayName("만기일시 (Bullet)")
    class Bullet {

        private final List<ScheduleLine> lines =
                new BulletGenerator().generate(P, RATE, 12, EXECUTED);

        @Test
        @DisplayName("1~11회차: 이자 50,000원만, 원금 0, 잔액 유지")
        void interestOnly() {
            for (int i = 0; i < 11; i++) {
                assertLine(lines.get(i), "0", "50000", "10000000");
            }
        }

        @Test
        @DisplayName("12회차: 원금 전액 + 이자 = 10,050,000원")
        void maturity() {
            assertLine(lines.get(11), "10000000", "50000", "0");
            assertThat(lines.get(11).total()).isEqualByComparingTo("10050000");
        }
    }

    @Nested
    @DisplayName("입력 검증")
    class Validation {

        private final EqualPaymentGenerator generator = new EqualPaymentGenerator();

        @Test
        @DisplayName("원금 0 이하 / 음수 금리 / 0개월 → IllegalArgumentException")
        void invalidInputs() {
            assertThatThrownBy(() -> generator.generate(BigDecimal.ZERO, RATE, 12, EXECUTED))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> generator.generate(P, new BigDecimal("-0.01"), 12, EXECUTED))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> generator.generate(P, RATE, 0, EXECUTED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("납부일은 실행일 + i개월")
        void dueDates() {
            List<ScheduleLine> lines = generator.generate(P, RATE, 3, EXECUTED);
            assertThat(lines.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 2, 15));
            assertThat(lines.get(2).dueDate()).isEqualTo(LocalDate.of(2026, 4, 15));
        }
    }
}
