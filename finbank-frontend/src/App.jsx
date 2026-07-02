import React, { useState, useEffect } from "react";
import Layout from "./components/Layout";
import AccountList from "./components/AccountList";
import AccountDetail from "./components/AccountDetail";
import TransactionHistory from "./components/TransactionHistory";
import { useAuth } from "./hooks/useAuth";
import { useAccount } from "./hooks/useAccount";
import { authApi } from "./api/authApi";
import { accountApi } from "./api/accountApi";

export default function App() {
  const [msg, setMsg] = useState("");
  const [msgType, setMsgType] = useState("info");
  const notify = (m, type = "info") => { setMsg(m); setMsgType(type); };

  const { auth, saveAuth } = useAuth();
  const handleLogout = () => {
    saveAuth(null);
    setAccounts([]);
    setSelected(null);
    notify("로그아웃되었습니다.");
  };

  const {
    accounts, selected, loading, setAccounts, setSelected,
    setLoading, loadAccounts, selectAccount
  } = useAccount(auth, notify, handleLogout);

  const [loginEmail, setLoginEmail]       = useState(auth?.email || "");
  const [loginName, setLoginName]         = useState(auth?.name || "");
  const [loginPassword, setLoginPassword] = useState("");
  const [isRegisterMode, setIsRegisterMode] = useState(false);
  const [initialDeposit, setInitialDeposit] = useState("");
  const [accountType, setAccountType]     = useState("REGULAR"); // REGULAR | SAVINGS

  useEffect(() => { if (auth?.token) loadAccounts(); }, [auth?.token]);
  useEffect(() => {
    if (!msg) return;
    const t = setTimeout(() => setMsg(""), 4000);
    return () => clearTimeout(t);
  }, [msg]);

  const handleLogin = async (e) => {
    e.preventDefault();
    try {
      setLoading(true);
      const data = await authApi.login(loginEmail, loginPassword);
      saveAuth({ token: data.token, email: data.email, name: data.name });
      setLoginPassword("");
      notify(`${data.name}님, 환영합니다.`, "success");
    } catch (e) { notify(e.message, "error"); } finally { setLoading(false); }
  };

  const handleRegister = async (e) => {
    e.preventDefault();
    try {
      setLoading(true);
      await authApi.register(loginEmail, loginName, loginPassword);
      notify("회원가입 완료. 로그인 해주세요.", "success");
      setIsRegisterMode(false);
    } catch (e) { notify(e.message, "error"); } finally { setLoading(false); }
  };

  const deposit = async (val) => {
    if (!selected) return;
    try {
      setLoading(true);
      await accountApi.deposit(selected.account.id, val);
      notify("입금 완료", "success");
      await selectAccount(selected.account.id, false);
      await loadAccounts();
    } catch (e) { notify(e.message, "error"); } finally { setLoading(false); }
  };

  const withdraw = async (val) => {
    if (!selected) return;
    try {
      setLoading(true);
      await accountApi.withdraw(selected.account.id, val);
      notify("출금 완료", "success");
      await selectAccount(selected.account.id, false);
      await loadAccounts();
    } catch (e) { notify(e.message, "error"); } finally { setLoading(false); }
  };

  const transfer = async (to, amt) => {
    if (!selected) return;
    try {
      setLoading(true);
      await accountApi.transfer({
        fromAccountId: selected.account.id,
        toAccountNumber: to,
        amount: Number(amt),
      });
      notify("이체 완료", "success");
      await selectAccount(selected.account.id, false);
      await loadAccounts();
    } catch (e) { notify(e.message, "error"); } finally { setLoading(false); }
  };

  const createAccount = async (e) => {
    e.preventDefault();
    try {
      setLoading(true);
      await accountApi.createAccount(Number(initialDeposit || 0), accountType);
      setInitialDeposit("");
      notify(`${accountType === "SAVINGS" ? "적금" : "일반"} 계좌가 개설되었습니다.`, "success");
      await loadAccounts();
    } catch (e) { notify(e.message, "error"); } finally { setLoading(false); }
  };

  return (
    <Layout
      auth={auth} isRegisterMode={isRegisterMode} setIsRegisterMode={setIsRegisterMode}
      loginEmail={loginEmail} setLoginEmail={setLoginEmail}
      loginName={loginName} setLoginName={setLoginName}
      loginPassword={loginPassword} setLoginPassword={setLoginPassword}
      handleLogin={handleLogin} handleRegister={handleRegister} handleLogout={handleLogout}
    >
      <div className="max-w-6xl mx-auto px-6 py-6 grid grid-cols-12 gap-5">

        {/* 왼쪽: 계좌 목록 */}
        <aside className="col-span-3">
          <AccountList
            accounts={accounts}
            selected={selected}
            selectAccount={selectAccount}
            auth={auth}
          />
        </aside>

        {/* 중앙: 계좌 상세 + 거래 내역 */}
        <section className="col-span-6 flex flex-col gap-4">
          <AccountDetail
            selected={selected}
            loading={loading}
            deposit={deposit}
            withdraw={withdraw}
            transfer={transfer}
          />
          <TransactionHistory transactions={selected?.transactions} />
        </section>

        {/* 오른쪽: 계좌 개설 */}
        <aside className="col-span-3">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl shadow-lg overflow-hidden">
            <div className="px-5 pt-5 pb-3">
              <h2 className="text-sm font-semibold text-slate-300">새 계좌 개설</h2>
            </div>

            {auth ? (
              <form onSubmit={createAccount} className="px-5 pb-5 space-y-3">
                {/* 계좌 타입 선택 */}
                <div className="grid grid-cols-2 gap-2">
                  {[
                    { val: "REGULAR", label: "일반",  sub: "이자 없음",    color: "border-slate-600 text-slate-300" },
                    { val: "SAVINGS", label: "적금",  sub: "연 2% 이자",   color: "border-teal-500/60 text-teal-300" },
                  ].map(({ val, label, sub, color }) => (
                    <button
                      key={val}
                      type="button"
                      onClick={() => setAccountType(val)}
                      className={`rounded-xl border p-2.5 text-left transition-all ${
                        accountType === val
                          ? `${color} bg-slate-800`
                          : "border-slate-700/50 text-slate-500 hover:border-slate-600"
                      }`}
                    >
                      <p className="text-xs font-bold">{label}</p>
                      <p className="text-[10px] opacity-70">{sub}</p>
                    </button>
                  ))}
                </div>

                {/* 초기 입금액 */}
                <input
                  type="number"
                  value={initialDeposit}
                  onChange={e => setInitialDeposit(e.target.value)}
                  className="w-full bg-slate-950 border border-slate-700/80 rounded-xl px-3 py-2.5 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500/50 transition"
                  placeholder="초기 입금액 (선택)"
                  min="0"
                />

                {/* 개설 정보 요약 */}
                {accountType === "SAVINGS" && (
                  <div className="bg-teal-500/5 border border-teal-500/20 rounded-xl px-3 py-2.5">
                    <p className="text-[11px] text-teal-400 font-semibold mb-0.5">적금 계좌 안내</p>
                    <p className="text-[10px] text-teal-500/80">매일 자정 배치로 연 2% 이자가 자동 정산됩니다.</p>
                  </div>
                )}

                <button
                  type="submit"
                  disabled={loading}
                  className={`w-full py-3 rounded-xl text-sm font-bold text-white transition shadow-lg disabled:opacity-40 ${
                    accountType === "SAVINGS"
                      ? "bg-teal-500 hover:bg-teal-400 shadow-teal-500/20"
                      : "bg-blue-500 hover:bg-blue-400 shadow-blue-500/20"
                  }`}
                >
                  {accountType === "SAVINGS" ? "적금 계좌 개설" : "일반 계좌 개설"}
                </button>
              </form>
            ) : (
              <p className="text-xs text-slate-500 text-center pb-8">로그인 후 이용 가능합니다.</p>
            )}
          </div>
        </aside>
      </div>

      {/* 토스트 알림 */}
      {msg && (
        <div className={`fixed bottom-6 left-1/2 -translate-x-1/2 px-5 py-2.5 rounded-full text-sm shadow-xl border backdrop-blur transition-all ${
          msgType === "error"
            ? "bg-rose-500/90 border-rose-400/50"
            : "bg-emerald-500/90 border-emerald-400/50"
        } text-white font-medium`}>
          {msg}
        </div>
      )}

      {/* 로딩 오버레이 */}
      {loading && (
        <div className="fixed inset-0 bg-black/20 backdrop-blur-[1px] flex items-center justify-center pointer-events-none z-40">
          <div className="px-4 py-2 rounded-full bg-slate-900/95 border border-slate-700 text-xs text-slate-300 flex items-center gap-2 shadow-xl">
            <span className="h-1.5 w-1.5 rounded-full bg-blue-400 animate-ping" />
            처리 중...
          </div>
        </div>
      )}
    </Layout>
  );
}
