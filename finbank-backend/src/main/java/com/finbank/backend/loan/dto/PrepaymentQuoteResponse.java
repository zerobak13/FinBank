package com.finbank.backend.loan.dto;

import com.finbank.backend.common.MoneyPolicy;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 전액 중도상환 견적 — 돈은 움직이지 않는다. 같은 날 실제로 중도상환하면 이 금액이 출금된다
 * (그 사이 다른 상환이 끝나면 잔여원금·기산일이 바뀌어 달라진다).
 */
@Schema(description = "전액 중도상환 견적")
public record PrepaymentQuoteResponse(
        @Schema(description = "대출 ID", example = "1") Long loanId,
        @Schema(description = "잔여 원금 (원)", example = "10000000") BigDecimal principalPortion,
        @Schema(description = "경과이자 (원) — 직전 납부 회차 납기일(없으면 실행일)부터 오늘까지", example = "48493") BigDecimal interestPortion,
        @Schema(description = "중도상환수수료 (원) — 대출일부터 3년 안에 갚을 때만", example = "147123") BigDecimal feePortion,
        @Schema(description = "출금될 총액 (원)", example = "10195616") BigDecimal totalAmount,
        @Schema(description = "견적 기준일", example = "2026-11-27") LocalDate quotedOn,
        @Schema(description = "이 날부터 중도상환수수료 없음 (만기일과 실행일 + 3년 중 빠른 날)", example = "2029-11-27") LocalDate feeFreeFrom
) {
    public static PrepaymentQuoteResponse of(Long loanId, BigDecimal principal, BigDecimal interest, BigDecimal fee,
                                             LocalDate quotedOn, LocalDate feeFreeFrom) {
        return new PrepaymentQuoteResponse(
                loanId,
                MoneyPolicy.toWon(principal),
                MoneyPolicy.toWon(interest),
                MoneyPolicy.toWon(fee),
                MoneyPolicy.toWon(principal.add(interest).add(fee)),
                quotedOn,
                feeFreeFrom);
    }
}
