import React, { useEffect, useRef, useState } from "react";
import LoanProductList from "../components/loan/LoanProductList";
import LoanApplyForm from "../components/loan/LoanApplyForm";
import MyLoanList from "../components/loan/MyLoanList";
import LoanRepaymentPanel from "../components/loan/LoanRepaymentPanel";
import { loanApi, LOAN_ERROR_MESSAGE, rejectReasonText } from "../api/loanApi";
import { createKeyRing } from "../api/idempotency";

/**
 * 대출 메뉴 페이지 — 신청 → 실행 → 상환의 전 흐름.
 *
 * 실행·상환은 돈이 움직이는 요청이므로 <b>같은 행동에는 결과가 확정될 때까지 같은 멱등키</b>를 보낸다
 * (응답을 못 받고 다시 눌러도 같은 요청 — api/idempotency.js의 createKeyRing). 회차 상환은 회차까지 행동에 넣는다.
 */

const won = (v) => Number(v ?? 0).toLocaleString("ko-KR");

export default function LoanPage({ auth, accounts = [], loading, setLoading, notify, onBalanceChanged }) {
    const [products, setProducts] = useState(null);   // null = 불러오는 중
    const [selectedProduct, setSelectedProduct] = useState(null);
    const [applications, setApplications] = useState([]);
    const [loans, setLoans] = useState([]);
    const [selectedLoanId, setSelectedLoanId] = useState(null);
    const [schedules, setSchedules] = useState([]);
    const [apiReady, setApiReady] = useState(false);
    const keys = useRef(createKeyRing()).current;   // 사용자가 바뀌면 이 화면이 새로 그려져 키도 새로 시작한다

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
                setProducts([]);
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
                notify(`심사 탈락: ${rejectReasonText(result.rejectReason)}`, "error");
            }
            await loadApplications();
        } catch (e) {
            notify(explain(e), "error");
        } finally {
            setLoading(false);
        }
    };

    const handleExecute = async (applicationId) => {
        try {
            setLoading(true);
            const res = await keys.run(`execute:${applicationId}`, (key) => loanApi.execute(applicationId, key));
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
        try {
            setLoading(true);
            const res = await keys.run(`repay:${loanId}:${installmentNo}`,
                (key) => loanApi.repay(loanId, installmentNo, key));
            // 연체 회차면 쌓인 연체이자까지 함께 출금된다
            const penaltyNote = Number(res.penaltyPortion ?? 0) > 0
                ? `, 연체이자 ${won(res.penaltyPortion)}원 포함`
                : "";
            notify(
                `${res.installmentNo}회차 상환 완료 — ${won(res.totalPaid)}원 출금${penaltyNote} ` +
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
        // 먼저 견적을 받아 출금될 총액을 보여 준다 (같은 날이면 실제 금액과 같다 — 서버가 같은 계산을 쓴다)
        let quote;
        try {
            setLoading(true);
            quote = await loanApi.quotePrepayment(loanId);
        } catch (e) {
            notify(explain(e), "error");
            await refreshAll(loanId);
            return;
        } finally {
            setLoading(false);
        }
        const feeNote = Number(quote.feePortion ?? 0) > 0
            ? `중도상환수수료 ${won(quote.feePortion)}원 (${quote.feeFreeFrom}부터는 없음)`
            : "중도상환수수료 없음";
        const ok = window.confirm(
            `전액 중도상환하면 ${won(quote.totalAmount)}원이 한 번에 출금됩니다.\n` +
            `원금 ${won(quote.principalPortion)}원 + 경과이자 ${won(quote.interestPortion)}원 + ${feeNote}\n` +
            `진행할까요?`,
        );
        if (!ok) return;

        try {
            setLoading(true);
            const res = await keys.run(`prepay:${loanId}`, (key) => loanApi.prepay(loanId, key));
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
