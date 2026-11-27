import React from "react";
import { formatCurrency, formatDateTime } from "../utils/formatter";

/** 거래 유형 → 화면 문구. 돈이 나간 거래(출금·이체 보냄)는 금액 앞에 −를 붙인다 */
const TYPE = {
    DEPOSIT: { label: "입금", outgoing: false },
    WITHDRAW: { label: "출금", outgoing: true },
    TRANSFER_IN: { label: "이체 받음", outgoing: false },
    TRANSFER_OUT: { label: "이체 보냄", outgoing: true },
};

/**
 * 서버 거래 로그의 기본 설명 — 유형 이름과 같은 말이라 따로 보여 주지 않는다.
 * 대출 실행금·상환금처럼 따로 적힌 설명("대출 상환 · 대출 #12 3회차")만 유형 아래에 보인다.
 */
const GENERIC_DESCRIPTIONS = new Set(["Deposit", "Withdraw", "Transfer In", "Transfer Out"]);

export default function TransactionHistory({ transactions }) {
    return (
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 shadow-lg min-h-[220px]">
            <h2 className="text-sm font-semibold text-slate-300 mb-3">거래 내역</h2>
            <div className="space-y-2 max-h-64 overflow-auto pr-1">
                {transactions?.length > 0 ? transactions.map((t) => {
                    const type = TYPE[t.type] ?? { label: t.type, outgoing: false };
                    return (
                        <div key={t.id} className="flex items-center justify-between px-3 py-2 rounded-xl border border-slate-800 bg-slate-950/40">
                            <div>
                                <div className="text-xs font-semibold text-slate-200">{type.label}</div>
                                {t.description && !GENERIC_DESCRIPTIONS.has(t.description) && (
                                    <div className="text-[11px] text-slate-300">{t.description}</div>
                                )}
                                <div className="text-[11px] text-slate-500">{formatDateTime(t.createdAt)}</div>
                            </div>
                            <div className="text-right">
                                <div className={`text-sm font-semibold ${type.outgoing ? "text-rose-300" : "text-emerald-300"}`}>
                                    {type.outgoing ? "−" : "+"}{formatCurrency(t.amount)}
                                </div>
                                <div className="text-[11px] text-slate-500">잔액 {formatCurrency(t.balanceAfter)}</div>
                            </div>
                        </div>
                    );
                }) : <div className="text-xs text-slate-500 mt-4">거래 내역이 없습니다.</div>}
            </div>
        </div>
    );
}
