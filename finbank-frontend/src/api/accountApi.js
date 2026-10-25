import { client } from "./client";
import { idempotentPost } from "./idempotency";

export const accountApi = {
    getAccounts: () => client("/api/accounts"),
    getAccountDetail: (id) => client(`/api/accounts/${id}`),
    getAccountTransactions: (id, page = 0, size = 20) =>
        client(`/api/accounts/${id}/transactions?page=${page}&size=${size}`),
    createAccount: (initialDeposit) =>
        client("/api/accounts", {
            method: "POST",
            body: JSON.stringify({ initialDeposit })
        }),
    deposit: (id, amount) =>
        client(`/api/accounts/${id}/deposit?amount=${amount}`, { method: "POST" }),
    withdraw: (id, amount) =>
        client(`/api/accounts/${id}/withdraw?amount=${amount}`, { method: "POST" }),
    /** 이체 — key는 사용자 행동 1회당 1개 (재시도에는 같은 키) */
    transfer: (payload, key) =>
        idempotentPost("/api/accounts/transfer", { key, body: payload }),
};