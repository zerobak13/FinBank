import React from "react";
import { formatCurrency, formatDate, getTxMeta, classNames } from "../utils/formatter";

export default function TransactionHistory({ transactions }) {
    const isEmpty = !transactions || transactions.length === 0;

    return (
        <div className="bg-slate-900 border border-slate-800 rounded-2xl shadow-lg overflow-hidden">
            <div className="px-5 pt-5 pb-3 flex items-center justify-between">
                <h2 className="text-sm font-semibold text-slate-300">거래 내역</h2>
                {!isEmpty && (
                    <span className="text-xs text-slate-500">{transactions.length}건</span>
                )}
            </div>

            <div className="divide-y divide-slate-800/60 max-h-72 overflow-auto">
                {isEmpty ? (
                    <div className="flex flex-col items-center justify-center py-10 gap-2">
                        <span className="text-2xl">📭</span>
                        <p className="text-xs text-slate-500">거래 내역이 없습니다.</p>
                    </div>
                ) : (
                    transactions.map((t) => {
                        const meta = getTxMeta(t.type);
                        const isIncome = meta.sign === "+";
                        return (
                            <div key={t.id} className="flex items-center gap-3 px-5 py-3.5 hover:bg-slate-800/40 transition-colors">
                                {/* 타입 아이콘 */}
                                <div className={classNames("w-8 h-8 rounded-full flex items-center justify-center shrink-0", meta.bg)}>
                                    <span className={classNames("w-2 h-2 rounded-full", meta.dot)} />
                                </div>

                                {/* 내용 */}
                                <div className="flex-1 min-w-0">
                                    <p className="text-sm font-semibold text-slate-200">{meta.label}</p>
                                    <p className="text-[11px] text-slate-500 truncate">{t.description || "-"}</p>
                                </div>

                                {/* 금액 + 잔액 */}
                                <div className="text-right shrink-0">
                                    <p className={classNames("text-sm font-bold", meta.color)}>
                                        {meta.sign}{formatCurrency(t.amount)}
                                    </p>
                                    <p className="text-[11px] text-slate-600">
                                        잔액 {formatCurrency(t.balanceAfter)}
                                    </p>
                                </div>
                            </div>
                        );
                    })
                )}
            </div>
        </div>
    );
}
