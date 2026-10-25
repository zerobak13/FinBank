import { client, ApiError } from "./client";

/**
 * 멱등 POST 공통 모듈 — 돈이 움직이는 요청(이체 · 대출 실행 · 상환)이 함께 쓴다.
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
 * 네트워크 실패 시 같은 키로 1회 자동 재시도한다.
 *
 * 재시도 대상은 "서버에 닿았는지조차 모르는" 네트워크 실패뿐이다.
 * 409/422처럼 서버가 판단해서 거절한 응답은 다시 보내도 결과가 같다.
 */
export async function idempotentPost(path, { key, body, retryOnNetworkFailure = true }) {
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
