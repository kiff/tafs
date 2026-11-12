"""The rule a pull request merges by itself on: each condition, broken in turn."""
import json
import unittest

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


if __name__ == "__main__":
    unittest.main()
