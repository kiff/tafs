#!/usr/bin/env python3
"""Checks the Chrome Web Store listing drafts: python3 chrome/store/check.py"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
LOCALE = {"pt": "pt_PT", "pt-BR": "pt_BR"}
URL = "https://kiff.dev/tafs/privacy"


def shipped():
    with open(os.path.join(ROOT, "..", "app", "strings", "languages.json"), encoding="utf-8") as f:
        return json.load(f)["shipped"]


def main():
    problems = []
    for code in shipped():
        path = os.path.join(HERE, "listing", code + ".md")
        if not os.path.exists(path):
            problems.append(f"{code}: no listing")
            continue
        text = open(path, encoding="utf-8").read()
        m = re.search(r"characters\):\n\n(.+)\n", text)
        short = m.group(1) if m else ""
        if not short:
            problems.append(f"{code}: no short description")
        elif len(short) > 132:
            problems.append(f"{code}: short description is {len(short)} characters (limit 132)")
        loc = os.path.join(ROOT, "_locales", LOCALE.get(code, code), "messages.json")
        if os.path.exists(loc):
            own = json.load(open(loc, encoding="utf-8"))["description"]["message"]
            if own != short:
                problems.append(f"{code}: the short description is not the extension's own")
        if "{" in text or "}" in text or "None" in text:
            problems.append(f"{code}: a placeholder or None is left")
        if URL not in text:
            problems.append(f"{code}: no privacy link")
        bullets = text.count("\n• ")
        if bullets != 10:
            problems.append(f"{code}: {bullets} bullets, want 10")
    for p in problems:
        print(p)
    print(f"{len(shipped())} languages, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
