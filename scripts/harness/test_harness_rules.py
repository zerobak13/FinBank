"""하네스 가드 단위 테스트.

실행: python3 -m unittest discover -s scripts/harness -p 'test_*.py'
(CI의 harness-guard 워크플로가 같은 명령으로 돌린다)
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import diff_guard  # noqa: E402
import harness_rules as rules  # noqa: E402

IMPL = "implementer"
VERI = "verifier"
MAIN_FILE = "finbank-backend/src/main/java/com/finbank/backend/loan/domain/calculator/EqualPaymentGenerator.java"
ORACLE_FILE = "finbank-backend/src/test/java/com/finbank/backend/loan/harness/LoanInvariant.java"
OTHER_TEST = "finbank-backend/src/test/java/com/finbank/backend/account/service/TransferRuleTest.java"
SECRET = "finbank-backend/src/main/resources/application-local.yml"


def write(root, rel, text, mode="w"):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, mode, encoding="utf-8") as f:
        f.write(text)


class RoleTest(unittest.TestCase):

    def test_env_role_wins_over_agent_type(self):
        self.assertEqual(rules.resolve_role("loan-verifier", "implementer"), IMPL)

    def test_invalid_env_role_is_most_strict(self):
        self.assertEqual(rules.resolve_role(None, "admin"), rules.UNKNOWN)

    def test_agent_type_mapping(self):
        self.assertEqual(rules.resolve_role("loan-implementer", None), IMPL)
        self.assertEqual(rules.resolve_role("loan-verifier", ""), VERI)

    def test_main_session_and_builtin_subagents_are_human(self):
        self.assertEqual(rules.resolve_role(None, None), rules.HUMAN)
        self.assertEqual(rules.resolve_role("Explore", None), rules.HUMAN)


class NormalizeTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp()

    def test_relative_and_absolute(self):
        self.assertEqual(rules.normalize("CLAUDE.md", self.root), "CLAUDE.md")
        self.assertEqual(rules.normalize(os.path.join(self.root, "a", "b.txt"), self.root), "a/b.txt")

    def test_dot_dot_is_resolved(self):
        self.assertEqual(rules.normalize("finbank-backend/src/main/../../../CLAUDE.md", self.root), "CLAUDE.md")

    def test_outside_root_is_none(self):
        self.assertIsNone(rules.normalize("../outside.txt", self.root))
        self.assertIsNone(rules.normalize("/etc/passwd", self.root))

    def test_symlink_is_resolved_to_its_target(self):
        write(self.root, "CLAUDE.md", "# rules\n")
        os.makedirs(os.path.join(self.root, "finbank-backend/src/main"))
        os.symlink(os.path.join(self.root, "CLAUDE.md"), os.path.join(self.root, "finbank-backend/src/main/evil"))
        rel = rules.normalize("finbank-backend/src/main/evil", self.root)
        self.assertEqual(rel, "CLAUDE.md")
        self.assertFalse(rules.check_write(IMPL, rel)[0])


class WriteRuleTest(unittest.TestCase):

    def test_implementer_only_main_code(self):
        self.assertTrue(rules.check_write(IMPL, MAIN_FILE)[0])
        for rel in (ORACLE_FILE, OTHER_TEST, rules.LLM_CASES, "CLAUDE.md", ".claude/settings.json",
                    "scripts/harness/harness_rules.py", "finbank-backend/build.gradle",
                    ".github/workflows/ci.yml", SECRET, "README.md"):
            self.assertFalse(rules.check_write(IMPL, rel)[0], rel)
        self.assertFalse(rules.check_write(IMPL, None)[0])

    def test_verifier_only_cases_and_reports(self):
        self.assertTrue(rules.check_write(VERI, rules.LLM_CASES)[0])
        self.assertTrue(rules.check_write(VERI, "harness-reports/task/verify-1.md")[0])
        for rel in (MAIN_FILE, ORACLE_FILE, "finbank-backend/src/test/resources/harness/schedule-cases.csv", SECRET):
            self.assertFalse(rules.check_write(VERI, rel)[0], rel)

    def test_human_needs_explicit_override_for_oracle(self):
        self.assertTrue(rules.check_write(rules.HUMAN, OTHER_TEST)[0])
        self.assertTrue(rules.check_write(rules.HUMAN, None)[0])
        self.assertFalse(rules.check_write(rules.HUMAN, ORACLE_FILE)[0])
        self.assertTrue(rules.check_write(rules.HUMAN, ORACLE_FILE, oracle_override=True)[0])
        self.assertFalse(rules.check_write(rules.HUMAN, SECRET, oracle_override=True)[0])

    def test_task_spec_is_human_only_even_inside_reports(self):
        task = "harness-reports/demo/task.md"
        self.assertTrue(rules.is_oracle(task))
        self.assertFalse(rules.check_write(VERI, task)[0])
        self.assertTrue(rules.check_write(VERI, "harness-reports/demo/verify-2.md")[0])
        self.assertFalse(rules.check_write(rules.HUMAN, task)[0])
        self.assertTrue(rules.check_write(rules.HUMAN, task, oracle_override=True)[0])

    def test_unknown_role_is_blocked(self):
        self.assertFalse(rules.check_write(rules.UNKNOWN, MAIN_FILE)[0])
        self.assertFalse(rules.check_command(rules.UNKNOWN, rules.FAST_TEST)[0])


class CommandAndReadRuleTest(unittest.TestCase):

    def test_agents_can_run_only_fast_test(self):
        for role in (IMPL, VERI):
            self.assertTrue(rules.check_command(role, "scripts/harness/fast-test.sh")[0])
            self.assertTrue(rules.check_command(role, "  ./scripts/harness/fast-test.sh ")[0])
            for cmd in ("scripts/harness/fast-test.sh; rm -rf /",
                        "scripts/harness/fast-test.sh && git commit -am x",
                        "./gradlew test", "git status", "cat CLAUDE.md", ""):
                self.assertFalse(rules.check_command(role, cmd)[0], cmd)

    def test_human_commands_follow_normal_permissions(self):
        self.assertTrue(rules.check_command(rules.HUMAN, "git status")[0])

    def test_secrets_are_unreadable_for_everyone(self):
        self.assertFalse(rules.check_read(IMPL, SECRET)[0])
        self.assertFalse(rules.check_read(rules.HUMAN, "finbank-frontend/.env.local")[0])
        self.assertTrue(rules.check_read(IMPL, ORACLE_FILE)[0])


class HookTest(unittest.TestCase):
    """oracle-guard.sh를 Claude Code가 부르는 방식 그대로(stdin JSON) 실행한다."""

    def setUp(self):
        self.root = tempfile.mkdtemp()

    def run_hook(self, event, **env):
        full_env = dict(os.environ, CLAUDE_PROJECT_DIR=self.root)
        full_env.pop("FINBANK_AGENT_ROLE", None)
        full_env.pop("FINBANK_ALLOW_ORACLE_EDIT", None)
        full_env.update(env)
        data = event if isinstance(event, str) else json.dumps(event)
        return subprocess.run(["bash", os.path.join(HERE, "oracle-guard.sh")],
                              input=data.encode("utf-8"), capture_output=True, env=full_env)

    def edit(self, rel, agent_type=None):
        event = {"tool_name": "Edit", "tool_input": {"file_path": os.path.join(self.root, rel)}}
        if agent_type:
            event["agent_type"] = agent_type
        return event

    def test_subagent_cannot_edit_oracle_and_it_is_logged(self):
        result = self.run_hook(self.edit(ORACLE_FILE, "loan-implementer"))
        self.assertEqual(result.returncode, 2)
        self.assertIn("판정 기준", result.stderr.decode("utf-8"))
        with open(os.path.join(self.root, ".harness", "guard-log.jsonl"), encoding="utf-8") as f:
            record = json.loads(f.readline())
        self.assertEqual(record["role"], IMPL)
        self.assertEqual(record["category"], "oracle")
        self.assertTrue(record["target"].endswith("LoanInvariant.java"))

    def test_subagent_can_edit_its_own_scope(self):
        self.assertEqual(self.run_hook(self.edit(MAIN_FILE, "loan-implementer")).returncode, 0)

    def test_env_role_applies_to_whole_session(self):
        self.assertEqual(self.run_hook(self.edit(MAIN_FILE), FINBANK_AGENT_ROLE="verifier").returncode, 2)

    def test_agent_bash_is_limited_to_fast_test(self):
        ok = {"tool_name": "Bash", "tool_input": {"command": "scripts/harness/fast-test.sh"}}
        bad = {"tool_name": "Bash", "tool_input": {"command": "sed -i '' 's/a/b/' CLAUDE.md"}}
        self.assertEqual(self.run_hook(ok, FINBANK_AGENT_ROLE="verifier").returncode, 0)
        blocked = self.run_hook(bad, FINBANK_AGENT_ROLE="verifier")
        self.assertEqual(blocked.returncode, 2)
        self.assertIn("Glob·Grep·Read", blocked.stderr.decode("utf-8"))  # 막을 때 대안을 알려준다

    def test_secret_read_is_blocked_for_everyone(self):
        event = {"tool_name": "Read", "tool_input": {"file_path": os.path.join(self.root, SECRET)}}
        self.assertEqual(self.run_hook(event).returncode, 2)

    def test_human_needs_explicit_override_for_oracle(self):
        self.assertEqual(self.run_hook(self.edit("CLAUDE.md")).returncode, 2)
        self.assertEqual(self.run_hook(self.edit("CLAUDE.md"), FINBANK_ALLOW_ORACLE_EDIT="1").returncode, 0)

    def test_malformed_input_fails_closed(self):
        self.assertEqual(self.run_hook("not json").returncode, 2)
        self.assertEqual(self.run_hook({"tool_name": "Write", "tool_input": {}}).returncode, 2)

    def test_unrelated_tools_pass(self):
        self.assertEqual(self.run_hook({"tool_name": "Grep", "tool_input": {"pattern": "x"}}).returncode, 0)


class DiffGuardTest(unittest.TestCase):
    """hook을 거치지 않은 변경(셸 우회 등)도 실제 diff로 잡히는지."""

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.git("init", "-q")
        write(self.root, MAIN_FILE, "class A {}\n")
        write(self.root, ORACLE_FILE, "enum LoanInvariant {}\n")
        write(self.root, rules.LLM_CASES, "id,type\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        command = ["git", "-c", "user.name=test", "-c", "user.email=test@example.com",
                   "-c", "commit.gpgsign=false"] + list(args)
        return subprocess.run(command, cwd=self.root, check=True, capture_output=True).stdout.decode("utf-8")

    def problems(self, role, env=None):
        return diff_guard.check(self.base, role, self.root, env or {})[1]

    def test_implementer_within_scope_passes(self):
        write(self.root, MAIN_FILE, "class A { int x; }\n")
        self.assertEqual(self.problems(IMPL), [])

    def test_oracle_change_is_caught_even_without_hook(self):
        write(self.root, ORACLE_FILE, "enum LoanInvariant { WEAKENED }\n")
        self.assertEqual(len(self.problems(IMPL)), 1)

    def test_new_untracked_file_outside_scope_is_caught(self):
        write(self.root, "finbank-backend/src/test/java/Sneaky.java", "class Sneaky {}\n")
        self.assertEqual(len(self.problems(IMPL)), 1)

    def test_verifier_may_only_append_cases(self):
        write(self.root, rules.LLM_CASES, "L-001,EQUAL_PAYMENT\n", mode="a")
        self.assertEqual(self.problems(VERI), [])

    def test_verifier_rewriting_existing_rows_is_caught(self):
        write(self.root, rules.LLM_CASES, "id,type,changed\n")
        self.assertEqual(len(self.problems(VERI)), 1)

    def test_pr_with_oracle_change_needs_approval_label(self):
        self.git("checkout", "-q", "-b", "feature")
        write(self.root, ORACLE_FILE, "enum LoanInvariant { NEW }\n")
        self.git("commit", "-q", "-am", "change oracle")
        self.assertEqual(len(self.problems("pr")), 1)
        self.assertEqual(self.problems("pr", {"ORACLE_APPROVED": "true"}), [])


if __name__ == "__main__":
    unittest.main()
