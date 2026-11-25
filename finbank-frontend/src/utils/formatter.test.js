// 금액 입력·날짜 표시 규칙 테스트 — Node 내장 테스트: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { parseWon, formatDateTime, MAX_WON } from "./formatter.js";

test("금액: 원 단위 정수만 받는다 — 1,500원처럼 천 원 단위가 아니어도 된다", () => {
    assert.equal(parseWon("1500"), 1500);
    assert.equal(parseWon(" 70000 "), 70000);
    assert.equal(parseWon(String(MAX_WON)), MAX_WON);
});

test("금액: 빈 값·0·소수·음수·지수 표기·상한 초과는 null", () => {
    for (const bad of ["", "0", "0.5", "1000.0", "-1000", "1e3", "abc", String(MAX_WON + 1)]) {
        assert.equal(parseWon(bad), null, `거절해야 하는 값: ${bad}`);
    }
});

test("금액: 초기 입금액은 0원도 된다", () => {
    assert.equal(parseWon("0", { allowZero: true }), 0);
    assert.equal(parseWon("-1", { allowZero: true }), null);
});

test("날짜: 서버 시각을 분까지 — 없으면 -", () => {
    assert.equal(formatDateTime("2026-11-25T14:03:12.123"), "2026-11-25 14:03");
    assert.equal(formatDateTime("2026-11-25"), "2026-11-25");
    assert.equal(formatDateTime(null), "-");
});
