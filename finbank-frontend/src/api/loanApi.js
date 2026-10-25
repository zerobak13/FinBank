import { client } from "./client";
import { idempotentPost, newIdempotencyKey } from "./idempotency";

/**
 * 여신(대출) API 모듈.
 * 실행 · 상환처럼 돈이 움직이는 요청은 멱등 POST로 보낸다 (키 사용 규칙은 ./idempotency.js 참고).
 */

// 기존 import 경로(`../api/loanApi`) 호환
export { newIdempotencyKey };

export const loanApi = {
    // ── 상품 · 신청 (C1) ───────────────────────────────
    getProducts: () => client("/api/loans/products"),
    apply: (payload) =>
        client("/api/loans/applications", {
            method: "POST",
            body: JSON.stringify(payload), // { productId, requestedAmount, termMonths }
        }),
    getMyApplications: () => client("/api/loans/applications"),
    cancelApplication: (id) =>
        client(`/api/loans/applications/${id}`, { method: "DELETE" }),

    // ── 실행 · 상환 (C3) ───────────────────────────────
    /** 대출 실행 — 입금 계좌는 서버가 회원의 활성 계좌 중에서 고른다 */
    execute: (applicationId, key) =>
        idempotentPost(`/api/loans/applications/${applicationId}/execute`, { key }),

    /** 회차 상환 — installmentNo는 확인용(생략 시 서버가 가장 빠른 미납 회차를 처리) */
    repay: (loanId, installmentNo, key) =>
        idempotentPost(`/api/loans/${loanId}/repayments`, { key, body: { installmentNo } }),

    /** 전액 중도상환 — 잔여원금 + 경과이자 + 중도상환수수료 */
    prepay: (loanId, key) =>
        idempotentPost(`/api/loans/${loanId}/prepayment`, { key }),

    // ── 조회 ─────────────────────────────────────────
    getMyLoans: () => client("/api/loans"),
    getLoan: (loanId) => client(`/api/loans/${loanId}`),
    getSchedules: (loanId) => client(`/api/loans/${loanId}/schedules`),
};

/** 서버 에러 코드 → 사용자 문구 (서버 메시지가 기술적일 때 대체할 기본값) */
export const LOAN_ERROR_MESSAGE = {
    ALREADY_EXECUTED: "이미 실행된 신청입니다.",
    ALREADY_REPAID: "이미 상환된 회차입니다. 최신 상태로 갱신했습니다.",
    OUT_OF_ORDER_INSTALLMENT: "회차는 순서대로만 상환할 수 있습니다.",
    IDEMPOTENT_REQUEST_IN_PROGRESS: "같은 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.",
    IDEMPOTENCY_KEY_MISMATCH: "요청 키가 중복되었습니다. 새로고침 후 다시 시도해주세요.",
    INSUFFICIENT_BALANCE: "계좌 잔액이 부족합니다.",
    ACCOUNT_LOCKED: "잠금된 계좌로는 처리할 수 없습니다.",
    NETWORK_ERROR: "네트워크가 불안정합니다. 잠시 후 다시 시도해주세요.",
};
