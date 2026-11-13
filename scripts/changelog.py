#!/usr/bin/env python3
"""The changelog's Unreleased entries, one file per pull request (CONTRIBUTING.md).

A pull request adds its entry as `changes/<branch>.md`, the branch's name with
each "/" as "-", and never edits CHANGELOG.md, so two pull requests never touch
the same line. A change with nothing user-visible adds an empty file.

    changelog.py check --base origin/main --branch <head branch>
        In a pull request: exactly one fragment added, named for the branch,
        and CHANGELOG.md untouched. A branch whose name comes back after its
        pull request merged finds its fragment on the base, empty until the
        next fold: filling it is its entry, and leaving it empty is the empty
        file. Dependabot's pull requests are exempt. A
        release branch (release/...) carries the fold instead: CHANGELOG.md
        edited and fragments only removed, none added or changed.
    changelog.py fold [--release "1.8.0 (2026-11-12)"]
        At a release: each fragment's lines go to the top of Unreleased,
        newest first (or under a new version heading), and the fragments are
        removed. Empty fragments add nothing.
"""
import argparse
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHANGES = "changes"
CHANGELOG = "CHANGELOG.md"
UNRELEASED = "## Unreleased"


def fragment_name(branch: str) -> str:
    return f"{CHANGES}/{branch.replace('/', '-')}.md"


def git(*args: str, cwd: str = ROOT) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout


def check(changed: list[tuple[str, str]], branch: str, empty_on_base: bool = False) -> list[str]:
    """What's wrong with a pull request's changes ((status, path) pairs against its base).

    empty_on_base: the branch's own fragment is already on the base, and empty."""
    if branch.startswith("dependabot/"):
        return []
    fragments = [(status, path) for status, path in changed
                 if path.startswith(CHANGES + "/") and path.endswith(".md") and path != f"{CHANGES}/README.md"]
    if branch.startswith("release/"):
        # The fold's shape: CHANGELOG.md takes the entries, the fragments go.
        errors = []
        kept = [path for status, path in fragments if not status.startswith("D")]
        if kept:
            errors.append(f"a release only removes fragments (scripts/changelog.py fold); added or changed: {', '.join(kept)}")
        if len(kept) < len(fragments) and not any(path == CHANGELOG for _, path in changed):
            errors.append(f"fragments are removed but {CHANGELOG} is unchanged: run scripts/changelog.py fold so no entry is lost")
        return errors
    errors = []
    if any(path == CHANGELOG for _, path in changed):
        errors.append(f"{CHANGELOG} is edited: put the entry in {fragment_name(branch)} instead")
    added = [path for status, path in fragments if status.startswith("A")]
    others = [path for status, path in fragments if not status.startswith("A")]
    want = fragment_name(branch)
    if empty_on_base and not any(path == want and status.startswith("D") for status, path in fragments):
        # The name came back after its pull request merged, and its empty fragment waits for the
        # fold: filling it is this pull request's entry, and leaving it is the empty file.
        others = [path for path in others if path != want]
        added = added or [want]
    if others:
        errors.append(f"another pull request's fragment is changed or removed: {', '.join(others)}")
    if added != [want]:
        found = ", ".join(added) or "none"
        errors.append(f"add exactly one fragment, {want} (an empty file when nothing is user-visible); found: {found}")
    return errors


def own_fragment_empty(base: str, branch: str, cwd: str = ROOT) -> bool:
    """Whether the branch's fragment is on the base already, and empty: the name came back after a merge."""
    size = subprocess.run(["git", "cat-file", "-s", f"{base}:{fragment_name(branch)}"], cwd=cwd, capture_output=True, text=True)
    return size.returncode == 0 and size.stdout.strip() == "0"


def fold(root: str = ROOT, release: str | None = None) -> int:
    """Moves every fragment into CHANGELOG.md, newest first. Returns how many entries it added."""
    folder = os.path.join(root, CHANGES)
    names = sorted(n for n in os.listdir(folder) if n.endswith(".md") and n != "README.md") if os.path.isdir(folder) else []

    def added_at(name: str) -> int:
        out = git("log", "--diff-filter=A", "--format=%ct", "-1", "--", f"{CHANGES}/{name}", cwd=root).strip()
        return int(out) if out else 0

    names.sort(key=lambda n: (added_at(n), n), reverse=True)
    entries = []
    for name in names:
        with open(os.path.join(folder, name), encoding="utf-8") as f:
            text = f.read().strip()
        if text:
            entries.append(text)
    path = os.path.join(root, CHANGELOG)
    with open(path, encoding="utf-8") as f:
        log = f.read()
    if UNRELEASED + "\n" not in log:
        raise SystemExit(f"{CHANGELOG} has no '{UNRELEASED}' heading")
    head, rest = log.split(UNRELEASED + "\n", 1)
    rest = rest.lstrip("\n")
    block = "".join(e + "\n" for e in entries)
    if release:
        # The entries and what Unreleased held go under the release; Unreleased starts empty.
        body = f"{UNRELEASED}\n\n## {release}\n\n{block}{rest}"
    else:
        body = f"{UNRELEASED}\n\n{block}{rest}"
    with open(path, "w", encoding="utf-8") as f:
        f.write(head + body)
    for name in names:
        os.remove(os.path.join(folder, name))
    return len(entries)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check")
    c.add_argument("--base", required=True)
    c.add_argument("--branch", required=True)
    f = sub.add_parser("fold")
    f.add_argument("--release")
    args = parser.parse_args(argv)
    if args.cmd == "check":
        out = git("diff", "--name-status", "--no-renames", f"{args.base}...HEAD")
        changed = [tuple(line.split("\t", 1)) for line in out.splitlines() if line]
        errors = check(changed, args.branch, empty_on_base=own_fragment_empty(args.base, args.branch))
        for e in errors:
            print(f"::error::{e}")
        return 1 if errors else 0
    n = fold(release=args.release)
    print(f"{n} entries folded into {CHANGELOG}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
