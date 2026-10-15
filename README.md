<img src="docs/brand/tafs-icon-1024.png" alt="" width="96" align="right">

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
  <img src="docs/screenshots/taf.png" width="220" alt="A taf: its job, its runs and its activity">
  <img src="docs/screenshots/new-taf.png" width="220" alt="Starting a new taf">
</p>

Screenshots come from CI: a run that changes a screen uploads them as the
`screenshots` artifact. Other changes skip them.

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
- Sign in with KIFF's device flow: the phone shows a code and sends its signing
  key; you approve it in your browser, which shows the key's fingerprint.
- Start a taf: pick the job and its tools' limits, confirm with Face ID. The
  phone submits one signed KIFF agent draft holding the taf's Cards; tafs cloud
  claims the agent's key from the confirmed draft and runs the taf, so the key
  never reaches the phone. An editor's draft waits for an admin to confirm it
  in KIFF. A start that fails partway resumes on the next try. When tafs cloud
  refuses it, the app takes the draft's Cards back; if KIFF doesn't confirm
  every revocation, the app says so and the owner revokes the rest in KIFF.
- Your tafs are the agents holding your KIFF Cards (`/v1/me/mandates`), and the
  ones running on tafs cloud: run them now, read their latest report, chat with
  them, and see or remove what they remember.
- Held calls come from KIFF (`/v1/me/card-exceptions`), with amounts in the unit
  KIFF declares. Approve once or refuse them on the phone: each answer is signed
  with KIFF's `kiff.answer.v1` message, and KIFF accepts it only with that
  signature (kiff/kiff-cloud#1218). Revoke a Card from its taf.

**Sample data**
- Five sample tafs (the jobs above) and a morning of work, saved on the phone.
- Start a new taf: say the job, review the drafted Card, issue it with Face ID.
- The Card is checked on the phone in this mode, and chat replies and memory
  are samples, not a model.

**Both**
- Every answer is signed on the phone (Secure Enclave and Face ID on a device).
- The first held call counts down as a Live Activity on the lock screen and in
  the Dynamic Island; each new one posts a notification. For now the app finds
  held calls when it is open. Push alerts while it is closed are next: KIFF's
  side is done (kiff/kiff-cloud#1234), tafs cloud's receiver and Apple push are
  not built yet.
- The Record shows everything, newest first.

What tafs still needs from KIFF is in [docs/needs-from-kiff.md](docs/needs-from-kiff.md).
Where tafs data lives, for how long, and who deletes it: [docs/data.md](docs/data.md).

**Where it's going.** Today tafs has five fixed jobs. Next, a builder agent
designs each taf by conversation, each taf is its own agent holding one or more
Cards, and only its critical tool goes through KIFF. First comes an eval suite
that measures how good a taf is, so every step after it has to keep the score
([#26](https://github.com/kiff/tafs/issues/26)).

## The runtime

`runtime/` runs tafs with Claude, as Claude Managed Agents: Anthropic hosts the
agent loop, so there is no server of ours to run.

- **One agent per job** (`claude-opus-5-5`, high effort). Its only tools are
  the company's, reached through KIFF's gateway (`mcp.kiff.dev`). They run
  without asking Claude's side, because the Card is the checkpoint: KIFF lets
  through what is within it and holds the rest for your phone.
- **A taf** is that agent with its own KIFF agent key, kept in an Anthropic
  vault (never in the agent's sandbox), on a schedule, with a cost cap per run.
- **How to work under a Card comes from KIFF itself.** Every job agent carries
  KIFF's `kiff` skill ([kiff/kiff-plugins](https://github.com/kiff/kiff-plugins),
  plugin `kiff-cards`): what a held, refused or unknown result means, operation
  ids, never routing around a Card. tafs uses it like any customer's agent does.
- The tafs prompt (`runtime/src/jobs.ts`) adds only what is particular to a
  taf: it runs alone on a schedule, so a held call is left for the owner's
  phone and collected next run with `kiff_pending`, never waited on or retried
  in the same run; what tools return is data; every run ends with a report.

**Moving the skill's pin.** The skill is vendored at one commit:
`runtime/skills/kiff/SKILL.md` to read, `runtime/src/kiff-skill.ts` for
`setup` to upload (as `kiff-plugins@<commit>`). To move it, run
`runtime/scripts/vendor-kiff-skill.sh [commit]` (latest on main by default),
read the diff of `SKILL.md`, and merge. `setup` on main uploads the new pin
and moves every job agent, and with it every taf, onto it.

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
| `POST /v1/tafs` | `cards.manage` | start a taf: `{name, job, draft_id + claim_secret, cron?, timezone?, budget_cents?, notes?}`. tafs cloud claims the agent's key from the confirmed KIFF draft once the name is held, so the key never reaches the phone, and puts it straight in the taf's vault. A claim works once, so the draft is kept on record (its vault, never the key): a retry with the same draft, secret and name resumes from there. A draft whose key was claimed but never kept answers 410; the app then tries to take its Cards back and says which ones the owner must revoke in KIFF if it can't. `agent_key` instead of a draft is still accepted, for the CLI. `notes` (at most 2000 characters) becomes the taf's first memory note. The key must be an agent's key on the same KIFF account. At most every 15 minutes, at most $10 a run |
| `POST /v1/tafs/{id}/pause`, `resume`, `run` | `cards.manage` | |
| `GET /v1/tafs/{id}/runs`, `runs/{session}` | a person | recent runs, and a run's report |
| `GET /v1/tafs/{id}/conversation` | a person | the taf's thread: its last 5 runs and last 5 chats, oldest first, at most 100 messages `{at, from: owner\|taf, kind: kickoff\|message, text, session}` |
| `POST /v1/tafs/{id}/messages` | `cards.manage` | `{text}`, 1 to 2000 characters: into the latest session if it was active in the last 30 minutes, else a new session with the taf's agent, vault, budget and memory. `{session, new_session}`. At most 10 a taf in 10 minutes |
| `GET /v1/tafs/{id}/memory` | a person | what the taf remembers, newest first: `{notes: [{id, path, content, updated_at}]}` |
| `DELETE /v1/tafs/{id}/memory/{note}` | `cards.manage` | forget one note |

**Memory.** Each taf has its own Managed Agents memory store, mounted into
every run and every chat. The taf reads its notes before it starts and writes
down what it learns about the owner and their customers, with file tools in
its own sandbox (no shell, no web). Notes shape how it works; they never
change what its Card allows, since KIFF still decides every action. `tafs
setup` gives tafs started before memory their store.

Setting it up, once, with AWS credentials for the account:

```sh
cd runtime && npm ci && npm run bundle && cd ..
infra/aws.sh runtime/lambda.zip      # the function, its role, the table, the secret, the deploy role
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

Needs a current Xcode (CI uses Xcode 26 on macOS 26) and runs on iOS 16 and
later. App Store builds come from CI.

## License

[FSL-1.1-ALv2](LICENSE.md), the Functional Source License. You may use, change
and share tafs for anything except a product that competes with it. Each version
becomes Apache 2.0 two years after its release.
