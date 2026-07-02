package com.finbank.backend.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class AccountDetailResponse {

    private AccountSummaryResponse account;
    private List<TransactionLogResponse> transactions;

    @JsonCreator
    public AccountDetailResponse(
            @JsonProperty("account")      AccountSummaryResponse account,
            @JsonProperty("transactions") List<TransactionLogResponse> transactions) {
        this.account = account;
        this.transactions = transactions;
    }

    public AccountSummaryResponse getAccount() { return account; }
    public List<TransactionLogResponse> getTransactions() { return transactions; }
}
