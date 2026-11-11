package com.finbank.backend.loan.domain.calculator;

import com.finbank.backend.common.MoneyPolicy;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;

/**
 * 상환 스케줄 생성기 — 상환 방식별 수식의 유일한 차이점을 캡슐화한다(전략 패턴).
 *
 * <p><b>순수 자바</b>: Spring/JPA 의존이 없다. 돈 계산 로직은 프레임워크와 무관하게
 * 검증 가능해야 하며, 덕분에 단위테스트가 ms 단위로 돌아 수백 케이스 대조가 가능하다.</p>
 *
 * <p><b>공통 불변식</b> (모든 구현체가 지켜야 하며, 속성 테스트로 강제된다):</p>
 * <ul>
 *   <li>Σ(회차 원금) == 대출 원금 (1원도 새지 않는다)</li>
 *   <li>마지막 회차의 balanceAfter == 0</li>
 *   <li>모든 회차의 이자 ≥ 0, 잔액은 단조 감소</li>
 * </ul>
 *
 * <p><b>끝수 조정</b>: 회차별 반올림 오차가 누적되므로, 마지막 회차는 공식값 대신
 * "잔여 원금 전액"을 원금으로 삼는다. 이를 빼먹으면 완납 후 잔액이 ±수 원 남는다.</p>
 */
public interface RepaymentScheduleGenerator {

    /**
     * 대출 기간 상한 600개월(50년) — 상품 정책이 아니라 엔진이 감당할 수 있는 범위 (INP-03).
     * 상한이 없으면 {@code new ArrayList<>(termMonths)}가 거대한 기간에서 OutOfMemoryError를 낸다.
     */
    int MAX_TERM_MONTHS = 600;

    /**
     * @param principal   대출 원금 (양수, 원 단위 정수)
     * @param annualRate  연이율 (예: 0.059) — 0 이상, 법정 최고금리 연 20% 이하
     * @param termMonths  대출 기간 (개월, 1 이상 {@value #MAX_TERM_MONTHS} 이하)
     * @param executedAt  실행일 (필수) — i회차 납부일은 실행일 + i개월
     * @throws IllegalArgumentException 입력 계약(INP-01~04) 밖의 입력
     */
    List<ScheduleLine> generate(BigDecimal principal, BigDecimal annualRate, int termMonths, LocalDate executedAt);

    /**
     * 입력 공통 검증 — 구현체 첫 줄에서 호출. 계약 밖이면 {@link IllegalArgumentException}.
     *
     * <ul>
     *   <li>INP-01 원금: 0보다 큰 원 단위 정수 (1000.00처럼 소수부가 0이면 정수로 본다)</li>
     *   <li>INP-02 연이율: 0 이상, {@link MoneyPolicy#MAX_LEGAL_RATE} 이하 (정확히 20%는 허용)</li>
     *   <li>INP-03 기간: 1 ~ {@value #MAX_TERM_MONTHS}개월</li>
     *   <li>INP-04 실행일: 필수, 만기일(실행일 + 기간)이 날짜 표현 범위 안</li>
     * </ul>
     */
    default void validate(BigDecimal principal, BigDecimal annualRate, int termMonths, LocalDate executedAt) {
        if (principal == null || principal.signum() <= 0) {
            throw new IllegalArgumentException("대출 원금은 양수여야 합니다.");
        }
        if (principal.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "대출 원금은 원 단위 정수여야 합니다: " + principal.toPlainString());
        }
        if (annualRate == null || annualRate.signum() < 0) {
            throw new IllegalArgumentException("연이율은 0 이상이어야 합니다.");
        }
        if (annualRate.compareTo(MoneyPolicy.MAX_LEGAL_RATE) > 0) {
            throw new IllegalArgumentException(
                    "연이율은 법정 최고금리(연 " + MoneyPolicy.MAX_LEGAL_RATE.toPlainString()
                            + ") 이하여야 합니다: " + annualRate.toPlainString());
        }
        if (termMonths < 1) {
            throw new IllegalArgumentException("대출 기간은 1개월 이상이어야 합니다.");
        }
        if (termMonths > MAX_TERM_MONTHS) {
            throw new IllegalArgumentException(
                    "대출 기간은 " + MAX_TERM_MONTHS + "개월 이하여야 합니다: " + termMonths);
        }
        if (executedAt == null) {
            throw new IllegalArgumentException("실행일은 필수입니다.");
        }
        try {
            // 만기일이 표현 가능하면 그 이전 회차 납부일(실행일 + i개월, i < 기간)도 모두 표현 가능하다.
            executedAt.plusMonths(termMonths);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "만기일이 날짜 표현 범위를 벗어납니다 (실행일 " + executedAt + ", " + termMonths + "개월)", e);
        }
    }
}
