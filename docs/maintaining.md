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

## Before a release

1. Resolve release-blocking findings or document the evidence that rules them out.
2. Review Unreleased in CHANGELOG.md; include behavior, compatibility changes,
   migration or dependency order, and disclosed security fixes.
3. Tag and describe the exact reviewed commit when making a versioned release.
   Keep build, source version, and deployed revision traceable. Do not label
   unverified work as shipped.
4. Verify the resulting artifact and deployment using authorized test accounts.
   Do not close a rollout issue solely because a PR merged.

Use [SECURITY.md](../SECURITY.md) for private reports and coordinated disclosure.

## Request logs

tafs cloud logs one line per request (`logRequest` in `handler.ts`): `{event: "request", method,
route, status, ms}`, with every id in the route masked as `:id`. It never logs a body, a token, a
query or an id. A check such as "did the phone register for alerts" is read from these lines in the
function's CloudWatch logs.
