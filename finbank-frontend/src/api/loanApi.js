import { client } from "./client";

/**
 * 여신(대출) API 모듈 — 백엔드 구현 일정에 맞춘 뼈대.
 *
 * 백엔드 준비 상태:
 *  - 상품 목록/신청/내 대출 조회: C1-2에서 구현 예정
 *  - 실행/상환/중도상환:        C3에서 구현 예정 (멱등키 헤더 필요)
 * 미구현 API 호출 시 404가 떨어지며, 화면에서는 안내 문구로 처리한다.
 */
export const loanApi = {
    // ── C1-2 ──────────────────────────────────────────
    getProducts: () => client("/api/loans/products"),
    apply: (payload) =>
        client("/api/loans/applications", {
            method: "POST",
            body: JSON.stringify(payload), // { productId, requestedAmount, termMonths }
        }),
    getMyApplications: () => client("/api/loans/applications"),
    cancelApplication: (id) =>
        client(`/api/loans/applications/${id}`, { method: "DELETE" }),

    // ── C3 ────────────────────────────────────────────
    execute: (applicationId, linkedAccountId) =>
        client(`/api/loans/applications/${applicationId}/execution`, {
            method: "POST",
            headers: { "Idempotency-Key": crypto.randomUUID() },
            body: JSON.stringify({ linkedAccountId }),
        }),
    getMyLoans: () => client("/api/loans"),
    getSchedules: (loanId) => client(`/api/loans/${loanId}/schedules`),
    repay: (loanId, installmentNo) =>
        client(`/api/loans/${loanId}/repayments`, {
            method: "POST",
            headers: { "Idempotency-Key": crypto.randomUUID() },
            body: JSON.stringify({ installmentNo }),
        }),
    prepay: (loanId) =>
        client(`/api/loans/${loanId}/prepayment`, {
            method: "POST",
            headers: { "Idempotency-Key": crypto.randomUUID() },
        }),
};
