#!/usr/bin/env python3
"""
Uploads the signed release bundle to Google Play's internal track, with its release notes in the eight languages
(docs/android-app.md, "Releases to Play"). Only the internal track, ever: never production, closed or open testing, and
never a rollout change. The token is a short-lived one from Google's workload identity federation (the release job's
`google-github-actions/auth`), passed in PLAY_ACCESS_TOKEN; no key file exists anywhere.

  play_internal.py --check                 the release notes of the current versionCode, if any: all eight, each short enough
  play_internal.py --upload BUNDLE.aab     one edit: the bundle, the internal track's release with its notes, committed

Standard library only: every call to Play is here, in the open.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

PACKAGE = "dev.kiff.tafs"
TRACK = "internal"  # The only track this script ever writes.
LOCALES = ["en-US", "de-DE", "fr-FR", "es-ES", "it-IT", "pt-PT", "nl-NL", "pl-PL"]
NOTES_MAX = 500  # Play's limit for a release note, per language.
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/" + PACKAGE
HERE = os.path.dirname(os.path.abspath(__file__))
GRADLE = os.path.join(HERE, "..", "app", "build.gradle.kts")


def version_code() -> int:
    with open(GRADLE, encoding="utf-8") as f:
        m = re.search(r"^\s*versionCode\s*=\s*(\d+)\s*$", f.read(), re.M)
    if not m:
        sys.exit("versionCode not found in app/build.gradle.kts")
    return int(m.group(1))


def notes(code: int, required: bool) -> list:
    """The release notes for `code`, from play/release-notes/<code>/<locale>.txt: all eight, or none when not required."""
    folder = os.path.join(HERE, "release-notes", str(code))
    if not os.path.isdir(folder):
        if required:
            sys.exit(f"no release notes for versionCode {code}: add play/release-notes/{code}/<locale>.txt for {', '.join(LOCALES)}")
        return []
    out, problems = [], []
    for locale in LOCALES:
        path = os.path.join(folder, f"{locale}.txt")
        if not os.path.isfile(path):
            problems.append(f"{locale}: missing")
            continue
        with open(path, encoding="utf-8") as f:
            text = f.read().strip()
        if not text:
            problems.append(f"{locale}: empty")
        elif len(text) > NOTES_MAX:
            problems.append(f"{locale}: {len(text)} characters, Play takes {NOTES_MAX}")
        out.append({"language": locale, "text": text})
    extra = sorted(set(n[:-4] for n in os.listdir(folder) if n.endswith(".txt")) - set(LOCALES))
    if extra:
        problems.append(f"not one of the eight: {', '.join(extra)}")
    if problems:
        sys.exit(f"release notes for versionCode {code}: " + "; ".join(problems))
    return out


def call(method: str, url: str, token: str, body=None, raw: bytes = None, kind: str = "application/json") -> dict:
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method, headers={"Authorization": f"Bearer {token}", **({"Content-Type": kind} if data is not None else {})})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            text = r.read().decode() or "{}"
            return json.loads(text)
    except urllib.error.HTTPError as e:
        # Play's own answer, for the log: never the token.
        sys.exit(f"Play answered {e.code} to {method} {url.split('?')[0].replace(API, '').replace(UPLOAD, '') or '/'}: {e.read().decode(errors='replace')[:2000]}")


def upload(bundle: str) -> None:
    token = os.environ.get("PLAY_ACCESS_TOKEN", "")
    if not token:
        sys.exit("PLAY_ACCESS_TOKEN is empty: the workload identity sign-in didn't run")
    code = version_code()
    release_notes = notes(code, required=True)
    edit = call("POST", f"{API}/edits", token, body={})["id"]
    with open(bundle, "rb") as f:
        sent = call("POST", f"{UPLOAD}/edits/{edit}/bundles?uploadType=media", token, raw=f.read(), kind="application/octet-stream")
    if int(sent.get("versionCode", -1)) != code:
        sys.exit(f"the bundle Play received is versionCode {sent.get('versionCode')}, but app/build.gradle.kts says {code}: nothing committed")
    # The internal track's release: this bundle alone, completed (internal testing has no staged rollout), with its notes.
    call("PUT", f"{API}/edits/{edit}/tracks/{TRACK}", token, body={
        "track": TRACK,
        "releases": [{"versionCodes": [str(code)], "status": "completed", "releaseNotes": release_notes}],
    })
    call("POST", f"{API}/edits/{edit}:commit", token)
    print(f"versionCode {code} is on Play's {TRACK} track, with release notes in {len(release_notes)} languages")


def main(argv: list) -> None:
    if argv[:1] == ["--check"]:
        code = version_code()
        found = notes(code, required=False)
        print(f"versionCode {code}: " + (f"release notes in {len(found)} languages" if found else "no release notes yet"))
    elif len(argv) == 2 and argv[0] == "--upload":
        upload(argv[1])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
