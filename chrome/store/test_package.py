"""python3 -m unittest chrome/store/test_package.py"""
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import package  # noqa: E402


def tree(root, extra=None):
    """A small extension on disk."""
    files = {
        "manifest.json": json.dumps({"version": "1.2.3", "default_locale": "en", "background": {"service_worker": "src/sw.js", "type": "module"},
                                     "side_panel": {"default_path": "sidepanel/index.html"}, "icons": {"16": "icons/i16.png"}, "action": {"default_icon": {"16": "icons/i16.png"}}}),
        "src/sw.js": 'import { a } from "./a.js";\n',
        "src/a.js": "export const a = 1;\n",
        "sidepanel/index.html": '<script type="module" src="panel.js"></script>',
        "sidepanel/panel.js": 'import { a } from "../src/a.js";\n',
        "_locales/en/messages.json": "{}",
        "icons/i16.png": "png",
        "test/core.test.js": "x",
        "store/privacy.md": "x",
        "package.json": "{}",
    }
    files.update(extra or {})
    for rel, text in files.items():
        if text is None:
            continue
        p = Path(root) / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
    return Path(root)


class PackageTest(unittest.TestCase):
    def test_the_extension_is_in_and_its_tests_and_paper_are_out(self):
        with tempfile.TemporaryDirectory() as d:
            root = tree(d)
            self.assertEqual(package.files(root), ["_locales/en/messages.json", "icons/i16.png", "manifest.json", "sidepanel/index.html", "sidepanel/panel.js", "src/a.js", "src/sw.js"])
            self.assertEqual(package.problems(root), [])

    def test_the_zip_has_the_manifest_at_its_root_and_is_the_same_bytes_twice(self):
        with tempfile.TemporaryDirectory() as d:
            root = tree(Path(d) / "x")
            one = package.build(Path(d) / "one", root)
            two = package.build(Path(d) / "two", root)
            self.assertEqual(one.name, "tafs-chrome-1.2.3.zip")
            self.assertEqual(one.read_bytes(), two.read_bytes())
            with zipfile.ZipFile(one) as z:
                self.assertIn("manifest.json", z.namelist())
                self.assertEqual(z.read("manifest.json"), (root / "manifest.json").read_bytes())
                self.assertFalse([n for n in z.namelist() if n.startswith(("test/", "store/")) or n.endswith("package.json")])

    def test_a_file_the_manifest_names_that_is_missing_is_a_problem(self):
        with tempfile.TemporaryDirectory() as d:
            root = tree(d, {"icons/i16.png": None})
            self.assertTrue(any("icons/i16.png" in p for p in package.problems(root)), package.problems(root))

    def test_an_import_of_a_file_that_is_not_in_the_package_is_a_problem(self):
        with tempfile.TemporaryDirectory() as d:
            root = tree(d, {"sidepanel/panel.js": 'import { b } from "../test/helper.js";\n', "test/helper.js": "export const b = 1;"})
            bad = package.problems(root)
            self.assertTrue(any("test/helper.js" in p for p in bad), bad)

    def test_the_real_extension_is_complete(self):
        self.assertEqual(package.problems(), [])


if __name__ == "__main__":
    unittest.main()
