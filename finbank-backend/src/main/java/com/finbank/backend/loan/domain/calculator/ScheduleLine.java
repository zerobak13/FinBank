package com.finbank.backend.loan.domain.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 상환 스케줄 1회차의 계산 결과 (불변).
 * 이자 엔진의 산출물이며, 대출 실행 시 RepaymentSchedule 엔티티로 변환되어 저장된다.
 */
public record ScheduleLine(
        int installmentNo,
        LocalDate dueDate,
        BigDecimal principal,
        BigDecimal interest,
        BigDecimal total,
        BigDecimal balanceAfter
) {
}
