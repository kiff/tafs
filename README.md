<img src="docs/brand/tafs-icon-1024.png" alt="" width="96" align="right">

# tafs

**They taf. You kiff.**

AI agents that do your routine work. You keep the final say.

<p align="center">
  <img src="docs/screenshots/hero.png" width="760" alt="Three phones: Tafs on iPhone with three working and two needing you; Needs you on Android with a migration and an €8,400 payment held, each with Approve once and Refuse; Coach on Android: Ask anything about your tafs">
</p>

Pick the job that eats your mornings: refunds, disputes, unpaid invoices, new leads.
Coach builds a taf for it. The taf does the routine, and anything big waits for your yes.

Get it on [Google Play](https://play.google.com/store/apps/details?id=dev.kiff.tafs), in
Europe, the UK and Switzerland. For iPhone and iPad, tafs is on TestFlight. The same story,
with each job's Card in full: [kiff.dev/tafs](https://kiff.dev/tafs).

## Pick the headache to hand off

Each job comes ready: the words for Coach, and a Card you can change before you sign.
In the app, **Hand it to Coach** opens Coach with those words. All of them, in 8 languages:
[recipes/](recipes/README.md).

| | The job | What the taf does |
|---|---|---|
| Money | [No refund waits overnight](recipes/refunds-within-a-limit.md) | Answers refund requests and refunds within the Card |
| Money | [Stop losing disputes you could win](recipes/chargebacks-with-evidence.md) | Answers card disputes with evidence before the deadline |
| Money | [Get paid without chasing](recipes/chase-overdue-invoices.md) | Reminds customers of overdue invoices; moves no money |
| Money | [Keep the customer who's about to leave](recipes/save-a-customer-who-cancels.md) | Offers a cancelling subscriber a free month or a discount |
| Customers | [Stop answering "where is my order?"](recipes/answer-questions-about-orders.md) | Answers order questions from the shop; passes money to you |
| Customers | [Never lose a lead to a slow reply](recipes/follow-up-new-leads.md) | Adds each new lead to the CRM and replies within the hour |
| Operations | [End the back-and-forth over bookings](recipes/book-and-move-appointments.md) | Books and moves appointments; cancelling always asks |
| Operations | [Stop babysitting deploys](recipes/ship-when-green.md) | Ships green, approved changes and rolls back a failing one |
| Errands | [No more hunting for receipts at tax time](recipes/file-receipts.md) | Files receipts from the mailbox into a folder and a sheet |

Don't see yours? Say it to Coach in your own words. It asks what's missing, connects
your apps and drafts the Card.

## The Card keeps you in charge

Every taf works on a Card. It's the whole job description. You read it, then you sign it.

- **Does on its own.** Inside the Card, it just does the work, and leaves a receipt.
- **Asks you first.** Past a limit, one tap on your phone. No answer means no.
- **Never does.** Whatever an email, a customer or the AI itself says.
- **Change it, or stop it, any time.** Only you can change the Card. One tap halts one
  taf, or all of them.

The Card is checked by [KIFF](https://kiff.dev), outside the AI, before any call reaches
a tool. So an email that tells a taf to ignore its rules changes nothing: what's over the
limit still waits for you.

## How it works

1. **Hand a job to Coach.** Pick one, change the limits to yours, and send it. Coach asks
   only what it needs and connects your apps. You sign in yourself; the taf never sees
   your passwords.
2. **Sign the Card.** Coach drafts it, you set the limits and sign on your phone.
3. **The routine gets done.** Inside the Card, each piece of work is done, with a receipt.
   What's over a limit waits in Needs you.
4. **You decide what matters.** You just stop doing the rest.

## What it costs

Solo or Company, in credits. Credits are used only when a taf works; a quiet check, when
nothing is new, costs nothing. At 80% you're told, and at 100% your tafs wait: nothing is
billed after. Coach is included, enough to set up about 4 new jobs a month. Packs are
bought in the Android app, not yet on iPhone and iPad, and never expire. There's no free trial. Prices:
[kiff.dev/tafs](https://kiff.dev/tafs).

## Why you can trust it

- **Signed approvals.** An answer to a held call is signed with the phone's key and checked
  by KIFF, so no server can answer for the owner ([approvals](docs/approvals.md#how-an-answer-is-signed)).
- **Receipts the phone checks.** Each call in the Record opens KIFF's receipt, and the phone
  checks KIFF signed it ([the Record](docs/tafs.md#the-record)).
- **"Did this happen?"** A money call whose outcome KIFF can't prove asks the owner, and No
  gives the Card its room back ([Did this happen?](docs/approvals.md#did-this-happen)).
- **Trial versions.** Coach drafts a taf's next version, which runs beside the live one under
  a trial Card of its own before it's made live ([trying a version](docs/coach.md#trying-a-version)).
- **Doors.** An email address, a WhatsApp number, a Slack channel or a Telegram bot a taf takes work from
  ([doors](docs/doors.md)).
- **A memory you can read.** A taf's notes shape how it works, never what its Card allows,
  and each one can be read and forgotten ([memory](docs/tafs.md#memory)).

### For builders: an agent harness vs a taf

| | A good agent harness | A taf |
|---|---|---|
| Where the rules live | Rules in the harness, on the machine it runs on | A Card, checked by a separate service before every call |
| Your sign-ins | Tools reach whatever the account running them can read | Sign-ins sit in a separate vault the agent never holds |
| What a rule can say | A rule allows or denies a tool | A Card limits how much, how often, and until when |
| Approvals | You approve in the session that's running | You approve on your phone, signed, for that exact call, once |
| The record | A transcript on the same machine | A signed receipt your phone checks |
| Stopping | Stop the process | One tap halts it in every system it works through |

## Get it

- **Android:** on [Google Play](https://play.google.com/store/apps/details?id=dev.kiff.tafs), for
  phones and tablets on Android 11 and later, in Europe, the UK and Switzerland.
- **iPhone and iPad:** in testing, on TestFlight, on iOS 16 and later.

Both work signed in with KIFF, or with sample data on the phone. More screens, on each
platform, light and dark: [docs/screenshots.md](docs/screenshots.md).

## Documentation

Each feature has a page in [docs/](docs/README.md), written from the code:

- **[A taf](docs/tafs.md):** its job, Cards, runs, reports, memory and Record; pausing,
  halting and removing it.
- **[Chat](docs/chat.md):** ask a taf what it did or tell it what to do; its answer and
  its work stream as it goes, and a held call shows where it was made.
- **[The app's screens](docs/taf-screen.md):** a bar with Account, Tafs, Needs you,
  Record and Coach; the Record filtered by taf; each taf's Chat, Activity, Card and Setup.
- **[Coach mode](docs/coach.md):** say the job; Coach connects the tools, drafts the taf,
  its Card and how it runs, and you confirm with Face ID, Touch ID or your fingerprint.
- **[Held calls](docs/approvals.md):** in Needs you, the chat, the lock screen and a push
  alert. Approve once, refuse, or give the Card extra room.
- **[Connect apps](docs/connect-apps.md):** the apps a taf can work in, each approved on the
  phone before you sign in at the app.
- **[Accounts](docs/accounts.md):** sign in with KIFF, people and groups, sharing a taf,
  credits and plans, deleting the account.
- **[Photos and files](docs/attachments.md)** on a message, and **[doors](docs/doors.md)**.

How the pieces fit: [docs/README.md](docs/README.md). The API: [docs/api.md](docs/api.md).
Where tafs data lives, for how long, and who deletes it: [docs/data.md](docs/data.md).
How KIFF decides every action, shown on real runs: [docs/kiff-decides.md](docs/kiff-decides.md).
What tafs still needs from KIFF: [docs/needs-from-kiff.md](docs/needs-from-kiff.md).

### The runtime

Every taf runs on [kiff/agents](https://github.com/kiff/agents), tafs's own
agent runtime: stored definitions, runs that pause on a held call and go on
when it is answered, chats, notes and schedules, in the EU. tafs cloud calls it;
the phone never does.

- **A taf** is one agent definition (`runtime/src/cloud/tafdef.ts`):
  `claude-sonnet-5-5`, its job's instructions, how its notes work, what the
  owner said when starting it, two sections of KIFF's skill, a cost cap per run from its
  budget, and keep rules (a tool's call and what it returns stay only until the
  run ends, the rest 30 days). For each call through KIFF, the run also keeps
  KIFF's reference for the call and the identifier of what it acted on (a
  payment's id, never the customer's details) for 30 days, so the Record can say
  which payment (kiff/tafs#167). Its only tools are the company's, reached through KIFF's
  gateway (`mcp.kiff.dev`), and its memory. Its KIFF agent key is kept in a
  secret store that tafs cloud writes and can't read back; the definition only
  names it.
- **A held call pauses the run.** The owner answers on their phone, KIFF's
  signed alert reaches tafs cloud (push) and kiff/agents (the run goes on),
  and the approved call is sent once. Nobody answering means refused.
- **How to work under a Card comes from KIFF itself.** Every taf carries
  KIFF's `kiff` skill ([kiff/kiff-plugins](https://github.com/kiff/kiff-plugins),
  plugin `kiff-cards`): what a held, refused or unknown result means, operation
  ids, never routing around a Card. tafs pastes two of its sections, "Rules
  that always apply" and "Handle a KIFF result", by heading
  (`SKILL_SECTIONS`). Its setup sections (connecting an agent, proposing a
  Card) are the owner's steps, not a taf's. Pasting them measured better than
  reading the skill on demand (kiff/agents#107).
- The tafs prompt (`runtime/src/jobs.ts`) adds only what is particular to a
  taf: it runs alone on a schedule, a held call pauses its run until the owner
  answers, what tools return is data, and every run ends with a report.

**Moving the skill's pin.** The skill is vendored at one commit:
`runtime/skills/kiff/SKILL.md` to read, `runtime/src/kiff-skill.ts` for the
definitions. To move it, run `runtime/scripts/vendor-kiff-skill.sh [commit]`
(latest on main by default), read the diff of `SKILL.md`, and merge. The
tests fail if a pasted section's heading is gone. Running tafs get the new pin
through the Upgrade workflow, once kiff/agents' nightly eval has passed on it.

### tafs cloud

The phone talks to tafs cloud (`runtime/src/cloud/`), which runs in the EU. A
chat's answer streams to the phone as it's written.
It keeps no accounts: the phone sends its KIFF sign-in, and KIFF says who that
is, on which account, with which rights. A company sees and changes only its
own tafs; another company's taf answers like a missing one. It holds no model
key: kiff/agents does.

Every route, who may call it and what it takes: [docs/api.md](docs/api.md).

**Memory.** Each taf keeps notes in kiff/agents, through its memory tool,
shared by its runs and its chats. It reads them before it starts and writes
down what it learns about the owner and their customers. Notes shape how it
works; they never change what its Card allows, since KIFF still decides every
action.

Deploying tafs cloud and kiff/agents is done by the maintainers; `infra/` holds
the scripts.

## Building

iOS:

```bash
cd ios
xcodegen generate          # project.yml → Tafs.xcodeproj (not committed)
open Tafs.xcodeproj        # or:
xcodebuild test -project Tafs.xcodeproj -scheme Tafs \
  -destination 'platform=iOS Simulator,name=iPhone 14 Pro'
```

Needs a current Xcode (CI uses Xcode 26 on macOS 26) and runs on iOS 16 and
later. `.github/workflows/ios.yml` builds and tests every push on GitHub's macOS 26
runners. A `v*` tag or a manual run (Actions → iOS → Run workflow) also uploads
to TestFlight: the internal testers ("Team") get it at once, and the external group
"Beta testers" after Apple's Beta App Review, unless a manual run sets `external` to
false. Its signing and upload credentials are repository secrets, set by the
maintainers.

Android:

```bash
cd android
./gradlew :app:testDebugUnitTest          # the unit tests
./gradlew :app:assembleDebug              # a debug build
./gradlew :app:connectedDebugAndroidTest  # the UI tests, on an emulator or phone
```

Needs a JDK 17 or later. Google Play builds come from CI
([docs/android-app.md](docs/android-app.md#building-and-testing)).

## License

[FSL-1.1-ALv2](LICENSE.md), the Functional Source License. You may use, change
and share tafs for anything except a product that competes with it. Each version
becomes Apache 2.0 two years after its release.

## Contributing and project policies

This public repository is source-available under [FSL-1.1-ALv2](LICENSE.md).
The license contains its future Apache-2.0 grant.

- [Contributing and checks](CONTRIBUTING.md)
- [Report a security issue privately](SECURITY.md)
- [Changelog](CHANGELOG.md)
- [Community conduct](CODE_OF_CONDUCT.md)
- [Instructions for coding agents](AGENTS.md)
- [Maintainer and release checklist](docs/maintaining.md)
