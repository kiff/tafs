"""Where the apps reach tafs cloud (app/hosts.json), and that each app's own constant says the same.

    python3 -W error -m unittest discover -s app -p test_hosts.py

app/hosts.json names tafs cloud's address (`tafs_cloud`) and the one it streams a run from
(`tafs_cloud_stream`): tafs cloud's function itself, as a run streams for minutes, longer than a
CDN waits between bytes. The iPad and Android keep each as a constant (`TafsCloud.defaultURL`,
`defaultStreamURL`; `DEFAULT_URL`, `DEFAULT_STREAM_URL`); the desktop apps read this file. A change
of address is made here and in both constants, or this fails.
"""
import json
import os
import re
import unittest
from urllib.parse import urlsplit

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SWIFT = os.path.join(REPO, "ios", "Tafs", "KIFF", "TafsCloud.swift")
KOTLIN = os.path.join(REPO, "android", "app", "src", "main", "kotlin", "bot", "tafs", "app", "kiff", "TafsCloud.kt")


def hosts():
    with open(os.path.join(HERE, "hosts.json"), encoding="utf-8") as f:
        return json.load(f)


def constant(path, pattern):
    with open(path, encoding="utf-8") as f:
        found = re.findall(pattern, f.read())
    if len(found) != 1:
        raise AssertionError(f"{os.path.relpath(path, REPO)}: {len(found)} matches for {pattern}")
    return found[0]


class Hosts(unittest.TestCase):
    def test_each_is_an_https_origin(self):
        h = hosts()
        self.assertEqual(sorted(h), ["tafs_cloud", "tafs_cloud_stream"])
        for name, url in h.items():
            with self.subTest(name=name):
                u = urlsplit(url)
                self.assertEqual(u.scheme, "https")
                self.assertTrue(u.hostname)
                self.assertIsNone(u.port)
                self.assertIsNone(u.username)
                self.assertEqual((u.path, u.query, u.fragment), ("", "", ""))

    def test_the_ipad_says_the_same(self):
        h = hosts()
        self.assertEqual(constant(SWIFT, r'static let defaultURL = URL\(string: "([^"]+)"\)!'), h["tafs_cloud"])
        self.assertEqual(constant(SWIFT, r'static let defaultStreamURL = URL\(string: "([^"]+)"\)!'), h["tafs_cloud_stream"])

    def test_android_says_the_same(self):
        h = hosts()
        self.assertEqual(constant(KOTLIN, r'const val DEFAULT_URL = "([^"]+)"'), h["tafs_cloud"])
        self.assertEqual(constant(KOTLIN, r'const val DEFAULT_STREAM_URL = "([^"]+)"'), h["tafs_cloud_stream"])


if __name__ == "__main__":
    unittest.main()
