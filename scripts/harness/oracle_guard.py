#!/usr/bin/env python3
"""Claude Code PreToolUse hook — 역할별 수정 경로·실행 명령을 강제한다.

허용이면 exit 0, 차단이면 exit 2 (stderr 메시지가 에이전트에게 전달된다).
입력을 해석하지 못하면 차단한다(fail-closed). 차단 기록은 .harness/guard-log.jsonl 에 남는다.
"""
import datetime
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import harness_rules as rules  # noqa: E402

WRITE_TOOLS = ("Edit", "Write", "MultiEdit", "NotebookEdit")


def project_root(event, env):
    return env.get("CLAUDE_PROJECT_DIR") or event.get("cwd") or os.getcwd()


def decide(event, env):
    """(허용 여부, 이유, 대상, 역할, 분류). 입출력이 없는 순수 함수라 단위 테스트가 쉽다."""
    root = project_root(event, env)
    tool = event.get("tool_name") or ""
    tool_input = event.get("tool_input") or {}
    role = rules.resolve_role(event.get("agent_type"), env.get("FINBANK_AGENT_ROLE"))

    if tool in WRITE_TOOLS:
        path = tool_input.get("file_path") or tool_input.get("notebook_path")
        if not path:
            return False, "수정 대상 경로를 알 수 없음", "", role, "input"
        rel = rules.normalize(path, root)
        allowed, reason = rules.check_write(role, rel, env.get("FINBANK_ALLOW_ORACLE_EDIT") == "1")
        return allowed, reason, rel if rel is not None else path, role, rules.category(tool, rel)
    if tool == "Read":
        path = tool_input.get("file_path") or ""
        rel = rules.normalize(path, root)
        allowed, reason = rules.check_read(role, rel)
        return allowed, reason, rel if rel is not None else path, role, rules.category(tool, rel)
    if tool == "Bash":
        command = tool_input.get("command") or ""
        allowed, reason = rules.check_command(role, command)
        return allowed, reason, command, role, rules.category(tool, None)
    return True, "검사 대상 아님", "", role, ""


def log_denial(root, record):
    try:
        log_dir = os.path.join(root, ".harness")
        os.makedirs(log_dir, exist_ok=True)
        with open(os.path.join(log_dir, "guard-log.jsonl"), "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
    except OSError:
        pass  # 기록 실패가 판정을 바꾸지는 않는다


def main():
    env = os.environ
    try:
        event = json.load(sys.stdin)
        if not isinstance(event, dict):
            raise ValueError("JSON 객체가 아님")
    except (ValueError, UnicodeDecodeError) as e:
        sys.stderr.write("[하네스 가드] 입력을 해석할 수 없어 차단합니다: %s\n" % e)
        return 2

    allowed, reason, target, role, kind = decide(event, env)
    if allowed:
        return 0

    log_denial(project_root(event, env), {
        "time": datetime.datetime.now().isoformat(timespec="seconds"),
        "role": role,
        "agent_type": event.get("agent_type"),
        "tool": event.get("tool_name"),
        "category": kind,
        "target": target,
        "reason": reason,
    })
    sys.stderr.write("[하네스 가드] 차단 — %s: %s (역할: %s)\n" % (reason, target, role))
    return 2


if __name__ == "__main__":
    sys.exit(main())
