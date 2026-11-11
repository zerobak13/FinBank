#!/bin/bash
# Claude Code PreToolUse hook 진입점 (.claude/settings.json에 등록).
# 판정은 oracle_guard.py가 한다. 가드 자체가 실행되지 못하면(파이썬 없음·오류) 안전을 위해 차단한다(fail-closed).
DIR="$(cd "$(dirname "$0")" && pwd)"
if ! command -v python3 >/dev/null 2>&1; then
  echo "[하네스 가드] python3이 없어 도구 호출을 차단합니다. macOS: xcode-select --install" >&2
  exit 2
fi
python3 "$DIR/oracle_guard.py"
status=$?
if [ "$status" -eq 0 ] || [ "$status" -eq 2 ]; then
  exit "$status"
fi
echo "[하네스 가드] 가드 실행 오류(종료 코드 $status) — 안전을 위해 차단합니다" >&2
exit 2
