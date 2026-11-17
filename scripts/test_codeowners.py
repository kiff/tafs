"""Who .github/CODEOWNERS asks to review a file: the apps' lanes ask no one by default, and the paths
that sign, hold a key or release ask @kiff-qa (docs/maintaining.md).

The merge bot counts every reviewer a pull request names, and GitHub names a file's code owners on
the author's behalf, so the bot can't tell those requests apart. What CODEOWNERS says for each path
is therefore part of the merge rule, and is checked here."""
import json
import os
import re
import subprocess
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CODEOWNERS = os.path.join(ROOT, ".github", "CODEOWNERS")
AUTOMERGE = os.path.join(ROOT, ".github", "automerge.json")
MAINTAINERS = ["@gabosarmiento", "@agentgabo"]


def rules(text):
    """(pattern, owners) for each line, in order. A line with no owner gives its files none."""
    out = []
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            pattern, *owners = line.split()
            out.append((pattern, owners))
    return out


def matches(pattern, path):
    """Whether a CODEOWNERS pattern takes `path`, as GitHub reads the forms this file uses: a leading
    `/` anchors it at the root, a trailing `/` takes everything under that folder, a pattern with no
    other `/` matches a name anywhere, and `*` never crosses a `/`."""
    folder = pattern.endswith("/")
    body = pattern.strip("/")
    anchored = pattern.startswith("/") or "/" in body
    regex = "".join("[^/]*" if c == "*" else re.escape(c) for c in body)
    regex = ("^" if anchored else "(^|.*/)") + regex + ("/.*$" if folder else "(/.*)?$")
    return re.match(regex, path) is not None


def owners_of(path, table):
    """The owners of `path`: the last line that matches it wins."""
    found = None
    for pattern, owners in table:
        if matches(pattern, path):
            found = owners
    return found


class CodeOwners(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(CODEOWNERS, encoding="utf-8") as f:
            cls.table = rules(f.read())
        with open(AUTOMERGE, encoding="utf-8") as f:
            cls.config = json.load(f)

    def owners(self, path):
        return owners_of(path, self.table)

    def test_the_matching_reads_these_forms_as_github_does(self):
        self.assertTrue(matches("*", "runtime/src/cloud/handler.ts"))
        self.assertTrue(matches("/ios/", "ios/Tafs/Views/TafsBar.swift"))
        self.assertFalse(matches("/ios/", "iosx/A.swift"))
        self.assertTrue(matches("/docs/ios-app.md", "docs/ios-app.md"))
        self.assertFalse(matches("/docs/ios-app.md", "docs/ios-app.md.bak"))
        self.assertTrue(matches("*.entitlements", "ios/Tafs/Tafs.entitlements"))
        self.assertTrue(matches("/android/play/*.py", "android/play/upload.py"))
        self.assertFalse(matches("/android/play/*.py", "android/play/tools/upload.py"))

    def test_the_apps_lanes_ask_no_one_by_default(self):
        # A file in each lane the merge bot can take, its pages in docs/ and a changelog fragment: no owner,
        # so a pull request there waits only for the reviewers its author asks.
        samples = ["ios/Tafs/Views/TafsBar.swift", "ios/TafsTests/BarTests.swift", "android/app/src/main/kotlin/bot/tafs/app/views/BriefView.kt",
                   "android/wear/src/main/kotlin/bot/tafs/wear/WatchModel.kt", "changes/ios-a-line.md"]
        for paths in self.config["lanes"].values():
            samples += [p + "Sample.kt" if p.endswith("/") else p for p in paths]
        for path in samples:
            self.assertEqual(self.owners(path), [], path)

    def test_what_signs_holds_a_key_or_releases_asks_kiff_qa(self):
        for path in ["ios/Tafs/KIFF/ReceiptCheck.swift", "ios/Tafs/KIFF/KIFFClient.swift", "ios/Tafs/Services/ApprovalSigner.swift",
                     "ios/Tafs/Tafs.entitlements", "android/app/src/main/kotlin/bot/tafs/app/signing/ApprovalSigner.kt",
                     "android/app/src/main/kotlin/bot/tafs/app/kiff/ReceiptCheck.kt", "android/app/build.gradle.kts",
                     ".github/workflows/ios.yml", ".github/workflows/android.yml", ".github/automerge.json", "scripts/automerge.py",
                     ".github/workflows/mac-developer-id.yml", "ios/scripts/asc-profiles.rb", "ios/scripts/build_phases.py",
                     ".github/CODEOWNERS"]:
            self.assertEqual(self.owners(path), ["@kiff-qa"], path)

    def test_the_rest_of_the_repository_keeps_its_two_maintainers(self):
        for path in ["runtime/src/cloud/handler.ts", "app/strings/en/common.json", "docs/drive.md", "README.md", "scripts/changelog.py"]:
            self.assertEqual(self.owners(path), MAINTAINERS, path)

    def test_every_line_names_a_path_that_exists(self):
        # A file renamed away from its line would quietly lose its owner.
        tracked = subprocess.run(["git", "ls-files"], cwd=ROOT, check=True, capture_output=True, text=True).stdout.splitlines()
        for pattern, _ in self.table:
            if pattern != "*":
                self.assertTrue(any(matches(pattern, f) for f in tracked), pattern)


if __name__ == "__main__":
    unittest.main()
