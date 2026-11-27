package com.finbank.backend.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 계좌 요약 정보.
 *
 * <p>레코드인 이유: 계좌 개설 응답이 멱등 저장소에 JSON으로 저장됐다가 재시도에 그대로 복원된다(다른 응답 레코드와 같다).
 * JSON 필드 이름은 예전 클래스(getter)와 같다.</p>
 */
@Schema(description = "계좌 요약 정보")
public record AccountSummaryResponse(
        @Schema(description = "계좌 ID", example = "1") Long id,
        @Schema(description = "계좌번호 (12자리)", example = "123456789012") String accountNumber,
        @Schema(description = "현재 잔액 (원)", example = "100000") BigDecimal balance,
        @Schema(description = "계좌 잠금 여부", example = "false") boolean locked,
        @Schema(description = "소유자 이메일", example = "user@example.com") String ownerEmail,
        @Schema(description = "소유자 이름", example = "박제영") String ownerName
) {
}
