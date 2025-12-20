# FinBank

- Backend: Spring Boot 3 + Spring Security + JWT + JPA(H2)
- Frontend: React + Vite + TailwindCSS
- 기능:
  - 이메일/이름/비밀번호 기반 회원가입 & JWT 로그인
  - 로그인 사용자 기준 계좌 개설
  - 계좌번호 기반 이체 (상대 계좌에도 입금 내역 기록)
  - 계좌별 거래 내역 조회
  - 트랜잭션 + 비관적 락(PESSIMISTIC_WRITE) 기반 동시성 제어

## 실행 순서

1. 백엔드 실행

```bash
cd finbank-backend
chmod +x gradlew   # 최초 1회
./gradlew bootRun
```

2. 프론트 실행

```bash
cd finbank-frontend
npm install
npm run dev
```

3. 브라우저에서 접속

- http://localhost:5173

## 데모 흐름

1. 상단에서 이메일/이름/비밀번호로 회원가입
2. 같은 이메일/비밀번호로 로그인 → JWT 토큰 발급
3. 우측 패널에서 새 계좌 개설 (초기 입금 가능)
4. 좌측에서 내 계좌 선택 → 중앙에서 계좌번호 기반 이체 실행
5. 다른 이메일로 회원가입/로그인 후, 본인 계좌를 선택해 입금 내역 확인
