"""The rule a pull request merges by itself on: each condition, broken in turn."""
import contextlib
import io
import json
import os
import subprocess
import unittest
from unittest import mock

import automerge

with open(automerge.CONFIG, encoding="utf-8") as f:
    CONFIG = json.load(f)

HEAD = "a" * 40
REPO = {"full_name": "kiff/tafs"}


def pr(**over):
    base = {
        "state": "open", "draft": False, "mergeable": True, "title": "iOS: a line", "labels": [],
        "user": {"login": "kiff-qa"},
        "base": {"ref": "main", "repo": REPO},
        "head": {"ref": "claude/ios-line", "sha": HEAD, "repo": REPO},
    }
    base.update(over)
    return base


FILES = ["ios/Tafs/Views/TafsBar.swift", "ios/TafsTests/BarTests.swift", "docs/ios-app.md", "changes/claude-ios-line.md"]
APPROVAL = [{"user": {"login": "agentgabo"}, "state": "APPROVED", "commit_id": HEAD}]
CHECKS = [
    {"name": "test", "status": "completed", "conclusion": "success"},
    {"name": "ipad", "status": "completed", "conclusion": "skipped"},
    {"name": "references", "status": "completed", "conclusion": "success"},
]


class Decide(unittest.TestCase):
    def decide(self, p=None, files=FILES, reviews=APPROVAL, checks=CHECKS, hold=""):
        return automerge.decide(p or pr(), files, reviews, checks, CONFIG, hold)

    def test_a_green_approved_pull_request_in_one_lane_merges(self):
        self.assertEqual(self.decide(), (True, "ios", ""))
        android = ["android/app/src/main/kotlin/A.kt", "docs/android-app.md"]
        qa = [{"user": {"login": "kiff-qa"}, "state": "APPROVED", "commit_id": HEAD}]
        ran = CHECKS + [{"name": "ui", "status": "completed", "conclusion": "success"}]
        self.assertEqual(self.decide(pr(user={"login": "gabosarmiento"}), files=android, reviews=qa, checks=ran), (True, "android", ""))

    def test_a_file_outside_the_lanes_waits(self):
        for name in ["runtime/src/cloud/handler.ts", "app/strings/en.json", ".github/workflows/ios.yml", ".github/automerge.json",
                     "scripts/automerge.py", "docs/drive.md", "docs/api.md", "CHANGELOG.md", "README.md", "iosx/A.swift", "docs/ios-app.md.bak"]:
            ok, _, why = self.decide(files=FILES + [name])
            self.assertFalse(ok, name)
            self.assertIn(name, why)

    def test_two_lanes_in_one_pull_request_wait(self):
        ok, _, why = self.decide(files=["ios/A.swift", "android/app/B.kt"])
        self.assertFalse(ok)
        self.assertIn("more than one lane", why)

    def test_only_its_own_fragment_is_allowed_under_changes(self):
        self.assertTrue(self.decide(files=["ios/A.swift", "changes/claude-ios-line.md"])[0])
        for other in ["changes/another-branch.md", "changes/README.md", "changes/claude-ios-line.md.txt"]:
            self.assertFalse(self.decide(files=["ios/A.swift", other])[0], other)
        # The fragment alone, or nothing at all, is no lane's work.
        self.assertFalse(self.decide(files=["changes/claude-ios-line.md"])[0])
        self.assertFalse(self.decide(files=[])[0])

    def test_it_needs_an_approval_of_its_latest_commit(self):
        self.assertFalse(self.decide(reviews=[])[0])
        stale = [{"user": {"login": "agentgabo"}, "state": "APPROVED", "commit_id": "b" * 40}]
        self.assertEqual(self.decide(reviews=stale)[2], "no listed reviewer approved its latest commit")
        commented = [{"user": {"login": "agentgabo"}, "state": "COMMENTED", "commit_id": HEAD}]
        self.assertFalse(self.decide(reviews=commented)[0])

    def test_the_author_and_strangers_dont_approve_it(self):
        own = [{"user": {"login": "kiff-qa"}, "state": "APPROVED", "commit_id": HEAD}]
        self.assertFalse(self.decide(reviews=own)[0], "the author's own account")
        stranger = [{"user": {"login": "someone-else"}, "state": "APPROVED", "commit_id": HEAD}]
        self.assertFalse(self.decide(reviews=stranger)[0])

    def test_changes_requested_stops_it_until_that_reviewer_approves(self):
        asked = APPROVAL + [{"user": {"login": "gabosarmiento"}, "state": "CHANGES_REQUESTED", "commit_id": HEAD}]
        self.assertEqual(self.decide(reviews=asked)[2], "a reviewer asked for changes")
        settled = asked + [{"user": {"login": "gabosarmiento"}, "state": "APPROVED", "commit_id": HEAD}]
        self.assertTrue(self.decide(reviews=settled)[0])
        # A later comment from the one who approved doesn't take the approval back.
        self.assertTrue(self.decide(reviews=APPROVAL + [{"user": {"login": "agentgabo"}, "state": "COMMENTED", "commit_id": HEAD}])[0])

    def test_every_check_must_have_finished_and_passed(self):
        self.assertFalse(self.decide(checks=[])[0])
        running = CHECKS + [{"name": "ui", "status": "in_progress", "conclusion": None}]
        self.assertEqual(self.decide(checks=running)[2], "ui hasn't finished")
        for bad in ["failure", "cancelled", "timed_out", "action_required", None]:
            failed = CHECKS + [{"name": "ui", "status": "completed", "conclusion": bad}]
            self.assertFalse(self.decide(checks=failed)[0], bad)

    def test_the_lanes_own_tests_must_have_run_not_only_the_quick_checks(self):
        # Right after a push, the quick checks finish before the app's tests have even started.
        quick = [{"name": "references", "status": "completed", "conclusion": "success"},
                 {"name": "strings", "status": "completed", "conclusion": "success"},
                 {"name": "changelog", "status": "completed", "conclusion": "success"}]
        self.assertEqual(self.decide(checks=quick), (False, "ios", "its lane's test hasn't run on its latest commit"))
        # Skipped isn't run.
        skipped = quick + [{"name": "test", "status": "completed", "conclusion": "skipped"}]
        self.assertFalse(self.decide(checks=skipped)[0])
        self.assertTrue(self.decide(checks=quick + [{"name": "test", "status": "completed", "conclusion": "success"}])[0])
        # Android's are its unit tests and its screens' tests: both.
        android, qa = ["android/app/src/main/kotlin/A.kt"], [{"user": {"login": "agentgabo"}, "state": "APPROVED", "commit_id": HEAD}]
        author = pr(user={"login": "gabosarmiento"})
        unit = quick + [{"name": "test", "status": "completed", "conclusion": "success"}]
        self.assertEqual(self.decide(author, files=android, reviews=qa, checks=unit)[2], "its lane's ui hasn't run on its latest commit")
        self.assertTrue(self.decide(author, files=android, reviews=qa, checks=unit + [{"name": "ui", "status": "completed", "conclusion": "success"}])[0])
        # A change to the app's page in docs/ alone starts no app tests, and waits for none.
        self.assertTrue(self.decide(files=["docs/ios-app.md", "changes/claude-ios-line.md"], checks=quick)[0])

    def test_a_hold_stops_everything(self):
        self.assertEqual(self.decide(hold="the evals are running")[2], "merging is on hold: the evals are running")
        labelled = pr(labels=[{"name": "hold-merge"}])
        self.assertFalse(self.decide(labelled)[0])

    def test_only_an_open_ready_pull_request_of_this_repository_against_main(self):
        for change in [{"state": "closed"}, {"draft": True}, {"mergeable": False}, {"mergeable": None},
                       {"base": {"ref": "release", "repo": REPO}},
                       {"head": {"ref": "claude/ios-line", "sha": HEAD, "repo": {"full_name": "someone/tafs"}}},
                       {"user": {"login": "dependabot[bot]"}}, {"user": {"login": "someone-else"}}]:
            self.assertFalse(self.decide(pr(**change))[0], change)

    def test_the_config_lists_no_lane_beyond_the_two_apps(self):
        self.assertEqual(sorted(CONFIG["lanes"]), ["android", "ios"])
        self.assertEqual(CONFIG["lanes"]["ios"], ["ios/", "docs/ios-app.md"])
        self.assertEqual(CONFIG["lanes"]["android"], ["android/", "docs/android-app.md"])
        self.assertEqual(CONFIG["required"], {"ios": ["test"], "android": ["test", "ui"]})


class Pass(unittest.TestCase):
    """One run over the open pull requests, with GitHub's answers made up."""

    def run_pass(self, answers, argv=()):
        """Runs `main` with `answers` for GitHub: a path's start → what it answers, or an
        error to raise. Returns (exit code, what it printed, the merges it made)."""
        merged = []

        def gh(*args):
            if args[0] == "-X":
                refusal = answers.get("PUT " + args[2])
                if refusal is not None:
                    raise refusal
                merged.append(args[2])
                return {"merged": True}
            for start, answer in answers.items():
                if args[0].startswith(start):
                    if isinstance(answer, Exception):
                        raise answer
                    return answer
            raise AssertionError(f"not answered: {args[0]}")

        env = {"GITHUB_REPOSITORY": "kiff/tafs", "AUTOMERGE": "on"}
        out = io.StringIO()
        with mock.patch.object(automerge, "gh", gh), mock.patch.dict(os.environ, env, clear=True), contextlib.redirect_stdout(out):
            code = automerge.main(list(argv))
        return code, out.getvalue().splitlines(), merged

    def answers(self, numbers, broken=()):
        """Open pull requests `numbers`, each green and approved, with `broken` failing to read."""
        failed = subprocess.CalledProcessError(1, ["gh", "api"], stderr="HTTP 502")
        out = {"repos/kiff/tafs/pulls?state=open": [{"number": n} for n in numbers]}
        for n in numbers:
            out[f"repos/kiff/tafs/pulls/{n}/files"] = [{"filename": f} for f in FILES]
            out[f"repos/kiff/tafs/pulls/{n}/reviews"] = APPROVAL
            out[f"repos/kiff/tafs/pulls/{n}"] = failed if n in broken else pr()
        out[f"repos/kiff/tafs/commits/{HEAD}/check-runs"] = {"check_runs": CHECKS}
        return out

    def test_a_pull_request_that_cant_be_read_is_skipped_and_the_next_still_merges(self):
        code, lines, merged = self.run_pass(self.answers([772, 773], broken=[772]))
        self.assertEqual(lines, ["#772: couldn't be read, skipped", "#773: merged (ios)"])
        self.assertEqual(merged, ["repos/kiff/tafs/pulls/773/merge"])
        self.assertEqual(code, 0, "one read failed, not all: the run passes")

    def test_a_read_that_fails_part_way_merges_nothing_of_that_pull_request(self):
        # Its reviews can't be read: nothing is decided on half of what the rule needs.
        answers = self.answers([772, 773])
        answers["repos/kiff/tafs/pulls/772/reviews"] = subprocess.CalledProcessError(1, ["gh", "api"])
        code, lines, merged = self.run_pass(answers)
        self.assertEqual(lines, ["#772: couldn't be read, skipped", "#773: merged (ios)"])
        self.assertEqual(merged, ["repos/kiff/tafs/pulls/773/merge"])
        # An answer that isn't the shape expected is unread too, never a crash.
        answers = self.answers([772, 773])
        answers["repos/kiff/tafs/pulls/772/files"] = [{"name": "no filename here"}]
        code, lines, merged = self.run_pass(answers)
        self.assertEqual(lines[0], "#772: couldn't be read, skipped")
        self.assertEqual(merged, ["repos/kiff/tafs/pulls/773/merge"])
        self.assertEqual(code, 0)

    def test_a_run_where_nothing_could_be_read_fails(self):
        code, lines, merged = self.run_pass(self.answers([772, 773], broken=[772, 773]))
        self.assertEqual(lines, ["#772: couldn't be read, skipped", "#773: couldn't be read, skipped"])
        self.assertEqual(merged, [])
        self.assertEqual(code, 1)
        # One pull request asked for by number, unread: the same.
        code, lines, merged = self.run_pass(self.answers([772], broken=[772]), argv=["--pr", "772"])
        self.assertEqual((code, lines, merged), (1, ["#772: couldn't be read, skipped"], []))

    def test_a_merge_github_refuses_is_skipped_and_the_next_still_merges(self):
        # The head moved between the read and the merge: GitHub answers 409.
        answers = self.answers([772, 773])
        answers["PUT repos/kiff/tafs/pulls/772/merge"] = subprocess.CalledProcessError(1, ["gh", "api"], stderr="gh: Head branch was modified (HTTP 409)")
        code, lines, merged = self.run_pass(answers)
        self.assertEqual(lines, ["#772: couldn't be merged, skipped (HTTP 409)", "#773: merged (ios)"])
        self.assertEqual(merged, ["repos/kiff/tafs/pulls/773/merge"])
        self.assertEqual(code, 0, "one merge was refused, not all: the run passes")
        # One that can't be merged after all (405), and a failure that names no status.
        answers["PUT repos/kiff/tafs/pulls/772/merge"] = subprocess.CalledProcessError(1, ["gh", "api"], stderr="gh: Pull Request is not mergeable (HTTP 405)")
        self.assertEqual(self.run_pass(answers)[1][0], "#772: couldn't be merged, skipped (HTTP 405)")
        answers["PUT repos/kiff/tafs/pulls/772/merge"] = subprocess.CalledProcessError(1, ["gh", "api"])
        self.assertEqual(self.run_pass(answers)[1][0], "#772: couldn't be merged, skipped (failed)")

    def test_a_run_where_every_pull_request_failed_on_github_fails(self):
        # One unread and one refused at the merge: nothing went through, so the run fails.
        answers = self.answers([772, 773], broken=[772])
        answers["PUT repos/kiff/tafs/pulls/773/merge"] = subprocess.CalledProcessError(1, ["gh", "api"], stderr="HTTP 409")
        code, lines, merged = self.run_pass(answers)
        self.assertEqual(lines, ["#772: couldn't be read, skipped", "#773: couldn't be merged, skipped (HTTP 409)"])
        self.assertEqual((code, merged), (1, []))
        # One refused at the merge and one that only waits for a maintainer: that isn't every one failing.
        answers = self.answers([772, 773])
        answers["PUT repos/kiff/tafs/pulls/772/merge"] = subprocess.CalledProcessError(1, ["gh", "api"], stderr="HTTP 409")
        answers["repos/kiff/tafs/pulls/773/reviews"] = []
        code, lines, merged = self.run_pass(answers)
        self.assertEqual(lines, ["#772: couldn't be merged, skipped (HTTP 409)", "#773: waits for a maintainer: no listed reviewer approved its latest commit"])
        self.assertEqual(code, 0)

    def test_a_run_with_every_pull_request_read_passes_as_before(self):
        code, lines, merged = self.run_pass(self.answers([772, 773]))
        self.assertEqual(lines, ["#772: merged (ios)", "#773: merged (ios)"])
        self.assertEqual(code, 0)
        # No open pull request at all is no failure.
        self.assertEqual(self.run_pass(self.answers([]))[0], 0)
        # A dry run reads and merges nothing.
        code, lines, merged = self.run_pass(self.answers([772]), argv=["--dry-run"])
        self.assertEqual((code, lines, merged), (0, ["#772: would merge (ios)"], []))


if __name__ == "__main__":
    unittest.main()
