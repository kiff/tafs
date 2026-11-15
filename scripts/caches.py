"""The repository's Actions caches, kept to one current copy of each.

GitHub keeps every cache a run saves until the repository's 10 GB is full,
then evicts the least used. Gradle's caches, saved on main by content, piled
up: 46 copies of its transforms and 48 of its build cache (10.2 GB, 14 Nov
2026), and every run got slower.

A cache goes when:
- a newer one of the same family on the same branch exists (superseded);
- or it was saved for a pull request that is no longer open.

A family is a cache's key without its trailing content hash
("gradle-transforms-v2-3f9a…" is "gradle-transforms-v2"). The newest of each
family on each branch stays, so every job still restores. Deleting a cache
is safe: the next run that needs it saves it again.

The workflow (.github/workflows/caches.yml) runs it weekly, and by hand;
`--dry-run` says what would go, and deletes nothing.
"""
import argparse
import json
import os
import re
import subprocess
import sys

# A trailing content hash: a last part of hex or base-64-like characters, long
# enough not to be a word ("v2", "Linux"), with a digit in it, so a long word
# ("dependencies") is never taken for one.
HASH = re.compile(r"[-_](?=[0-9A-Za-z+/=]*[0-9])([0-9A-Za-z+/=]{12,})$")
PULL = re.compile(r"^refs/pull/(\d+)/")


def family(key):
    """The cache's key without its trailing content hash."""
    return HASH.sub("", key)


def decide(caches, open_prs):
    """The caches to delete, each with why, from the repository's caches and its open pull
    requests' numbers. Caches are dicts as the API gives them: id, key, ref, created_at."""
    # A cache listed twice (the API's pages can overlap) counts once.
    caches = list({c["id"]: c for c in caches}.values())
    gone, newest = [], {}
    for c in caches:
        m = PULL.match(c.get("ref", ""))
        if m and int(m.group(1)) not in open_prs:
            gone.append((c, "its pull request is closed"))
            continue
        k = (family(c["key"]), c.get("ref", ""))
        if k not in newest or c["created_at"] > newest[k]["created_at"]:
            newest[k] = c
    kept = {id(c) for c in newest.values()}
    gone_ids = {id(c) for c, _ in gone}
    for c in caches:
        if id(c) not in kept and id(c) not in gone_ids:
            gone.append((c, "superseded by a newer one"))
    return gone


def gb(n):
    return f"{n / 1e9:.2f} GB"


# What follows talks to GitHub.

def gh(*args):
    out = subprocess.run(["gh", "api", *args], check=True, capture_output=True, text=True).stdout
    return json.loads(out) if out.strip() else None


def delete(repo, cache_id):
    """Deletes a cache; one already gone (404) is done, not a failure."""
    r = subprocess.run(["gh", "api", "-X", "DELETE", f"repos/{repo}/actions/caches/{cache_id}"], capture_output=True, text=True)
    if r.returncode != 0 and "404" not in r.stderr + r.stdout:
        raise subprocess.CalledProcessError(r.returncode, r.args, r.stdout, r.stderr)


def all_caches(repo):
    out, page = [], 1
    while True:
        got = gh(f"repos/{repo}/actions/caches?per_page=100&page={page}")["actions_caches"]
        out += got
        if len(got) < 100:
            return out
        page += 1


def open_pulls(repo):
    out, page = set(), 1
    while True:
        got = gh(f"repos/{repo}/pulls?state=open&per_page=100&page={page}")
        out |= {p["number"] for p in got}
        if len(got) < 100:
            return out
        page += 1


def usage(repo):
    u = gh(f"repos/{repo}/actions/cache/usage")
    return u["active_caches_count"], u["active_caches_size_in_bytes"]


def main(argv):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--dry-run", action="store_true", help="say what would go; delete nothing")
    args = p.parse_args(argv)
    repo = os.environ["GITHUB_REPOSITORY"]
    count, size = usage(repo)
    print(f"Before: {count} caches, {gb(size)}.")
    gone = decide(all_caches(repo), open_pulls(repo))
    freed = sum(c["size_in_bytes"] for c, _ in gone)
    for c, why in sorted(gone, key=lambda g: (family(g[0]["key"]), g[0]["created_at"])):
        print(f"{'Would delete' if args.dry_run else 'Delete'} {c['key']} on {c['ref']} ({gb(c['size_in_bytes'])}): {why}.")
        if not args.dry_run:
            delete(repo, c["id"])
    print(f"{len(gone)} caches, {gb(freed)}, {'would go' if args.dry_run else 'deleted'}.")
    if not args.dry_run:
        count, size = usage(repo)
        print(f"After: {count} caches, {gb(size)}. (GitHub's count can lag a few minutes.)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
