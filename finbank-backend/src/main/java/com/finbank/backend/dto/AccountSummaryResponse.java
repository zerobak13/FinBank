package com.finbank.backend.dto;

public class AccountSummaryResponse {

    private Long id;
    private String accountNumber;
    private long balance;
    private boolean locked;
    private String ownerEmail;
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
