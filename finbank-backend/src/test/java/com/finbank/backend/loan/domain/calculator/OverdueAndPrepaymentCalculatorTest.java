package com.finbank.backend.loan.domain.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 연체이자·중도상환수수료 계산기 — 확정 기대값 검증 */
class OverdueAndPrepaymentCalculatorTest {

    @Nested
    @DisplayName("연체이자 = 연체원리금 × min(약정+가산, 20%) × 일수/365, 원미만 절사")
    class OverdueInterest {

        private final OverdueInterestCalculator calculator = new OverdueInterestCalculator();

        @Test
        @DisplayName("연체원리금 860,664 / 약정 6% + 가산 3% / 10일 → 2,122원")
        void basic() {
            // 860,664 × 0.09 × 10 / 365 = 2,122.18… → 절사 2,122
            BigDecimal penalty = calculator.calculate(
                    new BigDecimal("860664"), new BigDecimal("0.06"), new BigDecimal("0.03"), 10);
            assertThat(penalty).isEqualByComparingTo("2122");
        }

        @Test
        @DisplayName("약정 19% + 가산 3% = 22% → 법정최고 20%로 캡")
        void legalRateCap() {
            // 860,664 × 0.20 × 10 / 365 = 4,715.96… → 절사 4,715
            BigDecimal penalty = calculator.calculate(
                    new BigDecimal("860664"), new BigDecimal("0.19"), new BigDecimal("0.03"), 10);
            assertThat(penalty).isEqualByComparingTo("4715");
        }

        @Test
        @DisplayName("연체 0일 → 0원")
        void zeroDays() {
            BigDecimal penalty = calculator.calculate(
                    new BigDecimal("860664"), new BigDecimal("0.06"), new BigDecimal("0.03"), 0);
            assertThat(penalty).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("음수 일수/음수 금액 → IllegalArgumentException")
        void invalid() {
            assertThatThrownBy(() -> calculator.calculate(
                    new BigDecimal("860664"), new BigDecimal("0.06"), new BigDecimal("0.03"), -1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> calculator.calculate(
                    new BigDecimal("-1"), new BigDecimal("0.06"), new BigDecimal("0.03"), 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("중도상환수수료 = 상환원금 × 수수료율 × 잔존일수/총일수 (체감), 원미만 절사")
    class PrepaymentFee {

        private final PrepaymentFeeCalculator calculator = new PrepaymentFeeCalculator();

        @Test
        @DisplayName("상환원금 500만 / 수수료율 1.5% / 잔존 200일 / 총 365일 → 41,095원")
        void basic() {
            // 5,000,000 × 0.015 × 200 / 365 = 41,095.89… → 절사 41,095
            BigDecimal fee = calculator.calculate(
                    new BigDecimal("5000000"), new BigDecimal("0.015"), 200, 365);
            assertThat(fee).isEqualByComparingTo("41095");
        }

        @Test
        @DisplayName("만기일 상환(잔존 0일) → 수수료 0원 (체감 방식의 핵심)")
        void maturityDay() {
            BigDecimal fee = calculator.calculate(
                    new BigDecimal("5000000"), new BigDecimal("0.015"), 0, 365);
            assertThat(fee).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("실행 직후 상환(잔존 == 총일수) → 수수료율 전액 부과")
        void immediatelyAfterExecution() {
            // 5,000,000 × 0.015 × 365/365 = 75,000
            BigDecimal fee = calculator.calculate(
                    new BigDecimal("5000000"), new BigDecimal("0.015"), 365, 365);
            assertThat(fee).isEqualByComparingTo("75000");
        }

        @Test
        @DisplayName("잔존일수 > 총일수 / 총일수 0 → IllegalArgumentException")
        void invalid() {
            assertThatThrownBy(() -> calculator.calculate(
                    new BigDecimal("5000000"), new BigDecimal("0.015"), 366, 365))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> calculator.calculate(
                    new BigDecimal("5000000"), new BigDecimal("0.015"), 0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
