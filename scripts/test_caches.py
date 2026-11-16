"""Which Actions caches go: superseded copies, and closed pull requests' caches."""
import subprocess
import unittest
from unittest import mock

import caches


def c(i, key, ref="refs/heads/main", at="2026-11-14T10:00:00Z", size=100):
    return {"id": i, "key": key, "ref": ref, "created_at": at, "size_in_bytes": size}


class Family(unittest.TestCase):
    def test_a_trailing_hash_goes(self):
        self.assertEqual(caches.family("gradle-transforms-v2-3f9a0c1e2b4d5e6f7a8b"), "gradle-transforms-v2")
        self.assertEqual(caches.family("gradle-home-v1|Linux|test[0a1b]-0123456789abcdef0123"), "gradle-home-v1|Linux|test[0a1b]")
        self.assertEqual(caches.family("node-cache-Linux-x64-npm-AbCd1234efGH5678ijKL"), "node-cache-Linux-x64-npm")

    def test_a_short_part_is_a_word_not_a_hash(self):
        self.assertEqual(caches.family("gradle-transforms-v2"), "gradle-transforms-v2")
        self.assertEqual(caches.family("setup-java-Linux-gradle"), "setup-java-Linux-gradle")
        self.assertEqual(caches.family("gradle-dependencies"), "gradle-dependencies")
        self.assertEqual(caches.family("android-sdk-transformations"), "android-sdk-transformations")


class Decide(unittest.TestCase):
    def ids(self, got):
        return sorted(x["id"] for x, _ in got)

    def test_the_newest_of_each_family_stays(self):
        all_ = [
            c(1, "gradle-transforms-v2-a1b2c3d4e5f60718", at="2026-11-12T10:00:00Z"),
            c(2, "gradle-transforms-v2-b2c3d4e5f6071829", at="2026-11-14T10:00:00Z"),
            c(3, "gradle-transforms-v2-c3d4e5f60718293a", at="2026-11-13T10:00:00Z"),
            c(4, "gradle-build-cache-v2-d4e5f60718293a4b", at="2026-11-10T10:00:00Z"),
        ]
        got = caches.decide(all_, set())
        self.assertEqual(self.ids(got), [1, 3])
        self.assertTrue(all(why == "superseded by a newer one" for _, why in got))

    def test_each_branch_keeps_its_own_newest(self):
        all_ = [
            c(1, "gradle-transforms-v2-a1b2c3d4e5f60718", ref="refs/heads/main"),
            c(2, "gradle-transforms-v2-b2c3d4e5f6071829", ref="refs/pull/7/merge"),
        ]
        self.assertEqual(caches.decide(all_, {7}), [])

    def test_a_closed_pull_requests_caches_go_all_of_them(self):
        all_ = [
            c(1, "gradle-transforms-v2-a1b2c3d4e5f60718", ref="refs/pull/7/merge", at="2026-11-14T10:00:00Z"),
            c(2, "gradle-build-cache-v2-b2c3d4e5f6071829", ref="refs/pull/7/merge"),
            c(3, "gradle-transforms-v2-c3d4e5f60718293a", ref="refs/pull/8/merge"),
        ]
        got = caches.decide(all_, {8})
        self.assertEqual(self.ids(got), [1, 2])
        self.assertTrue(all(why == "its pull request is closed" for _, why in got))

    def test_a_cache_listed_twice_counts_once(self):
        # The API's pages can overlap: the same cache must not be deleted twice (14 Nov's first run).
        all_ = [
            c(1, "gradle-transforms-v2-a1b2c3d4e5f60718", at="2026-11-12T10:00:00Z"),
            c(1, "gradle-transforms-v2-a1b2c3d4e5f60718", at="2026-11-12T10:00:00Z"),
            c(2, "gradle-transforms-v2-b2c3d4e5f6071829", at="2026-11-14T10:00:00Z"),
        ]
        self.assertEqual(self.ids(caches.decide(all_, set())), [1])

    def test_nothing_to_do(self):
        self.assertEqual(caches.decide([], set()), [])
        self.assertEqual(caches.decide([c(1, "gradle-dependencies-v2-a1b2c3d4e5f60718")], set()), [])


class Delete(unittest.TestCase):
    def run_with(self, code, err):
        done = subprocess.CompletedProcess(["gh"], code, stdout="", stderr=err)
        with mock.patch.object(caches.subprocess, "run", return_value=done):
            caches.delete("kiff/tafs", 7)

    def test_one_already_gone_is_done(self):
        self.run_with(1, "gh: Not Found (HTTP 404)")

    def test_any_other_failure_stops_the_run(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_with(1, "gh: Forbidden (HTTP 403)")


if __name__ == "__main__":
    unittest.main()
