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

A Card's totals carry KIFF's own `window` and its words, `window_words: {each, now}`, which
the app shows as they are:

| `window` | `each` ("$300 …") | `now` ("$40 used …") |
|---|---|---|
| `calendar_day` | a day | today |
| `rolling_24h` | in any 24 hours | in the last 24 hours |
| `rolling_1h` | in any hour | in the last hour |
| `lifetime` | in total | so far |

A window KIFF adds later is shown as it is sent, never as a day.

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
(`HomeSplitView.opening`). A tap anywhere on a row picks it, and a tap on the taf
already open counts as a pick too. On an Android tablet, either way up, or any screen 720 dp
wide or more, the Tafs tab shows the list (360 dp, as iOS's sidebar) beside what's open, a taf, Coach
or a trial, and opens on the list's first taf the same way (`MainActivity`, `WIDE_DP`). Overview
is at the foot of the list (`OverviewView`), without who uses each taf yet. In landscape, a
taf's page has the second column where the chat keeps 430 dp beside it (`SideColumn`).
The order settles when the tafs change, when the app comes back, and once more when a
session first reads KIFF's record, so it doesn't move under the owner's finger. The last
list is remembered on the phone, in its order, and shown at the next launch before KIFF
answers, only for the same account: the taf used last leads it from the first frame.

The app shows the list in two parts: **Your tafs**, the ones the person manages, and
**Shared with you**, the ones shared with them to use (`AppModel.tafSections`). Which is
which is KIFF's access answer (`manage`); a taf whose answer hasn't come yet counts as
theirs. Someone with only shared tafs sees just "Shared with you".

## A taf's page

`TafDetailView` opens under a compact header, one row: back, the taf's avatar and name, and
its state. Its tabs come next, and the Card's one line under them. On Android, Share and the
Card line fold away while the page scrolls down and come back on the way up, so the chat has
the screen; the header doesn't list the taf's tools, which the Card tab says in KIFF's words.
It has up to four tabs:

- **Chat** (first, where the taf can chat): [chat.md](chat.md).
- **Activity:** its job, its memory, its runs (the next one, Run now, Pause), its
  latest report, the calls waiting on the owner, and its part of the Record.
- **Card:** its Cards as a stack, as KIFF lists them, each with what it allows, what it
  has used and what's left, and every limit of the front one. An amount KIFF gives no
  currency for says so ("30 000 left today of 30 000 · no currency"), never reading
  like money. A Card scoped to an environment other than production says which; one
  that acts for real carries no label. Connections show only when there are some.
  Calls the vendor refused today still count against the Card's totals, and are listed.
  Below them: its connections ([coach.md](coach.md#connections)), Halt, its Usage, its
  Versions, Revoke and Remove for good.
- **Doors** (where the taf has them, and only for someone who manages it): [doors.md](doors.md).

On a wide screen in landscape, a second column beside the chat or activity shows what
waits for the owner and the Cards. Otherwise one line above shows the front Card and
opens the Card tab.

On Android the page is `views/TafDetailView.kt`, with the same tabs and parts
(`RunsPanel`, `RunReportPanel`, `MemoryPanel`), and no second column yet. Revoke asks KIFF
to revoke each of the taf's Cards (`POST /v1/me/mandates/{id}/revoke`, unsigned, since it
takes authority away) and shows the taf stopped only once KIFF has. Remove for good takes
one confirmation, revokes its Cards, then asks tafs cloud to remove the rest
(`DELETE /v1/tafs/{id}`). A removal left half way is finished on the next refresh, from
the home list's `removing`.

What the person can do on the page follows KIFF's access answer for that taf
(`AppModel.manages`): someone who doesn't manage it sees no Halt, Versions, Revoke,
Doors tab, or line saying who it's shared with. For whoever manages it, that line has a
**Share** button with the system's share mark, beside who uses the taf. On its Cards, "Issued by" names who
issued them to someone who manages the taf, and the account's name to someone who only
uses it (`AppModel.issuer`).

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

On Android the sheet is `views/ReceiptSheet.kt` and the check `kiff/ReceiptCheck.kt`, the
same digest, the same chain bytes as KIFF wrote them, and the same pinned signer as iOS.
Keccak-256 and the secp256k1 key recovery come from Bouncy Castle's lightweight API;
iOS's test receipt and vectors are Android's tests too. A verified receipt whose line
names another payment, amount or answer than the one KIFF signed doesn't check out
(`ReceiptSteps.mismatch`). Each line's payment comes from tafs cloud's call references,
read after each refresh and kept only where its hash is KIFF's (`KiffMapping.naming`).

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
