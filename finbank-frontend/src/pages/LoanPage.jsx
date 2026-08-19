import React, { useEffect, useState } from "react";
import LoanProductList from "../components/loan/LoanProductList";
import LoanApplyForm from "../components/loan/LoanApplyForm";
import MyLoanList from "../components/loan/MyLoanList";
import LoanRepaymentPanel from "../components/loan/LoanRepaymentPanel";
import { loanApi, newIdempotencyKey, LOAN_ERROR_MESSAGE } from "../api/loanApi";

/**
 * 대출 메뉴 페이지 — 신청 → 실행 → 상환의 전 흐름.
 *
 * 실행·상환은 돈이 움직이는 요청이므로 <b>사용자 행동 1회당 멱등키 1개</b>를 만들어 보낸다.
 * (재시도는 api 레이어가 같은 키로 처리한다 — loanApi.js 참고)
 */
const FALLBACK_PRODUCTS = [
    { id: 1, name: "직장인 신용대출 (원리금균등)", repaymentType: "EQUAL_PAYMENT",   interestRate: 0.059, overdueExtraRate: 0.03, minAmount: 1000000, maxAmount: 50000000, maxTermMonths: 60 },
    { id: 2, name: "신용대출 (원금균등)",          repaymentType: "EQUAL_PRINCIPAL", interestRate: 0.055, overdueExtraRate: 0.03, minAmount: 1000000, maxAmount: 30000000, maxTermMonths: 36 },
    { id: 3, name: "단기 신용대출 (만기일시)",     repaymentType: "BULLET",          interestRate: 0.068, overdueExtraRate: 0.03, minAmount: 1000000, maxAmount: 20000000, maxTermMonths: 12 },
];

const won = (v) => Number(v ?? 0).toLocaleString("ko-KR");

export default function LoanPage({ auth, accounts = [], loading, setLoading, notify, onBalanceChanged }) {
    const [products, setProducts] = useState(FALLBACK_PRODUCTS);
    const [selectedProduct, setSelectedProduct] = useState(null);
    const [applications, setApplications] = useState([]);
    const [loans, setLoans] = useState([]);
    const [selectedLoanId, setSelectedLoanId] = useState(null);
    const [schedules, setSchedules] = useState([]);
    const [apiReady, setApiReady] = useState(false);

    /** 에러 응답을 사용자 문구로 — 서버 에러 코드가 있으면 그것을 우선한다 */
    const explain = (e) => LOAN_ERROR_MESSAGE[e?.code] ?? e?.message ?? "요청을 처리하지 못했습니다.";

    const loadApplications = async () => {
        try {
            setApplications((await loanApi.getMyApplications()) ?? []);
        } catch { /* 이력 조회 실패는 신청 기능과 독립 — 조용히 무시 */ }
    };

    const loadLoans = async () => {
        try {
            const list = (await loanApi.getMyLoans()) ?? [];
            setLoans(list);
            return list;
        } catch {
            return [];
        }
    };

    const loadSchedules = async (loanId) => {
        if (!loanId) {
            setSchedules([]);
            return;
        }
        try {
            setSchedules((await loanApi.getSchedules(loanId)) ?? []);
        } catch {
            setSchedules([]);
        }
    };

    useEffect(() => {
        if (!auth?.token) return;
        (async () => {
            try {
                setProducts(await loanApi.getProducts());
                setApiReady(true);
            } catch {
                setApiReady(false);
                return;
            }
            await loadApplications();
            const list = await loadLoans();
            if (list.length) setSelectedLoanId((prev) => prev ?? list[0].loanId);
        })();
    }, [auth?.token]);

    useEffect(() => { loadSchedules(selectedLoanId); }, [selectedLoanId]);

    /** 실행·상환 후 공통 갱신: 신청 이력 + 대출 목록 + 스케줄 + 계좌 잔액 */
    const refreshAll = async (loanId) => {
        await Promise.all([loadApplications(), loadLoans(), loadSchedules(loanId ?? selectedLoanId)]);
        onBalanceChanged?.();
    };

    const handleApply = async (payload) => {
        if (!apiReady) return notify("대출 API에 연결할 수 없습니다.", "error");
        try {
            setLoading(true);
            const result = await loanApi.apply(payload);
            if (result.status === "APPROVED") {
                notify("심사 통과! 승인되었습니다. 실행 버튼으로 대출금을 받으세요.", "success");
            } else {
                notify(`심사 탈락: ${result.rejectReason ?? "사유 미상"}`, "error");
            }
            await loadApplications();
        } catch (e) {
            notify(explain(e), "error");
        } finally {
            setLoading(false);
        }
    };

    const handleExecute = async (applicationId) => {
        // 사용자 행동 1회 = 멱등키 1개. 재시도해도 이 키를 그대로 쓴다.
        const key = newIdempotencyKey();
        try {
            setLoading(true);
            const res = await loanApi.execute(applicationId, key);
            notify(`대출 실행 완료 — 계좌 잔액 ${won(res.accountBalanceAfter)}원`, "success");
            setSelectedLoanId(res.loanId);
            await refreshAll(res.loanId);
        } catch (e) {
            notify(explain(e), "error");
            await refreshAll(); // 409(이미 실행됨)라면 화면이 낡은 것이므로 서버 상태에 맞춘다
        } finally {
            setLoading(false);
        }
    };

    const handleRepay = async (loanId, installmentNo) => {
        const key = newIdempotencyKey();
        try {
            setLoading(true);
            const res = await loanApi.repay(loanId, installmentNo, key);
            notify(
                `${res.installmentNo}회차 상환 완료 — ${won(res.totalPaid)}원 출금 ` +
                `(잔여 원금 ${won(res.loanBalanceAfter)}원)`,
                "success",
            );
            await refreshAll(loanId);
        } catch (e) {
            notify(explain(e), "error");
            await refreshAll(loanId);
        } finally {
            setLoading(false);
        }
    };

    const handlePrepay = async (loanId) => {
        const loan = loans.find((l) => l.loanId === loanId);
        const ok = window.confirm(
            `전액 중도상환하면 잔여 원금 ${won(loan?.balance)}원에\n` +
            `경과이자와 중도상환수수료가 더해져 한 번에 출금됩니다. 진행할까요?`,
        );
        if (!ok) return;

        const key = newIdempotencyKey();
        try {
            setLoading(true);
            const res = await loanApi.prepay(loanId, key);
            notify(
                `중도상환 완료 — 원금 ${won(res.principalPortion)}원 + 경과이자 ${won(res.interestPortion)}원 ` +
                `+ 수수료 ${won(res.feePortion)}원 = ${won(res.totalPaid)}원`,
                "success",
            );
            await refreshAll(loanId);
        } catch (e) {
            notify(explain(e), "error");
            await refreshAll(loanId);
        } finally {
            setLoading(false);
        }
    };

    if (!auth) {
        return (
            <div className="max-w-6xl mx-auto px-6 py-10 text-center text-sm text-slate-500">
                대출 메뉴는 로그인 후 이용할 수 있습니다.
            </div>
        );
    }

    return (
        <div className="max-w-6xl mx-auto px-6 py-6 space-y-6">
            <div className="grid grid-cols-12 gap-6">
                <aside className="col-span-12 lg:col-span-4 space-y-3">
                    <h2 className="text-sm font-semibold text-slate-300">대출 상품</h2>
                    <LoanProductList
                        products={products}
                        selectedId={selectedProduct?.id}
                        onSelect={setSelectedProduct}
                    />
                </aside>

                <section className="col-span-12 lg:col-span-4">
                    <LoanApplyForm product={selectedProduct} accounts={accounts}
                                   loading={loading} onSubmit={handleApply} />
                </section>

                <aside className="col-span-12 lg:col-span-4">
                    <MyLoanList
                        applications={applications}
                        apiReady={apiReady}
                        busy={loading}
                        onExecute={handleExecute}
                    />
                </aside>
            </div>

            <LoanRepaymentPanel
                loans={loans}
                selectedLoanId={selectedLoanId}
                onSelectLoan={setSelectedLoanId}
                schedules={schedules}
                busy={loading}
                onRepay={handleRepay}
                onPrepay={handlePrepay}
            />
        </div>
    );
}
