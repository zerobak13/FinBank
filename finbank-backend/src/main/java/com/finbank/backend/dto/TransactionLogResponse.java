package com.finbank.backend.dto;

import java.time.LocalDateTime;

public class TransactionLogResponse {

    private Long id;
    private String type;
    private Long fromAccountId;
    private Long toAccountId;
    private long amount;
    private long balanceAfter;
    private String description;
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
