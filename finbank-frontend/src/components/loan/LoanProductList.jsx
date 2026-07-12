import React from "react";

const won = (v) => Number(v).toLocaleString("ko-KR");
const pct = (v) => (Number(v) * 100).toFixed(1);

const TYPE_LABEL = {
    EQUAL_PAYMENT: "원리금균등",
    EQUAL_PRINCIPAL: "원금균등",
    BULLET: "만기일시",
};

/** 대출 상품 카드 목록 */
export default function LoanProductList({ products, selectedId, onSelect }) {
    if (!products?.length) {
        return <p className="text-xs text-slate-500">판매 중인 상품이 없습니다.</p>;
    }
    return (
        <div className="space-y-3">
            {products.map((p) => (
                <button
                    key={p.id}
                    type="button"
                    onClick={() => onSelect(p)}
                    className={`w-full text-left bg-slate-900 border rounded-2xl p-4 transition
                        ${selectedId === p.id ? "border-blue-500 ring-1 ring-blue-500" : "border-slate-800 hover:border-slate-600"}`}
                >
                    <div className="flex items-center justify-between mb-1">
                        <span className="text-sm font-semibold">{p.name}</span>
                        <span className="text-xs px-2 py-0.5 rounded-full bg-slate-800 border border-slate-700">
                            {TYPE_LABEL[p.repaymentType] ?? p.repaymentType}
                        </span>
                    </div>
                    <div className="text-xs text-slate-400 space-y-0.5">
                        <div>연 {pct(p.interestRate)}% <span className="text-slate-600">(연체 가산 +{pct(p.overdueExtraRate)}%p)</span></div>
                        <div>{won(p.minAmount)}원 ~ {won(p.maxAmount)}원 · 최대 {p.maxTermMonths}개월</div>
                    </div>
                </button>
            ))}
        </div>
    );
}
