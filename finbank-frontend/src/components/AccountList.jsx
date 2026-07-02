import React from "react";
import { classNames, formatCurrency, formatAccountNumber, formatInterestRate } from "../utils/formatter";

export default function AccountList({ accounts, selected, selectAccount, auth }) {
    const total = accounts.reduce((sum, a) => sum + a.balance, 0);

    return (
        <div className="flex flex-col gap-3">
            {auth && accounts.length > 0 && (
                <div className="bg-gradient-to-br from-blue-600 to-blue-700 rounded-2xl p-4 shadow-lg">
                    <p className="text-blue-200 text-xs mb-1">총 보유 자산</p>
                    <p className="text-white text-2xl font-bold tracking-tight">{formatCurrency(total)}</p>
                    <p className="text-blue-200 text-xs mt-1">{accounts.length}개 계좌</p>
                </div>
            )}

            <div className="bg-slate-900 border border-slate-800 rounded-2xl shadow-lg overflow-hidden">
                <div className="px-4 pt-4 pb-2">
                    <h2 className="text-sm font-semibold text-slate-300">내 계좌</h2>
                </div>
                <div className="flex flex-col divide-y divide-slate-800/80">
                    {!auth && <p className="text-xs text-slate-500 text-center py-8">로그인이 필요합니다.</p>}
                    {auth && accounts.length === 0 && <p className="text-xs text-slate-500 text-center py-8">계좌가 없습니다.</p>}
                    {auth && accounts.map((a) => {
                        const isSelected = selected?.account.id === a.id;
                        const isSavings = a.accountType === "SAVINGS";
                        const rate = formatInterestRate(a.interestRate);
                        return (
                            <button
                                key={a.id}
                                onClick={() => selectAccount(a.id, true)}
                                className={classNames(
                                    "w-full text-left px-4 py-3 transition-all",
                                    isSelected ? "bg-blue-500/10" : "hover:bg-slate-800/60"
                                )}
                            >
                                <div className="flex items-start justify-between gap-2">
                                    <div className="flex-1 min-w-0">
                                        <div className="flex items-center gap-1.5 mb-1.5 flex-wrap">
                                            <span className={classNames(
                                                "text-[10px] font-bold px-1.5 py-0.5 rounded",
                                                isSavings ? "bg-teal-500/15 text-teal-400" : "bg-slate-700/60 text-slate-400"
                                            )}>
                                                {isSavings ? "적금" : "일반"}
                                            </span>
                                            {isSavings && rate && (
                                                <span className="text-[10px] text-teal-500 font-semibold">연 {rate}</span>
                                            )}
                                            {a.locked && (
                                                <span className="text-[10px] bg-rose-500/15 text-rose-400 px-1.5 py-0.5 rounded font-bold">잠금</span>
                                            )}
                                        </div>
                                        <p className="text-xs text-slate-400 font-mono tracking-wider">
                                            {formatAccountNumber(a.accountNumber)}
                                        </p>
                                    </div>
                                    <div className="text-right shrink-0">
                                        <p className={classNames("text-sm font-bold", isSelected ? "text-blue-300" : "text-slate-100")}>
                                            {formatCurrency(a.balance)}
                                        </p>
                                    </div>
                                </div>
                                {isSelected && <div className="mt-2 h-0.5 bg-blue-500 rounded-full" />}
                            </button>
                        );
                    })}
                </div>
            </div>
        </div>
    );
}
