#!/usr/bin/env python3
"""Merges a pull request that needs no judgment (docs/maintaining.md, "Merging by itself").

A pull request merges by itself only when all of this holds:

  - it is open, not a draft, against main, from a branch of this repository;
  - its author is one of the listed accounts;
  - every file it touches is in one lane's paths (.github/automerge.json), or is the
    one changelog fragment named after its branch;
  - it carries no hold label, and merging isn't on hold for the repository;
  - a listed reviewer other than its author approved its latest commit, and none of
    them has changes requested;
  - every check on its latest commit finished and passed, its lane's own tests among
    them when it touches the app's code, and it can be merged.

Anything else waits for a maintainer, as before. `decide` is the whole rule and reads
nothing; `main` gathers what it needs with `gh` and merges what `decide` allows.

    python3 scripts/automerge.py [--dry-run] [--pr N]
"""
import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.path.join(os.path.dirname(HERE), ".github", "automerge.json")

PASSED = {"success", "skipped", "neutral"}


def fragment_name(branch, folder):
    """The one changelog fragment a branch may add (changes/README.md)."""
    return folder + branch.replace("/", "-") + ".md"


def lane_of(files, branch, config):
    """(lane, whether it touches the app's code, why not). The one lane every file is in."""
    if not files:
        return None, False, "it changes no file"
    own = fragment_name(branch, config["fragments"])
    lanes, code = set(), False
    for name in files:
        if name == own:
            continue
        found = [(lane, p) for lane, paths in config["lanes"].items() for p in paths
                 if name == p or (p.endswith("/") and name.startswith(p))]
        if not found:
            return None, False, f"{name} is outside every lane that merges by itself"
        lanes.add(found[0][0])
        # A folder is the app's code; a single file named in the lane is its page in docs/.
        code = code or found[0][1].endswith("/")
    if not lanes:
        return None, False, "it changes only its changelog fragment"
    if len(lanes) > 1:
        return None, False, "it touches more than one lane: " + ", ".join(sorted(lanes))
    return lanes.pop(), code, ""


def approved(reviews, author, head, reviewers):
    """Whether a listed reviewer other than the author approved the latest commit, with
    no listed reviewer asking for changes. Each reviewer's latest review counts."""
    latest = {}
    for r in reviews:
        who = (r.get("user") or {}).get("login")
        if who in reviewers and who != author and r.get("state") in ("APPROVED", "CHANGES_REQUESTED", "DISMISSED"):
            latest[who] = r
    if any(r["state"] == "CHANGES_REQUESTED" for r in latest.values()):
        return False, "a reviewer asked for changes"
    if not any(r["state"] == "APPROVED" and r.get("commit_id") == head for r in latest.values()):
        return False, "no listed reviewer approved its latest commit"
    return True, ""


def checks_passed(checks, required=()):
    """Every check on the latest commit finished and passed, and each of `required` is
    among them and succeeded: the lane's own tests, which start later than the quick
    checks. A commit with only the quick ones green hasn't been tested yet."""
    if not checks:
        return False, "no check ran on its latest commit"
    for c in checks:
        if c.get("status") != "completed":
            return False, f"{c.get('name')} hasn't finished"
        if c.get("conclusion") not in PASSED:
            return False, f"{c.get('name')} ended as {c.get('conclusion')}"
    for name in required:
        if not any(c.get("name") == name and c.get("conclusion") == "success" for c in checks):
            return False, f"its lane's {name} hasn't run on its latest commit"
    return True, ""


def decide(pr, files, reviews, checks, config, hold=""):
    """(merge, lane, why not). The whole rule, with nothing read from outside."""
    if hold:
        return False, None, f"merging is on hold: {hold}"
    if pr.get("state") != "open" or pr.get("draft"):
        return False, None, "it isn't open and ready for review"
    if (pr.get("base") or {}).get("ref") != "main":
        return False, None, "it isn't against main"
    head = pr.get("head") or {}
    if ((head.get("repo") or {}).get("full_name")) != ((pr.get("base") or {}).get("repo") or {}).get("full_name"):
        return False, None, "its branch is in another repository"
    author = (pr.get("user") or {}).get("login")
    if author not in config["authors"]:
        return False, None, f"{author} isn't a listed author"
    if any(l.get("name") == config["hold_label"] for l in pr.get("labels") or []):
        return False, None, f"it carries the {config['hold_label']} label"
    lane, code, why = lane_of(files, head.get("ref", ""), config)
    if not lane:
        return False, None, why
    ok, why = approved(reviews, author, head.get("sha"), config["reviewers"])
    if not ok:
        return False, lane, why
    ok, why = checks_passed(checks, config["required"][lane] if code else ())
    if not ok:
        return False, lane, why
    if pr.get("mergeable") is not True:
        return False, lane, "it can't be merged as it is (a conflict, or not worked out yet)"
    return True, lane, ""


# What follows talks to GitHub.

def gh(*args):
    out = subprocess.run(["gh", "api", *args], check=True, capture_output=True, text=True).stdout
    return json.loads(out) if out.strip() else None


def pages(path):
    out, page = [], 1
    while True:
        got = gh(f"{path}{'&' if '?' in path else '?'}per_page=100&page={page}")
        items = got.get("check_runs", got) if isinstance(got, dict) else got
        out += items
        if len(items) < 100:
            return out
        page += 1


def eval_running(repo, workflow):
    """Whether the evals are running, queued or waiting in the repository that runs them.
    Not knowing counts as running: nothing merges by itself on a guess."""
    try:
        for status in ("in_progress", "queued", "waiting", "requested", "pending"):
            if gh(f"repos/{repo}/actions/workflows/{workflow}/runs?status={status}&per_page=1").get("total_count"):
                return True
        return False
    except (subprocess.CalledProcessError, ValueError, AttributeError):
        return True


def main(argv):
    p = argparse.ArgumentParser()
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--pr", type=int)
    args = p.parse_args(argv)
    with open(CONFIG, encoding="utf-8") as f:
        config = json.load(f)
    repo = os.environ["GITHUB_REPOSITORY"]
    hold = os.environ.get("AUTOMERGE_HOLD", "").strip()
    # Run by hand to see what would merge, the switch isn't needed: nothing is merged.
    if not hold and not args.dry_run and os.environ.get("AUTOMERGE", "").strip() != "on":
        hold = "it isn't switched on for this repository"
    eval_repo, eval_workflow = os.environ.get("EVAL_REPO", "").strip(), os.environ.get("EVAL_WORKFLOW", "").strip()
    if not hold and eval_repo and eval_workflow and eval_running(eval_repo, eval_workflow):
        hold = "the evals are running, or couldn't be read"
    numbers = [args.pr] if args.pr else [pr["number"] for pr in pages(f"repos/{repo}/pulls?state=open&base=main")]
    for n in numbers:
        pr = gh(f"repos/{repo}/pulls/{n}")
        changed = pages(f"repos/{repo}/pulls/{n}/files")
        # A renamed file leaves a place too: its old name counts.
        files = [f["filename"] for f in changed] + [f["previous_filename"] for f in changed if f.get("previous_filename")]
        reviews = pages(f"repos/{repo}/pulls/{n}/reviews")
        checks = pages(f"repos/{repo}/commits/{pr['head']['sha']}/check-runs")
        ok, lane, why = decide(pr, files, reviews, checks, config, hold)
        if not ok:
            print(f"#{n}: waits for a maintainer: {why}")
            continue
        if args.dry_run:
            print(f"#{n}: would merge ({lane})")
            continue
        # The latest commit is named, so a push that lands meanwhile stops the merge.
        gh("-X", "PUT", f"repos/{repo}/pulls/{n}/merge", "-f", "merge_method=squash", "-f", f"sha={pr['head']['sha']}",
           "-f", f"commit_title={pr['title']} (#{n})")
        print(f"#{n}: merged ({lane})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
