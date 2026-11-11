# 검증 리포트 — demo / 라운드 1

- 대상: `EqualPrincipalGenerator` (원금균등), 공통 `RepaymentScheduleGenerator.validate()`
- 판정: `scripts/harness/fast-test.sh` — 428 tests, **3 failed** (모두 이번 라운드 추가 케이스)
- 형식 가드(`CaseFileFormatTest`): 통과
- 무작위 탐색(`RandomExplorationTest`, 시드 42, 20,000건): 위반 0건, 거부 588건 — 기존 동작 회귀 없음

## 케이스별 결과

| 케이스 | 결과 | 불변식 ID | 내용 | 판단 |
|---|---|---|---|---|
| L-001 `EQUAL_PRINCIPAL,1000.5,0.05,12,2026-01-15,reject` | FAIL | MONEY-01 (입력 계약) | 거부되지 않고 스케줄이 만들어졌다. `validate()`가 원금이 양수인지만 보고 원 단위 정수인지는 확인하지 않는다. `basePrincipal = P/n`은 0자리로 절사(83원)되므로 끝수 0.5원이 마지막 회차 원금(87.5원)으로 넘어가 확정 금액이 원 미만이 된다. 인터페이스 Javadoc은 "원금: 양수, 원 단위 정수"라고 적어 두었고, 구현이 이 계약을 지키지 않는다. | **구현 버그 의심** |
| L-002 `EQUAL_PRINCIPAL,10000000,0.2000001,12,2026-01-15,reject` | FAIL | (OVD-03 취지 — 법정최고금리 20%) | 연 20%를 넘는 금리로도 스케줄이 만들어졌다. `validate()`에 금리 상한 검사가 없고 `MoneyPolicy.MAX_LEGAL_RATE`는 스케줄 생성에 쓰이지 않는다. 다만 카탈로그는 20% 상한을 연체이자(OVD-03)에만 명시하고 상환 스케줄(SCH-*)에는 정해 두지 않았다. 상품·신청 단계에서 이미 막고 있다면 엔진은 받아도 되는 입력일 수 있다. | **명세 확인 필요** |
| L-003 `EQUAL_PRINCIPAL,1000000,0.05,12,+999999999-06-30,reject` | FAIL | SCH-07 (납기일 계산) | `executedAt.plusMonths(i)`가 `LocalDate.MAX`를 넘어 `DateTimeException`을 던졌다. 거부 계약은 `IllegalArgumentException`이므로 실패다. `validate()`는 실행일을 아예 받지 않아 null·범위 검사가 없다. 현실적인 입력은 아니지만 엔진이 예외 계약을 지키는지 보는 케이스다. | **구현 버그 의심** (예외 계약 위반 — 우선순위 낮음) |

## 케이스로 넣지 않은 발견 (사람 확인 필요)

- **기간 상한 없음 → `OutOfMemoryError`**: `new ArrayList<>(termMonths)`가 검증 없이 기간을 그대로 받는다.
  `term = 2147483647`이면 `IllegalArgumentException`이 아니라 `OutOfMemoryError`(Requested array size exceeds VM limit)가 난다.
  JUnit은 OOM을 복구 불가 예외로 다시 던져 테스트 실행 전체를 멈출 수 있다. 그래서 케이스로 넣지 않았다.
  하네스 쪽에서 이 입력을 안전하게 다룰 방법이 생기면 케이스로 넣는 것을 권한다.
- **확인했지만 문제없었던 지점**: 말일·월말 실행의 `plusMonths` 기준(하네스 SCH-07도 `executedAt.plusMonths(i)` 기준이라 일치),
  월이율 scale 10 반올림 오차(SCH-08 허용폭 `잔액 × 1e-10` 안), 원금 < 기간(H-002가 이미 덮음).

## 총평

- 추가한 케이스: **3건** (L-001 ~ L-003, 모두 `EQUAL_PRINCIPAL`, `expect=reject`)
- 실패: **3건** — 구현 버그 의심 2건(L-001, L-003), 명세 확인 필요 1건(L-002)
- 케이스 오류: 0건

### 구현 에이전트에게 전달할 수정 요청

1. **[L-001] 원금 정수 검사** — `RepaymentScheduleGenerator.validate()`에서
   `principal.stripTrailingZeros().scale() > 0`이면 `IllegalArgumentException`을 던진다.
   (모든 생성기가 공통으로 쓰므로 원리금균등·만기일시도 같이 고쳐진다.)
2. **[L-003] 실행일 검증·예외 계약** — `validate()`가 `executedAt`도 받아 null을 거부한다.
   만기일(`executedAt.plusMonths(termMonths)`) 계산에서 나는 `DateTimeException`은 `IllegalArgumentException`으로 바꿔 던진다.
3. **[기간 상한]** — 기간 상한을 두고 넘으면 `IllegalArgumentException`을 던진다.
   상한값은 명세에 정해진 것이 없으니 사람과 정한다.
4. **[L-002] 보류** — 스케줄 엔진이 금리 상한(`MAX_LEGAL_RATE`)을 검사해야 하는지 사람이 먼저 정한다.
   검사해야 한다면 `annualRate.compareTo(MoneyPolicy.MAX_LEGAL_RATE) > 0`일 때 거부한다.
   검사하지 않기로 하면 L-002는 사람이 정리한다 (`expect` 변경 또는 삭제).
