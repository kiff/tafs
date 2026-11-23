"""vectors/api-door-endpoint.json holds to its own rule, checked by one reference reading of it.

The apps (iOS DoorsTests, Android ApiDoorScreensTest, the desktops' door tests) read the vector against their own code;
this keeps the vector itself right: every `exact` endpoint is what the rule gives, every `hostile` address keeps the
`rules`, and the example's prefix and suffix quote the endpoint.
"""

import json
import re
import unittest
from pathlib import Path

VECTOR = Path(__file__).resolve().parent.parent / "vectors" / "api-door-endpoint.json"


def path_of(address):
    """The address's path as written, query and fragment cut; None for whitespace, a backslash, or a scheme with no host."""
    if any(c.isspace() or c == "\\" for c in address):
        return None
    s = re.split(r"[?#]", address, maxsplit=1)[0]
    if "://" in s:
        rest = s.split("://", 1)[1]
        return rest[rest.index("/"):] if "/" in rest else ""
    if s.startswith("//"):
        rest = s[2:]
        return rest[rest.index("/"):] if "/" in rest else ""
    if ":" in s and ("/" not in s or s.index(":") < s.index("/")):
        return None
    return s


def endpoint(v, address):
    path = path_of(address.strip())
    if path is None:
        return v["alone"]
    rooted = path if path.startswith("/") else "/" + path
    return v["base"] + rooted if re.fullmatch(v["rules"]["path_pattern"][1:-1], rooted) else v["alone"]


class APIDoorVector(unittest.TestCase):
    def setUp(self):
        self.v = json.loads(VECTOR.read_text(encoding="utf-8"))

    def test_the_base_and_its_alone_form(self):
        r = self.v["rules"]
        self.assertEqual(self.v["base"], f"{r['scheme']}://{r['host']}")
        self.assertEqual(self.v["alone"], self.v["base"] + "/")

    def test_every_exact_endpoint_is_the_rules(self):
        for c in self.v["exact"]:
            self.assertEqual(endpoint(self.v, c["address"]), c["endpoint"], c["address"])

    def test_every_hostile_address_keeps_the_rules(self):
        keep = re.compile(re.escape(self.v["base"]) + self.v["rules"]["path_pattern"][1:])
        for a in self.v["hostile"]:
            self.assertRegex(endpoint(self.v, a), keep, repr(a))

    def test_the_example_quotes_the_endpoint(self):
        self.assertTrue(self.v["example_prefix"].endswith("'"))
        self.assertTrue(self.v["example_suffix"].startswith("'"))
        self.assertIn("$TAFS_API_KEY", self.v["example_suffix"])
        # No allowed endpoint can close the quote.
        self.assertNotIn("'", "".join(c["endpoint"] for c in self.v["exact"]))


if __name__ == "__main__":
    unittest.main()
