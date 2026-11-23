"""scripts/public_text.py: it finds a thread, a lane, the PM or a dated rule, and leaves ordinary words alone."""

import hashlib
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import public_text  # noqa: E402


class PublicText(unittest.TestCase):
    def test_a_thread_the_pm_or_a_dated_rule_is_found(self):
        for line in ["see kiff/work#16", "decided (PM)", "PM 18 Nov 23:35", "(PM, 20 Nov)", "the PM's call",
                     "(owner, 13 Nov 2026)", "owner's 22 Nov"]:
            self.assertIsNotNone(public_text.named(line), line)

    def test_ordinary_words_are_left(self):
        for line in ["2:05 PM", "AM/PM", "var lane: UInt64 = 0", "the owner's rule: approve on your phone",
                     "dashlane.com", "a PM2.5 reading", "@team on line 3"]:
            self.assertIsNone(public_text.named(line), line)

    def test_a_name_is_matched_by_its_hash_as_a_whole_word(self):
        name = "exampleteam-lane"
        public_text.NAMES.add(hashlib.sha256(name.encode()).hexdigest()[:16])
        try:
            self.assertEqual(public_text.named(f"(exampleteam-lane's read)"), name)
            self.assertIsNone(public_text.named("an exampleteam-lane-two note"))
            self.assertIsNone(public_text.named("@exampleteam-lane"), "a GitHub mention is a login, where KEEP allows it")
        finally:
            public_text.NAMES.discard(hashlib.sha256(name.encode()).hexdigest()[:16])


if __name__ == "__main__":
    unittest.main()
