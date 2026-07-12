import React from "react";

const won = (v) => Number(v).toLocaleString("ko-KR");

const STATUS_BADGE = {
    ACTIVE: "bg-emerald-500/20 text-emerald-300 border-emerald-500/40",
    OVERDUE: "bg-red-500/20 text-red-300 border-red-500/40",
    PAID_OFF: "bg-slate-500/20 text-slate-300 border-slate-500/40",
    APPLIED: "bg-blue-500/20 text-blue-300 border-blue-500/40",
    APPROVED: "bg-blue-500/20 text-blue-300 border-blue-500/40",
    REJECTED: "bg-red-500/20 text-red-300 border-red-500/40",
};

const STATUS_LABEL = {
    ACTIVE: "정상", OVERDUE: "연체", PAID_OFF: "완납",
    APPLIED: "심사중", APPROVED: "승인", REJECTED: "거절", EXECUTED: "실행됨", CANCELED: "취소",
};

/** 내 대출/신청 현황 — C1-2/C3 API 연동 전까지는 빈 상태 안내를 보여준다 */
export default function MyLoanList({ loans, apiReady }) {
    return (
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
            <h2 className="text-sm font-semibold mb-3">내 대출 현황</h2>

            {!apiReady && (
                <p className="text-xs text-slate-500">
                    대출 조회 API 연동 예정입니다. (백엔드 C1-2 진행 중)
                </p>
            )}

            {apiReady && !loans?.length && (
                <p className="text-xs text-slate-500">진행 중인 대출이 없습니다.</p>
            )}

            {apiReady && loans?.map((l) => (
                <div key={l.id} className="border border-slate-800 rounded-xl p-3 mb-2 last:mb-0">
                    <div className="flex items-center justify-between mb-1">
                        <span className="text-sm font-medium">{l.productName}</span>
                        <span className={`text-[11px] px-2 py-0.5 rounded-full border ${STATUS_BADGE[l.status] ?? ""}`}>
                            {STATUS_LABEL[l.status] ?? l.status}
                        </span>
                    </div>
                    <div className="text-xs text-slate-400">
                        {l.balance != null
                            ? <>잔여 원금 {won(l.balance)}원 / 원금 {won(l.principal)}원</>
                            : <>신청 금액 {won(l.requestedAmount)}원 · {l.termMonths}개월</>}
                    </div>
                    {l.status === "REJECTED" && l.rejectReason && (
                        <div className="text-[11px] text-red-400/80 mt-1">탈락 사유: {l.rejectReason}</div>
                    )}
                </div>
            ))}
        </div>
    );
}
