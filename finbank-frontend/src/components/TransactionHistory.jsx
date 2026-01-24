import React from "react";
import { formatCurrency } from "../utils/formatter";

export default function TransactionHistory({ transactions }) {
    return (
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 shadow-lg min-h-[220px]">
            <h2 className="text-sm font-semibold text-slate-300 mb-3">거래 내역</h2>
            <div className="space-y-2 max-h-64 overflow-auto pr-1">
                {transactions?.length > 0 ? transactions.map((t) => (
                    <div key={t.id} className="flex items-center justify-between px-3 py-2 rounded-xl border border-slate-800 bg-slate-950/40">
                        <div>
                            <div className="text-xs font-semibold text-slate-200">{t.type}</div>
                            <div className="text-[11px] text-slate-500">{t.description || "-"}</div>
                        </div>
                        <div className="text-right">
                            <div className="text-sm font-semibold">{formatCurrency(t.amount)}</div>
                            <div className="text-[11px] text-slate-500">잔액 {formatCurrency(t.balanceAfter)}</div>
                        </div>
                    </div>
                )) : <div className="text-xs text-slate-500 mt-4">거래 내역이 없습니다.</div>}
            </div>
        </div>
    );
}