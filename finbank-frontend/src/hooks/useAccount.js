import { useState } from "react";
import { accountApi } from "../api/accountApi";

export function useAccount(auth, notify, onLogout) {
    const [accounts, setAccounts] = useState([]);
    const [selected, setSelected] = useState(null);
    const [loading, setLoading] = useState(false);

    const loadAccounts = async () => {
        if (!auth?.token) return;
        try {
            setLoading(true);
            const data = await accountApi.getAccounts();
            setAccounts(data);
            if (data.length > 0) {
                await selectAccount(data[0].id, false);
            } else {
                setSelected(null);
            }
        } catch (e) {
            if (String(e.message).includes("HTTP 403")) {
                onLogout();
            } else {
                notify(e.message, "error");
            }
        } finally {
            setLoading(false);
        }
    };

    const selectAccount = async (id, showToast = true) => {
        try {
            setLoading(true);
            const data = await accountApi.getAccountDetail(id);
            setSelected(data);
            if (showToast) notify("계좌를 불러왔습니다.", "success");
        } catch (e) {
            notify(e.message, "error");
        } finally {
            setLoading(false);
        }
    };

    return {
        accounts, selected, loading,
        setAccounts, setSelected, setLoading,
        loadAccounts, selectAccount
    };
}