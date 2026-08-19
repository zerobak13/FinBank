package com.finbank.backend.loan.domain.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 경과이자 계산기 단위 테스트 (확정 기대값 — 손으로 계산한 값과 대조).
 *
 * <p>수식: 잔여원금 × 연이율 × 경과일수 / 365, 원 미만 절사.</p>
 */
class AccruedInterestCalculatorTest {

    private final AccruedInterestCalculator calculator = new AccruedInterestCalculator();

    @Test
    @DisplayName("1,000만 · 연 6% · 30일 → 49,315원 (10,000,000×0.06×30/365 = 49,315.068… 절사)")
    void calculate_30days() {
        BigDecimal interest = calculator.calculate(
                new BigDecimal("10000000"), new BigDecimal("0.06"), 30);

        assertThat(interest).isEqualByComparingTo(new BigDecimal("49315"));
    }

    @Test
    @DisplayName("1,000만 · 연 6% · 365일 → 600,000원 (1년치는 정확히 연이자와 같다)")
    void calculate_fullYear() {
        BigDecimal interest = calculator.calculate(
                new BigDecimal("10000000"), new BigDecimal("0.06"), 365);

        assertThat(interest).isEqualByComparingTo(new BigDecimal("600000"));
    }

    @Test
    @DisplayName("경과일수 0 / 금리 0 / 잔여원금 0 → 이자 0")
    void calculate_zeroCases() {
        assertThat(calculator.calculate(new BigDecimal("10000000"), new BigDecimal("0.06"), 0))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(calculator.calculate(new BigDecimal("10000000"), BigDecimal.ZERO, 30))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(calculator.calculate(BigDecimal.ZERO, new BigDecimal("0.06"), 30))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("원 미만은 절사한다 — 1원 · 연 6% · 1일은 0원")
    void calculate_truncatesUnderWon() {
        assertThat(calculator.calculate(BigDecimal.ONE, new BigDecimal("0.06"), 1))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("음수 입력은 거부한다")
    void calculate_rejectsNegative() {
        assertThatThrownBy(() -> calculator.calculate(new BigDecimal("-1"), new BigDecimal("0.06"), 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(new BigDecimal("10000"), new BigDecimal("-0.01"), 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(new BigDecimal("10000"), new BigDecimal("0.06"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
