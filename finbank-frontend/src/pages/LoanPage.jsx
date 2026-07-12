import React, { useEffect, useState } from "react";
import LoanProductList from "../components/loan/LoanProductList";
import LoanApplyForm from "../components/loan/LoanApplyForm";
import MyLoanList from "../components/loan/MyLoanList";
import { loanApi } from "../api/loanApi";

/**
 * 대출 메뉴 페이지 (뼈대).
 *
 * 백엔드 대출 API(C1-2)가 배포되기 전까지는 V4 시드와 동일한 정적 상품 목록을
 * 보여주고, 신청 시 준비 중 안내를 띄운다. API가 열리면 자동으로 실데이터로 전환된다.
 */
const FALLBACK_PRODUCTS = [
    { id: 1, name: "직장인 신용대출 (원리금균등)", repaymentType: "EQUAL_PAYMENT",   interestRate: 0.059, overdueExtraRate: 0.03, minAmount: 1000000, maxAmount: 50000000, maxTermMonths: 60 },
    { id: 2, name: "신용대출 (원금균등)",          repaymentType: "EQUAL_PRINCIPAL", interestRate: 0.055, overdueExtraRate: 0.03, minAmount: 1000000, maxAmount: 30000000, maxTermMonths: 36 },
    { id: 3, name: "단기 신용대출 (만기일시)",     repaymentType: "BULLET",          interestRate: 0.068, overdueExtraRate: 0.03, minAmount: 1000000, maxAmount: 20000000, maxTermMonths: 12 },
];

export default function LoanPage({ auth, loading, setLoading, notify }) {
    const [products, setProducts] = useState(FALLBACK_PRODUCTS);
    const [selectedProduct, setSelectedProduct] = useState(null);
    const [loans, setLoans] = useState([]);
    const [apiReady, setApiReady] = useState(false); // 대출 API 연동 여부

    useEffect(() => {
        if (!auth?.token) return;
        (async () => {
            try {
                const data = await loanApi.getProducts();
                setProducts(data);
                setApiReady(true);
                const myLoans = await loanApi.getMyLoans();
                setLoans(myLoans?.content ?? myLoans ?? []);
            } catch {
                // 대출 API 미배포 상태 — 정적 상품 목록으로 표시 (신청은 안내 처리)
                setApiReady(false);
            }
        })();
    }, [auth?.token]);

    const handleApply = async (payload) => {
        if (!apiReady) {
            notify("대출 신청 API는 준비 중입니다. (백엔드 C1-2 배포 후 사용 가능)", "error");
            return;
        }
        try {
            setLoading(true);
            const result = await loanApi.apply(payload);
            if (result.status === "APPROVED") {
                notify("심사 통과! 대출이 승인되었습니다.", "success");
            } else {
                notify(`심사 탈락: ${result.rejectReason ?? "사유 미상"}`, "error");
            }
        } catch (e) {
            notify(e.message, "error");
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
        <div className="max-w-6xl mx-auto px-6 py-6 grid grid-cols-12 gap-6">
            <aside className="col-span-4 space-y-3">
                <h2 className="text-sm font-semibold text-slate-300">대출 상품</h2>
                <LoanProductList
                    products={products}
                    selectedId={selectedProduct?.id}
                    onSelect={setSelectedProduct}
                />
            </aside>

            <section className="col-span-4">
                <LoanApplyForm product={selectedProduct} loading={loading} onSubmit={handleApply} />
            </section>

            <aside className="col-span-4">
                <MyLoanList loans={loans} apiReady={apiReady} />
            </aside>
        </div>
    );
}
