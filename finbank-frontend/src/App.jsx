import React, { useState, useEffect } from "react";
import Layout from "./components/Layout";
import AccountList from "./components/AccountList";
import AccountDetail from "./components/AccountDetail";
import TransactionHistory from "./components/TransactionHistory";
import { useAuth } from "./hooks/useAuth";
import { useAccount } from "./hooks/useAccount";
import { authApi } from "./api/authApi";
import { accountApi } from "./api/accountApi";
import LoanPage from "./pages/LoanPage";

export default function App() {
  const [msg, setMsg] = useState("");
  const [msgType, setMsgType] = useState("info");
  const [menu, setMenu] = useState("account"); // account | loan
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
    setLoading, loadAccounts, selectAccount, refreshAfterTransaction
  } = useAccount(auth, notify, handleLogout);

  const [loginEmail, setLoginEmail] = useState(auth?.email || "");
  const [loginName, setLoginName] = useState(auth?.name || "");
  const [loginPassword, setLoginPassword] = useState("");
  const [isRegisterMode, setIsRegisterMode] = useState(false);
  const [initialDeposit, setInitialDeposit] = useState("");

  useEffect(() => { if (auth?.token) loadAccounts(); }, [auth?.token]);
  useEffect(() => { if (!msg) return; const t = setTimeout(() => setMsg(""), 4000); return () => clearTimeout(t); }, [msg]);

  const handleLogin = async (e) => {
    e.preventDefault();
    try {
      setLoading(true);
      const data = await authApi.login(loginEmail, loginPassword);
      saveAuth({ token: data.token, email: data.email, name: data.name });
      setLoginPassword("");
      notify(`${data.name}님 환영합니다.`, "success");
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
      await refreshAfterTransaction(selected.account.id);
    } catch (e) { notify(e.message, "error"); } finally { setLoading(false); }
  };

  const withdraw = async (val) => {
    if (!selected) return;
    try {
      setLoading(true);
      await accountApi.withdraw(selected.account.id, val);
      notify("출금 완료", "success");
      await refreshAfterTransaction(selected.account.id);
    } catch (e) { notify(e.message, "error"); } finally { setLoading(false); }
  };

  const transfer = async (to, amt) => {
    if (!selected) return;
    try {
      setLoading(true);
      await accountApi.transfer({ fromAccountId: selected.account.id, toAccountNumber: to, amount: Number(amt) });
      notify("이체 완료", "success");
      await refreshAfterTransaction(selected.account.id);
    } catch (e) { notify(e.message, "error"); } finally { setLoading(false); }
  };

  const createAccount = async (e) => {
    e.preventDefault();
    try {
      setLoading(true);
      await accountApi.createAccount(Number(initialDeposit || 0));
      setInitialDeposit("");
      notify("계좌 생성 완료", "success");
      await loadAccounts();
    } catch (e) { notify(e.message, "error"); } finally { setLoading(false); }
  };

  return (
      <Layout
          auth={auth} menu={menu} setMenu={setMenu}
          isRegisterMode={isRegisterMode} setIsRegisterMode={setIsRegisterMode}
          loginEmail={loginEmail} setLoginEmail={setLoginEmail} loginName={loginName} setLoginName={setLoginName}
          loginPassword={loginPassword} setLoginPassword={setLoginPassword}
          handleLogin={handleLogin} handleRegister={handleRegister} handleLogout={handleLogout}
      >
        {menu === "loan" ? (
            <LoanPage auth={auth} loading={loading} setLoading={setLoading} notify={notify} />
        ) : (
        <div className="max-w-6xl mx-auto px-6 py-6 grid grid-cols-12 gap-6">
          <aside className="col-span-3 space-y-4">
            <AccountList accounts={accounts} selected={selected} selectAccount={selectAccount} auth={auth} />
          </aside>

          <section className="col-span-6 space-y-4">
            <AccountDetail selected={selected} loading={loading} deposit={deposit} withdraw={withdraw} transfer={transfer} />
            <TransactionHistory transactions={selected?.transactions} />
          </section>

          <aside className="col-span-3 space-y-4">
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-lg">
              <h2 className="text-sm font-semibold mb-2">새 계좌 개설</h2>
              {auth ? (
                  <form onSubmit={createAccount}>
                    <input type="number" step="1000" min="0" value={initialDeposit} onChange={e => setInitialDeposit(e.target.value)} className="w-full bg-slate-950 border border-slate-700 rounded p-2 mb-2 text-sm" placeholder="초기 입금액" />
                    <button type="submit" disabled={loading} className="w-full bg-emerald-500 py-2 rounded text-sm font-bold">개설하기</button>
                  </form>
              ) : <p className="text-xs text-slate-500">로그인 후 이용 가능합니다.</p>}
            </div>
          </aside>
        </div>
        )}

        {msg && (
            <div className={`fixed bottom-4 left-1/2 -translate-x-1/2 px-4 py-2 rounded-full text-sm shadow-lg border ${msgType === 'error' ? 'bg-red-500/90 border-red-400' : 'bg-emerald-500/90 border-emerald-400'} text-white`}>
              {msg}
            </div>
        )}
        {loading && (
            <div className="fixed inset-0 bg-black/30 flex items-center justify-center pointer-events-none">
              <div className="px-4 py-2 rounded-full bg-slate-900/90 border border-slate-700 text-xs text-slate-300 flex items-center gap-2">
                <span className="h-2 w-2 rounded-full bg-blue-400 animate-ping" /> 작업 중...
              </div>
            </div>
        )}
      </Layout>
  );
}