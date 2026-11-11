#!/bin/bash
# 빠른 테스트 — 여신 계산 엔진 + 불변식 하네스 (DB 불필요, 수십 초).
# 에이전트가 실행할 수 있는 유일한 명령이다 (harness_rules.py의 AGENT_COMMANDS).
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT/finbank-backend" || exit 1
./gradlew --console=plain test --rerun \
  --tests 'com.finbank.backend.loan.harness.*' \
  --tests 'com.finbank.backend.loan.domain.calculator.*' 2>&1 | grep -v ' PASSED$'
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
  echo ""
  echo "[fast-test] 실패. 위반 리포트 전문: finbank-backend/build/test-results/test/TEST-*.xml"
fi
exit "$status"
