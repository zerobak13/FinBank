package com.finbank.backend.loan.dto;

import com.finbank.backend.common.MoneyPolicy;
import com.finbank.backend.loan.domain.RepaymentSchedule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 상환 스케줄 1회차 응답.
 * 금액은 MoneyPolicy로 원 단위 정수화한다 (DB의 DECIMAL(19,4)가 그대로 내려가지 않도록).
 *
 * <p>{@code penaltyAccrued}는 연체 회차에 지금까지 쌓인 연체이자다 (연체가 아니면 0).
 * 이 회차를 상환하면 {@code totalAmount + penaltyAccrued}가 한 번에 출금된다 — 화면의 상환 금액도 이 합이다.</p>
 */
public record RepaymentScheduleResponse(
        Long id,
        int installmentNo,
        LocalDate dueDate,
        BigDecimal principalAmount,
        BigDecimal interestAmount,
        BigDecimal totalAmount,
        BigDecimal penaltyAccrued,
        String status,
        LocalDateTime paidAt
) {
    /** 연체이자가 없는 회차 (대출 실행 직후 등) */
    public static RepaymentScheduleResponse from(RepaymentSchedule s) {
        return from(s, BigDecimal.ZERO);
    }

    public static RepaymentScheduleResponse from(RepaymentSchedule s, BigDecimal penaltyAccrued) {
        return new RepaymentScheduleResponse(
                s.getId(),
                s.getInstallmentNo(),
                s.getDueDate(),
                MoneyPolicy.toWon(s.getPrincipalAmount()),
                MoneyPolicy.toWon(s.getInterestAmount()),
                MoneyPolicy.toWon(s.getTotalAmount()),
                MoneyPolicy.toWon(penaltyAccrued),
                s.getStatus().name(),
                s.getPaidAt()
        );
    }
}
