"""This repository is public, the whole of it: its code, docs and tests say each rule as itself, never where it came from.

So nothing names a planning thread, its project manager, a dated decision, or a team's own name for its working lanes and reviewers.
Those names are matched by the first 16 hex digits of their SHA-256, so this file doesn't spell them out either. To add
one: printf %s NAME | sha256sum | cut -c1-16. GitHub logins stay only where they do something: the files in KEEP, with this check and its test.

Run from the repository's root: `python3 scripts/public_text.py`. It prints each line it finds and exits 1, else "clean".
`--text` checks what's on stdin instead: a pull request's title, body or review.
"""

import hashlib
import re
import subprocess
import sys

NAMES = set("""
3c32e58c6fc3cf62 20cca48ece26c362 f5068c4b858ead38 fe9c6bd7a66301f4 50077b782480811e be896ac583d76627
afb42e3f1a4bce7e d419916f176e52d3 bd184cdc7aea9ef8 ab63a9b276c86bec 2ad06b22b301f873 3c00d0a9d4c2eb08
018a7f9527b114ab 94c6cdf43ac3f929 4a25962615e31ac6 53dd1be914bd441c f66e74c6b8883a1f 3726111e555e250c
d184eb5cf606a3eb d1cac6ef5a9ddbd1 a74b534355e685fc ea0edce134693c74 cb722454717333e5 6ce2da740ecd217f
""".split())
MONTH = r"(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*"
SAID = re.compile(
    r"kiff/work\b|\bthe P[M]\b|\bP[M]'s\b|\(P[M][ ,)]|\bP[M] [0-9]{1,2}(:[0-9]{2}| " + MONTH + r")"
    r"|\b(review|probe|ask|point|negatives|read) (on|of)( [A-Za-z0-9/_.-]*#[0-9]+)?\)"
    rf"|\bowner(?:'s|,) [0-9]{{1,2}} {MONTH}\b"
    # A status line's own words: a hand-off ("READY" with an arrow) or a board's line ("BOARD:").
    r"|\bREADY\s*(?:→|->)|\bBOARD:"
)
WORD = re.compile(r"(?<![\w-])[a-z0-9]+(?:-[a-z0-9]+)*(?![\w-])")
# Where a login does something: who reviews what, who may merge, and their tests and docs.
KEEP = {
    ".github/CODEOWNERS", ".github/automerge.json", "scripts/test_automerge.py", "scripts/test_codeowners.py",
    "docs/maintaining.md", "scripts/public_text.py", "scripts/test_public_text.py",
}
# Another pull request's changelog fragment can't be changed (scripts/changelog.py check): its wording is fixed when a
# release folds it into CHANGELOG.md, which this check reads.
SKIP_DIRS = ("changes/",)


def named(line):
    """What in a line names a thread, a lane, its manager or a dated rule; None when nothing does."""
    said = SAID.search(line)
    if said:
        return said.group()
    for w in WORD.finditer(line.lower()):
        if hashlib.sha256(w.group().encode()).hexdigest()[:16] in NAMES:
            return w.group()
    return None


def in_text(lines):
    """What a pull request's title, body or review says (read from stdin by --text): each line that names us, numbered."""
    return [f"line {n}: {line.strip()[:160]}" for n, line in enumerate(lines, 1) if named(line)]


def main():
    if sys.argv[1:] == ["--text"]:
        found = in_text(sys.stdin.read().splitlines())
        if found:
            print("This pull request's text names a thread, a lane, its manager or a dated rule; say what changed and why:")
            print("\n".join(found))
            return 1
        print("clean")
        return 0
    files = subprocess.run(["git", "ls-files", "-z"], capture_output=True, check=True).stdout.decode().split("\0")
    found = []
    for path in files:
        if not path or path in KEEP or path.startswith(SKIP_DIRS):
            continue
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except (UnicodeDecodeError, IsADirectoryError, FileNotFoundError):
            continue  # binary, a submodule or gone: no words to read
        for n, line in enumerate(text.splitlines(), 1):
            if named(line):
                found.append(f"{path}:{n}: {line.strip()[:160]}")
    if found:
        print("Public text names a thread, a lane, its manager or a dated rule; say the rule itself:")
        print("\n".join(found))
        return 1
    print("clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
