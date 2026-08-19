import React from "react";

const won = (v) => Number(v ?? 0).toLocaleString("ko-KR");
const pct = (v) => (Number(v) * 100).toFixed(1);

const LOAN_STATUS = {
    ACTIVE: { label: "정상", cls: "bg-emerald-500/20 text-emerald-300 border-emerald-500/40" },
    OVERDUE: { label: "연체", cls: "bg-red-500/20 text-red-300 border-red-500/40" },
    PAID_OFF: { label: "완납", cls: "bg-slate-500/20 text-slate-300 border-slate-500/40" },
};

const SCHEDULE_STATUS = {
    PENDING: { label: "미납", cls: "text-slate-400" },
    PAID: { label: "완납", cls: "text-emerald-400" },
    OVERDUE: { label: "연체", cls: "text-red-400" },
    CANCELED: { label: "무효", cls: "text-slate-600 line-through" },
};

/**
 * 실행된 대출의 상환 화면 — 계약 요약 + 전 회차 스케줄 + 상환 버튼.
 *
 * 스케줄은 실행 시점에 전 회차가 확정 저장된 값을 그대로 보여준다(서버가 매번 계산하지 않는다).
 * 상환은 "가장 빠른 미납 회차"만 가능하므로, 다음 회차 한 줄만 강조하고 나머지는 열람용이다.
 */
export default function LoanRepaymentPanel({
    loans, selectedLoanId, onSelectLoan, schedules, busy, onRepay, onPrepay,
}) {
    if (!loans?.length) {
        return (
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
                <h2 className="text-sm font-semibold mb-2">대출 상환</h2>
                <p className="text-xs text-slate-500">
                    실행된 대출이 없습니다. 승인된 신청을 실행하면 상환 스케줄이 생성됩니다.
                </p>
            </div>
        );
    }

    const loan = loans.find((l) => l.loanId === selectedLoanId) ?? loans[0];
    const status = LOAN_STATUS[loan.status] ?? { label: loan.status, cls: "" };
    const nextDue = schedules?.find((s) => s.status === "PENDING" || s.status === "OVERDUE");
    const paidOff = loan.status === "PAID_OFF";

    return (
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
            <div className="flex items-center justify-between mb-3">
                <h2 className="text-sm font-semibold">대출 상환</h2>
                {loans.length > 1 && (
                    <div className="flex gap-1">
                        {loans.map((l) => (
                            <button
                                key={l.loanId}
                                type="button"
                                onClick={() => onSelectLoan(l.loanId)}
                                className={`text-[11px] px-2 py-1 rounded border transition ${
                                    l.loanId === loan.loanId
                                        ? "border-blue-500 text-blue-300"
                                        : "border-slate-700 text-slate-400 hover:border-slate-500"
                                }`}
                            >
                                대출 #{l.loanId}
                            </button>
                        ))}
                    </div>
                )}
            </div>

            {/* 계약 요약 — 실행 시점 스냅샷 */}
            <div className="border border-slate-800 rounded-xl p-4 mb-4">
                <div className="flex items-center justify-between mb-2">
                    <span className="text-sm font-medium">{loan.productName}</span>
                    <span className={`text-[11px] px-2 py-0.5 rounded-full border ${status.cls}`}>
                        {status.label}
                    </span>
                </div>
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-y-1 gap-x-4 text-xs text-slate-400">
                    <div>원금 <span className="text-slate-200">{won(loan.principal)}원</span></div>
                    <div>잔여 원금 <span className="text-slate-200">{won(loan.balance)}원</span></div>
                    <div>금리 <span className="text-slate-200">연 {pct(loan.interestRate)}%</span></div>
                    <div>만기 <span className="text-slate-200">{loan.maturityDate}</span></div>
                </div>
            </div>

            {/* 상환 액션 */}
            <div className="flex flex-wrap items-center gap-2 mb-4">
                <button
                    type="button"
                    disabled={busy || paidOff || !nextDue}
                    onClick={() => onRepay(loan.loanId, nextDue?.installmentNo)}
                    className="px-3 py-2 rounded-lg text-sm bg-blue-600 hover:bg-blue-500 disabled:opacity-40 disabled:cursor-not-allowed"
                >
                    {nextDue
                        ? `${nextDue.installmentNo}회차 상환 (${won(nextDue.totalAmount)}원)`
                        : "상환할 회차 없음"}
                </button>
                <button
                    type="button"
                    disabled={busy || paidOff || loan.status !== "ACTIVE"}
                    onClick={() => onPrepay(loan.loanId)}
                    className="px-3 py-2 rounded-lg text-sm border border-slate-700 hover:border-slate-500 disabled:opacity-40 disabled:cursor-not-allowed"
                >
                    전액 중도상환
                </button>
                <span className="text-[11px] text-slate-500">
                    연결계좌에서 자동 출금됩니다. 중복 클릭은 멱등키로 차단됩니다.
                </span>
            </div>

            {/* 스케줄 표 */}
            <div className="overflow-x-auto">
                <table className="w-full text-xs">
                    <thead className="text-slate-500">
                        <tr className="border-b border-slate-800">
                            <th className="text-left font-normal py-2">회차</th>
                            <th className="text-left font-normal py-2">납기일</th>
                            <th className="text-right font-normal py-2">원금</th>
                            <th className="text-right font-normal py-2">이자</th>
                            <th className="text-right font-normal py-2">납부액</th>
                            <th className="text-right font-normal py-2">상태</th>
                        </tr>
                    </thead>
                    <tbody>
                        {schedules?.map((s) => {
                            const st = SCHEDULE_STATUS[s.status] ?? { label: s.status, cls: "" };
                            const isNext = nextDue && s.installmentNo === nextDue.installmentNo;
                            return (
                                <tr
                                    key={s.id ?? s.installmentNo}
                                    className={`border-b border-slate-800/60 ${isNext ? "bg-blue-500/5" : ""}`}
                                >
                                    <td className="py-1.5">
                                        {s.installmentNo}
                                        {isNext && <span className="ml-1 text-[10px] text-blue-400">다음</span>}
                                    </td>
                                    <td className="py-1.5 text-slate-400">{s.dueDate}</td>
                                    <td className="py-1.5 text-right">{won(s.principalAmount)}</td>
                                    <td className="py-1.5 text-right text-slate-400">{won(s.interestAmount)}</td>
                                    <td className="py-1.5 text-right">{won(s.totalAmount)}</td>
                                    <td className={`py-1.5 text-right ${st.cls}`}>{st.label}</td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
                {!schedules?.length && (
                    <p className="text-xs text-slate-500 py-3">스케줄을 불러오는 중입니다.</p>
                )}
            </div>
        </div>
    );
}
