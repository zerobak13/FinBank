# 작업 명세 — 상환 스케줄 입력 계약 보강

- 작성: 사람 (2026-11-04) · 이 파일은 판정 기준이라 에이전트가 수정할 수 없다
- 출처: 검증 리포트 `verify-1.md` (L-001 ~ L-003, 기간 상한 부재) + 사람 결정

## 명세 — 입력 계약
계약 밖의 입력은 `IllegalArgumentException`으로 거부한다. 판정 기준: `InputContract`, `LoanInvariant` INP-01 ~ 04.

| ID | 규칙 | 관련 케이스 |
|---|---|---|
| INP-01 | 원금은 0보다 큰 원 단위 정수 | L-001 |
| INP-02 | 연이율은 0 이상, 법정 최고금리 연 20% 이하 (정확히 20%는 허용) | L-002 |
| INP-03 | 대출 기간은 1개월 이상 600개월(50년) 이하 | 기간 상한 부재 (OutOfMemoryError) |
| INP-04 | 실행일은 필수이고, 만기일 계산이 날짜 표현 범위를 넘으면 거부한다 (`DateTimeException`을 그대로 내보내지 않는다) | L-003 |

## 구현 범위
- `finbank-backend/src/main/java/com/finbank/backend/loan/domain/calculator/`
  — `RepaymentScheduleGenerator.validate()`와 세 생성기
- 계약 안의 입력은 지금과 똑같이 동작해야 한다 (기존 계산 결과·외부 계산기 대조값 불변)
- 기존 거부 사유("반올림 누적으로 N회차에 잔액이 음수가 됩니다")는 그대로 유지한다

## 완료 조건
- `scripts/harness/fast-test.sh` 통과
  - L-001 ~ L-003이 거부로 판정된다
  - 무작위 탐색: 불변식 위반 0건, 계약 밖인데 통과 0건, 예상 밖 거부 0건
