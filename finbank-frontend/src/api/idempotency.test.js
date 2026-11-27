// idempotency.js의 행동별 멱등키 보관함 테스트 — npm test
//
// 핵심: 서버가 결과를 확정해 주기 전(네트워크 실패·5xx·처리 중)에는 같은 행동에 같은 키가 나가야 한다.
// 그래야 응답을 못 받은 사용자가 다시 눌러도 서버가 "아까 그 요청"으로 알아본다.
import { test } from "node:test";
import assert from "node:assert/strict";

// client.js가 쓰는 브라우저 전역 — 이 테스트는 네트워크를 쓰지 않는다
globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
globalThis.window = { dispatchEvent: () => true };

const { createKeyRing, isSettled } = await import("./idempotency.js");
const { ApiError } = await import("./client.js");

let n = 0;
const ring = () => createKeyRing(() => `k${++n}`);
const fail = (status, code = null) => async () => { throw new ApiError("x", status, code); };

test("성공하기 전까지 같은 행동에는 같은 키, 성공하면 다음 행동은 새 키", async () => {
    const keys = ring();
    const sent = [];
    await assert.rejects(keys.run("deposit:1", async (key) => { sent.push(key); throw new ApiError("net", 0, "NETWORK_ERROR"); }));
    await keys.run("deposit:1", async (key) => { sent.push(key); return "ok"; });
    await keys.run("deposit:1", async (key) => { sent.push(key); return "ok"; });
    assert.equal(sent[0], sent[1]);      // 네트워크 실패 뒤 다시 눌러도 같은 키
    assert.notEqual(sent[1], sent[2]);   // 성공한 뒤의 새 행동은 새 키
});

test("확정되지 않은 실패(네트워크·5xx·같은 요청 처리 중)는 키를 남긴다", async () => {
    for (const [status, code] of [[0, "NETWORK_ERROR"], [500, "INTERNAL_ERROR"], [503, null], [409, "IDEMPOTENT_REQUEST_IN_PROGRESS"]]) {
        const keys = ring();
        const first = keys.keyFor("transfer:1");
        await assert.rejects(keys.run("transfer:1", fail(status, code)));
        assert.equal(keys.keyFor("transfer:1"), first, `${status} ${code}`);
    }
});

test("서버가 거절한 응답(4xx — 잔액 부족·키 불일치 422·이미 상환 409)은 확정이라 키를 버린다", async () => {
    for (const [status, code] of [[400, "INSUFFICIENT_BALANCE"], [422, "IDEMPOTENCY_KEY_MISMATCH"], [409, "ALREADY_REPAID"]]) {
        const keys = ring();
        const first = keys.keyFor("repay:1:3");
        await assert.rejects(keys.run("repay:1:3", fail(status, code)));
        assert.notEqual(keys.keyFor("repay:1:3"), first, `${status} ${code}`);
    }
});

test("행동이 다르면 키도 다르고, clear는 모든 키를 버린다 (로그아웃)", () => {
    const keys = ring();
    const a = keys.keyFor("repay:1:3");
    const b = keys.keyFor("repay:1:4");
    assert.notEqual(a, b);
    keys.clear();
    assert.notEqual(keys.keyFor("repay:1:3"), a);
});

test("isSettled — 응답이 없거나 ApiError가 아니면 확정이 아니다", () => {
    assert.equal(isSettled(undefined), true);                       // 성공
    assert.equal(isSettled(new Error("boom")), false);              // 알 수 없는 실패
    assert.equal(isSettled(new ApiError("x", 401, "SESSION_EXPIRED")), true);
});
