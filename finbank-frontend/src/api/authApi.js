import { client } from "./client";

export const authApi = {
    login: (email, password) =>
        client("/api/auth/login", {
            method: "POST",
            body: JSON.stringify({ email, password })
        }),
    register: (email, name, password) =>
        client("/api/auth/register", {
            method: "POST",
            body: JSON.stringify({ email, name, password })
        }),
    /** Refresh Token 폐기 — 로그아웃하면 서버에서도 바로 못 쓰게 한다 */
    logout: (refreshToken) =>
        client("/api/auth/logout", {
            method: "POST",
            body: JSON.stringify({ refreshToken })
        }),
};