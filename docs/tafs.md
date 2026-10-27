# A taf

A taf is an AI agent that does one job for the business. It runs on kiff/agents and
acts as one KIFF agent, which holds its Cards. This page covers what a taf is made of
and what the owner can do with one. Making and changing one is [coach.md](coach.md);
talking to one is [chat.md](chat.md).

Code: `runtime/src/jobs.ts`, `runtime/src/cloud/tafdef.ts`, `runtime/src/cloud/home.ts`,
`runtime/src/cloud/handler.ts` (`start`, `remove`, `view`, `notes`, `forget`, `callRefs`);
in the app, `TafDetailView`, `RecordView`, `HomeView`, `HomeSplitView` and the Runs,
Memory and Stopping sections of `AppModel`.

## Its job

Every taf does one of five jobs (`runtime/src/jobs.ts`):

| Job | What it does | Runs by default |
|---|---|---|
| Refunds | Answers refund requests and issues refunds and credits within the Card | every 30 minutes |
| Bill pay | Matches bills to purchase orders and pays approved bills within the Card | 09:00 on weekdays |
| Access | Grants and removes access for joiners and leavers within the Card | every hour |
| Deploys | Ships approved changes and rolls back failed ones within the Card | every 15 minutes |
| Chargebacks | Answers disputes with evidence, or accepts small ones, within the Card | every 4 hours |

A job gives the taf its instructions, what each scheduled run is asked to do (its
kickoff) and its default schedule. What the owner says about the work when it is made
goes into the taf's instructions too, as their notes (at most 2,000 characters).

A taf made by Coach for work its tools can't find on their own is **unscheduled**: it
has no schedule and no first run, and works from its chat. Pause, resume and Run now
answer 409 for it.

## Its definition

tafs cloud stores one agent definition per taf in kiff/agents
(`runtime/src/cloud/tafdef.ts`):

- **Model:** `claude-opus-5-5`, at medium effort, at most 16,000 tokens a turn. The
  model's thinking streams to the owner as a summary while it works.
- **Instructions:** the shared tafs instructions, the job's, the owner's notes, how its
  notes work, and two sections of KIFF's `kiff` skill ("Rules that always apply" and
  "Handle a KIFF result"). The skill is vendored at one commit; moving it is in the
  [README](../README.md#the-runtime).
- **Tools:** only the company's tools, through KIFF's gateway (`mcp.kiff.dev`), and its
  memory. Its KIFF agent key is in Secrets Manager; the definition only names it.
- **Cost:** a cap per run, $2.00 unless set at start (1 to $10.00).
- **History:** the last 50 finished runs of a chat, and their messages up to 30,000
  tokens; what no longer fits is summarised once.
- **What's kept:** a tool's call and its result only until the run ends; the rest 30
  days. For each call through KIFF, the run also keeps KIFF's reference for the call and
  the value its Card scopes on (a payment's id, never a customer's details), 30 days.
  [data.md](data.md) has every item.

When main changes what a definition is built from, the **Upgrade** workflow
(`.github/workflows/upgrade.yml`, by hand, after kiff/agents' eval passed on the same
sources) rewrites each running taf's definition, from its next run.

## Starting a taf

A taf starts from a KIFF agent draft that holds its Cards:

1. Coach drafts the taf and its Cards; the owner reads the Card and confirms it with
   Face ID or Touch ID ([coach.md](coach.md)). An editor's draft waits for an admin to
   confirm it in KIFF.
2. tafs cloud claims the name first, so two taps or a retry can't start two tafs.
3. It claims the agent's key from the confirmed draft, so the key never reaches the
   phone. A claim works once; the draft is kept on record (never the key), so a retry
   with the same draft resumes.
4. It stores the definition in kiff/agents and, for a scheduled taf, its schedule, in
   the time zone given. A scheduled taf runs once at once; an unscheduled one waits for
   the owner.

`POST /v1/tafs` ([api.md](api.md)) does all of it. A taf runs at most every 15 minutes.
Its name is at most 60 characters and unique on the account; a name Coach chose that's
taken becomes "Name 2", "Name 3"….

## The home list

`GET /v1/home` is the app's list: each taf with its state, colour and Card summary, then
the account's other agents with Cards (made through KIFF's API, not run by tafs), listed
apart. tafs cloud builds it from its records and KIFF's Cards and held calls, read with
the owner's session, so the app combines nothing (`runtime/src/cloud/home.ts`). It lists
only what KIFF says the person may use or oversee.

A taf's state (`stateOf`):

| State | When |
|---|---|
| waiting | a call of it is held for the owner |
| stopped | it has no active Card |
| paused | its schedule is off |
| working | otherwise, for a taf tafs cloud runs |
| idle | an agent with a Card that tafs doesn't run |

The list is ordered with the taf a call waits on first, then by when each was last used.
On an iPad, the app opens on the list's first taf until the owner picks another
(`HomeSplitView.opening`); a tap on the taf already open counts as a pick.
The order settles when the tafs change and when the app comes back, so it doesn't move
under the owner's finger. The last list is remembered on the phone and shown at the next
launch before KIFF answers, only for the same account.

## A taf's page

`TafDetailView` has up to four tabs:

- **Chat** (first, where the taf can chat): [chat.md](chat.md).
- **Activity:** its job, its memory, its runs (the next one, Run now, Pause), its
  latest report, the calls waiting on the owner, and its part of the Record.
- **Card:** its Cards as a stack, as KIFF lists them, each with what it allows, what it
  has used and what's left, live and test ones apart, and every limit of the front one.
  Calls the vendor refused today still count against the Card's totals, and are listed.
  Below them: its connections ([coach.md](coach.md#connections)), Halt, its Usage, its
  Versions, Revoke and Remove for good.
- **Doors** (where the taf has them): [doors.md](doors.md).

On a wide screen in landscape, a second column beside the chat or activity shows what
waits for the owner and the Cards. Otherwise one line above shows the front Card and
opens the Card tab.

What the person can do on the page follows KIFF's access answer for that taf
(`AppModel.manages`): someone who doesn't manage it sees no Halt, Versions, Revoke or
door controls.

## Runs and reports

- **Run now** (`POST /v1/tafs/{id}/run`) runs a scheduled taf outside its schedule.
- **Runs** (`GET /v1/tafs/{id}/runs`) lists its recent job runs; a run's report is the
  agent's last answer. A run waiting on the owner counts as still running.
- Every scheduled run ends with a report, which shows in the taf's thread with the job's
  instruction above it as a small note (a "kickoff").

## Memory

A taf keeps notes in kiff/agents through its memory tool, shared by its runs and chats.
It reads them before it starts and writes down what it learns about the owner and their
customers. Notes shape how it works; they never change what its Card allows.

- `GET /v1/tafs/{id}/memory` shows the shared notes and the person's own, never another
  person's.
- Forgetting a note (`DELETE /v1/tafs/{id}/memory/{note}`) is final: kiff/agents keeps
  no versions. A person forgets their own notes (named `@…`); a shared note takes
  managing the taf.

## The Record

The Record tab (`RecordView`) shows every call each taf made: how much, on what, who
decided and what the tool did. Reads fold into one line a day. Filters: All, Money
moved, Stopped, Waiting.

- Each entry opens KIFF's receipt for it (`GET /v1/receipts/{trace}` on KIFF), checked
  on the phone (`ReceiptCheck`): the signer recovered from the signature over the
  receipt's causal chain must be KIFF's. KIFF signs about 30 seconds after a decision,
  so a new entry can read "not signed yet".
- What a call acted on (a payment's id) comes from tafs cloud's call references
  (`GET /v1/tafs/{id}/calls`), checked against KIFF's signed hash of it.
- An answer the owner gave that KIFF hasn't recorded yet shows as answered until KIFF's
  record has it (`withDecisions`).

## Pausing, revoking and removing

- **Pause / Resume** turns the taf's schedule off or on. It doesn't touch its Cards.
- **Revoke** a Card in KIFF to stop the taf's authority under it.
- **Halt** takes all its authority at once, in KIFF, kiff/agents and planque together
  ([approvals.md](approvals.md#halting)).
- **Remove** (`DELETE /v1/tafs/{id}`), after Face ID: the app revokes its Cards in KIFF
  first, so it can't act whatever happens next. Then kiff/agents withdraws its held
  calls and deletes its schedule, runs, chats and notes; then its key and its name go.
  Nothing else is deleted unless the first step worked, and a second remove finishes
  what the first left. Receipts stay in KIFF.
