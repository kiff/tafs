# Reliable work

**Status: being built.** This page is the agreement between tafs cloud and kiff/agents for
this work. Each part says what does it today and what changes. As each part lands, its
section moves into the page of the feature it belongs to.

A taf's work must survive what goes wrong around it:
- **Accepted work isn't lost.** Work from a door or a schedule is stored before anything runs.
- **A crash doesn't end the work.** It continues from its last safe step.
- **Nothing is paid twice.** A step tried again never repeats an action that already happened.
- **Results arrive.** Each one is delivered, and tried again if it fails, separately from the work.

Cards, held calls, halts, cancelling, isolation and budgets don't change. A halt or a cancel
also stops the retries and deliveries that are waiting.

## Who does what

| | Does |
|---|---|
| **kiff/agents** | Runs the work: keeps accepted requests, recovers a crashed run, retries a step, keeps a step's operation id, ignores duplicates, runs one request at a time per door thread, applies a schedule's rules, and keeps each note to tafs cloud until it's taken |
| **tafs cloud** | Owns the settings: a taf's schedules with their rules, and where results go. Sends each result to each place from its own outbox. Builds the taf's view for the apps. Coach proposes changes to these settings |
| **KIFF** | Decides every action, as today. A call sent again with the same operation id gives back its first outcome and is never carried out twice. An outcome that isn't known is never sent again: it is checked first |
| **The apps** | Show what tafs cloud returns, send the owner's changes, sign approvals. They compute no time, retry or state |

## A request

A request is one piece of work for a taf, from one source:

```json
{
  "id": "req_…",
  "taf": "…",
  "source": { "door": "email", "thread": "…", "message_id": "…" },
  "dedup_key": "…",
  "accepted": "2026-10-30T09:00:00Z"
}
```

- `source` is a door message (`door`, `thread`, `message_id`) or a schedule firing
  (`schedule`, `due`).
- `dedup_key` is the door's message id, or the firing's key. A second request with the same
  key is the same request: it's accepted once and starts nothing new.
- A request is stored before it runs. If kiff/agents can't store it, the door or the
  schedule is told so and tries again. It isn't dropped. A door message is tafs cloud's until
  `startRun` takes it: one that didn't start isn't marked seen, so the provider's retry
  starts it.

### Its states

```
accepted → working → done
              │
              ├→ retrying { try, of, next_at, reason } → working …
              │
              └→ needs_you { reason }
paused, cancelled: from any state but done
```

- **retrying:** a step failed in a way that can pass. It's tried again up to **3 times**, after
  **5 minutes**, **20 minutes** and **1 hour**. Then it's `needs_you`, with the reason.
- **needs_you:** the owner has to do something: a held call to answer, or a failure retries
  didn't fix. It says what, in words the app shows as they are.
- kiff/agents sends each change of state on the note tafs cloud already receives when a run
  pauses or ends.

## A crash

Today a run whose Lambda stopped is marked failed after a few minutes. Instead:
- A working run holds a **lease** that ends a minute after its invocation's deadline. A Lambda
  can't outlive its deadline, so the lease ends when the worker does.
- Before a turn's write calls go out, the run stores that turn. That's its **last safe step**.
  Read-only turns aren't stored.
- A run whose lease ran out is picked up again from its last safe step.
- Picking a run up again counts as one of its tries. Past the last try it's `needs_you`.

## Never twice

Each call a run makes sends KIFF an **operation id**, made from the run and the id the model gave the action (or, when it gave none, the model's id for the call). A retry the model makes with the same id is the same operation.
It's the same every time that call is made, after a crash too, because a crashed run is picked up as the same run. A request that arrives twice returns its first run, never a new one. So:
- a step that had already gone through gets its first outcome back from KIFF, and nothing is
  done again;
- a step KIFF never sent goes through once;
- a step whose outcome is **unknown** is never sent again on its own. kiff/agents asks KIFF to
  reconcile it (`POST /v1/operations/{key}/reconcile`), and KIFF asks the tool's own read-only
  status lookup, for tools that have one. Proven to have had no effect, the step may go once
  more, with a new id. Otherwise it's `needs_you`, in KIFF's words.

## One at a time per thread

A message on a door thread that already has a request working waits its turn. It doesn't
start a second chat. It runs when the request before it is done, paused for the owner, or
ended.

## Schedules

A taf's schedule is the one kiff/agents keeps today: five-field cron, an IANA time zone,
the input each run is asked, on or off, and one run per firing even if a firing arrives
twice. Two rules are added:

| Rule | Values | Default |
|---|---|---|
| `missed` | `within_1h`: a time missed by less than an hour still runs once, an older one is skipped. `skip`: every missed time is skipped | `within_1h` |
| `busy` | `skip`: a time that comes while the taf is still working on the last one is skipped | `skip` |

Every firing goes into the schedule's history: run, skipped as missed, or skipped as busy.

## Where results go

**Landed:** the places and their sending, in [doors.md](doors.md#where-results-go). Still to come: kiff/agents keeping the note.

A taf's settings list the places its results go:

| Place | What it sends |
|---|---|
| `door_reply` | The answer, through the door the request came in on, on the same thread |
| `app` | A line in the app, with a notification |
| `owner_email` | An email to the owner |

The work of delivering is split in two:
- **kiff/agents keeps the note.** Each change of a request's state is a note to tafs cloud,
  kept until tafs cloud answers it with a 2xx and tried again on the same waits as a step.
  The note only says which request and run to read back, as notes do today.
- **tafs cloud sends.** It reads the request back and makes one **delivery** per place, kept
  in its own outbox. It already does this for a door's reply. That becomes the `door_reply`
  delivery, and `app` and `owner_email` work the same way:

```json
{ "request": "req_…", "place": "door_reply", "key": "…", "state": "sent", "tries": 1, "next_at": null }
```

- `key` is made from the request and the place. A delivery is claimed before it's sent, so
  two notes for the same request send it once.
- A delivery that fails is tried again on the same 3 tries as a step. Past the last it's
  `needs_you` for that delivery only.
- Trying a delivery again never repeats the work. A refund that went through stays one
  refund while its reply is tried again.

## The taf's view

**Landed:** `work` on `GET /v1/home`'s tafs and `GET /v1/tafs/{id}/work` (`runtime/src/cloud/work.ts`), from the runs' `state` as kiff/agents gives it, in the account's time zone from KIFF. Still to come: **Send again**.

tafs cloud returns one view of a taf for both apps. The apps show it as it is.

- **Words and fields both.** Each state comes with `line`, the words the app shows, with every
  time already written in the account's time zone ("Trying again at 14:20, 2 of 3"). The
  fields (`state`, `try`, `of`, `next_at`, `reason`) are there for sorting and badges only. An
  app never formats a time or builds a line from them.
- **In the list:** each taf in `GET /v1/home` gains `work: {status, line, needs_you}`. That's
  the taf's status (`working`, `retrying`, `needs_you`, `paused` or `idle`), its line, and how
  many of its requests need the owner. The list needs no read per taf.
- **On the taf's page:** `GET /v1/tafs/{id}/work` gives the counts per state, the next run, and
  the recent requests, newest first, each with its `line` and its deliveries.
- **A delivery that needs you** is a request whose work is done but whose result didn't reach
  one place after its tries. Its line says where and why. Its action is **Send again**
  (`POST /v1/tafs/{id}/requests/{request}/deliveries/{place}/send`, for someone who manages the
  taf). It sends what the taf already wrote, once, and does no work. It isn't a KIFF action, so
  nothing is signed.
- **The `app` place's alert** is the alert tafs cloud already sends, through APNs and FCM. It has
  `open: "taf"` and `taf_id`, which every door alert now carries, so tapping it opens the taf.

## How it's proven

One end-to-end test, on a vendor's test mode: Coach sets up a taf that refunds payments,
an email arrives at its door, the Lambda is stopped in the middle of the refund, the run
recovers, and the customer gets **exactly one refund and exactly one reply**.
