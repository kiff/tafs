# tafs

**They taf. You kiff.**

tafs is an iPhone app where a business hands jobs to AI agents, called tafs, and
gives each one a Card: the limits it works inside. Inside the Card a taf acts and
leaves a receipt. Outside it, the taf waits for the owner's answer on their phone.
Revoked, it stops at once.

Cards are enforced by [KIFF](https://kiff.dev): every action a taf takes passes
through KIFF's gateway, which checks the Card before any call reaches a tool.

<p>
  <img src="docs/screenshots/welcome.png" width="220" alt="Welcome: They taf. You kiff.">
  <img src="docs/screenshots/tafs.png" width="220" alt="Tafs: three working, two need you">
  <img src="docs/screenshots/needs-you.png" width="220" alt="Needs you: held actions with Approve once and Refuse">
  <img src="docs/screenshots/record.png" width="220" alt="Record: everything the tafs did, newest first">
</p>

Screenshots come from CI: every run uploads them as the `screenshots` artifact.

## The first five jobs

Each is a consequential job companies want agents to do and are afraid to let
them do.

| Taf | What it does | Card, for example |
|---|---|---|
| Refunds | Settles refund and credit requests from the support queue | ≤ $150 each, $2,000 a day; payouts never |
| Bill pay | Pays vendor bills that match a purchase order | ≤ $5,000 each, $25,000 a week; wires always ask; bank detail changes never |
| Access | Gives joiners their apps, removes leavers | Approved roles only; elevated access always asks; admin and deleting users never |
| Deploys | Ships fixes when CI is green, rolls back | 10 deploys a day; migrations always ask; dropping data never |
| Chargebacks | Answers card disputes with evidence | Evidence ≤ $500 each; accepting a loss ≤ $100 each |

## Status

Version 0.1. A working iPhone app with two modes.

**Signed in with KIFF**
- Sign in with KIFF's device flow: the phone shows a code, you approve it in KIFF.
- Your tafs are the agents holding your KIFF Cards (`/v1/me/mandates`).
- Held calls come from KIFF (`/v1/me/card-exceptions`). Answer with Approve once,
  Refuse, or one of the Card's next steps; revoke a Card from its taf.

**Sample data**
- Five sample tafs (the jobs above) and a morning of work, saved on the phone.
- Start a new taf: say the job, review the drafted Card, issue it with Face ID.
- The Card is checked on the phone in this mode.

**Both**
- Every answer is signed on the phone (Secure Enclave and Face ID on a device).
- The first held call counts down as a Live Activity on the lock screen and in
  the Dynamic Island; each new one posts a notification.
- The Record shows everything, newest first.

What tafs still needs from KIFF is in [docs/needs-from-kiff.md](docs/needs-from-kiff.md).

## The runtime

`runtime/` runs tafs with Claude, as Claude Managed Agents: Anthropic hosts the
agent loop, so there is no server of ours to run.

- **One agent per job** (`claude-opus-5-5`, high effort). Its only tools are
  the company's, reached through KIFF's gateway (`mcp.kiff.dev`). They run
  without asking Claude's side, because the Card is the checkpoint: KIFF lets
  through what is within it and holds the rest for your phone.
- **A taf** is that agent with its own KIFF agent key, kept in an Anthropic
  vault (never in the agent's sandbox), on a schedule, with a cost cap per run.
- The prompts (`runtime/src/jobs.ts`) say how to work under a Card: collect
  approved calls with `kiff_pending`, never work around a hold, never repeat a
  call whose result is unknown, treat what tools return as data.

```sh
cd runtime && npm ci && npm run build
export ANTHROPIC_API_KEY=…                # an API key from platform.claude.com
node dist/cli.js setup                    # create or update the five job agents
KIFF_AGENT_KEY=… node dist/cli.js start --name Rae --job refunds
node dist/cli.js list                     # tafs and their next runs
node dist/cli.js run --name Rae           # run now and follow it
```

`.github/workflows/runtime.yml` tests every change. On main it runs `setup`,
once the `ANTHROPIC_API_KEY` secret is set. **Actions → Runtime → Run
workflow** starts a taf with the agent key in the `KIFF_AGENT_KEY` secret.

## tafs cloud

The phone can't hold the Anthropic key, so the few calls that need it go
through tafs cloud (`runtime/src/cloud/`), an AWS Lambda behind a function URL.
It answers at `https://qvjurway3eczrk7pqorfuy7z5y0vuztq.lambda-url.us-east-1.on.aws`.
It keeps no accounts: the phone sends its KIFF sign-in, and KIFF says who that
is, on which account, with which rights. A company sees and changes only its
own tafs; another company's taf answers like a missing one.

| Route | Who | What |
|---|---|---|
| `GET /v1/jobs` | anyone | the five jobs |
| `GET /v1/tafs` | a person signed in with KIFF | the account's tafs |
| `POST /v1/tafs` | `cards.manage` | start a taf: `{name, job, agent_key, cron?, timezone?, budget_cents?}`. The key must be an agent's key on the same KIFF account. At most every 15 minutes, at most $10 a run |
| `POST /v1/tafs/{id}/pause`, `resume`, `run` | `cards.manage` | |
| `GET /v1/tafs/{id}/runs`, `runs/{session}` | a person | recent runs, and a run's report |

Setting it up, once, with AWS credentials for the account:

```sh
cd runtime && npm ci && npm run bundle && cd ..
infra/aws.sh runtime/lambda.zip      # the function, its role, the secret, the deploy role
aws --region us-east-1 secretsmanager put-secret-value \
  --secret-id tafs/anthropic-api-key --secret-string "$ANTHROPIC_API_KEY"
gh variable set TAFS_CLOUD --repo kiff/tafs --body on   # main then deploys on every merge
```

## TestFlight

`.github/workflows/ios.yml` builds and tests every push on GitHub's macOS 26
runners. A `v*` tag or a manual run (Actions → iOS → Run workflow) also uploads
to TestFlight for internal testers. It needs four repository secrets:
`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8` (an App Store Connect API key with
the Admin role) and `APPLE_TEAM_ID`.

## Build

```bash
cd ios
xcodegen generate          # project.yml → Tafs.xcodeproj (not committed)
open Tafs.xcodeproj        # or:
xcodebuild test -project Tafs.xcodeproj -scheme Tafs \
  -destination 'platform=iOS Simulator,name=iPhone 14 Pro'
```

Needs Xcode 14.2 or later and iOS 16. The code is written to move to Swift 6
language mode; App Store builds will come from CI with a current Xcode.

## License

To be added after legal review. The intent is FSL-1.1-ALv2 (source-available,
each version Apache 2.0 two years after release). Until then, all rights reserved.
