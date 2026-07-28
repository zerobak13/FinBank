import React, { useState } from "react";

const won = (v) => Number(v).toLocaleString("ko-KR");

/** 대출 신청 폼 — 상품 선택 후 금액/기간 입력 */
export default function LoanApplyForm({ product, loading, onSubmit }) {
    const [amount, setAmount] = useState("");
    const [termMonths, setTermMonths] = useState("");

    if (!product) {
        return (
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
                <h2 className="text-sm font-semibold mb-2">대출 신청</h2>
                <p className="text-xs text-slate-500">왼쪽에서 상품을 먼저 선택해주세요.</p>
            </div>
        );
    }

    const submit = (e) => {
        e.preventDefault();
        onSubmit({
            productId: product.id,
            requestedAmount: Number(amount),
            termMonths: Number(termMonths),
        });
    };

    return (
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
            <h2 className="text-sm font-semibold mb-1">대출 신청</h2>
            <p className="text-xs text-slate-400 mb-3">{product.name}</p>
            <form onSubmit={submit} className="space-y-2">
                <input
                    type="number" step="10000" min={product.minAmount} max={product.maxAmount}
                    value={amount} onChange={(e) => setAmount(e.target.value)} required
                    className="w-full bg-slate-950 border border-slate-700 rounded p-2 text-sm"
                    placeholder={`신청 금액 (${won(product.minAmount)} ~ ${won(product.maxAmount)}원)`}
                />
                <input
                    type="number" min="1" max={product.maxTermMonths}
                    value={termMonths} onChange={(e) => setTermMonths(e.target.value)} required
                    className="w-full bg-slate-950 border border-slate-700 rounded p-2 text-sm"
                    placeholder={`기간 (1 ~ ${product.maxTermMonths}개월)`}
                />
                <button type="submit" disabled={loading}
                        className="w-full bg-blue-500 hover:bg-blue-600 py-2 rounded text-sm font-bold disabled:opacity-50">
                    신청하기 (즉시 자동심사)
                </button>
            </form>
            <p className="text-[11px] text-slate-500 mt-2">
                신청 즉시 자동심사가 진행됩니다. (활성 계좌 보유 · 한도 · 연체 이력 검사)
            </p>
        </div>
    );
}
