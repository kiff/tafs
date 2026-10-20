# AGENTS.md

For AI assistants working on tafs. Read this first.

- tafs is its own product. It uses KIFF the way any customer does: the public API
  (`api.kiff.dev`) and the gateway (`mcp.kiff.dev`). Never a back door into
  kiff-cloud. If tafs needs something KIFF lacks, open an issue on kiff/kiff-cloud.
- The phone never decides. In production KIFF decides every action; `CardCheck`
  only drives the sample mode and explains decisions offline.
- An approval counts only when KIFF verifies its signature against the device's
  key. Never add a path where a server vouches for an owner's answer.
- Voice: plain, short, specific. Tafs, Cards, held, approve once, refuse, revoke.
  Vibrant look, dark ground, one amber accent for "needs you".
- iOS only (SwiftUI, iOS 16+). No Android, no React Native.
- Jobs are few and consequential. Add a job only with evidence it is painful and
  high-stakes; tafs is not a catalogue of integrations.

## Working

```bash
cd ios && xcodegen generate
xcodebuild test -project Tafs.xcodeproj -scheme Tafs -destination 'platform=iOS Simulator,name=iPhone 14 Pro'
```

New behaviour gets tests. Feature branches only; never push to `main`.

## Public contribution and review rules

Read [CONTRIBUTING.md](CONTRIBUTING.md) for scope and checks,
[SECURITY.md](SECURITY.md) for private vulnerability reporting, and
[docs/maintaining.md](docs/maintaining.md) for release checks.

- Follow the user's authorized scope. Audit work does not authorize fixes,
  deployment, merging, or changes to customer accounts. An explicit user
  instruction takes precedence over general workflow guidance here.
- Before posting an issue, comment, PR, or review, verify the authorized
  GitHub identity. Do not impersonate another maintainer. An author cannot
  approve their own PR; request an independent review of the final revision.
- Search existing work before opening an issue or changing shared code. Use
  an isolated branch or worktree and preserve other contributors' edits.
- Public commits, PRs, issues, fixtures, screenshots, and Actions logs must
  contain no live credentials, customer data, private infrastructure details,
  or private assistant-session links. Use synthetic evidence. Report new
  vulnerabilities through the private channel, not a public exploit report.
- State what checks actually ran, their results, and any limitations. Do not
  substitute prompt wording tests for measured agent behavior or a happy-path
  test for approval, failure, retry, concurrency, and account-isolation checks.
- Update Unreleased in [CHANGELOG.md](CHANGELOG.md) for user-visible or
  compatibility changes. Do not claim a deployment, release, or security fix
  is complete solely because code merged.
