import { client, ApiError } from "./client";

/**
 * 여신(대출) API 모듈.
 *
 * ── 멱등키를 쓰는 방식이 이 파일의 핵심이다 ──────────────────────────
 * 흔한 실수: 호출할 때마다 `crypto.randomUUID()`로 새 키를 만드는 것.
 * 그러면 재시도할 때마다 서버 입장에서는 "처음 보는 요청"이라 멱등이 전혀 걸리지 않는다.
 *
 * 올바른 방식: <b>사용자 행동 1회 = 키 1개</b>. 버튼을 누른 순간 키를 만들고,
 * 응답이 오지 않거나 네트워크가 끊겨 재시도할 때는 <b>같은 키</b>를 그대로 다시 보낸다.
 * 그래야 서버가 "아까 그 요청"임을 알아보고 저장해둔 응답을 돌려준다.
 */

/** 사용자 행동 1회당 한 번만 호출한다 (재시도에는 이미 만든 키를 재사용) */
export const newIdempotencyKey = () =>
    (crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`);

/**
 * 돈이 움직이는 요청 — 네트워크 실패 시 같은 키로 1회 자동 재시도한다.
 *
 * 재시도 대상은 "서버에 닿았는지조차 모르는" 네트워크 실패뿐이다.
 * 409/422처럼 서버가 판단해서 거절한 응답은 다시 보내도 결과가 같다.
 */
async function idempotentPost(path, { key, body, retryOnNetworkFailure = true }) {
    const send = () =>
        client(path, {
            method: "POST",
            headers: { "Idempotency-Key": key },
            ...(body ? { body: JSON.stringify(body) } : {}),
        });

    try {
        return await send();
    } catch (e) {
        if (retryOnNetworkFailure && e instanceof ApiError && e.isNetworkFailure) {
            return await send(); // 같은 키 → 서버가 이미 처리했다면 저장된 응답이 돌아온다
        }
        throw e;
    }
}

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
