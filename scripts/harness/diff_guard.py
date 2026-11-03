#!/usr/bin/env python3
"""git diff 가드 — 실제로 바뀐 파일을 역할 규칙(harness_rules.py)으로 검사한다.

hook은 도구 호출 단위로 막지만 셸을 거친 우회는 놓칠 수 있다(공식 문서도 Bash 권한 규칙은
보안 경계가 아니라고 명시). 그래서 라운드가 끝날 때 '실제로 바뀐 것'을 다시 본다 — 최종 방어선.

사용:
  diff_guard.py --base <ref> --role implementer   # 에이전트 라운드 검사 (작업 트리·새 파일 포함)
  diff_guard.py --base <ref> --role verifier      # + llm-cases.csv는 끝에 추가만 했는지
  diff_guard.py --base <ref> --role pr            # CI: 판정 기준이 바뀌면 승인 필요 (ORACLE_APPROVED=true)
"""
import argparse
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import harness_rules as rules  # noqa: E402

ROOT = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def git(args, root, binary=False):
    result = subprocess.run(["git", "-c", "core.quotePath=false"] + list(args),
                            cwd=root, check=True, capture_output=True)
    return result.stdout if binary else result.stdout.decode("utf-8")


def changed_files(base, root, include_worktree):
    if include_worktree:
        names = git(["diff", "--name-only", "-z", base], root).split("\0")
        names += git(["ls-files", "--others", "--exclude-standard", "-z"], root).split("\0")
    else:
        names = git(["diff", "--name-only", "-z", base + "...HEAD"], root).split("\0")
    return sorted(name for name in set(names) if name)


def appended_only(base, rel, root):
    """기준 시점의 내용이 지금 내용의 앞부분과 바이트 단위로 같은가 (= 끝에 추가만 했는가)."""
    try:
        before = git(["show", "%s:%s" % (base, rel)], root, binary=True)
    except subprocess.CalledProcessError:
        before = b""  # 기준 시점에 없던 파일이면 전체가 추가분이다
    path = os.path.join(root, rel)
    if not os.path.exists(path):
        return False  # 삭제는 추가가 아니다
    with open(path, "rb") as f:
        return f.read().startswith(before)


def check(base, role, root, env):
    """(변경 파일 목록, 위반 목록)"""
    problems = []
    if role in rules.AGENT_ROLES:
        files = changed_files(base, root, include_worktree=True)
        for rel in files:
            allowed, reason = rules.check_write(role, rel)
            if not allowed:
                problems.append("%s — %s" % (rel, reason))
        if role == "verifier" and rules.LLM_CASES in files and not appended_only(base, rules.LLM_CASES, root):
            problems.append("%s — 기존 행을 수정·삭제함 (끝에 추가만 허용)" % rules.LLM_CASES)
        return files, problems
    if role == "pr":
        files = changed_files(base, root, include_worktree=False)
        oracle = [f for f in files if rules.is_oracle(f)]
        if oracle and env.get("ORACLE_APPROVED") != "true":
            problems += ["%s — 판정 기준 변경 (PR에 oracle-approved 라벨 필요)" % f for f in oracle]
        return files, problems
    raise ValueError("알 수 없는 역할: %s" % role)


def main(argv=None):
    parser = argparse.ArgumentParser(description="하네스 git diff 가드")
    parser.add_argument("--base", required=True, help="비교 기준 ref (예: origin/main, 라운드 시작 커밋)")
    parser.add_argument("--role", required=True, choices=list(rules.AGENT_ROLES) + ["pr"])
    parser.add_argument("--root", default=ROOT)
    args = parser.parse_args(argv)

    files, problems = check(args.base, args.role, args.root, os.environ)
    print("[diff 가드] 역할=%s, 기준=%s, 변경 파일 %d개" % (args.role, args.base, len(files)))
    if problems:
        print("[diff 가드] 위반 %d건:" % len(problems))
        for problem in problems:
            print("  - " + problem)
        return 1
    if args.role == "pr" and any(rules.is_oracle(f) for f in files):
        print("[diff 가드] 판정 기준 변경 있음 — oracle-approved 라벨로 승인됨")
    print("[diff 가드] 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main())
