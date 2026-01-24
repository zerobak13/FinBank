import React, { useState } from "react";
import { formatCurrency, classNames } from "../utils/formatter";

export default function AccountDetail({ selected, loading, deposit, withdraw, transfer }) {
    const [dAmount, setDAmount] = useState("");
    const [wAmount, setWAmount] = useState("");
    const [toAcc, setToAcc] = useState("");
    const [tAmount, setTAmount] = useState("");

    if (!selected) return (
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 shadow-lg h-[220px] flex flex-col items-center justify-center text-sm text-slate-500">
            <div className="mb-2 text-slate-300 font-semibold">계좌를 선택해 주세요.</div>
        </div>
    );

    return (
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 shadow-lg min-h-[220px]">
            <div className="flex justify-between items-start mb-4">
                <div>
                    <div className="text-xs uppercase text-slate-500">Account Number</div>
                    <div className="text-lg font-semibold tracking-tight">{selected.account.accountNumber}</div>
                </div>
                <div className="text-right">
                    <div className="text-xs text-slate-500">잔액</div>
                    <div className="text-3xl font-bold text-blue-400">{formatCurrency(selected.account.balance)}</div>
                </div>
            </div>

            <div className="grid grid-cols-2 gap-4 mb-4">
                <div className="bg-slate-950 border border-slate-700 p-4 rounded-xl">
                    <input type="number" value={dAmount} onChange={e => setDAmount(e.target.value)} className="w-full bg-slate-900 border border-slate-700 rounded p-2 mb-2 text-sm" placeholder="입금액" />
                    <button onClick={() => { deposit(dAmount); setDAmount(""); }} disabled={loading} className="w-full bg-emerald-500 py-2 rounded text-xs font-bold">입금</button>
                </div>
                <div className="bg-slate-950 border border-slate-700 p-4 rounded-xl">
                    <input type="number" value={wAmount} onChange={e => setWAmount(e.target.value)} className="w-full bg-slate-900 border border-slate-700 rounded p-2 mb-2 text-sm" placeholder="출금액" />
                    <button onClick={() => { withdraw(wAmount); setWAmount(""); }} disabled={loading} className="w-full bg-rose-500 py-2 rounded text-xs font-bold">출금</button>
                </div>
            </div>

            <form className="bg-slate-950 border border-slate-700 p-4 rounded-xl" onSubmit={e => { e.preventDefault(); transfer(toAcc, tAmount); setToAcc(""); setTAmount(""); }}>
                <input value={toAcc} onChange={e => setToAcc(e.target.value)} className="w-full bg-slate-900 border border-slate-700 rounded p-2 mb-2 text-sm" placeholder="받는 계좌번호" />
                <div className="flex gap-2">
                    <input type="number" value={tAmount} onChange={e => setTAmount(e.target.value)} className="flex-1 bg-slate-900 border border-slate-700 rounded p-2 text-sm" placeholder="금액" />
                    <button type="submit" disabled={loading} className="bg-blue-500 px-6 rounded font-bold text-xs">이체</button>
                </div>
            </form>
        </div>
    );
}