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

    const res = await fetch(path, { ...options, headers });

    if (!res.ok) {
        let message = `요청 실패 (HTTP ${res.status})`;
        try {
            const text = await res.text();
            if (text) {
                try {
                    const data = JSON.parse(text);
                    message = data?.message ?? text;
                } catch {
                    message = text;
                }
            }
        } catch {}
        throw new Error(message);
    }

    return res.status === 204 ? null : res.json();
}