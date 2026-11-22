#!/usr/bin/env python3
"""
Reads where tafs stands on Google Play, for the owner's release board (docs/android-app.md, "Releases to Play"): each track
(production, internal, the watch's), and on it each release's name, status and version codes. Nothing else is printed: no
ids, no tokens, no countries' names.

Read only, always. Play's API reads tracks only inside an edit, so this opens one, reads the tracks and deletes the edit
unsaved: nothing is uploaded, changed or sent for review, and managed publishing never sees it. This file never names
Play's save call, and the workflow fails if it ever does (`.github/workflows/play-status.yml`).

  play_status.py           prints the tracks, one line per release

The token is the short-lived one from Google's workload identity federation (`google-github-actions/auth`), passed in
PLAY_ACCESS_TOKEN, as play_internal.py's. Standard library only: every call to Play is here, in the open.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

PACKAGE = "dev.kiff.tafs"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE
# The only calls this script makes, by their exact shape: open an edit, read its tracks, delete it.
ALLOWED = (("POST", re.compile(r"^/edits$")), ("GET", re.compile(r"^/edits/[^/:?]+/tracks$")), ("DELETE", re.compile(r"^/edits/[^/:?]+$")))


def call(method: str, url: str, token: str, body=None) -> dict:
    path = url.replace(API, "")
    if not any(method == m and shape.match(path) for m, shape in ALLOWED):
        sys.exit(f"play_status.py makes only read calls; refused {method} {path}")
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Authorization": f"Bearer {token}", **({"Content-Type": "application/json"} if data is not None else {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        # Play's own answer, for the log: never the token.
        sys.exit(f"Play answered {e.code} to {method} {path.split('/edits/')[0] or '/'}: {e.read().decode(errors='replace')[:500]}")


def lines(tracks: list) -> list:
    """One line per release: track, release name, status, version codes. Only those."""
    out = []
    for t in sorted(tracks, key=lambda t: t.get("track", "")):
        releases = t.get("releases") or []
        if not releases:
            out.append(f"{t.get('track', '?')}: no release")
        for r in releases:
            codes = ", ".join(str(c) for c in r.get("versionCodes") or []) or "-"
            out.append(f"{t.get('track', '?')}: {r.get('name') or '(no name)'} · {r.get('status', '?')} · {codes}")
    return out


def read(token: str) -> list:
    edit = call("POST", f"{API}/edits", token, body={})["id"]
    try:
        return call("GET", f"{API}/edits/{edit}/tracks", token).get("tracks") or []
    finally:
        # Unsaved, always: the edit only held the read.
        call("DELETE", f"{API}/edits/{edit}", token)


def main() -> None:
    token = os.environ.get("PLAY_ACCESS_TOKEN", "")
    if not token:
        sys.exit("PLAY_ACCESS_TOKEN is empty: run this from the workflow, signed in with workload identity")
    for line in lines(read(token)):
        print(line)


if __name__ == "__main__":
    main()
