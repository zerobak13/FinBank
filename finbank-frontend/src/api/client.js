const API_URL = window.location.hostname === 'localhost'
    ? "http://localhost:8080"
    : "https://finbank-backend.onrender.com";

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

export async function client(path, options = {}) {
    const rawAuth = localStorage.getItem("finbankAuth");
    const auth = rawAuth ? JSON.parse(rawAuth) : null;

    const headers = {
        "Content-Type": "application/json",
        ...(options.headers || {}),
    };

    if (auth?.token) {
        headers["Authorization"] = `Bearer ${auth.token}`;
    }

    let res;
    try {
        res = await fetch(`${API_URL}${path}`, { ...options, headers });
    } catch {
        // fetch 자체가 실패하면 요청이 서버에 도달했는지 알 수 없다.
        // 돈이 움직이는 요청에서 가장 위험한 순간이고, 멱등키가 존재하는 이유다.
        throw new ApiError("네트워크 오류로 요청을 완료하지 못했습니다.", 0, "NETWORK_ERROR");
    }

    if (!res.ok) {
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
        throw new ApiError(message, res.status, code);
    }

    return res.status === 204 ? null : res.json();
}
