"""하네스 경로 규칙 — 판정 기준(오라클)과 역할별 권한의 단일 출처.

Claude Code hook(oracle_guard.py), git diff 가드(diff_guard.py), CI(harness-guard.yml)가
모두 이 파일 하나만 본다. 규칙을 바꾸려면 여기만 고친다. 이 파일 자체도 판정 기준이라 사람만 수정한다.

경로는 저장소 루트 기준 상대 경로, 구분자는 '/'.
"""
import os
import posixpath

# ── 판정 기준(오라클) ─────────────────────────────────────────────
# 구현을 판정하는 기준. 에이전트는 수정할 수 없고, 사람도 명시적으로 열어야
# (FINBANK_ALLOW_ORACLE_EDIT=1) 수정할 수 있다. PR에서 바뀌면 oracle-approved 라벨이 필요하다.
ORACLE_DIRS = (
    "finbank-backend/src/test/java/com/finbank/backend/loan/harness/",
    "finbank-backend/src/test/java/com/finbank/backend/loan/domain/calculator/",
    "scripts/harness/",
    ".claude/",
    ".github/workflows/",
)
ORACLE_FILES = (
    "finbank-backend/src/test/resources/harness/schedule-cases.csv",
    "finbank-backend/build.gradle",
    "CLAUDE.md",
)

# ── 에이전트 산출물 ───────────────────────────────────────────────
LLM_CASES = "finbank-backend/src/test/resources/harness/llm-cases.csv"
REPORTS_DIR = "harness-reports/"
TASK_FILE = "task.md"  # harness-reports/<작업명>/task.md — 사람이 쓰는 작업 명세 (판정 기준)

# ── 역할별 수정 허용 범위 (여기 없는 경로는 전부 차단) ─────────────
ROLE_WRITE = {
    "implementer": {"dirs": ("finbank-backend/src/main/",), "files": ()},
    "verifier": {"dirs": (REPORTS_DIR,), "files": (LLM_CASES,)},
}
AGENT_ROLES = tuple(ROLE_WRITE)

# Claude Code 서브에이전트 이름(.claude/agents) → 역할
AGENT_TYPES = {"loan-implementer": "implementer", "loan-verifier": "verifier"}

# ── 에이전트가 실행할 수 있는 명령 (문자열이 정확히 일치할 때만) ─────
FAST_TEST = "scripts/harness/fast-test.sh"
AGENT_COMMANDS = (FAST_TEST, "./" + FAST_TEST, "bash " + FAST_TEST)

# ── 비밀 정보: 역할과 무관하게 읽기·쓰기 차단 ───────────────────────
SECRET_BASENAMES = ("application-local.yml",)
SECRET_PREFIXES = (".env",)

HUMAN = "human"
UNKNOWN = "unknown"


def resolve_role(agent_type, env_role):
    """hook 입력의 agent_type과 실행 스크립트가 넘긴 FINBANK_AGENT_ROLE로 역할을 정한다.

    환경변수가 있으면 그것을 따른다(run-agent.sh로 띄운 세션 — 그 안에서 부른 서브에이전트도 같은 역할).
    값이 이상하면 가장 엄격한 UNKNOWN(쓰기·명령 전부 차단)으로 본다.
    """
    if env_role:
        return env_role if env_role in AGENT_ROLES else UNKNOWN
    return AGENT_TYPES.get(agent_type or "", HUMAN)


def normalize(path, root):
    """경로 → 저장소 루트 기준 상대 경로. 저장소 밖이면 None.

    심볼릭 링크와 '..'를 실제 경로로 풀어서 비교한다 — 'src/main/../../CLAUDE.md' 같은 우회를 막는다.
    """
    if not path:
        return None
    candidate = path if os.path.isabs(path) else os.path.join(root, path)
    real = os.path.realpath(candidate)
    real_root = os.path.realpath(root)
    if real == real_root:
        return ""
    if not real.startswith(real_root + os.sep):
        return None
    return os.path.relpath(real, real_root).replace(os.sep, "/")


def is_oracle(rel):
    if rel in ORACLE_FILES or any(rel.startswith(d) for d in ORACLE_DIRS):
        return True
    # 작업 명세는 검증 에이전트의 리포트 폴더 안에 있어도 사람이 쓰는 판정 기준이다
    return rel.startswith(REPORTS_DIR) and posixpath.basename(rel) == TASK_FILE


def is_secret(rel):
    base = posixpath.basename(rel)
    return base in SECRET_BASENAMES or any(base.startswith(p) for p in SECRET_PREFIXES)


def check_write(role, rel, oracle_override=False):
    """수정 가능 여부 → (허용 여부, 이유). rel이 None이면 저장소 밖 경로."""
    if role in AGENT_ROLES:
        if rel is None:
            return False, "저장소 밖 경로"
        if is_secret(rel):
            return False, "비밀 정보 파일"
        if is_oracle(rel):
            return False, "판정 기준 파일은 에이전트가 수정할 수 없음"
        scope = ROLE_WRITE[role]
        if rel in scope["files"] or any(rel.startswith(d) for d in scope["dirs"]):
            return True, "허용"
        return False, "%s 역할의 수정 범위 밖" % role
    if role == HUMAN:
        if rel is None:
            return True, "허용"
        if is_secret(rel):
            return False, "비밀 정보 파일"
        if is_oracle(rel) and not oracle_override:
            return False, "판정 기준 파일 — FINBANK_ALLOW_ORACLE_EDIT=1 로 열어야 수정 가능"
        return True, "허용"
    return False, "알 수 없는 역할"


def category(tool, rel):
    """차단 기록 분류 — 무해한 탐색 명령 차단과 실제 판정 기준 수정 시도를 구분해서 세기 위함."""
    if tool == "Bash":
        return "command"
    if rel is None:
        return "outside"
    if is_secret(rel):
        return "secret"
    if is_oracle(rel):
        return "oracle"
    return "scope"


def check_read(role, rel):
    """읽기 가능 여부. 비밀 정보만 막는다(에이전트도 코드 전체는 읽어야 일을 할 수 있다)."""
    if rel is not None and is_secret(rel):
        return False, "비밀 정보 파일"
    return True, "허용"


def check_command(role, command):
    """셸 명령 실행 가능 여부. 에이전트는 빠른 테스트 하나만, 사람은 Claude Code 기본 권한 절차를 따른다."""
    if role == HUMAN:
        return True, "허용"
    if role in AGENT_ROLES and (command or "").strip() in AGENT_COMMANDS:
        return True, "허용"
    return False, "에이전트는 %s 만 실행할 수 있음 — 파일 찾기·검색은 Glob·Grep·Read 도구를 쓸 것" % FAST_TEST
