package com.finbank.backend.loan.dto;

import java.math.BigDecimal;

/**
 * 회차 상환 / 전액 중도상환 공통 응답.
 *
 * <p>한 번의 상환에서 "무엇이 원금이고 무엇이 이자·수수료인지", 그리고
 * "그 직후 대출 잔액과 계좌 잔액이 얼마인지"를 한 화면에 담는다.
 * 멱등 재요청 시 이 스냅샷이 그대로 반환된다.</p>
 *
 * <p>{@code penaltyPortion}은 연체 회차를 갚을 때 함께 받은 연체이자다 (그 밖에는 0).
 * {@code totalPaid} = 원금 + 이자 + 연체이자 + 수수료 = 계좌에서 빠진 금액.</p>
 */
public record RepaymentResponse(
        Long loanId,
        String type,
        Integer installmentNo,
        BigDecimal principalPortion,
        BigDecimal interestPortion,
        BigDecimal penaltyPortion,
        BigDecimal feePortion,
        BigDecimal totalPaid,
        BigDecimal loanBalanceAfter,
        BigDecimal accountBalanceAfter,
        String loanStatus
) {
}
