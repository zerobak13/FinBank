package com.finbank.backend.dto;

import java.util.List;

public class AccountDetailResponse {

    private AccountSummaryResponse account;
    private List<TransactionLogResponse> transactions;

    public AccountDetailResponse(AccountSummaryResponse account,
                                 List<TransactionLogResponse> transactions) {
        this.account = account;
        this.transactions = transactions;
    }

    public AccountSummaryResponse getAccount() { return account; }
    public List<TransactionLogResponse> getTransactions() { return transactions; }
}
