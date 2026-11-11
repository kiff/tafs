import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import changelog  # noqa: E402


class Check(unittest.TestCase):
    def test_one_fragment_named_for_the_branch_passes(self):
        self.assertEqual(changelog.check([("A", "changes/android-batch-56.md"), ("M", "android/x.kt")], "android/batch-56"), [])

    def test_editing_the_changelog_fails(self):
        errors = changelog.check([("A", "changes/a-b.md"), ("M", "CHANGELOG.md")], "a/b")
        self.assertEqual(len(errors), 1)
        self.assertIn("CHANGELOG.md is edited", errors[0])

    def test_no_fragment_or_two_or_a_misnamed_one_fails(self):
        for changed in ([("M", "ios/x.swift")],
                        [("A", "changes/a-b.md"), ("A", "changes/c.md")],
                        [("A", "changes/other.md")]):
            with self.subTest(changed=changed):
                self.assertEqual(len(changelog.check(changed, "a/b")), 1)

    def test_changing_another_pull_requests_fragment_fails(self):
        errors = changelog.check([("A", "changes/a-b.md"), ("D", "changes/old.md")], "a/b")
        self.assertIn("another pull request's fragment", errors[0])

    def test_the_folders_readme_isnt_a_fragment(self):
        self.assertEqual(changelog.check([("A", "changes/a-b.md"), ("M", "changes/README.md")], "a/b"), [])

    def test_a_release_carries_the_fold(self):
        fold = [("M", "CHANGELOG.md"), ("D", "changes/a-b.md"), ("D", "changes/c.md"), ("M", "ios/project.yml")]
        self.assertEqual(changelog.check(fold, "release/1.8.0"), [])
        # A release can't add or change a fragment, nor remove them without folding them in.
        self.assertEqual(len(changelog.check(fold + [("A", "changes/release-1.8.0.md")], "release/1.8.0")), 1)
        self.assertEqual(len(changelog.check([("D", "changes/a-b.md")], "release/1.8.0")), 1)
        # The same fold on any other branch is refused.
        self.assertTrue(changelog.check(fold, "claude/x"))

    def test_dependabot_is_exempt(self):
        self.assertEqual(changelog.check([("M", "android/build.gradle.kts")], "dependabot/gradle/x"), [])


class Fold(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        run = lambda *a: subprocess.run(["git", *a], cwd=self.dir, check=True, capture_output=True)
        self.run_git = run
        run("init", "-q"); run("config", "user.email", "t@example.com"); run("config", "user.name", "t")
        os.makedirs(os.path.join(self.dir, "changes"))
        self.write("CHANGELOG.md", "# Changelog\n\nIntro.\n\n## Unreleased\n\n- **Old.** Already here.\n")
        self.write("changes/README.md", "How to write one.\n")
        self.commit("start", 1_700_000_000)

    def write(self, name, text):
        with open(os.path.join(self.dir, name), "w", encoding="utf-8") as f:
            f.write(text)

    def read(self, name):
        with open(os.path.join(self.dir, name), encoding="utf-8") as f:
            return f.read()

    def commit(self, msg, at):
        env = dict(os.environ, GIT_AUTHOR_DATE=f"{at} +0000", GIT_COMMITTER_DATE=f"{at} +0000")
        subprocess.run(["git", "add", "-A"], cwd=self.dir, check=True)
        subprocess.run(["git", "commit", "-qm", msg], cwd=self.dir, check=True, env=env)

    def test_fragments_go_on_top_newest_first_and_are_removed(self):
        self.write("changes/a.md", "- **First.** Added first.\n"); self.commit("a", 1_700_000_100)
        self.write("changes/b.md", "- **Second.** Added second.\n"); self.commit("b", 1_700_000_200)
        self.write("changes/empty.md", ""); self.commit("empty", 1_700_000_300)
        self.assertEqual(changelog.fold(self.dir), 2)
        log = self.read("CHANGELOG.md")
        self.assertEqual(log, "# Changelog\n\nIntro.\n\n## Unreleased\n\n- **Second.** Added second.\n"
                              "- **First.** Added first.\n- **Old.** Already here.\n")
        self.assertEqual(sorted(os.listdir(os.path.join(self.dir, "changes"))), ["README.md"])

    def test_a_release_takes_everything_and_unreleased_starts_empty(self):
        self.write("changes/a.md", "- **New.** It.\n"); self.commit("a", 1_700_000_100)
        changelog.fold(self.dir, release="1.8.0 (2026-11-12)")
        log = self.read("CHANGELOG.md")
        self.assertEqual(log, "# Changelog\n\nIntro.\n\n## Unreleased\n\n## 1.8.0 (2026-11-12)\n\n- **New.** It.\n- **Old.** Already here.\n")


if __name__ == "__main__":
    unittest.main()
