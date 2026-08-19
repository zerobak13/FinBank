import React, { useEffect, useState } from "react";

const won = (v) => Number(v).toLocaleString("ko-KR");

/** 대출 신청 폼 — 상품 선택 후 입금 계좌·금액·기간 입력 */
export default function LoanApplyForm({ product, accounts = [], loading, onSubmit }) {
    const [accountId, setAccountId] = useState("");
    const [amount, setAmount] = useState("");
    const [termMonths, setTermMonths] = useState("");

    // 잠긴 계좌는 대출금을 받을 수 없으므로 선택지에서 제외한다
    const selectable = accounts.filter((a) => !a.locked);

    // 계좌가 하나뿐이면 고를 이유가 없으므로 자동 선택 (선택을 강요하지 않는다)
    useEffect(() => {
        if (!accountId && selectable.length === 1) setAccountId(String(selectable[0].id));
    }, [selectable, accountId]);

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
            accountId: Number(accountId),
            requestedAmount: Number(amount),
            termMonths: Number(termMonths),
        });
    };

    return (
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
            <h2 className="text-sm font-semibold mb-1">대출 신청</h2>
            <p className="text-xs text-slate-400 mb-3">{product.name}</p>

            {selectable.length === 0 ? (
                <p className="text-xs text-amber-400">
                    대출금을 받을 활성 계좌가 없습니다. 계좌를 먼저 개설해주세요.
                </p>
            ) : (
                <form onSubmit={submit} className="space-y-2">
                    <label className="block">
                        <span className="text-[11px] text-slate-400">대출금을 입금받을 계좌</span>
                        <select
                            value={accountId} onChange={(e) => setAccountId(e.target.value)} required
                            className="w-full bg-slate-950 border border-slate-700 rounded p-2 text-sm mt-1"
                        >
                            <option value="" disabled>계좌를 선택하세요</option>
                            {selectable.map((a) => (
                                <option key={a.id} value={a.id}>
                                    {a.accountNumber} (잔액 {won(a.balance)}원)
                                </option>
                            ))}
                        </select>
                    </label>

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
            )}

            <p className="text-[11px] text-slate-500 mt-2">
                선택한 계좌로 대출금이 입금되고, 이후 상환도 같은 계좌에서 출금됩니다.
            </p>
        </div>
    );
}
