package com.finbank.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 이체 요청 바디 (POST /api/accounts/transfer).
 * 출금 계좌 ID, 받는 계좌번호, 이체 금액을 담는다. 각 필드 설명은 @Schema 참고.
 */
@Schema(description = "이체 요청")
public class TransferRequest {

    @Schema(description = "출금 계좌 ID", example = "1")
    @NotNull
    private Long fromAccountId;

    @Schema(description = "입금 계좌번호 (12자리)", example = "123456789012")
    @NotBlank
    private String toAccountNumber;

    @Schema(description = "이체 금액 (1 이상, 원 단위 정수)", example = "10000")
    @NotNull
    @DecimalMin("1")
    @Digits(integer = 15, fraction = 0)
    private BigDecimal amount;

    public TransferRequest() {}

    public TransferRequest(Long fromAccountId, String toAccountNumber, BigDecimal amount) {
        this.fromAccountId = fromAccountId;
        this.toAccountNumber = toAccountNumber;
        this.amount = amount;
    }

    public Long getFromAccountId() { return fromAccountId; }
    public void setFromAccountId(Long fromAccountId) { this.fromAccountId = fromAccountId; }

    public String getToAccountNumber() { return toAccountNumber; }
    public void setToAccountNumber(String toAccountNumber) { this.toAccountNumber = toAccountNumber; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
