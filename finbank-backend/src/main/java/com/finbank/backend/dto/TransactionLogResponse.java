package com.finbank.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "거래 내역 응답")
public class TransactionLogResponse {

    @Schema(description = "거래 로그 ID", example = "1")
    private Long id;

    @Schema(description = "거래 유형 (DEPOSIT / WITHDRAW / TRANSFER_IN / TRANSFER_OUT)", example = "TRANSFER_OUT")
    private String type;

    @Schema(description = "출금 계좌 ID (입금 시 null)", example = "1")
    private Long fromAccountId;

    @Schema(description = "입금 계좌 ID (출금 시 null)", example = "2")
    private Long toAccountId;

    @Schema(description = "거래 금액 (원)", example = "10000")
    private long amount;

    @Schema(description = "거래 후 잔액 (원)", example = "90000")
    private long balanceAfter;

    @Schema(description = "거래 설명", example = "Transfer Out")
    private String description;

    @Schema(description = "거래 발생 시각", example = "2025-01-01T12:00:00")
    private LocalDateTime createdAt;

    public TransactionLogResponse(Long id, String type, Long fromAccountId, Long toAccountId,
                                  long amount, long balanceAfter, String description,
                                  LocalDateTime createdAt) {
        this.id = id;
        this.type = type;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.description = description;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getType() { return type; }
    public Long getFromAccountId() { return fromAccountId; }
    public Long getToAccountId() { return toAccountId; }
    public long getAmount() { return amount; }
    public long getBalanceAfter() { return balanceAfter; }
    public String getDescription() { return description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
