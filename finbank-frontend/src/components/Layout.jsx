import React from "react";

const inputCls = "bg-slate-900 border border-slate-700/80 rounded-xl px-3 py-1.5 text-xs text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500/50 focus:border-blue-500/50 transition";

export default function Layout({
    children, auth, isRegisterMode, setIsRegisterMode,
    loginEmail, setLoginEmail, loginName, setLoginName,
    loginPassword, setLoginPassword, handleLogin, handleRegister, handleLogout
}) {
    return (
        <div className="min-h-screen bg-slate-950 text-slate-50 flex flex-col">
            <header className="border-b border-slate-800/80 bg-slate-950/90 backdrop-blur sticky top-0 z-50">
                <div className="max-w-6xl mx-auto px-6 py-3.5 flex items-center justify-between gap-4">
                    {/* 로고 */}
                    <div className="flex items-center gap-2.5 shrink-0">
                        <div className="h-8 w-8 rounded-xl bg-blue-500 flex items-center justify-center text-white font-black text-base shadow-lg shadow-blue-500/20">
                            F
                        </div>
                        <div>
                            <p className="text-sm font-bold tracking-tight text-slate-100">FinBank</p>
                            <p className="text-[10px] text-slate-500 leading-none">인터넷뱅킹 데모</p>
                        </div>
                    </div>

                    {/* 로그인/회원가입 폼 or 사용자 정보 */}
                    {auth ? (
                        <div className="flex items-center gap-3">
                            <div className="hidden sm:flex items-center gap-2">
                                <div className="w-6 h-6 rounded-full bg-blue-500/20 border border-blue-500/30 flex items-center justify-center text-[10px] font-bold text-blue-400">
                                    {auth.name?.[0] ?? "U"}
                                </div>
                                <div>
                                    <p className="text-xs font-semibold text-slate-200">{auth.name}</p>
                                    <p className="text-[10px] text-slate-500">{auth.email}</p>
                                </div>
                            </div>
                            <button
                                onClick={handleLogout}
                                className="text-xs px-3 py-1.5 rounded-xl border border-slate-700 text-slate-400 hover:text-slate-200 hover:border-slate-500 transition"
                            >
                                로그아웃
                            </button>
                        </div>
                    ) : (
                        <form
                            onSubmit={isRegisterMode ? handleRegister : handleLogin}
                            className="flex items-center gap-2 text-xs"
                        >
                            <input className={`${inputCls} w-36`} placeholder="이메일" value={loginEmail} onChange={e => setLoginEmail(e.target.value)} type="email" />
                            {isRegisterMode && (
                                <input className={`${inputCls} w-24`} placeholder="이름" value={loginName} onChange={e => setLoginName(e.target.value)} />
                            )}
                            <input className={`${inputCls} w-28`} placeholder="비밀번호" type="password" value={loginPassword} onChange={e => setLoginPassword(e.target.value)} />
                            <button
                                type="button"
                                onClick={() => setIsRegisterMode(v => !v)}
                                className="px-3 py-1.5 rounded-xl border border-slate-700 text-slate-400 hover:text-slate-200 transition"
                            >
                                {isRegisterMode ? "로그인" : "회원가입"}
                            </button>
                            <button
                                type="submit"
                                className="px-3 py-1.5 rounded-xl bg-blue-500 hover:bg-blue-400 font-semibold text-white transition shadow-lg shadow-blue-500/20"
                            >
                                {isRegisterMode ? "가입완료" : "로그인"}
                            </button>
                        </form>
                    )}
                </div>
            </header>

            <main className="flex-1">{children}</main>

            <footer className="border-t border-slate-800/60 mt-8">
                <div className="max-w-6xl mx-auto px-6 py-4 text-center text-[11px] text-slate-600">
                    FinBank Demo · JWT 인증 · 비관적 락 · 멱등성 · 배치 이자 정산
                </div>
            </footer>
        </div>
    );
}
