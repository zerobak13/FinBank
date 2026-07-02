export function classNames(...classes) {
    return classes.filter(Boolean).join(" ");
}

export const formatCurrency = (value) => {
    if (value === null || value === undefined) return "0원";
    return Number(value).toLocaleString("ko-KR") + "원";
};

export const formatAccountNumber = (num) => {
    if (!num) return "";
    return num.replace(/(\d{4})(\d{4})(\d{4})/, "$1-$2-$3");
};

export const formatDate = (dateStr) => {
    if (!dateStr) return "";
    const d = new Date(dateStr);
    const now = new Date();
    const isToday = d.toDateString() === now.toDateString();
    if (isToday) {
        return d.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" });
    }
    return d.toLocaleDateString("ko-KR", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
};

export const formatInterestRate = (rate) => {
    if (!rate || Number(rate) === 0) return null;
    return (Number(rate) * 100).toFixed(2) + "%";
};

export const TX_META = {
    DEPOSIT:      { label: "입금",     color: "text-emerald-400", bg: "bg-emerald-500/10", sign: "+", dot: "bg-emerald-400" },
    WITHDRAW:     { label: "출금",     color: "text-rose-400",    bg: "bg-rose-500/10",    sign: "-", dot: "bg-rose-400" },
    TRANSFER_IN:  { label: "이체 수신", color: "text-blue-400",   bg: "bg-blue-500/10",    sign: "+", dot: "bg-blue-400" },
    TRANSFER_OUT: { label: "이체 송금", color: "text-orange-400", bg: "bg-orange-500/10",  sign: "-", dot: "bg-orange-400" },
    INTEREST:     { label: "이자",     color: "text-teal-400",    bg: "bg-teal-500/10",    sign: "+", dot: "bg-teal-400" },
};

export const getTxMeta = (type) =>
    TX_META[type] ?? { label: type, color: "text-slate-400", bg: "bg-slate-700/40", sign: "", dot: "bg-slate-500" };
