import { client } from "./client";

export const accountApi = {
    getAccounts: () => client("/api/accounts"),
    getAccountDetail: (id) => client(`/api/accounts/${id}`),
    createAccount: (initialDeposit) =>
        client("/api/accounts", {
            method: "POST",
            body: JSON.stringify({ initialDeposit })
        }),
    deposit: (id, amount) =>
        client(`/api/accounts/${id}/deposit?amount=${amount}`, { method: "POST" }),
    withdraw: (id, amount) =>
        client(`/api/accounts/${id}/withdraw?amount=${amount}`, { method: "POST" }),
    transfer: (payload) =>
        client("/api/accounts/transfer", {
            method: "POST",
            body: JSON.stringify(payload)
        }),
};