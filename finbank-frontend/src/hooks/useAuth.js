import { useState } from "react";
import { AUTH_KEY, readAuth } from "../api/client";

/** 로그인 정보 — 저장소 읽기·키 이름은 client.js 한 곳에 둔다 (재발급도 같은 저장소를 쓴다) */
export function useAuth() {
    const [auth, setAuth] = useState(readAuth);

    const saveAuth = (nextAuth) => {
        setAuth(nextAuth);
        if (nextAuth) {
            localStorage.setItem(AUTH_KEY, JSON.stringify(nextAuth));
        } else {
            localStorage.removeItem(AUTH_KEY);
        }
    };

    return { auth, saveAuth };
}
