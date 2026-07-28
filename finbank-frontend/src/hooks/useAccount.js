import { useState } from "react";
import { accountApi } from "../api/accountApi";

export function useAccount(auth, notify, onLogout) {
    const [accounts, setAccounts] = useState([]);
    const [selected, setSelected] = useState(null);
    const [loading, setLoading] = useState(false);

    /** 상세+거래내역 응답을 selected 상태 형태로 변환 */
    const toSelected = (account, txPage) => ({
        account,
        transactions: txPage.content,
        pageInfo: {
            page: txPage.page,
            size: txPage.size,
            totalElements: txPage.totalElements,
            totalPages: txPage.totalPages,
            first: txPage.first,
            last: txPage.last,
        },
    });

    /**
     * 계좌 목록 로드 (초기 진입/새 계좌 개설 시).
     * - 목록은 받자마자 즉시 반영한다 (상세 로딩을 기다리지 않음 → 왼쪽 UI 먼저 그려짐)
     * - 선택 계좌는 유지한다: 기존 선택이 목록에 남아 있으면 그대로, 없으면 첫 계좌
     */
    const loadAccounts = async () => {
        if (!auth?.token) return;
        try {
            setLoading(true);
            const data = await accountApi.getAccounts();
            setAccounts(data); // ← 왼쪽 목록 즉시 반영
            if (data.length === 0) {
                setSelected(null);
                return;
            }
            const keepId = selected?.account?.id;
            const targetId = data.some((a) => a.id === keepId) ? keepId : data[0].id;
            await selectAccount(targetId, false);
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

    /** 계좌 선택 (목록 클릭 시): 상세 + 거래내역 병렬 조회 */
    const selectAccount = async (id, showToast = true) => {
        try {
            setLoading(true);
            const [account, txPage] = await Promise.all([
                accountApi.getAccountDetail(id),
                accountApi.getAccountTransactions(id, 0, 20),
            ]);
            setSelected(toSelected(account, txPage));
            if (showToast) notify("계좌를 불러왔습니다.", "success");
        } catch (e) {
            notify(e.message, "error");
        } finally {
            setLoading(false);
        }
    };

    /**
     * 거래(입금/출금/이체) 직후 화면 갱신 전용.
     * 목록 + 상세 + 거래내역을 "병렬 3요청 1회"로 가져온다.
     * (기존: selectAccount → loadAccounts → 내부 selectAccount로 직렬 5요청 + 선택 리셋 버그)
     */
    const refreshAfterTransaction = async (accountId) => {
        try {
            setLoading(true);
            const [list, account, txPage] = await Promise.all([
                accountApi.getAccounts(),
                accountApi.getAccountDetail(accountId),
                accountApi.getAccountTransactions(accountId, 0, 20),
            ]);
            setAccounts(list);
            setSelected(toSelected(account, txPage));
        } catch (e) {
            notify(e.message, "error");
        } finally {
            setLoading(false);
        }
    };

    /**
     * 조용한 갱신 — 로딩 오버레이/토스트 없이 최신 상태만 반영.
     * 창 포커스 복귀·주기 폴링에서 호출한다. (다른 계좌에서 이체를 "받았을 때"
     * 수신자 화면은 스스로 알 수 없으므로, 이 방식으로 따라잡는다.)
     */
    const refreshSilently = async () => {
        if (!auth?.token) return;
        try {
            const list = await accountApi.getAccounts();
            setAccounts(list);
            const id = selected?.account?.id;
            if (id && list.some((a) => a.id === id)) {
                const [account, txPage] = await Promise.all([
                    accountApi.getAccountDetail(id),
                    accountApi.getAccountTransactions(id, 0, 20),
                ]);
                setSelected(toSelected(account, txPage));
            }
        } catch {
            // 백그라운드 갱신 실패는 조용히 무시 (다음 주기에 재시도)
        }
    };

    return {
        accounts, selected, loading,
        setAccounts, setSelected, setLoading,
        loadAccounts, selectAccount, refreshAfterTransaction, refreshSilently,
    };
}
