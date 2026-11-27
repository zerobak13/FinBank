import { client, ApiError } from "./client.js";

/**
 * 멱등 POST 공통 모듈 — 돈이 움직이는 요청(계좌 개설 · 입금 · 출금 · 이체 · 대출 실행 · 상환)이 함께 쓴다.
 *
 * ── 멱등키를 쓰는 방식이 이 파일의 핵심이다 ──────────────────────────
 * 흔한 실수: 호출할 때마다 `crypto.randomUUID()`로 새 키를 만드는 것.
 * 그러면 재시도할 때마다 서버 입장에서는 "처음 보는 요청"이라 멱등이 전혀 걸리지 않는다.
 *
 * 올바른 방식: <b>같은 행동에는 서버가 결과를 확정해 줄 때까지 같은 키</b>.
 * 응답을 못 받은 채(네트워크 끊김) 사용자가 버튼을 다시 눌러도 같은 키가 나가야, 첫 요청이 실제로 처리됐을 때
 * 서버가 "아까 그 요청"임을 알아보고 저장해 둔 응답을 돌려준다. 누를 때마다 새 키를 만들면 바로 이 순간에
 * 이체·입금이 두 번 된다. 그 키 관리를 아래 createKeyRing이 맡는다.
 */

/** 새 키 하나 — 화면에서는 직접 부르지 말고 createKeyRing의 run/keyFor를 쓴다 */
export const newIdempotencyKey = () =>
    (crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`);

/**
 * 서버가 결과를 확정했는가 — 성공했거나(error 없음), 처리하지 않겠다고 답했다(4xx).
 * 확정되지 않은 것은 네트워크 실패(서버에 닿았는지 모름), 5xx, "같은 요청 처리 중"(409)이다 — 이때는 같은 키로 다시 보내야 한다.
 */
export function isSettled(error) {
    if (!error) return true;
    if (!(error instanceof ApiError)) return false;
    return error.status >= 400 && error.status < 500 && error.code !== "IDEMPOTENT_REQUEST_IN_PROGRESS";
}

/**
 * 행동별 멱등키 보관함 — 같은 행동("deposit:3", "repay:12:3" …)에는 결과가 확정될 때까지 같은 키를 준다.
 *
 *   await keys.run(`deposit:${id}`, (key) => accountApi.deposit(id, amount, key));
 *
 * 실패해도 서버는 키를 풀어 두므로(멱등 실행기의 release) 같은 키로 다시 보내는 것이 안전하다.
 * 첫 요청이 실제로 처리됐는데 금액을 바꿔 다시 보내면 422(키 불일치)로 드러난다 — 확정된 응답이라 키를 버린다.
 */
export function createKeyRing(makeKey = newIdempotencyKey) {
    const keys = new Map();
    const keyFor = (action) => {
        if (!keys.has(action)) keys.set(action, makeKey());
        return keys.get(action);
    };
    const settle = (action, error) => {
        if (isSettled(error)) keys.delete(action);
    };
    /** 행동 하나를 보낸다 — send(key)에 키를 넘기고, 결과에 따라 키를 버리거나 남긴다 */
    const run = async (action, send) => {
        try {
            const result = await send(keyFor(action));
            settle(action);
            return result;
        } catch (e) {
            settle(action, e);
            throw e;
        }
    };
    return { keyFor, settle, run, clear: () => keys.clear() };
}

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
