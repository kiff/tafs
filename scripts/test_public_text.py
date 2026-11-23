"""scripts/public_text.py: it finds a thread, a lane, its manager or a dated rule, and leaves ordinary words alone."""

import hashlib
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import public_text  # noqa: E402


class PublicText(unittest.TestCase):
    def test_a_thread_the_pm_or_a_dated_rule_is_found(self):
        pm = "P" + "M"
        for line in ["see kiff/work#16", f"decided ({pm})", f"{pm} 18 Nov 23:35", f"({pm}, 20 Nov)", f"the {pm}'s call",
                     "(owner, 13 Nov 2026)", "owner's 22 Nov"]:
            self.assertIsNotNone(public_text.named(line), line)

    def test_ordinary_words_are_left(self):
        for line in ["2:05 PM", "AM/PM", "var lane: UInt64 = 0", "the owner's rule: approve on your phone",
                     "dashlane.com", "a PM2.5 reading"]:
            self.assertIsNone(public_text.named(line), line)

    def test_a_name_is_matched_by_its_hash_as_a_whole_word(self):
        name = "exampleteam-lane"
        public_text.NAMES.add(hashlib.sha256(name.encode()).hexdigest()[:16])
        try:
            self.assertEqual(public_text.named(f"(exampleteam-lane's read)"), name)
            self.assertIsNone(public_text.named("an exampleteam-lane-two note"))
            self.assertEqual(public_text.named("@exampleteam-lane"), name, "an @mention is caught too, outside KEEP")
        finally:
            public_text.NAMES.discard(hashlib.sha256(name.encode()).hexdigest()[:16])

    def test_ci_fails_on_a_planted_line_and_passes_once_it_is_gone(self):
        """The script as CI runs it, in a repository of its own: a planted line fails it, the cleaned file passes."""
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "public_text.py")
        # A lane's name, built here so this file never spells it out.
        lane = "-".join(["qa", "kiff"])
        for planted in [f"// ({lane}'s read)", "// see " + "kiff/" + "work#24", "// decided (" + "PM" + ", 20 Nov)"]:
            with tempfile.TemporaryDirectory() as repo:
                run = lambda *a: subprocess.run(a, cwd=repo, capture_output=True, text=True)
                run("git", "init", "-q")
                path = os.path.join(repo, "a.kt")
                with open(path, "w", encoding="utf-8") as f:
                    f.write(f"val x = 1\n{planted}\n")
                run("git", "add", "a.kt")
                failed = run(sys.executable, script)
                self.assertEqual(failed.returncode, 1, planted)
                self.assertIn("a.kt:2:", failed.stdout)
                with open(path, "w", encoding="utf-8") as f:
                    f.write("val x = 1\n// The rule itself.\n")
                passed = run(sys.executable, script)
                self.assertEqual((passed.returncode, passed.stdout.strip()), (0, "clean"), planted)


if __name__ == "__main__":
    unittest.main()
