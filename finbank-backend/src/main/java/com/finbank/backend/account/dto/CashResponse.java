package com.finbank.backend.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 입금·출금 결과.
 *
 * <p>멱등 재시도 시 저장된 응답을 그대로 돌려주기 위해 record + 기본 타입으로만 구성한다 (이체 응답과 같은 이유).</p>
 */
@Schema(description = "입금·출금 결과")
public record CashResponse(
        @Schema(description = "계좌 ID", example = "1") Long accountId,
        @Schema(description = "입금·출금 금액 (원)", example = "10000") BigDecimal amount,
        @Schema(description = "처리 후 잔액 (원)", example = "110000") BigDecimal balanceAfter,
        @Schema(description = "결과 메시지", example = "입금 완료") String message
) {
}
