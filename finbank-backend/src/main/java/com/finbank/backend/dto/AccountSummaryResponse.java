package com.finbank.backend.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.finbank.backend.domain.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

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

    @Schema(description = "계좌 타입", example = "SAVINGS")
    private AccountType accountType;

    @Schema(description = "연이율 (0.0200 = 2%)", example = "0.0200")
    private BigDecimal interestRate;

    @Schema(description = "소유자 이메일", example = "user@example.com")
    private String ownerEmail;

    @Schema(description = "소유자 이름", example = "박제영")
    private String ownerName;

    @JsonCreator
    public AccountSummaryResponse(
            @JsonProperty("id")           Long id,
            @JsonProperty("accountNumber") String accountNumber,
            @JsonProperty("balance")       long balance,
            @JsonProperty("locked")        boolean locked,
            @JsonProperty("accountType")   AccountType accountType,
            @JsonProperty("interestRate")  BigDecimal interestRate,
            @JsonProperty("ownerEmail")    String ownerEmail,
            @JsonProperty("ownerName")     String ownerName) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.balance = balance;
        this.locked = locked;
        this.accountType = accountType;
        this.interestRate = interestRate;
        this.ownerEmail = ownerEmail;
        this.ownerName = ownerName;
    }

    public Long getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public long getBalance() { return balance; }
    public boolean isLocked() { return locked; }
    public AccountType getAccountType() { return accountType; }
    public BigDecimal getInterestRate() { return interestRate; }
    public String getOwnerEmail() { return ownerEmail; }
    public String getOwnerName() { return ownerName; }
}
