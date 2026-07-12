import React from "react";

const MENUS = [
    { key: "account", label: "계좌" },
    { key: "loan", label: "대출" },
];

export default function Layout({
                                   children, auth, menu, setMenu, isRegisterMode, setIsRegisterMode,
                                   loginEmail, setLoginEmail, loginName, setLoginName,
                                   loginPassword, setLoginPassword, handleLogin, handleRegister, handleLogout
                               }) {
    return (
        <div className="min-h-screen bg-slate-950 text-slate-50 flex flex-col">
            <header className="border-b border-slate-800 bg-slate-950/80 backdrop-blur">
                <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between gap-4">
                    <div className="flex items-center gap-2">
                        <div className="h-8 w-8 rounded-xl bg-blue-500 flex items-center justify-center text-white font-bold text-lg">F</div>
                        <div>
                            <div className="text-lg font-semibold tracking-tight">FINBANK</div>
                            <div className="text-xs text-slate-400">Enterprise Internet Banking</div>
                        </div>

                        {/* 메뉴 탭 (로그인 시에만 노출) */}
                        {auth && (
                            <nav className="ml-6 flex items-center gap-1">
                                {MENUS.map((m) => (
                                    <button
                                        key={m.key}
                                        type="button"
                                        onClick={() => setMenu(m.key)}
                                        className={`px-3 py-1.5 rounded-lg text-sm transition
                                            ${menu === m.key
                                                ? "bg-blue-500/20 text-blue-300 border border-blue-500/40 font-semibold"
                                                : "text-slate-400 hover:text-slate-200 hover:bg-slate-800 border border-transparent"}`}
                                    >
                                        {m.label}
                                    </button>
                                ))}
                            </nav>
                        )}
                    </div>

                    {auth ? (
                        // 로그인 상태: 인사말 + 로그아웃만 노출
                        <div className="flex items-center justify-end gap-2 text-xs">
                            <span className="hidden md:inline text-slate-400">{auth.name} ({auth.email})</span>
                            <button type="button" onClick={handleLogout} className="px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 border border-slate-600">로그아웃</button>
                        </div>
                    ) : (
                        // 비로그인 상태: 입력칸 + 회원가입 토글 + 제출 버튼
                        <form onSubmit={isRegisterMode ? handleRegister : handleLogin} className="flex flex-wrap items-center justify-end gap-2 text-xs">
                            <div className="flex items-center gap-2">
                                <input className="bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 focus:outline-none focus:ring-1 focus:ring-blue-500 w-40" placeholder="이메일" value={loginEmail} onChange={(e) => setLoginEmail(e.target.value)} type="email" />
                                {isRegisterMode && (
                                    <input className="bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 focus:outline-none focus:ring-1 focus:ring-blue-500 w-28" placeholder="이름" value={loginName} onChange={(e) => setLoginName(e.target.value)} />
                                )}
                                <input className="bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 focus:outline-none focus:ring-1 focus:ring-blue-500 w-32" placeholder="비밀번호" type="password" value={loginPassword} onChange={(e) => setLoginPassword(e.target.value)} />
                            </div>
                            <div className="flex items-center gap-2">
                                <button type="button" onClick={() => setIsRegisterMode(v => !v)} className="px-3 py-1.5 rounded-lg border border-slate-600 hover:bg-slate-800">
                                    {isRegisterMode ? "로그인 모드" : "회원가입"}
                                </button>
                                <button type="submit" className="px-3 py-1.5 rounded-lg bg-blue-500 hover:bg-blue-600 font-semibold">
                                    {isRegisterMode ? "회원가입 완료" : "로그인"}
                                </button>
                            </div>
                        </form>
                    )}
                </div>
            </header>
            <main className="flex-1">{children}</main>
        </div>
    );
}
