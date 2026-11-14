"""play_internal.py without a network: Play's calls are recorded, and what would be sent is checked."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import play_internal as p  # noqa: E402


class PlayInternal(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.answers = {}
        self.real = (p.call, p.version_code)

        def call(method, url, token, body=None, raw=None, kind="application/json"):
            self.calls.append((method, url.replace(p.API, "").replace(p.UPLOAD, "U").split("?")[0], body))
            for k, v in self.answers.items():
                if k in url:
                    return v
            return {}

        p.call = call
        self.answers = {"/edits/e1/bundles": {"versionCode": 85}, "/edits": {"id": "e1"}}
        os.environ["PLAY_ACCESS_TOKEN"] = "t"
        self.bundle = tempfile.NamedTemporaryFile(delete=False, suffix=".aab")
        self.bundle.write(b"aab")
        self.bundle.close()

    def tearDown(self):
        p.call, p.version_code = self.real
        os.unlink(self.bundle.name)

    def test_one_edit_to_the_internal_track_only_with_eight_notes(self):
        p.version_code = lambda: 85
        p.upload(self.bundle.name)
        self.assertEqual([c[:2] for c in self.calls], [
            ("POST", "/edits"), ("POST", "U/edits/e1/bundles"), ("PUT", "/edits/e1/tracks/internal"), ("POST", "/edits/e1:commit")])
        release = self.calls[2][2]["releases"][0]
        self.assertEqual(self.calls[2][2]["track"], "internal")
        self.assertEqual(release["versionCodes"], ["85"])
        self.assertEqual(release["status"], "completed")
        self.assertEqual([n["language"] for n in release["releaseNotes"]], p.LOCALES)

    def test_a_bundle_of_another_version_commits_nothing(self):
        p.version_code = lambda: 85
        self.answers["/edits/e1/bundles"] = {"versionCode": 84}
        with self.assertRaises(SystemExit):
            p.upload(self.bundle.name)
        self.assertNotIn(("POST", "/edits/e1:commit"), [c[:2] for c in self.calls])
        self.assertNotIn("PUT", [c[0] for c in self.calls])

    def test_no_notes_for_the_version_sends_nothing(self):
        p.version_code = lambda: 99999
        with self.assertRaises(SystemExit):
            p.upload(self.bundle.name)
        self.assertEqual(self.calls, [])

    def test_no_token_sends_nothing(self):
        os.environ["PLAY_ACCESS_TOKEN"] = ""
        with self.assertRaises(SystemExit):
            p.upload(self.bundle.name)
        self.assertEqual(self.calls, [])

    def test_the_track_is_internal_alone(self):
        self.assertEqual(p.TRACK, "internal")
        with open(p.__file__, encoding="utf-8") as f:
            code = f.read()
        for other in ("production", "alpha", "beta", "userFraction", "inProgress"):
            self.assertNotIn(f'"{other}"', code)


if __name__ == "__main__":
    unittest.main()
