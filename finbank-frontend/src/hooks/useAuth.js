import { useState } from "react";

export function useAuth() {
    const [auth, setAuth] = useState(() => {
        const raw = localStorage.getItem("finbankAuth");
        try {
            return raw ? JSON.parse(raw) : null;
        } catch {
            return null;
        }
    });

    const saveAuth = (nextAuth) => {
        setAuth(nextAuth);
        if (nextAuth) {
            localStorage.setItem("finbankAuth", JSON.stringify(nextAuth));
        } else {
            localStorage.removeItem("finbankAuth");
        }
    };

    return { auth, saveAuth };
}