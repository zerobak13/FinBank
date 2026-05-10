package com.finbank.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "계좌 요약 정보")
public class AccountSummaryResponse {

    @Schema(description = "계좌 ID", example = "1")
    private Long id;

    @Schema(description = "계좌번호 (12자리)", example = "123456789012")
    private String accountNumber;

    @Schema(description = "현재 잔액 (원)", example = "100000")
    private long balance;

    @Schema(description = "계좌 잠금 여부", example = "false")
    private boolean locked;

    @Schema(description = "소유자 이메일", example = "user@example.com")
    private String ownerEmail;

    @Schema(description = "소유자 이름", example = "박제영")
    private String ownerName;

    public AccountSummaryResponse(Long id, String accountNumber, long balance,
                                  boolean locked, String ownerEmail, String ownerName) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.balance = balance;
        this.locked = locked;
        this.ownerEmail = ownerEmail;
        this.ownerName = ownerName;
    }

    public Long getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public long getBalance() { return balance; }
    public boolean isLocked() { return locked; }
    public String getOwnerEmail() { return ownerEmail; }
    public String getOwnerName() { return ownerName; }
}
