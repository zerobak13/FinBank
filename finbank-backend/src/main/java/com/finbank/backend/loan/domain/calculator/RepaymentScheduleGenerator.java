package com.finbank.backend.loan.domain.calculator;

import java.math.BigDecimal;
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
     * @param principal   대출 원금 (양수, 원 단위 정수)
     * @param annualRate  연이율 (예: 0.059) — 0 허용
     * @param termMonths  대출 기간 (개월, 1 이상)
     * @param executedAt  실행일 — i회차 납부일은 실행일 + i개월
     */
    List<ScheduleLine> generate(BigDecimal principal, BigDecimal annualRate, int termMonths, LocalDate executedAt);

    /** 입력 공통 검증 — 구현체 첫 줄에서 호출 */
    default void validate(BigDecimal principal, BigDecimal annualRate, int termMonths) {
        if (principal == null || principal.signum() <= 0) {
            throw new IllegalArgumentException("대출 원금은 양수여야 합니다.");
        }
        if (annualRate == null || annualRate.signum() < 0) {
            throw new IllegalArgumentException("연이율은 0 이상이어야 합니다.");
        }
        if (termMonths < 1) {
            throw new IllegalArgumentException("대출 기간은 1개월 이상이어야 합니다.");
        }
    }
}
