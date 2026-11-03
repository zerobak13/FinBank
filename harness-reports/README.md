# 하네스 작업 기록

에이전트 작업을 라운드별로 남기는 곳이다. 검증 에이전트(`loan-verifier`)는 이 폴더와
`llm-cases.csv`에만 쓸 수 있다 (`scripts/harness/harness_rules.py`).

```
harness-reports/<작업명>/
  task.md        작업 명세 (사람만 — 판정 기준이라 에이전트는 수정 불가)
  verify-1.md    1라운드 검증 리포트 (검증 에이전트)
  verify-2.md    ...
  summary.md     라운드 수·반려 사유·잡은 버그·차단 기록 요약
```

가드가 차단한 시도는 `.harness/guard-log.jsonl`(로컬, 커밋 안 함)에 남는다.
