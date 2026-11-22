"""play_status.py without a network: Play's calls are recorded, and only reads are made."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import play_status as p  # noqa: E402


class PlayStatus(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.real_urlopen = p.urllib.request.urlopen

        tracks = {"tracks": [
            {"track": "production", "releases": [{"name": "128 (1.41.3)", "status": "completed", "versionCodes": ["128"], "countryTargeting": {"countries": ["FR"]}}]},
            {"track": "internal", "releases": [{"name": "131 (1.42.0)", "status": "completed", "versionCodes": ["131"]}]},
            {"track": "wear:internal", "releases": []},
        ]}

        class Answer:
            def __init__(self, body):
                self.body = body

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def read(self):
                import json
                return json.dumps(self.body).encode()

        def urlopen(req, timeout=0):
            path = req.full_url.replace(p.API, "")
            self.calls.append((req.get_method(), path))
            if req.get_method() == "POST":
                return Answer({"id": "edit_1"})
            if req.get_method() == "GET":
                return Answer(tracks)
            return Answer({})

        p.urllib.request.urlopen = urlopen

    def tearDown(self):
        p.urllib.request.urlopen = self.real_urlopen

    def test_it_opens_an_edit_reads_the_tracks_and_deletes_the_edit_unsaved(self):
        out = p.lines(p.read("tok"))
        self.assertEqual([("POST", "/edits"), ("GET", "/edits/edit_1/tracks"), ("DELETE", "/edits/edit_1")], self.calls)
        self.assertEqual([
            "internal: 131 (1.42.0) · completed · 131",
            "production: 128 (1.41.3) · completed · 128",
            "wear:internal: no release",
        ], out)

    def test_only_track_name_status_and_codes_are_printed(self):
        text = "\n".join(p.lines(p.read("tok")))
        self.assertNotIn("edit_1", text)
        self.assertNotIn("FR", text)
        self.assertNotIn("tok", text)

    def test_it_refuses_any_call_but_the_three_reads(self):
        with self.assertRaises(SystemExit):
            p.call("POST", f"{p.API}/edits/edit_1:validate", "tok")
        with self.assertRaises(SystemExit):
            p.call("PUT", f"{p.API}/edits/edit_1/tracks/internal", "tok", body={})
        self.assertEqual([], self.calls)

    def test_the_edit_is_deleted_even_when_the_read_fails(self):
        real = p.call

        def failing(method, url, token, body=None):
            if method == "GET":
                raise SystemExit("Play answered 500")
            return real(method, url, token, body)

        p.call = failing
        try:
            with self.assertRaises(SystemExit):
                p.read("tok")
        finally:
            p.call = real
        self.assertEqual(("DELETE", "/edits/edit_1"), self.calls[-1])

    def test_a_refusal_says_the_step_and_code_only(self):
        def refusing(req, timeout=0):
            import io
            raise p.urllib.error.HTTPError(req.full_url, 403, "Forbidden", {}, io.BytesIO(b'{"error":{"message":"edit_9 of dev.kiff.tafs, token abc"}}'))

        p.urllib.request.urlopen = refusing
        with self.assertRaises(SystemExit) as said:
            p.call("GET", f"{p.API}/edits/edit_9/tracks", "abc")
        self.assertEqual("Play answered 403 while reading the tracks", str(said.exception))

    def test_the_file_never_names_plays_save_call(self):
        with open(p.__file__, encoding="utf-8") as f:
            self.assertNotIn("commit", f.read())


if __name__ == "__main__":
    unittest.main()
