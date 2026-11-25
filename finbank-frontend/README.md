# FinBank Frontend (React + Vite + Tailwind)

- React + Vite
- Tailwind CSS
- JWT 로그인 / 회원가입 — 토큰이 만료되면 Refresh Token으로 한 번 재발급하고 같은 요청을 다시 보냄, 재발급도 실패하면 로그아웃 (`src/api/client.js`)
- 계좌 목록, 계좌번호 이체, 거래 내역 조회
- 대출 신청·실행·상환

## 실행 방법

```bash
cd finbank-frontend
npm install
npm run dev
```

## 테스트

```bash
npm test   # client.js의 토큰 만료 처리 16개 — Node 내장 테스트라 추가 의존성 없음
```
