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
};