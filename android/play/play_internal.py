#!/usr/bin/env python3
"""
Uploads the signed release bundle to Google Play's internal track, with its release notes in the listing's languages (LOCALES)
(docs/android-app.md, "Releases to Play"). Only the internal track, ever: never production, closed or open testing, and
never a rollout change. With --wear, the Wear OS watch's bundle goes to the watch's own internal track (`wear:internal`),
with its own notes (docs/watch-wear.md, Versions). The token is a short-lived one from Google's workload identity federation (the release job's
`google-github-actions/auth`), passed in PLAY_ACCESS_TOKEN; no key file exists anywhere.

  play_internal.py --check                 the release notes of the current versionCode: one per language in LOCALES, each short enough; none fails
  play_internal.py --upload BUNDLE.aab     one edit: the bundle, the internal track's release with its notes, committed
                                           without sending anything for review (changesNotSentForReview)
  play_internal.py --wear --check          the same, for the watch's versionCode and notes
  play_internal.py --wear --upload BUNDLE.aab

Standard library only: every call to Play is here, in the open.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

PACKAGE = "dev.kiff.tafs"
TRACK = "internal"  # The only track this script ever writes for the phone.
WEAR_TRACK = "wear:internal"  # And for the watch: Play's internal track for the Wear OS form factor, never another.
# The listing's languages, each as Play names it. Korean from 20 Nov; Latin American Spanish and Brazilian Portuguese from 132
# and 0.4.5 (400,000,010), the listing having both.
LOCALES = ["en-US", "de-DE", "fr-FR", "es-ES", "es-419", "it-IT", "pt-PT", "pt-BR", "nl-NL", "pl-PL", "ko-KR"]
NOTES_MAX = 500  # Play's limit for a release note, per language.
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/" + PACKAGE
HERE = os.path.dirname(os.path.abspath(__file__))
GRADLE = os.path.join(HERE, "..", "app", "build.gradle.kts")
WEAR_GRADLE = os.path.join(HERE, "..", "wear", "build.gradle.kts")
# The watch's versionCodes are in their own band (400,000,001 and up): a phone's code can never name a watch's notes.
WEAR_FIRST = 400_000_001


def version_code(wear: bool = False) -> int:
    with open(WEAR_GRADLE if wear else GRADLE, encoding="utf-8") as f:
        # `400_000_001` is Kotlin's way of writing 400000001.
        m = re.search(r"^\s*versionCode\s*=\s*([\d_]+)\s*$", f.read(), re.M)
    if not m:
        sys.exit(f"versionCode not found in {'wear' if wear else 'app'}/build.gradle.kts")
    code = int(m.group(1).replace("_", ""))
    if wear != (code >= WEAR_FIRST):
        sys.exit(f"versionCode {code} is outside the {'watch' if wear else 'phone'}'s band (the watch's starts at {WEAR_FIRST})")
    return code


def notes(code: int, required: bool) -> list:
    """The release notes for `code`, from play/release-notes/<code>/<locale>.txt: one for each of LOCALES, or none when not required."""
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
        problems.append(f"not one of the listing's languages: {', '.join(extra)}")
    if problems:
        sys.exit(f"release notes for versionCode {code}: " + "; ".join(problems))
    return out


class PlayError(Exception):
    """Play's refusal, for a caller that answers one itself (`raising=True`): its code and its own words."""

    def __init__(self, code: int, words: str, said: str):
        super().__init__(said)
        self.code, self.words = code, words


def call(method: str, url: str, token: str, body=None, raw: bytes = None, kind: str = "application/json", raising: bool = False) -> dict:
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method, headers={"Authorization": f"Bearer {token}", **({"Content-Type": kind} if data is not None else {})})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            text = r.read().decode() or "{}"
            return json.loads(text)
    except urllib.error.HTTPError as e:
        # Play's own answer, for the log: never the token.
        words = e.read().decode(errors="replace")[:2000]
        said = f"Play answered {e.code} to {method} {url.split('?')[0].replace(API, '').replace(UPLOAD, '') or '/'}: {words}"
        if raising:
            raise PlayError(e.code, words, said)
        sys.exit(said)


# Play's words when managed publishing is on: a commit's changes already wait for the console's Publish, and the parameter is refused.
MANAGED = "changesNotSentForReview must not be set"


def commit(edit: str, token: str) -> str:
    """
    Commits the edit without sending anything for review, and says how. With managed publishing off, a plain commit sent every
    change pending in the console for review (an internal upload once restarted 128's review), so the commit asks for none. With it on, Play
    refuses that parameter (400, 21 Nov) because nothing it commits goes out before the console's Publish: then, and only on
    those words, the edit is committed plainly. Any other refusal stops here, committing nothing.
    """
    try:
        call("POST", f"{API}/edits/{edit}:commit?changesNotSentForReview=true", token, raising=True)
        return "sending nothing for review"
    except PlayError as e:
        if e.code != 400 or MANAGED not in e.words:
            sys.exit(str(e))
    call("POST", f"{API}/edits/{edit}:commit", token)
    return "under managed publishing, waiting for the console's Publish"


def upload(bundle: str, wear: bool = False) -> None:
    token = os.environ.get("PLAY_ACCESS_TOKEN", "")
    if not token:
        sys.exit("PLAY_ACCESS_TOKEN is empty: the workload identity sign-in didn't run")
    track = WEAR_TRACK if wear else TRACK
    code = version_code(wear) if wear else version_code()
    release_notes = notes(code, required=True)
    edit = call("POST", f"{API}/edits", token, body={})["id"]
    with open(bundle, "rb") as f:
        sent = call("POST", f"{UPLOAD}/edits/{edit}/bundles?uploadType=media", token, raw=f.read(), kind="application/octet-stream")
    if int(sent.get("versionCode", -1)) != code:
        sys.exit(f"the bundle Play received is versionCode {sent.get('versionCode')}, but {'wear' if wear else 'app'}/build.gradle.kts says {code}: nothing committed")
    # The internal track's release: this bundle alone, completed (internal testing has no staged rollout), with its notes.
    call("PUT", f"{API}/edits/{edit}/tracks/{track}", token, body={
        "track": track,
        "releases": [{"versionCodes": [str(code)], "status": "completed", "releaseNotes": release_notes}],
    })
    # This edit goes to the internal track only; anything to review is sent from the console, by the person who owns the listing.
    how = commit(edit, token)
    print(f"versionCode {code} is committed to Play's {track} track, with release notes in {len(release_notes)} languages, "
          f"{how}: it reaches testers once it's published in Play Console")


def main(argv: list) -> None:
    wear = argv[:1] == ["--wear"]
    if wear:
        argv = argv[1:]
    if argv[:1] == ["--check"]:
        code = version_code(wear) if wear else version_code()
        # Required here as at the upload: a version with no notes fails `test` in its own pull request, never the release
        # after its merge (Android 105 merged with none, and only `release` refused it).
        found = notes(code, required=True)
        print(f"versionCode {code}: release notes in {len(found)} languages")
    elif len(argv) == 2 and argv[0] == "--upload":
        upload(argv[1], wear) if wear else upload(argv[1])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
