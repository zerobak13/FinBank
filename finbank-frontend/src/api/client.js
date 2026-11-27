// 개발 서버(vite)에서는 같은 출처의 /api를 vite proxy가 백엔드(:8080)로 넘긴다 — localhost든 127.0.0.1이든 그대로 된다.
// 배포 빌드는 VITE_API_URL(빌드 환경변수)을 쓰고, 없으면 지금의 배포 주소(Render)를 쓴다.
const env = import.meta.env ?? {};   // node:test로 돌릴 때는 import.meta.env가 없다
const API_URL = env.VITE_API_URL ?? (env.DEV ? "" : "https://finbank-backend.onrender.com");

/** 로그인 정보를 담는 localStorage 키 — useAuth도 이 값을 쓴다 */
export const AUTH_KEY = "finbankAuth";

/** 로그인이 만료됐고 토큰 재발급도 실패했을 때 화면 전체에 알리는 이벤트 — App이 듣고 로그아웃한다 */
export const SESSION_EXPIRED_EVENT = "finbank:session-expired";

/**
 * API 오류 — 메시지뿐 아니라 HTTP 상태와 서버 에러 코드를 함께 들고 다닌다.
 *
 * 여신 API는 409(이미 상환된 회차 / 동일 요청 처리 중)와 422(멱등키 재사용)를 구분해 내려주는데,
 * 문자열 메시지만 남기면 화면이 "재시도해도 되는 상황인지"를 판단할 수 없다.
 */
export class ApiError extends Error {
    constructor(message, status, code) {
        super(message);
        this.name = "ApiError";
        this.status = status;
        this.code = code;
    }

    /** 네트워크 단절 등 "서버에 닿았는지조차 모르는" 실패 — 같은 멱등키로 재시도해도 안전하다 */
    get isNetworkFailure() {
        return this.status === 0;
    }
}

/** 저장소의 로그인 정보 — 재발급 뒤의 최신 토큰은 React 상태(auth)가 아니라 여기에 있다 */
export function readAuth() {
    try {
        const raw = localStorage.getItem(AUTH_KEY);
        return raw ? JSON.parse(raw) : null;
    } catch {
        return null;
    }
}

/**
 * Access Token 재발급 — 여러 요청이 동시에 401을 받아도 재발급은 한 번만 한다.
 *
 * 서버는 Refresh Token을 쓸 때마다 새 것으로 바꾸고 옛 것은 버린다(Rotation).
 * 요청마다 따로 재발급하면 두 번째 요청은 이미 버려진 토큰을 내밀어 실패하고, 멀쩡한 세션이 로그아웃된다.
 */
let refreshing = null;

function refreshAccessToken() {
    if (!refreshing) {
        refreshing = requestNewTokens().finally(() => { refreshing = null; });
    }
    return refreshing;
}

async function requestNewTokens() {
    const auth = readAuth();
    if (!auth?.refreshToken) return false;   // 이 기능 전에 로그인해 Refresh Token이 없는 경우 — 다시 로그인
    let data = null;
    try {
        const res = await fetch(`${API_URL}/api/auth/refresh`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ refreshToken: auth.refreshToken }),
        });
        if (res.ok) data = await res.json();
    } catch { /* 네트워크 실패 — 아래에서 재발급 실패로 처리 */ }

    // 응답을 기다리는 사이 저장소가 바뀌었을 수 있다.
    // 로그아웃했으면(비었으면) 세션을 되살리지 않고, 다른 탭이 먼저 재발급했으면 그 토큰을 쓴다.
    const latest = readAuth();
    if (latest?.refreshToken !== auth.refreshToken) return !!latest?.token;
    if (!data) return false;

    localStorage.setItem(AUTH_KEY, JSON.stringify({
        ...auth,
        token: data.accessToken,
        refreshToken: data.refreshToken,
    }));
    return true;
}

function expireSession() {
    if (!readAuth()) return;   // 동시에 실패한 다른 요청이 이미 처리했다 (알림은 한 번만)
    localStorage.removeItem(AUTH_KEY);
    window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
}

async function send(path, options) {
    const token = readAuth()?.token;
    const headers = {
        "Content-Type": "application/json",
        ...(options.headers || {}),
    };
    if (token) {
        headers["Authorization"] = `Bearer ${token}`;
    }

    try {
        return { res: await fetch(`${API_URL}${path}`, { ...options, headers }), sentToken: token };
    } catch {
        // fetch 자체가 실패하면 요청이 서버에 도달했는지 알 수 없다.
        // 돈이 움직이는 요청에서 가장 위험한 순간이고, 멱등키가 존재하는 이유다.
        throw new ApiError("네트워크 오류로 요청을 완료하지 못했습니다.", 0, "NETWORK_ERROR");
    }
}

async function toApiError(res) {
    let message = `요청 실패 (HTTP ${res.status})`;
    let code = null;
    try {
        const text = await res.text();
        if (text) {
            try {
                const data = JSON.parse(text);
                message = data?.message ?? text;
                code = data?.code ?? null;
            } catch {
                message = text;
            }
        }
    } catch {}
    return new ApiError(message, res.status, code);
}

export async function client(path, options = {}) {
    let { res, sentToken } = await send(path, options);

    // 401 = 토큰이 없거나 만료됨 → 한 번 재발급받고 같은 요청을 다시 보낸다.
    // 401은 보안 필터에서 막힌 것이라 서버에서 아무 일도 일어나지 않았다 — 같은 멱등키로 다시 보내도 안전하다.
    // 로그인·재발급 요청 자체의 401(비밀번호 틀림 등)은 그대로 돌려준다.
    if (res.status === 401 && sentToken && !path.startsWith("/api/auth/")) {
        const current = readAuth()?.token;
        const renewed = (current && current !== sentToken)   // 다른 요청이 방금 재발급을 마쳤다
            || await refreshAccessToken();
        if (renewed) {
            ({ res } = await send(path, options));
        }
        if (res.status === 401) {
            expireSession();
            throw new ApiError("로그인이 만료되었습니다. 다시 로그인해 주세요.", 401, "SESSION_EXPIRED");
        }
    }

    if (!res.ok) {
        throw await toApiError(res);
    }
    return res.status === 204 ? null : res.json();
}
