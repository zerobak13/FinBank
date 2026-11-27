import { client } from "./client";
import { idempotentPost } from "./idempotency";

export const accountApi = {
    getAccounts: () => client("/api/accounts"),
    getAccountDetail: (id) => client(`/api/accounts/${id}`),
    getAccountTransactions: (id, page = 0, size = 20) =>
        client(`/api/accounts/${id}/transactions?page=${page}&size=${size}`),
    /** 개설·입금·출금·이체는 돈이 움직이는 요청 — key는 행동별로 결과가 확정될 때까지 같은 것 (idempotency.js) */
    createAccount: (initialDeposit, key) =>
        idempotentPost("/api/accounts", { key, body: { initialDeposit } }),
    deposit: (id, amount, key) =>
        idempotentPost(`/api/accounts/${id}/deposit`, { key, body: { amount } }),
    withdraw: (id, amount, key) =>
        idempotentPost(`/api/accounts/${id}/withdraw`, { key, body: { amount } }),
    transfer: (payload, key) =>
        idempotentPost("/api/accounts/transfer", { key, body: payload }),
};