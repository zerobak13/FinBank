import React from "react";

const won = (v) => Number(v ?? 0).toLocaleString("ko-KR");

const STATUS_BADGE = {
    APPLIED: "bg-blue-500/20 text-blue-300 border-blue-500/40",
    APPROVED: "bg-blue-500/20 text-blue-300 border-blue-500/40",
    REJECTED: "bg-red-500/20 text-red-300 border-red-500/40",
    EXECUTED: "bg-emerald-500/20 text-emerald-300 border-emerald-500/40",
    CANCELED: "bg-slate-500/20 text-slate-300 border-slate-500/40",
};

const STATUS_LABEL = {
    APPLIED: "심사중", APPROVED: "승인", REJECTED: "거절", EXECUTED: "실행됨", CANCELED: "취소",
};

const REJECT_REASON = {
    CREDIT_LIMIT_EXCEEDED: "기존 대출 잔액 합산이 상품 한도를 넘습니다",
    ACTIVE_OVERDUE_EXISTS: "진행 중인 연체 대출이 있습니다",
};

/**
 * 내 대출 신청 이력.
 *
 * 승인(APPROVED) 상태에서만 실행 버튼이 열린다 — 상태 전이의 최종 판단은 서버가 하지만,
 * 화면도 같은 규칙을 보여줘야 사용자가 왜 못 누르는지 알 수 있다.
 */
export default function MyLoanList({ applications, apiReady, busy, onExecute }) {
    return (
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
            <h2 className="text-sm font-semibold mb-3">내 신청 이력</h2>

            {!apiReady && (
                <p className="text-xs text-slate-500">대출 API에 연결하지 못했습니다.</p>
            )}

            {apiReady && !applications?.length && (
                <p className="text-xs text-slate-500">신청 이력이 없습니다.</p>
            )}

            {apiReady && applications?.map((a) => (
                <div key={a.id} className="border border-slate-800 rounded-xl p-3 mb-2 last:mb-0">
                    <div className="flex items-center justify-between mb-1">
                        <span className="text-sm font-medium">{a.productName ?? `신청 #${a.id}`}</span>
                        <span className={`text-[11px] px-2 py-0.5 rounded-full border ${STATUS_BADGE[a.status] ?? ""}`}>
                            {STATUS_LABEL[a.status] ?? a.status}
                        </span>
                    </div>

                    <div className="text-xs text-slate-400">
                        신청 금액 {won(a.requestedAmount)}원 · {a.termMonths}개월
                    </div>

                    {a.accountNumber && (
                        <div className="text-[11px] text-slate-500 mt-0.5">
                            입금 계좌 {a.accountNumber}
                        </div>
                    )}

                    {a.status === "REJECTED" && a.rejectReason && (
                        <div className="text-[11px] text-red-400/80 mt-1">
                            탈락 사유: {REJECT_REASON[a.rejectReason] ?? a.rejectReason}
                        </div>
                    )}

                    {a.status === "APPROVED" && (
                        <button
                            type="button"
                            disabled={busy}
                            onClick={() => onExecute(a.id)}
                            className="mt-2 w-full py-1.5 rounded-lg text-xs bg-emerald-600 hover:bg-emerald-500 disabled:opacity-40 disabled:cursor-not-allowed"
                        >
                            대출 실행 ({a.accountNumber ? `${a.accountNumber}로 ` : ""}{won(a.requestedAmount)}원 입금)
                        </button>
                    )}
                </div>
            ))}
        </div>
    );
}
