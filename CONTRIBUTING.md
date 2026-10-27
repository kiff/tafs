# Contributing to tafs

Read [README.md](README.md), [AGENTS.md](AGENTS.md), and
[LICENSE.md](LICENSE.md) before changing the project. This is a public,
source-available repository under FSL-1.1-ALv2, with the future Apache-2.0
grant described in the license. Public visibility does not remove its current
use restrictions.

## Discuss and scope the change

Search existing issues and pull requests first. Report ordinary bugs with the
issue template; report vulnerabilities privately through [SECURITY.md](SECURITY.md).
For a new feature, protocol change, or changed authority boundary, explain the
user need and proposed behavior on an issue before building it. Reuse an
existing issue for related work. A focused PR can fix several closely related
problems without creating an issue for each edit.

Keep changes in this project's responsibility.
tafs presents the owner's choices and runs jobs through public service
interfaces; KIFF decides authority, planque holds vendor credentials, and
kiff/agents runs agents.
Document cross-repository prerequisites using public interfaces and public
references that an outside contributor can read.

## Prepare a pull request

1. Work on a feature branch. Do not push directly to main.
2. Use synthetic fixtures. Remove credentials, customer data, personal
   identifiers, internal infrastructure details, and private assistant-session
   URLs from code, screenshots, logs, commits, and issue/PR text.
3. Explain the previous behavior, the resulting behavior, and why it matters.
   Link the relevant issue; use `Closes #N` only when the whole issue is done.
4. Add regression coverage for changed behavior, including relevant failure,
   retry, concurrency, and cross-account cases. For documentation-only edits,
   checking links and configuration syntax is sufficient.
5. Add a short entry under Unreleased in [CHANGELOG.md](CHANGELOG.md) for
   user-visible behavior, security fixes safe to disclose, or compatibility
   changes. Pure refactors and routine documentation corrections need no entry.
6. Update the feature's page in [docs/](docs/README.md) when the change adds or
   changes what a feature does, a route, a limit, or a screen's behavior. A new
   feature gets its page, linked from `docs/README.md`. A page that no longer
   matches the code is a bug.
7. State exactly what was tested and what could not be tested. Describe any
   rollout, migration, or cross-repository dependency.

For runtime changes, use Node 22 and the committed lockfile:

```sh
cd runtime
npm ci
npm run typecheck
npm test
```

For iOS changes, use Xcode, XcodeGen, and an available iOS simulator:

```sh
cd ios
xcodegen generate
xcrun simctl list devices available
xcodebuild test -project Tafs.xcodeproj -scheme Tafs \
  -destination 'platform=iOS Simulator,id=<SIMULATOR_UDID>' \
  CODE_SIGNING_ALLOWED=NO
```

Choose a UDID from the device list; do not run the placeholder literally. If
Xcode is unavailable, report that limitation and require the iOS CI test job
to pass. Do not commit the generated Xcode project. Runtime instruction/model
changes also need the existing kiff/agents eval gate; a passing unit suite
alone does not establish agent behavior.

## Review and release

A maintainer reviews the final revision, with required CI passing. Authors do
not approve their own changes. Resolve requested changes with evidence and
obtain a new review when the implementation changes. Avoid unrelated generated
files, dependency updates, and formatting churn.

A merged PR is not evidence of a deployment, completed rollout, or successful
business operation. Record those separately when relevant. A docs-only PR does
not authorize a release, deployment, or change to customer accounts.

AI-assisted contributions follow the same rules. The person or agent submitting
the PR remains responsible for its accuracy, tests, licensing, and review.
