# Maintaining tafs

## Repository controls

Verify these in GitHub settings; documentation files alone do not enforce them:

- Private vulnerability reporting, secret scanning, push protection, Dependabot
  alerts, and Dependabot security updates.
- A main-branch ruleset requiring pull requests, at least one independent
  approval, review of the latest changes, resolved review conversations, and
  the relevant CI checks. Restrict force pushes and branch deletion.
- Read-only default workflow token permissions. Give a job additional access
  only where needed; never expose secrets to untrusted pull-request code.

[GitHub's repository security guide](https://docs.github.com/en/code-security/getting-started/quickstart-for-securing-your-repository)
explains the settings. CODEOWNERS routes reviews; it does not replace a ruleset.
Dependabot opens update PRs; updates still require review and CI. Do not add
blanket auto-merge or workflow approval for external contributions.

## Before merging

Check the final diff, review state, and required CI. Resolve requested changes;
a change after approval may require another review. Check dependency and
workflow changes for changes in permissions or secret access. Keep unrelated
work out of the PR. A change to what a feature does, a route or a limit carries
its update to that feature's page in `docs/`.

For approval, account/session, Card, or Record changes, verify what the owner
sees against what the server accepts and what the vendor actually did. For
agent instructions, preserve the existing eval/ref/rollout gate in kiff/agents;
a green unit test is not a completed rollout.

## Merging by itself

A pull request that needs no judgment merges by itself, so an app's change doesn't wait on a
maintainer (`.github/workflows/automerge.yml`, `scripts/automerge.py`). This isn't blanket
auto-merge: every condition below must hold, and anything else waits for a maintainer, as before.

- **It's open, ready for review, against main, from a branch of this repository,** and its author
  is a listed account (`.github/automerge.json`). A fork's pull request never merges by itself.
- **Every file it touches is in one lane's paths:** `ios/` and `docs/ios-app.md`, or `android/` and
  `docs/android-app.md`. Its own changelog fragment may come with it. One file anywhere else
  (tafs cloud, the shared strings, a workflow, a contract in `docs/`, this rule itself) and it waits.
- **A listed reviewer other than its author approved its latest commit,** and none of them has
  changes requested. A push after the approval needs a new one. Authors and reviewers are listed
  by GitHub login, so "other than its author" means another login.
- **Every check on its latest commit finished and passed,** and GitHub says it can be merged. When
  it touches an app's code, that app's own tests must be among them and have succeeded (`required`
  in `.github/automerge.json`: iOS's `test`; Android's `test` and `ui`). They start later than the
  quick checks, so a commit with only those green hasn't been tested yet, and waits.
- **Nothing holds it:** the `hold-merge` label on the pull request, or the repository's
  `AUTOMERGE_HOLD` variable set to any words (a release being cut, an eval running). With
  `AUTOMERGE_EVAL_REPO` and `AUTOMERGE_EVAL_WORKFLOW` set, nothing merges while that workflow is
  running or queued there. If it can't be read, that counts as running.

It's off until the repository's `AUTOMERGE` variable is `on`. The workflow runs main's copy of the
rule every ten minutes and when a check workflow finishes, never a pull request's copy, and it never
checks out a pull request's code. The merge is a squash, made as the organization's `kiff-automerge`
app (its client id in the `AUTOMERGE_APP_CLIENT_ID` variable, its private key in the
`AUTOMERGE_APP_KEY` secret), with a token that lasts one run and reaches only this repository. A
personal token in `AUTOMERGE_TOKEN` is the fallback while the app isn't set. It isn't the workflow's
own token, because a merge made with that starts no workflow on main, so main's tests wouldn't run on
it. Run by hand, it only says what would merge and why the rest waits.

A pull request that can't be read in a run (a request to GitHub failed, or its answer wasn't what
was expected) is skipped with "couldn't be read, skipped", and the run goes on to the others. So is
one whose merge GitHub refuses (its branch moved meanwhile, or it can't be merged after all), with
"couldn't be merged, skipped" and GitHub's status. Each is tried again on the next run. The run
fails only when every pull request in it failed one of these ways.

Adding a lane, an author or a reviewer is a change to `.github/automerge.json`, which never merges by
itself. The rule's tests break each condition in turn (`scripts/test_automerge.py`).

## Before a release

1. Resolve release-blocking findings or document the evidence that rules them out.
   Set the repository's `AUTOMERGE_HOLD` variable while the release is cut: every merge into
   `ios/` starts a run on main, and the archive shouldn't wait behind one. Clear it after.
2. Fold the pull requests' entries in (`python3 scripts/changelog.py fold
   --release "<version>"`, [changes/README.md](../changes/README.md)), then
   review the release's entries in CHANGELOG.md; include behavior,
   compatibility changes, migration or dependency order, and disclosed
   security fixes.
3. Tag and describe the exact reviewed commit when making a versioned release.
   Keep build, source version, and deployed revision traceable. Do not label
   unverified work as shipped.
4. Verify the resulting artifact and deployment using authorized test accounts.
   Do not close a rollout issue solely because a PR merged.

Use [SECURITY.md](../SECURITY.md) for private reports and coordinated disclosure.

## Actions caches

GitHub keeps 10 GB of a repository's Actions caches, then evicts the least
used, and every run gets slower. Two things keep them small:

- **Android's Gradle cache is saved by one job, on main:** the `test` job in
  `.github/workflows/android.yml`, after the action's own clean-up of stale
  entries. The `ui` and `release` jobs only restore it, and pull requests
  only ever read.
- **`.github/workflows/caches.yml`** runs `scripts/caches.py` weekly. It
  deletes each cache that a newer one of the same family on the same branch
  supersedes, and the caches of pull requests that are no longer open, and it
  logs the caches' count and size before and after. Run it by hand with
  `dry_run` on to see what would go; deleting is safe, since a run that needs
  a cache saves it again.

## Request logs

tafs cloud logs one line per request (`logRequest` in `handler.ts`): `{event: "request", method,
route, status, ms}`, with every id in the route masked as `:id`. It never logs a body, a token, a
query or an id. A check such as "did the phone register for alerts" is read from these lines in the
function's CloudWatch logs.

Each of Coach's tool calls adds one line (`called` in `coach.ts`): `{event: "coach_tool", tool,
outcome, taf?}`. `outcome` is `done`, `done_with_error`, `refused_by_tool`, `refused` (no permit, or
not the account's Coach), `repeat` (a retried operation's kept answer), `busy` or `failed`. `taf` is
the taf's own id, and only when the call named the taf by its id. It never logs the call's arguments
or answer, a name, an address or a vendor's id.
