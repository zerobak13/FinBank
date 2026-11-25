export function classNames(...classes) {
    return classes.filter(Boolean).join(" ");
}

export const formatCurrency = (value) => {
    return (value || 0).toLocaleString() + "원";
};

/** 서버의 LocalDateTime("2026-11-25T14:03:12.123") → "2026-11-25 14:03" */
export const formatDateTime = (value) => {
    if (!value) return "-";
    const [date, time = ""] = String(value).split("T");
    return time ? `${date} ${time.slice(0, 5)}` : date;
};

/** 금액 상한 — 서버의 금액 규칙(15자리 이하)과 같다 */
export const MAX_WON = 999_999_999_999_999;

/**
 * 입력한 금액을 원 단위 정수로 바꾼다. 규칙에 맞지 않으면 null.
 *
 * 서버도 같은 규칙(0보다 큰 원 단위 정수, 15자리 이하)으로 거절하지만, 보내기 전에 화면에서 먼저 알려 준다.
 * 계좌 개설의 초기 입금액처럼 0원이 되는 곳은 allowZero.
 */
export function parseWon(value, { allowZero = false } = {}) {
    const text = String(value ?? "").trim();
    if (!/^\d+$/.test(text)) return null;   // 빈 값·소수·음수·지수 표기(1e3)는 거절
    const won = Number(text);
    if (!Number.isSafeInteger(won) || won > MAX_WON) return null;
    if (won === 0 && !allowZero) return null;
    return won;
}
