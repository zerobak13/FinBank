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
    /** 입금·출금·이체는 돈이 움직이는 요청 — key는 사용자 행동 1회당 1개 (재시도에는 같은 키) */
    deposit: (id, amount, key) =>
        idempotentPost(`/api/accounts/${id}/deposit`, { key, body: { amount } }),
    withdraw: (id, amount, key) =>
        idempotentPost(`/api/accounts/${id}/withdraw`, { key, body: { amount } }),
    transfer: (payload, key) =>
        idempotentPost("/api/accounts/transfer", { key, body: payload }),
};