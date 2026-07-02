import React, { useState } from "react";
import { formatCurrency, formatAccountNumber, formatInterestRate, classNames } from "../utils/formatter";

const inputCls = "w-full bg-slate-900 border border-slate-700/80 rounded-xl px-3 py-2.5 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500/50 focus:border-blue-500/50 transition";

export default function AccountDetail({ selected, loading, deposit, withdraw, transfer }) {
    const [dAmount, setDAmount] = useState("");
    const [wAmount, setWAmount] = useState("");
    const [toAcc, setToAcc]     = useState("");
    const [tAmount, setTAmount] = useState("");
    const [activeTab, setActiveTab] = useState("deposit"); // deposit | withdraw | transfer

    if (!selected) return (
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-8 shadow-lg flex flex-col items-center justify-center gap-2 min-h-[260px]">
            <div className="w-12 h-12 rounded-full bg-slate-800 flex items-center justify-center text-2xl mb-1">🏦</div>
            <p className="text-slate-300 font-semibold text-sm">계좌를 선택해 주세요</p>
            <p className="text-slate-600 text-xs">왼쪽 목록에서 계좌를 선택하면 상세 정보가 표시됩니다.</p>
        </div>
    );

    const acct = selected.account;
    const isSavings = acct.accountType === "SAVINGS";
    const rate = formatInterestRate(acct.interestRate);

    return (
        <div className="bg-slate-900 border border-slate-800 rounded-2xl shadow-lg overflow-hidden">
            {/* 잔액 헤더 */}
            <div className="px-6 pt-6 pb-5 bg-gradient-to-b from-slate-800/60 to-transparent">
                <div className="flex items-start justify-between mb-4">
                    <div>
                        <div className="flex items-center gap-2 mb-1">
                            <span className={classNames(
                                "text-xs font-bold px-2 py-0.5 rounded-md",
                                isSavings ? "bg-teal-500/15 text-teal-400" : "bg-slate-700 text-slate-400"
                            )}>
                                {isSavings ? "적금" : "일반"}
                            </span>
                            {isSavings && rate && (
                                <span className="text-xs text-teal-400 font-semibold">연 {rate} 이자</span>
                            )}
                            {acct.locked && (
                                <span className="text-xs bg-rose-500/15 text-rose-400 px-2 py-0.5 rounded-md font-bold">잠금</span>
                            )}
                        </div>
                        <p className="text-xs text-slate-500 font-mono tracking-widest">
                            {formatAccountNumber(acct.accountNumber)}
                        </p>
                    </div>
                </div>

                <div>
                    <p className="text-xs text-slate-500 mb-0.5">현재 잔액</p>
                    <p className="text-4xl font-bold text-slate-50 tracking-tight">
                        {formatCurrency(acct.balance)}
                    </p>
                </div>
            </div>

            {/* 탭 */}
            <div className="flex border-b border-slate-800 px-6">
                {[
                    { key: "deposit",  label: "입금" },
                    { key: "withdraw", label: "출금" },
                    { key: "transfer", label: "이체" },
                ].map(({ key, label }) => (
                    <button
                        key={key}
                        onClick={() => setActiveTab(key)}
                        className={classNames(
                            "px-4 py-3 text-sm font-semibold border-b-2 transition-colors -mb-px",
                            activeTab === key
                                ? "border-blue-500 text-blue-400"
                                : "border-transparent text-slate-500 hover:text-slate-300"
                        )}
                    >
                        {label}
                    </button>
                ))}
            </div>

            {/* 탭 콘텐츠 */}
            <div className="px-6 py-5">
                {activeTab === "deposit" && (
                    <div className="space-y-3">
                        <input
                            type="number"
                            value={dAmount}
                            onChange={e => setDAmount(e.target.value)}
                            className={inputCls}
                            placeholder="입금할 금액을 입력하세요"
                        />
                        {dAmount > 0 && (
                            <p className="text-xs text-slate-400 px-1">
                                입금 후 잔액: <span className="text-emerald-400 font-semibold">{formatCurrency(acct.balance + Number(dAmount))}</span>
                            </p>
                        )}
                        <button
                            onClick={() => { deposit(dAmount); setDAmount(""); }}
                            disabled={loading || !dAmount}
                            className="w-full bg-emerald-500 hover:bg-emerald-400 disabled:opacity-40 disabled:cursor-not-allowed py-3 rounded-xl text-sm font-bold text-white transition"
                        >
                            입금하기
                        </button>
                    </div>
                )}

                {activeTab === "withdraw" && (
                    <div className="space-y-3">
                        <input
                            type="number"
                            value={wAmount}
                            onChange={e => setWAmount(e.target.value)}
                            className={inputCls}
                            placeholder="출금할 금액을 입력하세요"
                        />
                        {wAmount > 0 && (
                            <p className="text-xs px-1">
                                {Number(wAmount) > acct.balance
                                    ? <span className="text-rose-400 font-semibold">잔액 부족</span>
                                    : <span className="text-slate-400">출금 후 잔액: <span className="text-rose-400 font-semibold">{formatCurrency(acct.balance - Number(wAmount))}</span></span>
                                }
                            </p>
                        )}
                        <button
                            onClick={() => { withdraw(wAmount); setWAmount(""); }}
                            disabled={loading || !wAmount}
                            className="w-full bg-rose-500 hover:bg-rose-400 disabled:opacity-40 disabled:cursor-not-allowed py-3 rounded-xl text-sm font-bold text-white transition"
                        >
                            출금하기
                        </button>
                    </div>
                )}

                {activeTab === "transfer" && (
                    <form
                        onSubmit={e => { e.preventDefault(); transfer(toAcc, tAmount); setToAcc(""); setTAmount(""); }}
                        className="space-y-3"
                    >
                        <input
                            value={toAcc}
                            onChange={e => setToAcc(e.target.value)}
                            className={inputCls}
                            placeholder="받는 계좌번호 (12자리)"
                        />
                        <input
                            type="number"
                            value={tAmount}
                            onChange={e => setTAmount(e.target.value)}
                            className={inputCls}
                            placeholder="이체 금액"
                        />
                        {tAmount > 0 && toAcc && (
                            <p className="text-xs text-slate-400 px-1">
                                이체 후 잔액: <span className="text-blue-400 font-semibold">{formatCurrency(acct.balance - Number(tAmount))}</span>
                                <span className="text-slate-600 ml-2 text-[10px]">멱등성 키 자동 발급</span>
                            </p>
                        )}
                        <button
                            type="submit"
                            disabled={loading || !toAcc || !tAmount}
                            className="w-full bg-blue-500 hover:bg-blue-400 disabled:opacity-40 disabled:cursor-not-allowed py-3 rounded-xl text-sm font-bold text-white transition"
                        >
                            이체하기
                        </button>
                    </form>
                )}
            </div>
        </div>
    );
}
