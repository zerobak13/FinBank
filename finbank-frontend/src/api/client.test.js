// client.js의 토큰 만료 처리 테스트 — 추가 의존성 없이 Node 내장 테스트로 돌린다: npm test
//
// 브라우저 대신 localStorage·window·fetch를 흉내 낸다.
// 서버가 401을 주는 상황(Access Token 만료)에서 재발급 → 재시도 → 실패 시 로그아웃이 맞게 되는지 본다.
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";

const store = new Map();
globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
};
const events = [];
globalThis.window = { location: { hostname: "localhost" }, dispatchEvent: (e) => { events.push(e.type); return true; } };
let calls = [];
let handler = null;
globalThis.fetch = async (url, init = {}) => { calls.push({ url, init }); return handler(url, init, calls.length); };

// client.js가 window·localStorage·fetch를 쓰므로 흉내를 다 만든 뒤에 불러온다
const { client, ApiError, SESSION_EXPIRED_EVENT, readAuth } = await import("./client.js");

const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const empty = (status) => new Response(null, { status });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const isRefresh = (url) => url.endsWith("/api/auth/refresh");
const login = (token, refreshToken) => store.set("finbankAuth", JSON.stringify({ token, refreshToken, email: "u@x", name: "U" }));
const isSessionExpired = (e) => e instanceof ApiError && e.status === 401 && e.code === "SESSION_EXPIRED";

beforeEach(() => { calls = []; events.length = 0; store.clear(); });

test("정상 요청은 저장된 토큰을 붙여 보낸다", async () => {
    login("A1", "R1");
    handler = () => json(200, { ok: true });
    assert.deepEqual(await client("/api/accounts"), { ok: true });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].init.headers.Authorization, "Bearer A1");
});

test("401 → 재발급 성공 → 같은 요청을 같은 멱등키·새 토큰으로 다시 보낸다", async () => {
    login("A1", "R1");
    handler = (url, init) => {
        if (isRefresh(url)) {
            assert.equal(JSON.parse(init.body).refreshToken, "R1");
            assert.equal(init.headers.Authorization, undefined);
            return json(200, { accessToken: "A2", refreshToken: "R2", email: "u@x", name: "U" });
        }
        return init.headers.Authorization === "Bearer A2" ? json(200, { id: 1 }) : empty(401);
    };
    const res = await client("/api/loans/1/repay", { method: "POST", headers: { "Idempotency-Key": "k-1" }, body: "{}" });
    assert.deepEqual(res, { id: 1 });
    assert.equal(calls.length, 3);
    assert.equal(calls[2].init.method, "POST");
    assert.equal(calls[2].init.headers["Idempotency-Key"], "k-1");
    assert.deepEqual(readAuth(), { token: "A2", refreshToken: "R2", email: "u@x", name: "U" });
    assert.equal(events.length, 0);
});

test("401 → 재발급 실패 → 저장소를 비우고 만료 이벤트를 한 번 보낸다", async () => {
    login("A1", "R1");
    handler = (url) => (isRefresh(url) ? json(401, { code: "INVALID_REFRESH_TOKEN", message: "x" }) : empty(401));
    await assert.rejects(client("/api/accounts"), isSessionExpired);
    assert.equal(readAuth(), null);
    assert.deepEqual(events, [SESSION_EXPIRED_EVENT]);
    assert.equal(calls.length, 2);
});

test("동시에 401 세 건 → 재발급은 한 번만, 세 건 모두 재시도 성공", async () => {
    login("A1", "R1");
    handler = async (url, init) => {
        if (isRefresh(url)) { await sleep(20); return json(200, { accessToken: "A2", refreshToken: "R2" }); }
        return init.headers.Authorization === "Bearer A2" ? json(200, { url }) : empty(401);
    };
    const rs = await Promise.all(["/api/a", "/api/b", "/api/c"].map((p) => client(p)));
    assert.equal(calls.filter((c) => isRefresh(c.url)).length, 1);
    assert.deepEqual(rs.map((r) => r.url.slice(-1)), ["a", "b", "c"]);
});

test("동시에 401 세 건 + 재발급 실패 → 만료 알림은 한 번", async () => {
    login("A1", "R1");
    handler = async (url) => { if (isRefresh(url)) await sleep(10); return empty(401); };
    const rs = await Promise.allSettled(["/api/a", "/api/b", "/api/c"].map((p) => client(p)));
    assert.ok(rs.every((r) => r.status === "rejected" && isSessionExpired(r.reason)));
    assert.deepEqual(events, [SESSION_EXPIRED_EVENT]);
    assert.equal(calls.filter((c) => isRefresh(c.url)).length, 1);
});

test("로그인 실패 401은 재발급하지 않고 서버 메시지를 그대로 돌려준다", async () => {
    login("A1", "R1");
    handler = () => json(401, { code: "LOGIN_FAILED", message: "이메일 또는 비밀번호가 올바르지 않습니다." });
    await assert.rejects(client("/api/auth/login", { method: "POST", body: "{}" }), (e) => e.status === 401 && e.code === "LOGIN_FAILED");
    assert.equal(calls.length, 1);
    assert.equal(events.length, 0);
    assert.notEqual(readAuth(), null);
});

test("Refresh Token 없이 저장된 예전 로그인 → 재발급 요청 없이 만료 처리", async () => {
    store.set("finbankAuth", JSON.stringify({ token: "A1", email: "u@x", name: "U" }));
    handler = () => empty(401);
    await assert.rejects(client("/api/accounts"), isSessionExpired);
    assert.equal(calls.length, 1);
    assert.deepEqual(events, [SESSION_EXPIRED_EVENT]);
});

test("응답을 기다리는 사이 다른 요청이 재발급을 끝냈으면 재발급 없이 바로 재시도", async () => {
    login("A1", "R1");
    handler = (url, init, n) => {
        if (n === 1) { login("A2", "R2"); return empty(401); }
        assert.ok(!isRefresh(url));
        return init.headers.Authorization === "Bearer A2" ? json(200, { ok: 1 }) : empty(401);
    };
    assert.deepEqual(await client("/api/accounts"), { ok: 1 });
    assert.equal(calls.length, 2);
});

test("재발급은 됐는데 재시도도 401 → 만료 처리", async () => {
    login("A1", "R1");
    handler = (url) => (isRefresh(url) ? json(200, { accessToken: "A2", refreshToken: "R2" }) : empty(401));
    await assert.rejects(client("/api/accounts"), isSessionExpired);
    assert.equal(calls.length, 3);
    assert.deepEqual(events, [SESSION_EXPIRED_EVENT]);
    assert.equal(readAuth(), null);
});

test("재발급을 기다리는 사이 로그아웃 → 세션을 되살리지 않고 만료 알림도 없다", async () => {
    login("A1", "R1");
    handler = async (url) => {
        if (isRefresh(url)) { store.delete("finbankAuth"); return json(200, { accessToken: "A2", refreshToken: "R2" }); }
        return empty(401);
    };
    await assert.rejects(client("/api/accounts"), isSessionExpired);
    assert.equal(readAuth(), null);
    assert.equal(events.length, 0);
    assert.equal(calls.length, 2);
});

test("다른 탭이 먼저 재발급해 우리 재발급이 실패 → 그 탭이 저장한 새 토큰으로 재시도", async () => {
    login("A1", "R1");
    handler = (url, init) => {
        if (isRefresh(url)) { login("A9", "R9"); return json(401, { code: "INVALID_REFRESH_TOKEN" }); }
        return init.headers.Authorization === "Bearer A9" ? json(200, { ok: 9 }) : empty(401);
    };
    assert.deepEqual(await client("/api/accounts"), { ok: 9 });
    assert.equal(events.length, 0);
});

test("재발급 요청이 네트워크 오류 → 만료 처리", async () => {
    login("A1", "R1");
    handler = (url) => { if (isRefresh(url)) throw new TypeError("Failed to fetch"); return empty(401); };
    await assert.rejects(client("/api/accounts"), isSessionExpired);
    assert.deepEqual(events, [SESSION_EXPIRED_EVENT]);
});

test("네트워크 오류 → NETWORK_ERROR(status 0)", async () => {
    login("A1", "R1");
    handler = () => { throw new TypeError("Failed to fetch"); };
    await assert.rejects(client("/api/accounts"), (e) => e.status === 0 && e.code === "NETWORK_ERROR" && e.isNetworkFailure);
});

test("401이 아닌 오류는 재발급 없이 서버의 code·message 그대로", async () => {
    login("A1", "R1");
    handler = () => json(409, { code: "INSTALLMENT_ALREADY_PAID", message: "이미 상환된 회차입니다." });
    await assert.rejects(client("/api/loans/1/repay", { method: "POST" }),
        (e) => e.status === 409 && e.code === "INSTALLMENT_ALREADY_PAID" && e.message === "이미 상환된 회차입니다.");
    assert.equal(calls.length, 1);
});

test("토큰 없이 보낸 요청의 401 → 재발급·이벤트 없이 일반 오류", async () => {
    handler = () => empty(401);
    await assert.rejects(client("/api/accounts"), (e) => e.status === 401 && e.code === null && e.message === "요청 실패 (HTTP 401)");
    assert.equal(calls.length, 1);
    assert.equal(events.length, 0);
});

test("204 → null", async () => {
    login("A1", "R1");
    handler = () => empty(204);
    assert.equal(await client("/api/x", { method: "DELETE" }), null);
});
