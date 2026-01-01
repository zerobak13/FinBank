import React, { useState, useEffect } from "react";

function classNames(...classes) {
  return classes.filter(Boolean).join(" ");
}

function getStoredAuth() {
  try {
    const raw = localStorage.getItem("finbankAuth");
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (parsed && parsed.token && parsed.email && parsed.name) {
      return parsed;
    }
  } catch (e) {
    console.error(e);
  }
  return null;
}

async function api(path, options = {}) {
  const auth = getStoredAuth();
  const headers = {
    "Content-Type": "application/json",
    ...(options.headers || {}),
  };
  if (auth?.token) {
    headers["Authorization"] = `Bearer ${auth.token}`;
  }

  const res = await fetch(path, {
    ...options,
    headers,
  });

  if (!res.ok) {
    let message = "요청에 실패했습니다.";
    try {
      const data = await res.json();
      if (data && data.message) message = data.message;
      else if (typeof data === "string" && data) message = data;
    } catch {
      message = `요청에 실패했습니다. (HTTP ${res.status})`;
    }
    throw new Error(message);
  }


  if (res.status === 204) return null;
  return res.json();
}

export default function App() {
  const [auth, setAuth] = useState(() => getStoredAuth());
  const [loginEmail, setLoginEmail] = useState(auth?.email || "");
  const [loginName, setLoginName] = useState(auth?.name || "");
  const [loginPassword, setLoginPassword] = useState("");
  const [isRegisterMode, setIsRegisterMode] = useState(false);

  const [accounts, setAccounts] = useState([]);
  const [selected, setSelected] = useState(null);
  const [initialDeposit, setInitialDeposit] = useState("");
  const [toAccountNumber, setToAccountNumber] = useState("");
  const [amount, setAmount] = useState("");

  const [loading, setLoading] = useState(false);
  const [msg, setMsg] = useState("");
  const [msgType, setMsgType] = useState("info");

  useEffect(() => {
    if (!msg) return;
    const t = setTimeout(() => setMsg(""), 4000);
    return () => clearTimeout(t);
  }, [msg]);

  useEffect(() => {
    if (auth?.token) {
      loadAccounts();
    }
  }, [auth?.token]);

  const notify = (message, type = "info") => {
    setMsg(message);
    setMsgType(type);
  };

  const saveAuth = (nextAuth) => {
    setAuth(nextAuth);
    if (nextAuth) {
      localStorage.setItem("finbankAuth", JSON.stringify(nextAuth));
    } else {
      localStorage.removeItem("finbankAuth");
    }
  };

  const handleRegister = async (e) => {
    e.preventDefault();
    if (!loginEmail || !loginName || !loginPassword) {
      notify("이메일, 이름, 비밀번호를 모두 입력하세요.", "error");
      return;
    }
    try {
      setLoading(true);
      await api("/api/auth/register", {
        method: "POST",
        body: JSON.stringify({
          email: loginEmail,
          name: loginName,
          password: loginPassword,
        }),
      });
      notify("회원가입이 완료되었습니다. 로그인해 주세요.", "success");
      setIsRegisterMode(false);
    } catch (e) {
      notify(e.message, "error");
    } finally {
      setLoading(false);
    }
  };

  const handleLogin = async (e) => {
    e.preventDefault();
    if (!loginEmail || !loginPassword) {
      notify("이메일과 비밀번호를 입력하세요.", "error");
      return;
    }
    try {
      setLoading(true);
      const data = await api("/api/auth/login", {
        method: "POST",
        body: JSON.stringify({
          email: loginEmail,
          password: loginPassword,
        }),
      });
      const nextAuth = {
        token: data.token,
        email: data.email,
        name: data.name,
      };
      saveAuth(nextAuth);
      setLoginName(data.name);
      setLoginPassword("");
      notify(`${data.name} 님, 환영합니다.`, "success");
      await loadAccounts();
    } catch (e) {
      notify(e.message, "error");
    } finally {
      setLoading(false);
    }
  };

  const handleLogout = () => {
    saveAuth(null);
    setAccounts([]);
    setSelected(null);
    setInitialDeposit("");
    setToAccountNumber("");
    setAmount("");
    notify("로그아웃되었습니다.", "info");
  };

  const loadAccounts = async () => {
    if (!auth?.token) return;
    try {
      setLoading(true);
      const data = await api("/api/accounts");
      setAccounts(data);
      if (data.length > 0) {
        await selectAccount(data[0].id, false);
      } else {
        setSelected(null);
      }
    } catch (e) {
      //토큰이 죽었으면 자동 로그아웃 처리
      if (String(e.message).includes("HTTP 403")) {
        handleLogout();
        return;
      }
      notify(e.message, "error");
    } finally {
      setLoading(false);
    }
  };

  const selectAccount = async (id, showToast = true) => {
    try {
      setLoading(true);
      const data = await api(`/api/accounts/${id}`);
      setSelected(data);
      if (showToast) notify("계좌를 불러왔습니다.", "success");
    } catch (e) {
      notify(e.message, "error");
    } finally {
      setLoading(false);
    }
  };

  const createAccount = async (e) => {
    e.preventDefault();
    if (!auth?.token) {
      notify("먼저 로그인하세요.", "error");
      return;
    }
    const value = Number(initialDeposit || 0);
    if (value < 0) {
      notify("초기 입금액은 0 이상이어야 합니다.", "error");
      return;
    }
    try {
      setLoading(true);
      await api("/api/accounts", {
        method: "POST",
        body: JSON.stringify({ initialDeposit: value }),
      });
      setInitialDeposit("");
      notify("새 계좌가 생성되었습니다.", "success");
      await loadAccounts();
    } catch (e) {
      notify(e.message, "error");
    } finally {
      setLoading(false);
    }
  };

  const transfer = async (e) => {
    e.preventDefault();
    if (!selected) {
      notify("먼저 계좌를 선택하세요.", "error");
      return;
    }
    if (!toAccountNumber || !amount) {
      notify("받는 계좌번호와 금액을 입력하세요.", "error");
      return;
    }
    const value = Number(amount);
    if (value <= 0) {
      notify("이체 금액은 1원 이상이어야 합니다.", "error");
      return;
    }
    try {
      setLoading(true);
      await api("/api/accounts/transfer", {
        method: "POST",
        body: JSON.stringify({
          fromAccountId: selected.account.id,
          toAccountNumber,
          amount: value,
        }),
      });
      notify("이체가 완료되었습니다.", "success");
      setAmount("");
      setToAccountNumber("");
      await selectAccount(selected.account.id, false);
      await loadAccounts();
    } catch (e) {
      notify(e.message, "error");
    } finally {
      setLoading(false);
    }
  };

  const currentUserLabel = auth
    ? `${auth.name} (${auth.email})`
    : "로그인 필요";

  return (
    <div className="min-h-screen bg-slate-950 text-slate-50 flex flex-col">
      {/* 상단 네비 + 로그인 바 */}
      <header className="border-b border-slate-800 bg-slate-950/80 backdrop-blur">
        <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between gap-4">
          <div className="flex items-center gap-2">
            <div className="h-8 w-8 rounded-xl bg-blue-500 flex items-center justify-center text-white font-bold text-lg">
              F
            </div>
            <div>
              <div className="text-lg font-semibold tracking-tight">FINBANK</div>
              <div className="text-xs text-slate-400">
                Enterprise Internet Banking · JWT Demo
              </div>
            </div>
          </div>

          <form
            onSubmit={isRegisterMode ? handleRegister : handleLogin}
            className="flex flex-wrap items-center justify-end gap-2 text-xs"
          >
            <div className="flex items-center gap-2">
              <input
                className="bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 focus:outline-none focus:ring-1 focus:ring-blue-500 w-40"
                placeholder="이메일"
                value={loginEmail}
                onChange={(e) => setLoginEmail(e.target.value)}
                type="email"
              />
              {isRegisterMode && (
                <input
                  className="bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 focus:outline-none focus:ring-1 focus:ring-blue-500 w-28"
                  placeholder="이름"
                  value={loginName}
                  onChange={(e) => setLoginName(e.target.value)}
                />
              )}
              <input
                className="bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 focus:outline-none focus:ring-1 focus:ring-blue-500 w-32"
                placeholder="비밀번호"
                type="password"
                value={loginPassword}
                onChange={(e) => setLoginPassword(e.target.value)}
              />
            </div>

            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setIsRegisterMode((v) => !v)}
                className="px-3 py-1.5 rounded-lg border border-slate-600 hover:bg-slate-800"
              >
                {isRegisterMode ? "로그인 모드" : "회원가입"}
              </button>

              {auth ? (
                <>
                  <span className="hidden md:inline text-slate-400">
                    {currentUserLabel}
                  </span>
                  <button
                    type="button"
                    onClick={handleLogout}
                    className="px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 border border-slate-600"
                  >
                    로그아웃
                  </button>
                </>
              ) : (
                <button
                  type="submit"
                  className="px-3 py-1.5 rounded-lg bg-blue-500 hover:bg-blue-600 font-semibold"
                >
                  {isRegisterMode ? "회원가입 완료" : "로그인"}
                </button>
              )}
            </div>
          </form>
        </div>
      </header>

      {/* 본문 */}
      <main className="flex-1">
        <div className="max-w-6xl mx-auto px-6 py-6 grid grid-cols-12 gap-6">
          {/* 좌측: 계좌 목록 */}
          <section className="col-span-3 space-y-4">
            <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-4 shadow-lg">
              <div className="flex items-center justify-between mb-2">
                <h2 className="text-sm font-semibold text-slate-300">
                  로그인 정보
                </h2>
                <span className="text-xs text-slate-500">JWT</span>
              </div>
              <p className="text-xs text-slate-400">
                {auth
                  ? "현재 토큰 기반으로 인증된 상태입니다."
                  : "상단에서 회원가입 또는 로그인 후 사용 가능합니다."}
              </p>
            </div>

            <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-4 shadow-lg h-[420px] flex flex-col">
              <div className="flex items-center justify-between mb-3">
                <h2 className="text-sm font-semibold text-slate-300">내 계좌</h2>
                <span className="text-xs text-slate-500">
                  {accounts.length ? `${accounts.length}개` : "-"}
                </span>
              </div>
              <div className="flex-1 overflow-auto space-y-2 pr-1">
                {auth && accounts.map((a) => (
                  <button
                    key={a.id}
                    onClick={() => selectAccount(a.id, true)}
                    className={classNames(
                      "w-full text-left px-3 py-2 rounded-xl border text-sm transition-colors",
                      selected && selected.account.id === a.id
                        ? "bg-blue-500/10 border-blue-500 text-blue-100"
                        : "bg-slate-900 border-slate-700 hover:border-blue-500/60 hover:bg-slate-800"
                    )}
                  >
                    <div className="flex justify-between items-center mb-1">
                      <span className="font-semibold tracking-tight">
                        {a.accountNumber}
                      </span>
                      <span
                        className={classNames(
                          "text-2xs px-2 py-0.5 rounded-full",
                          a.locked
                            ? "bg-red-500/10 text-red-300"
                            : "bg-emerald-500/10 text-emerald-300"
                        )}
                      >
                        {a.locked ? "잠금" : "정상"}
                      </span>
                    </div>
                    <div className="flex justify-between items-end">
                      <span className="text-xs text-slate-400 truncate">
                        {a.ownerName} · {a.ownerEmail}
                      </span>
                      <span className="text-sm font-semibold">
                        {a.balance.toLocaleString()}원
                      </span>
                    </div>
                  </button>
                ))}

                {auth && accounts.length === 0 && (
                  <div className="text-xs text-slate-500 mt-6 text-center">
                    아직 계좌가 없습니다.
                    <br />
                    우측에서 새 계좌를 개설해 보세요.
                  </div>
                )}

                {!auth && (
                  <div className="text-xs text-slate-500 mt-6 text-center">
                    먼저 로그인하면 계좌 목록이 표시됩니다.
                  </div>
                )}
              </div>
            </div>
          </section>

          {/* 중앙: 계좌 상세 + 거래 내역 */}
          <section className="col-span-6 space-y-4">
            <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-6 shadow-lg min-h-[220px]">
              {selected ? (
                <>
                  <div className="flex justify-between items-start mb-4">
                    <div>
                      <div className="text-xs uppercase text-slate-500">
                        Account Number
                      </div>
                      <div className="text-lg font-semibold tracking-tight">
                        {selected.account.accountNumber}
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-xs text-slate-500">잔액</div>
                      <div className="text-3xl font-bold text-blue-400">
                        {selected.account.balance.toLocaleString()}원
                      </div>
                    </div>
                  </div>
                  <div className="flex justify-between text-sm text-slate-400 mb-4">
                    <div>
                      소유자:{" "}
                      <span className="text-slate-100">
                        {selected.account.ownerName}
                      </span>{" "}
                      ({selected.account.ownerEmail})
                    </div>
                    <div>
                      상태:{" "}
                      <span
                        className={classNames(
                          "px-2 py-0.5 rounded-full text-xs",
                          selected.account.locked
                            ? "bg-red-500/10 text-red-300"
                            : "bg-emerald-500/10 text-emerald-300"
                        )}
                      >
                        {selected.account.locked ? "잠금" : "정상"}
                      </span>
                    </div>
                  </div>

                  {/* 이체 폼 */}
                  <form
                    onSubmit={transfer}
                    className="mt-4 grid grid-cols-12 gap-3 items-end"
                  >
                    <div className="col-span-5">
                      <label className="block text-xs text-slate-400 mb-1">
                        받는 계좌번호
                      </label>
                      <input
                        className="w-full bg-slate-950 border border-slate-700 rounded-xl px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                        placeholder="상대 계좌번호 12자리"
                        value={toAccountNumber}
                        onChange={(e) => setToAccountNumber(e.target.value)}
                      />
                    </div>
                    <div className="col-span-4">
                      <label className="block text-xs text-slate-400 mb-1">
                        이체 금액
                      </label>
                      <input
                        type="number"
                        className="w-full bg-slate-950 border border-slate-700 rounded-xl px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                        placeholder="예: 50000"
                        value={amount}
                        onChange={(e) => setAmount(e.target.value)}
                      />
                    </div>
                    <div className="col-span-3 flex gap-2">
                      <button
                        type="submit"
                        disabled={loading}
                        className="flex-1 bg-blue-500 hover:bg-blue-600 text-sm font-semibold rounded-xl px-4 py-2 mt-5 transition-colors disabled:opacity-60"
                      >
                        계좌번호로 이체
                      </button>
                    </div>
                  </form>

                  <p className="mt-2 text-[11px] text-slate-500">
                    상대방도 자신의 이메일로 로그인하여 본인 계좌를 선택하면,
                    입금 거래 내역을 통해 이체 결과를 확인할 수 있습니다.
                  </p>
                </>
              ) : (
                <div className="h-full flex flex-col items-center justify-center text-sm text-slate-500">
                  <div className="mb-2 text-slate-300 font-semibold">
                    좌측에서 계좌를 선택하면 상세 정보가 표시됩니다.
                  </div>
                  <div>이체 기능과 거래 내역도 이곳에서 확인할 수 있습니다.</div>
                </div>
              )}
            </div>

            <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 shadow-lg min-h-[220px]">
              <div className="flex items-center justify-between mb-3">
                <div>
                  <h2 className="text-sm font-semibold text-slate-300">
                    거래 내역
                  </h2>
                  <p className="text-xs text-slate-500">
                    선택한 계좌 기준 입출금 및 이체 기록입니다.
                  </p>
                </div>
              </div>
              <div className="space-y-2 max-h-64 overflow-auto pr-1">
                {selected && selected.transactions.length > 0 ? (
                  selected.transactions.map((t) => (
                    <div
                      key={t.id}
                      className="flex items-center justify-between px-3 py-2 rounded-xl border border-slate-800 bg-slate-950/40"
                    >
                      <div>
                        <div className="text-xs font-semibold text-slate-200">
                          {t.type}
                        </div>
                        <div className="text-[11px] text-slate-500">
                          {t.description || "-"}
                        </div>
                      </div>
                      <div className="text-right">
                        <div className="text-sm font-semibold">
                          {t.amount.toLocaleString()}원
                        </div>
                        <div className="text-[11px] text-slate-500">
                          이후 잔액 {t.balanceAfter.toLocaleString()}원
                        </div>
                      </div>
                    </div>
                  ))
                ) : (
                  <div className="text-xs text-slate-500 mt-4">
                    거래 내역이 없습니다.
                  </div>
                )}
              </div>
            </div>
          </section>

          {/* 우측: 계좌 개설 & 설명 */}
          <section className="col-span-3 space-y-4">
            <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 shadow-lg">
              <h2 className="text-sm font-semibold text-slate-300 mb-1">
                새 계좌 개설
              </h2>
              <p className="text-xs text-slate-500 mb-4">
                로그인된 사용자 기준으로 계좌가 생성됩니다.
              </p>

              {auth ? (
                <>
                  <div className="text-xs text-slate-400 mb-3">
                    <span className="font-semibold text-slate-200">
                      {auth.name}
                    </span>{" "}
                    ({auth.email})
                  </div>
                  <form onSubmit={createAccount} className="space-y-3">
                    <div>
                      <label className="block text-xs text-slate-400 mb-1">
                        초기 입금액
                      </label>
                      <input
                        type="number"
                        className="w-full bg-slate-950 border border-slate-700 rounded-xl px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                        placeholder="예: 100000"
                        value={initialDeposit}
                        onChange={(e) => setInitialDeposit(e.target.value)}
                      />
                    </div>
                    <button
                      type="submit"
                      disabled={loading}
                      className="w-full bg-emerald-500 hover:bg-emerald-600 text-sm font-semibold rounded-xl px-4 py-2 mt-1 transition-colors disabled:opacity-60"
                    >
                      계좌 개설
                    </button>
                  </form>
                </>
              ) : (
                <div className="text-xs text-slate-500 mt-2">
                  상단에서 먼저 로그인해 주세요.
                </div>
              )}
            </div>

            <div className="bg-slate-900/60 border border-slate-800 rounded-2xl p-4 text-xs text-slate-400 space-y-2">
              <div className="font-semibold text-slate-200 text-sm">
                프로젝트 핵심 포인트
              </div>
              <ul className="list-disc list-inside space-y-1">
                <li>JWT 기반 토큰 인증 (React SPA + Spring Security)</li>
                <li>트랜잭션 단위로 계좌/거래 로그 정합성 보장</li>
                <li>비관적 락(PESSIMISTIC_WRITE) 기반 동시성 제어</li>
                <li>계좌번호 기반 이체로 실제 인터넷뱅킹 UX 유사 구현</li>
              </ul>
            </div>
          </section>
        </div>
      </main>

      {/* 하단 알림 바 */}
      {msg && (
        <div className="fixed bottom-4 left-1/2 -translate-x-1/2">
          <div
            className={classNames(
              "px-4 py-2 rounded-full text-sm shadow-lg border",
              msgType === "error"
                ? "bg-red-500/90 border-red-400 text-white"
                : msgType === "success"
                ? "bg-emerald-500/90 border-emerald-400 text-white"
                : "bg-slate-800/90 border-slate-600 text-slate-50"
            )}
          >
            {msg}
          </div>
        </div>
      )}

      {/* 로딩 오버레이 */}
      {loading && (
        <div className="fixed inset-0 bg-black/30 flex items-center justify-center pointer-events-none">
          <div className="px-4 py-2 rounded-full bg-slate-900/90 border border-slate-700 text-xs text-slate-300 flex items-center gap-2">
            <span className="h-2 w-2 rounded-full bg-blue-400 animate-ping" />
            작업 중입니다...
          </div>
        </div>
      )}
    </div>
  );
}
