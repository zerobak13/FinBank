package com.finbank.backend.loan.dto;

import com.finbank.backend.common.MoneyPolicy;
import com.finbank.backend.loan.domain.RepaymentSchedule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 상환 스케줄 1회차 응답.
 * 금액은 MoneyPolicy로 원 단위 정수화한다 (DB의 DECIMAL(19,4)가 그대로 내려가지 않도록).
 */
public record RepaymentScheduleResponse(
        Long id,
        int installmentNo,
        LocalDate dueDate,
        BigDecimal principalAmount,
        BigDecimal interestAmount,
        BigDecimal totalAmount,
        String status,
        LocalDateTime paidAt
) {
    public static RepaymentScheduleResponse from(RepaymentSchedule s) {
        return new RepaymentScheduleResponse(
                s.getId(),
                s.getInstallmentNo(),
                s.getDueDate(),
                MoneyPolicy.toWon(s.getPrincipalAmount()),
                MoneyPolicy.toWon(s.getInterestAmount()),
                MoneyPolicy.toWon(s.getTotalAmount()),
                s.getStatus().name(),
                s.getPaidAt()
        );
    }
}
