package com.finbank.backend.account.dto;

import java.math.BigDecimal;

/**
 * 이체 결과 (POST /api/accounts/transfer).
 *
 * <p>멱등 재시도 시 저장된 응답을 그대로 돌려주기 위해 Jackson이 역직렬화할 수 있는
 * record + 기본 타입으로만 구성한다. {@code message} 필드는 기존 응답({"message": "이체 완료"})과의
 * 호환을 위해 유지한다.</p>
 */
public record TransferResponse(
        Long fromAccountId,
        String toAccountNumber,
        BigDecimal amount,
        BigDecimal fromBalanceAfter,
        String message
) {
}
