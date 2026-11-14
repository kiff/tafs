"""The checks over app/routes.json and the files app/routes.py writes from it.

    python3 -W error -m unittest discover -s app -p test_routes.py
"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import routes  # noqa: E402

LINES = {
    "home.title": "Tafs",
    "taf.section.card": "Card",
    "people.share.title": "Share {taf}",
    "usage.calls": {"one": "{count} call", "other": "{count} calls"},
}


def one(**entry):
    return routes.problems([entry], LINES)


class TheList(unittest.TestCase):
    def test_the_list_is_right(self):
        self.assertEqual(routes.problems(routes.load(), routes.english()), [])

    def test_generated_files_are_current(self):
        for path, text in routes.generated(routes.load()).items():
            with self.subTest(path=os.path.relpath(path, routes.REPO)):
                with open(path, encoding="utf-8") as f:
                    self.assertEqual(f.read(), text, "run `python3 app/routes.py`")

    def test_tafs_cloud_gets_each_route_with_its_english_title(self):
        lines = routes.english()
        text = routes.generated(routes.load(), lines)[routes.RUNTIME_TS]
        for entry in routes.load():
            with self.subTest(route=entry["route"]):
                want = {"route": entry["route"], "title": entry["title"], "english": lines[entry["title"]]}
                if entry.get("needs"):
                    want["needs"] = entry["needs"]
                self.assertIn(f"  {json.dumps(want, ensure_ascii=False)},\n", text)

    def test_every_route_reaches_both_apps(self):
        listed = routes.load()
        files = routes.generated(listed)
        for entry in listed:
            for text in files.values():
                self.assertIn(f'"{entry["route"]}"', text)
                self.assertIn(f'"{entry["title"]}"', text)


class Refused(unittest.TestCase):
    def test_a_good_route_passes(self):
        self.assertEqual(one(route="taf/{taf}/card", title="taf.section.card"), [])

    def test_a_title_that_isnt_a_line(self):
        self.assertIn("isn't a line", one(route="tafs", title="home.titel")[0])

    def test_a_count_as_a_title(self):
        self.assertIn("count's line", one(route="tafs", title="usage.calls")[0])

    def test_a_title_value_the_route_doesnt_name(self):
        self.assertIn("doesn't name", one(route="account/share", title="people.share.title")[0])

    def test_unknown_needs(self):
        self.assertIn("not one of", one(route="tafs", title="home.title", needs="admin")[0])

    def test_manages_without_a_taf(self):
        self.assertIn("names no taf", one(route="tafs", title="home.title", needs="manages")[0])

    def test_a_bad_part(self):
        for bad in ("Tafs", "taf//card", "taf/{Taf}", "taf_card", "tafs/", "-tafs"):
            with self.subTest(route=bad):
                self.assertTrue(one(route=bad, title="home.title"))

    def test_a_value_first(self):
        self.assertIn("fixed part", one(route="{taf}/card", title="taf.section.card")[0])

    def test_a_value_named_twice(self):
        self.assertTrue(any("named twice" in p for p in one(route="taf/{taf}/x/{taf}", title="taf.section.card")))

    def test_an_unknown_field(self):
        self.assertIn("unknown fields", one(route="tafs", title="home.title", owner="x")[0])

    def test_listed_twice(self):
        entry = {"route": "tafs", "title": "home.title"}
        self.assertTrue(any("listed twice" in p for p in routes.problems([entry, entry], LINES)))

    def test_two_routes_with_the_same_path(self):
        a = {"route": "taf/{taf}", "title": "home.title"}
        b = {"route": "taf/{id}", "title": "home.title"}
        self.assertTrue(any("can't tell them apart" in p for p in routes.problems([a, b], LINES)))

    def test_two_routes_with_the_same_case_name(self):
        a = {"route": "taf/{taf}/card", "title": "home.title"}
        b = {"route": "taf-card", "title": "home.title"}
        self.assertTrue(any("case name" in p for p in routes.problems([a, b], LINES)))


class Names(unittest.TestCase):
    def test_case_names(self):
        self.assertEqual(routes.name("taf/{taf}/setup/versions"), "TafSetupVersions")
        self.assertEqual(routes.name("account/time-zone"), "AccountTimeZone")
        self.assertEqual(routes.name("needs-you"), "NeedsYou")

    def test_params(self):
        self.assertEqual(routes.params("taf/{taf}/setup/versions"), ["taf"])
        self.assertEqual(routes.params("tafs"), [])


if __name__ == "__main__":
    unittest.main()
