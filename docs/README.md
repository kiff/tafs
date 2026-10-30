# tafs documentation

How tafs works, feature by feature, as the code has it. Each page names the files it
describes, so you can go from a behaviour to its code and back.

**A change that adds or changes a feature updates its page in the same pull request.**
A page that no longer matches the code is a bug (see [AGENTS.md](../AGENTS.md)).

## The pages

| Page | What it covers |
|---|---|
| [How tafs fits together](#how-tafs-fits-together) | The app, tafs cloud, kiff/agents, KIFF and planque, and who decides what |
| [tafs.md](tafs.md) | A taf: its job, definition, schedule, runs, reports, memory, Record, pausing and removing it |
| [chat.md](chat.md) | A taf's chat: sending, the streamed answer, the work block, held calls in the chat, steering, Stop, chats and archives |
| [coach.md](coach.md) | Coach mode: making a taf, connecting tools, improving a taf, versions, trials, and Coach's own tools |
| [approvals.md](approvals.md) | Held calls: how they reach the owner, how an answer is signed, what happens next, halting |
| [accounts.md](accounts.md) | Signing in, roles, people and groups, sharing a taf, usage, plans and purchases, the time zone, deleting the account |
| [attachments.md](attachments.md) | Photos and files on a message |
| [doors.md](doors.md) | Doors: email and WhatsApp addresses a taf takes work from |
| [api.md](api.md) | tafs cloud's HTTP API, every route |
| [ios-app.md](ios-app.md) | The app's code: its model, screens, services, launch arguments and tests |
| [android-app.md](android-app.md) | The Android app's code: building and testing it, its packages, signing, releases |
| [data.md](data.md) | Where every piece of data lives, for how long, and who deletes it |
| [kiff-decides.md](kiff-decides.md) | How KIFF decides every taf action, with the first held calls on a phone |
| [needs-from-kiff.md](needs-from-kiff.md) | What tafs needs from KIFF, and where each need stands |
| [maintaining.md](maintaining.md) | Repository controls, merging and releasing |

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
