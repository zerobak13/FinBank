#!/bin/bash
# 역할별 에이전트 세션 시작 — 터미널 하나에 역할 하나.
#   터미널 A: scripts/harness/run-agent.sh implementer
#   터미널 B: scripts/harness/run-agent.sh verifier
# FINBANK_AGENT_ROLE로 역할을 넘겨, hook이 이 세션(과 그 안의 서브에이전트)을 해당 역할로 판정하게 한다.
set -eu
role="${1:-}"
case "$role" in
  implementer|verifier) shift ;;
  *) echo "사용법: $0 implementer|verifier [claude 옵션...]" >&2; exit 1 ;;
esac
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export FINBANK_AGENT_ROLE="$role"
exec claude --agent "loan-$role" "$@"
