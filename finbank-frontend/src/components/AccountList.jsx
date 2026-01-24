import React from "react";
import { classNames, formatCurrency } from "../utils/formatter";

export default function AccountList({ accounts, selected, selectAccount, auth }) {
    return (
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-4 shadow-lg h-[420px] flex flex-col">
            <div className="flex items-center justify-between mb-3">
                <h2 className="text-sm font-semibold text-slate-300">내 계좌</h2>
                <span className="text-xs text-slate-500">{accounts.length ? `${accounts.length}개` : "-"}</span>
            </div>
            <div className="flex-1 overflow-auto space-y-2 pr-1">
                {auth && accounts.map((a) => (
                    <button key={a.id} onClick={() => selectAccount(a.id, true)} className={classNames("w-full text-left px-3 py-2 rounded-xl border text-sm transition-colors", selected?.account.id === a.id ? "bg-blue-500/10 border-blue-500 text-blue-100" : "bg-slate-900 border-slate-700 hover:border-blue-500/60 hover:bg-slate-800")}>
                        <div className="flex justify-between items-center mb-1">
                            <span className="font-semibold tracking-tight">{a.accountNumber}</span>
                            <span className={classNames("text-2xs px-2 py-0.5 rounded-full", a.locked ? "bg-red-500/10 text-red-300" : "bg-emerald-500/10 text-emerald-300")}>{a.locked ? "잠금" : "정상"}</span>
                        </div>
                        <div className="flex justify-between items-end">
                            <span className="text-xs text-slate-400 truncate">{a.ownerName}</span>
                            <span className="text-sm font-semibold">{formatCurrency(a.balance)}</span>
                        </div>
                    </button>
                ))}
                {auth && accounts.length === 0 && <div className="text-xs text-slate-500 mt-6 text-center">계좌가 없습니다.</div>}
                {!auth && <div className="text-xs text-slate-500 mt-6 text-center">로그인이 필요합니다.</div>}
            </div>
        </div>
    );
}