import { client } from "./client";

const generateUUID = () =>
    "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        return (c === "x" ? r : (r & 0x3) | 0x8).toString(16);
    });

export const accountApi = {
    getAccounts: () => client("/api/accounts"),

    getAccountDetail: (id) => client(`/api/accounts/${id}`),

    getAccountTransactions: (id, page = 0, size = 20) =>
        client(`/api/accounts/${id}/transactions?page=${page}&size=${size}`),

    createAccount: (initialDeposit, accountType = "REGULAR") =>
        client("/api/accounts", {
            method: "POST",
            body: JSON.stringify({ initialDeposit, accountType }),
        }),

    deposit: (id, amount) =>
        client(`/api/accounts/${id}/deposit?amount=${amount}`, {
            method: "POST",
            headers: { "Idempotency-Key": generateUUID() },
        }),

    withdraw: (id, amount) =>
        client(`/api/accounts/${id}/withdraw?amount=${amount}`, {
            method: "POST",
            headers: { "Idempotency-Key": generateUUID() },
        }),

    // 이체는 호출 시점에 UUID를 생성해 멱등성 키로 사용
    // 네트워크 오류로 재시도해도 중복 이체 방지
    transfer: (payload) =>
        client("/api/accounts/transfer", {
            method: "POST",
            body: JSON.stringify(payload),
            headers: { "Idempotency-Key": generateUUID() },
        }),
};
