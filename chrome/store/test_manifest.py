"""python3 -m unittest chrome/store/test_manifest.py

The manifest as the owner chose it: the extension asks Chrome once, at install, for every https site, as
Claude in Chrome does, and never per site. What stops a taf reaching a site its connection doesn't list is then the extension's own
checks (docs/chrome.md, "Runs"), not Chrome's permission, so the manifest must say exactly this and nothing wider.
"""
import json
import re
import unittest
from pathlib import Path

MANIFEST = json.loads((Path(__file__).resolve().parent.parent / "manifest.json").read_text())
# What the manifest must never ask for (chrome/store/privacy-practices.md, "Not requested").
FORBIDDEN = {"debugger", "downloads", "cookies", "history", "bookmarks", "webRequest", "nativeMessaging", "clipboardRead", "activeTab"}


class TheManifest(unittest.TestCase):
    def test_asks_for_every_https_site_at_install_and_no_other_host(self):
        self.assertEqual(MANIFEST.get("host_permissions"), ["https://*/*"])
        self.assertNotIn("optional_host_permissions", MANIFEST, "no per-site Allow any more")
        self.assertNotIn("content_scripts", MANIFEST, "no script is injected by Chrome on its own; a run's tab alone is read")

    def test_asks_for_nothing_it_does_not_need(self):
        self.assertFalse(FORBIDDEN & set(MANIFEST["permissions"]), sorted(FORBIDDEN & set(MANIFEST["permissions"])))
        # "notifications" is the permission Chrome asks of an extension that receives Web Push, silent here: KIFF's wake-up when a taf needs
        # this Chrome (userVisibleOnly: false, Chrome 121). The extension shows no notification.
        self.assertEqual(MANIFEST["permissions"], ["storage", "alarms", "scripting", "sidePanel", "tabs", "tabGroups", "webNavigation", "declarativeNetRequest", "notifications"])

    def test_the_version_is_one_chrome_takes(self):
        self.assertRegex(MANIFEST["version"], r"^\d+\.\d+\.\d+$")
        self.assertGreaterEqual(tuple(map(int, MANIFEST["version"].split("."))), (0, 2, 0), "the all-sites manifest is a new version")


if __name__ == "__main__":
    unittest.main()
