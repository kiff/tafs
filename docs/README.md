# tafs documentation

How tafs works, feature by feature, as the code has it. Each page names the files it
describes, so you can go from a behaviour to its code and back.

**A change that adds or changes a feature updates its page in the same pull request.**
A page that no longer matches the code is a bug (see [AGENTS.md](../AGENTS.md)).

## The pages

| Page | What it covers |
|---|---|
| [How tafs fits together](#how-tafs-fits-together) | The app, tafs cloud, kiff/agents, KIFF and planque, and who decides what |
| [runtime.md](runtime.md) | What a taf runs on (kiff/agents), its definition and KIFF's skill, and tafs cloud |
| [tafs.md](tafs.md) | A taf: its job, definition, schedule, runs, reports, memory, Record, pausing and removing it |
| [taf-screen.md](taf-screen.md) | The app's screens: the bar, Record by taf, a taf's four tabs, Setup › Starts when and Results go to, Account › Alerts, dark |
| [chat.md](chat.md) | A taf's chat: sending, the streamed answer, the work block, held calls in the chat, steering, Stop, chats and archives |
| [coach.md](coach.md) | Coach mode: making a taf, connecting tools, improving a taf, versions, trials, and Coach's own tools |
| [approvals.md](approvals.md) | Held calls: how they reach the owner, how an answer is signed, what happens next, halting |
| [allow-from-now-on.md](allow-from-now-on.md) | A held call's third answer: the narrowest Card change that would have let it go, what it would have done, one signature (the contract) |
| [languages.md](languages.md) | The eight languages, the glossary every surface uses, and how the person's language is chosen |
| [store-listing.md](store-listing.md) | The Play Store listing in English and the seven other languages, each reviewed against the glossary |
| [release-notes.md](release-notes.md) | What each Android release says in Play's "What's new", in the eight languages |
| [accounts.md](accounts.md) | Signing in, roles, people and groups, sharing a taf, usage, plans and purchases, the time zone, deleting the account |
| [attachments.md](attachments.md) | Photos and files on a message |
| [coach-drives-the-app.md](coach-drives-the-app.md) | Coach drives the app, for the person running tafs: starting and stopping a drive, what Coach can't do, and what it never sees |
| [drive.md](drive.md) | The drive's contract between tafs cloud and the apps: routes, the stream, requests and results, never confirming, never captured, and how a drive ends |
| [secure-connection.md](secure-connection.md) | The secure sheet for an app's key (its words from tafs cloud, Paste the key, Details, the receipt) and one signature for the key and its Card (the contract) |
| [connect-apps.md](connect-apps.md) | Connect apps, for the person running tafs: connecting, what you approve, what a taf can do, app events, the Slack door vs an action, disconnecting |
| [doors.md](doors.md) | Doors: email, Slack and WhatsApp addresses a taf takes work from |
| [reliability.md](reliability.md) | Reliable work, being built: accepted work kept, crashes recovered, nothing paid twice, results delivered |
| [api.md](api.md) | tafs cloud's HTTP API, every route |
| [ios-app.md](ios-app.md) | The app's code: its model, screens, services, launch arguments and tests |
| [mac-app.md](mac-app.md) | The Mac app (macOS 13 and later): the iPhone and iPad app built with Mac Catalyst, what differs from the iPad, its tests, and what isn't done yet |
| [android-app.md](android-app.md) | The Android app's code: building and testing it, its packages, signing, releases |
| [data.md](data.md) | Where every piece of data lives, for how long, and who deletes it |
| [kiff-decides.md](kiff-decides.md) | How KIFF decides every taf action, with the first held calls on a phone |
| [screenshots.md](screenshots.md) | The apps' screenshots, iPhone, iPad and Android, light and dark, and where each set comes from |
| [needs-from-kiff.md](needs-from-kiff.md) | What tafs needs from KIFF, and where each need stands |
| [maintaining.md](maintaining.md) | Repository controls, merging and releasing |

## Why you can trust it

- **Signed approvals.** An answer to a held call is signed with the phone's key and checked
  by KIFF, so no server can answer for the owner ([approvals](approvals.md#how-an-answer-is-signed)).
- **Receipts the phone checks.** Each call in the Record opens KIFF's receipt, and the phone
  checks KIFF signed it ([the Record](tafs.md#the-record)).
- **"Did this happen?"** A money call whose outcome KIFF can't prove asks the owner, and No
  gives the Card its room back ([Did this happen?](approvals.md#did-this-happen)).
- **Trial versions.** Coach drafts a taf's next version, which runs beside the live one under
  a trial Card of its own before it's made live ([trying a version](coach.md#trying-a-version)).
- **Doors.** An email address, a WhatsApp number, a Slack channel or a Telegram bot a taf takes work from
  ([doors](doors.md)).
- **A memory you can read.** A taf's notes shape how it works, never what its Card allows,
  and each one can be read and forgotten ([memory](tafs.md#memory)).

## How tafs fits together

```
            ┌── the owner's iPhone, iPad (ios/) or Android (android/) ──┐
            │  reads Cards and held calls, signs every answer, never    │
            │  holds an agent key or a vendor token                     │
            └─────┬───────────────────┬────────────────────┬────────────┘
                  │ KIFF session      │ KIFF session       │ signed by the phone
                  ▼                   ▼                    ▼
   tafs cloud (runtime/src/cloud)   KIFF (api.kiff.dev)   planque (planque.kiff.dev)
   the phone's API: tafs, chats,    Cards, held calls,    vendor sign-ins, one grant
   Coach, doors, alerts             receipts, people,     per connection
                  │                 usage and billing           ▲
                  │ its AWS role          ▲                     │
                  ▼                       │ every tool call     │
   kiff/agents: definitions, runs, ──────┴── through KIFF's gateway (mcp.kiff.dev)
   chats, notes, schedules, files
```

- **The phone decides nothing.** In KIFF mode, KIFF decides every action a taf takes
  under its Card. The app shows what KIFF says, and each answer the owner gives is
  signed on the phone and checked by KIFF ([approvals.md](approvals.md)). `CardCheck`
  decides only in sample mode, where nothing is real.
- **tafs cloud keeps no accounts.** Each request carries the person's KIFF session, and
  KIFF says who they are, on which account, with which rights. Another account's taf
  answers like a missing one ([api.md](api.md)).
- **kiff/agents runs every taf and Coach.** tafs cloud calls it with its AWS role. It
  holds the definitions, runs, chats, notes, schedules and attached files, and the
  model key. The phone never calls it.
- **Every tool a taf uses goes through KIFF's gateway.** The company's own tools are
  connected through planque, which keeps the vendor sign-in. Neither the phone nor
  tafs cloud holds a vendor token.

## Two modes

The app runs in one of two modes (`AppModel.Mode`):

- **KIFF**: signed in with KIFF. The tafs are tafs cloud's, the Cards and held calls
  KIFF's, and everything above applies.
- **Sample**: five sample tafs and a morning of work, saved on the phone. The Card is
  checked on the phone, and chat replies and memory are samples, not a model. It is
  labelled as sample wherever it shows. CI's screenshots and UI tests use it.

## Words

| Word | Meaning |
|---|---|
| **Taf** | An AI agent that does one job for the business, on kiff/agents, acting as one KIFF agent |
| **Card** | The limits a taf works inside, kept and enforced by KIFF: what it may do, how much each time, how much a day or week |
| **Held call** | A call KIFF stopped because it was outside the Card's room. It waits for the owner's answer: approve once, refuse, or give the Card extra room |
| **Run** | One piece of a taf's work in kiff/agents: a scheduled run, or one answer to a chat message |
| **Chat** | A conversation with a taf; each message the owner sends is a run in it |
| **Coach** | The account's Setup agent: it makes, connects and changes tafs, in Coach mode |
| **Version** | One definition of a taf: its instructions and its Cards. A new one is tried, then installed |
| **Door** | An email address or WhatsApp number a taf takes work from ([doors.md](doors.md)) |
| **Record** | Every call each taf made, with KIFF's receipt for it |
| **Needs you** | The held calls waiting for the owner |
| **Halt** | Taking every authority from one taf, or all of them, at once |
