"""play_internal.py without a network: Play's calls are recorded, and what would be sent is checked."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import play_internal as p  # noqa: E402


class PlayInternal(unittest.TestCase):
    # The versions these tests send are real ones whose notes carry every language in LOCALES (es-419 and pt-BR since 132 and 0.4.5).

    def setUp(self):
        self.calls = []
        self.urls = []
        self.answers = {}
        self.real = (p.call, p.version_code)

        def call(method, url, token, body=None, raw=None, kind="application/json", raising=False):
            self.urls.append(url)
            self.calls.append((method, url.replace(p.API, "").replace(p.UPLOAD, "U").split("?")[0], body))
            refusal = self.refusals.pop(url.replace(p.API, ""), None)
            if refusal is not None:
                if raising:
                    raise refusal
                sys.exit(str(refusal))
            for k, v in self.answers.items():
                if k in url:
                    return v
            return {}

        self.refusals = {}

        p.call = call
        self.answers = {"/edits/e1/bundles": {"versionCode": 140}, "/edits": {"id": "e1"}}
        os.environ["PLAY_ACCESS_TOKEN"] = "t"
        self.bundle = tempfile.NamedTemporaryFile(delete=False, suffix=".aab")
        self.bundle.write(b"aab")
        self.bundle.close()

    def tearDown(self):
        p.call, p.version_code = self.real
        os.unlink(self.bundle.name)

    def test_one_edit_to_the_internal_track_only_with_a_note_per_language(self):
        p.version_code = lambda: 140
        p.upload(self.bundle.name)
        self.assertEqual([c[:2] for c in self.calls], [
            ("POST", "/edits"), ("POST", "U/edits/e1/bundles"), ("PUT", "/edits/e1/tracks/internal"), ("POST", "/edits/e1:commit")])
        release = self.calls[2][2]["releases"][0]
        self.assertEqual(self.calls[2][2]["track"], "internal")
        self.assertEqual(release["versionCodes"], ["140"])
        self.assertEqual(release["status"], "completed")
        self.assertEqual([n["language"] for n in release["releaseNotes"]], p.LOCALES)

    def test_the_commit_sends_nothing_for_review(self):
        # A plain commit once sent every change pending in Play Console for review (128's review restarted). The
        # internal upload commits its own edit only, never a review, for the phone and for the watch.
        p.version_code = lambda wear=False: 400000010 if wear else 140
        self.answers["/edits/e1/bundles"] = {"versionCode": 140}
        p.upload(self.bundle.name)
        self.answers["/edits/e1/bundles"] = {"versionCode": 400000010}
        p.upload(self.bundle.name, wear=True)
        commits = [u for u in self.urls if ":commit" in u]
        self.assertEqual(len(commits), 2)
        for u in commits:
            self.assertTrue(u.endswith(":commit?changesNotSentForReview=true"), u)

    def test_with_managed_publishing_on_the_refused_parameter_is_dropped_and_the_edit_committed(self):
        # Play, 21 Nov 00:04, with managed publishing on: the parameter is refused, since a commit's changes wait for Publish.
        p.version_code = lambda wear=False: 400000010
        self.answers["/edits/e1/bundles"] = {"versionCode": 400000010}
        self.refusals["/edits/e1:commit?changesNotSentForReview=true"] = p.PlayError(
            400, '{"error": {"message": "Changes are sent for review automatically. The query parameter changesNotSentForReview must not be set."}}', "400")
        p.upload(self.bundle.name, wear=True)
        commits = [u.replace(p.API, "") for u in self.urls if ":commit" in u]
        self.assertEqual(commits, ["/edits/e1:commit?changesNotSentForReview=true", "/edits/e1:commit"])

    def test_any_other_refusal_of_the_commit_commits_nothing_else(self):
        p.version_code = lambda wear=False: 400000010
        self.answers["/edits/e1/bundles"] = {"versionCode": 400000010}
        self.refusals["/edits/e1:commit?changesNotSentForReview=true"] = p.PlayError(400, '{"error": {"message": "Something else."}}', "400")
        with self.assertRaises(SystemExit):
            p.upload(self.bundle.name, wear=True)
        self.assertEqual([u.replace(p.API, "") for u in self.urls if ":commit" in u], ["/edits/e1:commit?changesNotSentForReview=true"])

    def test_a_bundle_of_another_version_commits_nothing(self):
        p.version_code = lambda: 140
        self.answers["/edits/e1/bundles"] = {"versionCode": 135}
        with self.assertRaises(SystemExit):
            p.upload(self.bundle.name)
        self.assertNotIn(("POST", "/edits/e1:commit"), [c[:2] for c in self.calls])
        self.assertNotIn("PUT", [c[0] for c in self.calls])

    def test_no_notes_for_the_version_sends_nothing(self):
        p.version_code = lambda: 99999
        with self.assertRaises(SystemExit):
            p.upload(self.bundle.name)
        self.assertEqual(self.calls, [])

    def test_the_check_fails_a_version_with_no_notes(self):
        # The pull request that raises versionCode fails `test` without its notes, before the merge, never only at release.
        p.version_code = lambda: 99999
        with self.assertRaises(SystemExit) as raised:
            p.main(["--check"])
        self.assertIn("no release notes for versionCode 99999", str(raised.exception))
        p.version_code = lambda: 140
        p.main(["--check"])

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

    def test_the_watch_goes_to_its_own_internal_track_only(self):
        p.version_code = lambda wear=False: 400000010 if wear else 140
        self.answers["/edits/e1/bundles"] = {"versionCode": 400000010}
        p.upload(self.bundle.name, wear=True)
        self.assertEqual([c[:2] for c in self.calls], [
            ("POST", "/edits"), ("POST", "U/edits/e1/bundles"), ("PUT", "/edits/e1/tracks/wear:internal"), ("POST", "/edits/e1:commit")])
        body = self.calls[2][2]
        self.assertEqual(body["track"], "wear:internal")
        self.assertEqual(body["releases"][0]["versionCodes"], ["400000010"])
        self.assertEqual([n["language"] for n in body["releases"][0]["releaseNotes"]], p.LOCALES)
        self.assertEqual(p.WEAR_TRACK, "wear:internal")

    def test_a_phone_bundle_sent_as_the_watch_commits_nothing(self):
        p.version_code = lambda wear=False: 400000010 if wear else 140
        self.answers["/edits/e1/bundles"] = {"versionCode": 140}
        with self.assertRaises(SystemExit):
            p.upload(self.bundle.name, wear=True)
        self.assertNotIn("PUT", [c[0] for c in self.calls])

    def test_the_watchs_version_is_read_from_its_own_build_in_its_own_band(self):
        # Kotlin writes it 400_000_001; the phone's file is never read for the watch.
        self.assertGreaterEqual(p.version_code(wear=True), p.WEAR_FIRST)
        self.assertLess(p.version_code(), p.WEAR_FIRST)
        p.main(["--wear", "--check"])


if __name__ == "__main__":
    unittest.main()
