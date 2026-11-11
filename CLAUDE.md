# FinBank — Claude Code 작업 지침

금융 거래의 무결성·동시성을 중심으로 만든 Spring Boot 백엔드 + React 프론트엔드.
계좌(수신)와 대출(여신) 모듈로 구성된다. 응답·주석·커밋 메시지는 한국어로 쓴다.

## 지도
- `finbank-backend/src/main/java/com/finbank/backend/`
  - `account/` 계좌·입출금·이체 (비관적 락, 이체 멱등키)
  - `loan/` 대출 상품·신청·실행·상환·연체 배치
    - `loan/domain/calculator/` 이자 계산 엔진 (순수 자바, Spring 무의존)
  - `common/` `MoneyPolicy`(금액 정책), `idempotency/`(멱등 실행기)
  - `auth/`, `member/`, `exception/`(`ErrorCode`)
- `finbank-backend/src/main/resources/db/migration/` Flyway 마이그레이션
- `finbank-backend/src/test/java/com/finbank/backend/loan/harness/` 불변식 하네스 (판정 기준)
- `scripts/harness/` 하네스 가드·테스트 스크립트
- `harness-reports/` 에이전트 작업 기록
- `finbank-frontend/` React + Vite

## 테스트
- 빠른 테스트 (DB 불필요 — 계산 엔진 + 불변식 하네스): `scripts/harness/fast-test.sh`
- 전체 테스트: `cd finbank-backend && ./gradlew test` (MySQL `finbank_test` 스키마 필요 — README Quick Start 참고)

## 코드 규칙
- 금액은 `BigDecimal`. 스케일·반올림은 `MoneyPolicy`만 따른다 (중간 계산 scale 10, 확정 금액 원 미만 절사, 일할 계산 365일). 비교는 `compareTo`/`signum` — `equals` 금지.
- 잔액이 바뀌는 계좌는 비관적 락 조회(`findWithLockingById`)가 그 엔티티의 **첫 조회**여야 한다 (1차 캐시 함정). 락 전에 필요한 값은 ID·소유자 프로젝션으로 읽는다.
- 락 순서: 계좌끼리는 ID 오름차순, 자원 간에는 `Account` → `LoanAccount`. 권한 확인은 락보다 먼저.
- 돈이 움직이는 API는 `IdempotentExecutor`로 감싸고, 응답 확정(`complete`)은 본 처리 트랜잭션 안에서 한다.
- 새 예외는 `ErrorCode` 기반 `BusinessException`.
- DB 스키마 변경은 새 Flyway 버전 파일로만. 이미 적용된 마이그레이션은 수정하지 않는다.
- 테스트 계좌번호는 `TestAccountNumbers.next()`.
- 상환 스케줄 엔진의 입력 계약: 원금은 양의 정수(원), 연이율 0~20%, 기간 1~600개월, 실행일 필수.
  계약 밖은 `IllegalArgumentException`으로 거부 (판정 기준: `InputContract`, `LoanInvariant` INP-01~04).
- 커밋 메시지: `feat:`/`fix:`/`test:`/`docs:`/`chore:` + 한국어 요약.

## 판정 기준 (사람만 수정)
구현을 판정하는 기준이다. 에이전트는 수정할 수 없고(hook이 차단), 사람도 명시적으로 열어야 한다.
경로 목록의 단일 출처는 `scripts/harness/harness_rules.py`.
- `finbank-backend/src/test/java/com/finbank/backend/loan/harness/`
- `finbank-backend/src/test/java/com/finbank/backend/loan/domain/calculator/`
- `finbank-backend/src/test/resources/harness/schedule-cases.csv`
- `finbank-backend/build.gradle`, `scripts/harness/`, `.claude/`, `.github/workflows/`, `CLAUDE.md`
- 작업 명세 `harness-reports/<작업명>/task.md`

판정 기준을 바꿔야 할 때: `FINBANK_ALLOW_ORACLE_EDIT=1 claude`로 실행하고, PR에 `oracle-approved` 라벨을 붙인다.
비밀 정보(`application-local.yml`, `.env*`)는 누구도 읽거나 쓸 수 없다.

## 에이전트
- `loan-implementer` — `finbank-backend/src/main/`만 수정, 명령은 `scripts/harness/fast-test.sh`만
- `loan-verifier` — `llm-cases.csv`(끝에 추가만)와 `harness-reports/`만 수정, 명령은 `fast-test.sh`만
- 역할별로 띄우기 (터미널 하나에 역할 하나): `scripts/harness/run-agent.sh implementer` / `verifier`
- 차단 기록: `.harness/guard-log.jsonl`
