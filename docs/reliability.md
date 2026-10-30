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
| **kiff/agents** | Runs the work: keeps accepted requests, recovers a crashed run, retries a step, keeps a step's operation id, ignores duplicates, runs one request at a time per door thread, and keeps the outbox of deliveries |
| **tafs cloud** | Owns the settings: a taf's schedules with their rules, and where results go. Builds the taf's view for the apps. Coach proposes changes to these settings |
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
  schedule is told so and tries again. It isn't dropped.

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
- A working run holds a **lease** and renews it while it works.
- A run whose lease ran out is picked up again and continues from its **last safe step**: the
  last step whose outcome kiff/agents recorded.
- Picking a run up again counts as one of its tries. Past the last try it's `needs_you`.

## Never twice

Each step that acts sends KIFF an **operation id** made from the request's id and the step.
It's the same every time that step is tried, after a crash too, never a new one. So:
- a step that had already gone through gets its first outcome back from KIFF, and nothing is
  done again;
- a step whose outcome is unknown is checked with the tool's status lookup before anything is
  sent again;
- a step that hadn't gone through goes through once.

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

A taf's settings list the places its results go:

| Place | What it sends |
|---|---|
| `door_reply` | The answer, through the door the request came in on, on the same thread |
| `app` | A line in the app, with a notification |
| `owner_email` | An email to the owner |

Each result becomes one **delivery** per place, kept in an outbox:

```json
{ "request": "req_…", "place": "door_reply", "key": "…", "state": "sent", "tries": 1, "next_at": null }
```

- `key` is made from the request and the place. A delivery sent again with the same key is
  sent once.
- A delivery that fails is tried again on the same 3 tries as a step. Past the last it's
  `needs_you` for that delivery only.
- Trying a delivery again never repeats the work. A refund that went through stays one
  refund while its reply is tried again.

## The taf's view

tafs cloud returns one view of a taf for both apps: its status, the count of requests in each
state, its next run, and its recent requests with their deliveries. Every time, every
"next try at" and every state in it is worked out by tafs cloud. The apps show it as it is.

## How it's proven

One end-to-end test, on a vendor's test mode: Coach sets up a taf that refunds payments,
an email arrives at its door, the Lambda is stopped in the middle of the refund, the run
recovers, and the customer gets **exactly one refund and exactly one reply**.
