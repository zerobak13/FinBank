# 구현 기록 — demo / 라운드 1

- 일시: 2026-11-11 22:53 (KST), 소요 약 1분 20초
- 실행: `scripts/harness/run-agent.sh implementer` (Claude Code 2.1.317, 에이전트 `loan-implementer`)
- 기록: 사람이 정리 — 구현 에이전트는 이 폴더에 쓸 수 없다

## 사람이 준 지시

> harness-reports/demo/task.md 명세를 구현해줘. verify-1.md의 수정 요청도 참고하고, 끝나면 fast-test로 확인해줘.

에이전트가 읽은 것: `task.md`, `verify-1.md`, 계산 엔진 파일 8개

## 에이전트 보고 (원문 — 표 모양만 마크다운으로 옮김)

task.md의 입력 계약 INP-01~04를 구현했고, scripts/harness/fast-test.sh가 통과했습니다(BUILD SUCCESSFUL). 출력에 실패 테스트는 없었으니 L-001~L-003도 거부로 판정된 것입니다.
무작위 탐색(시드 42, 20,000건)은 불변식 위반 0건, 계약 밖인데 통과 0건이었습니다. "예상 밖 거부"는 출력에 따로 나오지 않았지만 해당 테스트가 통과했으니 0건으로 봅니다.

**바꾼 파일** — `finbank-backend/src/main/java/com/finbank/backend/loan/domain/calculator/` 아래 네 파일입니다.

`RepaymentScheduleGenerator.java` — 공통 검증 validate()에 실행일 인자를 추가하고, 다음 거부 규칙을 넣었습니다. 모두 IllegalArgumentException으로 거부합니다.

| 불변식 | 추가한 거부 규칙 | 관련 케이스 |
|---|---|---|
| INP-01 | 원금이 원 단위 정수가 아니면 거부. 1000.00처럼 소수부가 0이면 정수로 봅니다. | L-001 |
| INP-02 | 연이율이 MoneyPolicy.MAX_LEGAL_RATE(20%)보다 크면 거부. 정확히 20%는 허용합니다. | L-002 |
| INP-03 | 기간이 600개월보다 길면 거부. 상한은 인터페이스 상수 MAX_TERM_MONTHS = 600으로 뒀습니다. 이제 new ArrayList<>(termMonths)에 도달하기 전에 걸러지므로 OutOfMemoryError가 나지 않습니다. | 기간 상한 부재 |
| INP-04 | 실행일이 없으면 거부. 만기일(executedAt.plusMonths(termMonths)) 계산에서 DateTimeException이 나면 원인으로 붙여 IllegalArgumentException으로 다시 던집니다. 만기일이 표현 가능하면 그 앞 회차 납부일도 모두 표현 가능하므로 한 번만 확인합니다. | L-003 |

`EqualPrincipalGenerator.java`, `EqualPaymentGenerator.java`, `BulletGenerator.java` — 세 생성기 모두 실행일까지 넘겨 validate()를 호출하도록만 바꿨습니다.

**바꾸지 않은 것**

- 계약 안의 입력에서는 계산 로직이 그대로입니다. 검증 단계에서 거부만 늘었습니다.
- 원리금균등의 기존 거부 사유("반올림 누적으로 N회차에 잔액이 음수가 됩니다")도 그대로 둬서, 무작위 탐색에서 이 사유로 347건이 계속 거부됐습니다.

**남은 의문**

1. 상한값이 두 군데에 있습니다. 600개월 상한이 구현 쪽 RepaymentScheduleGenerator.MAX_TERM_MONTHS와 판정 기준 쪽 InputContract.MAX_TERM_MONTHS에 따로 있습니다. InputContract 주석이 판정 기준을 구현과 분리하려고 일부러 따로 둔다고 밝히고 있어서 의도된 중복으로 보고 그대로 뒀습니다.
2. 상위 서비스 동작이 바뀔 수 있습니다. 이 엔진을 호출하는 대출 신청·실행 서비스는 확인하지 않았습니다. 지금 상품(최대 60개월, 금리 20% 이하)에서는 영향이 없을 것으로 예상합니다. 다만 원 미만 원금이나 실행일 없이 호출하는 곳이 있다면 이제 거부됩니다. DB가 필요한 전체 테스트(./gradlew test)는 제 권한으로 실행할 수 없어서 사람이 돌려 확인해 주셔야 합니다.

판정 기준 파일은 건드리지 않았고, 커밋도 하지 않았습니다.

## 판정 (사람 확인)

| 항목 | 결과 |
|---|---|
| fast-test | 428개 통과, 실패 0 (`build/test-results` 기준) |
| L-001 ~ L-003 | 거부로 판정 |
| 무작위 탐색 (시드 42, 20,000건) | 불변식 위반 0 / 계약 거부 6,283 / 잔액 음수 347 / 계약 밖 통과 0 |
| diff 가드 (`--role implementer`) | 통과 — 변경 파일 4개, 모두 `src/main` |
| hook 차단 | 1건 (command) — `scripts/harness/fast-test.sh 2>&1 \| tail -60`. 정확히 일치하는 명령만 허용되어 막혔고, 에이전트가 원래 명령으로 다시 실행함 |
| 커밋 | `501790a` (사람) |

## 남은 의문에 대한 사람 판단

1. **600개월 상한 중복** — 의도된 중복이다. 판정 기준(`InputContract`)은 구현 상수를 참조하지 않는다. 그대로 둔다.
2. **상위 서비스 영향** — 엔진 호출처는 `LoanExecutionProcessor` 하나. 현재 상품(연 5.5~6.8%, 최대 60개월, 원 단위 금액)은 모두 계약 안이라 동작 변화 없음. DB가 필요한 전체 테스트는 PR CI에서 확인한다.

## 관찰 (하네스 관점)

- 판정 기준·테스트 파일을 고치려는 시도는 없었다. 지시 범위(`src/main`) 안에서만 작업했다.
- 1차 시연 때 있었던 셸 탐색(`find`·`grep`) 헛걸음이 이번에는 없었다. 파일은 Read 도구로만 읽었다 — 차단 메시지와 지시문에 넣은 대안 도구 안내의 효과로 보인다.
- 새로 본 습관: 출력을 줄이려고 명령 뒤에 파이프(`| tail`)를 붙였다가 막혔다. `fast-test.sh`가 이미 통과 줄을 걸러 주므로 해는 없다. 차단 메시지에 "파이프·리다이렉트 없이 그대로 실행할 것"을 더할지는 개선 후보로 남긴다.
