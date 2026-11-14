# Reliable work

**Status: built.** This page is the agreement between tafs cloud and kiff/agents for this
work, and each part below is live: kiff/agents runs and recovers the work, and tafs cloud
keeps the settings and delivers the results. The proof below runs every night.

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
- `dedup_key` is the door's message id, or the firing's key. A door sends it, with `source`, on every
  message (`startDoorRun` in `doors.ts`), and makes the run's note ref from it, so a message sent
  again after an answer lost on the way gets the first run, and its one reply. A second request with the same
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

A taf on a schedule checks its work first (kiff/agents' `check`, `Taf.check`): each firing
reads the taf's work list through one of its apps' read tools, without the model, and a
run starts only when the read lists something new, those items handed to it as data. A
firing that found nothing is `quiet`: it's healthy, and never shown as skipped or failed.
One that couldn't read the app is `failed_check`, and counts toward the taf's health.

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

### The taf's own places

A result place at a connected app, or another taf (`docs/api.md`, the knob), is delivered by the
taf itself or handed over by tafs cloud, and tafs cloud checks what became of it from the run's
record, never from the taf's report (`runtime/src/cloud/delivery.ts`). By the sweep, which walks
every taf whose live version has a place of its own, so a hand-off and a retry happen on its
cadence, read or not; and when the taf's page or a request is read (a manager's read starts a
hand-off or a retry; a viewer's records what it can and starts nothing), each run that ended and
wasn't checked is settled, once:

- **an action at a connected app**: the run's usage record says whether the taf called the
  action and whether the tool took it (the action, not its arguments: a call of that action
  made for another purpose counts too, which the record can't tell apart). Called and taken:
  `sent`. Not called, or refused: the taf
  **retries once itself**, as a task with the result and the action by its id, 10 minutes later,
  with the fixed arguments exactly as the owner approved them, never changed: a place that no
  longer fits (a sheet renamed or gone) makes no call, and Coach proposes the new one as a
  version of the dial, which the owner installs. The
  retry's run is settled the same way, however it ended (one that failed or was stopped
  delivered nothing): taken, `sent`; still not, `not_sent`, with the reason the stream kept
  (`steps.ts`, the failed step's `why`) or the run's own, and **one silent note to the people who
  manage the taf** ("Rae didn't deliver to Google Sheets · Refunds 2026: the sheet was renamed.
  Send it again from Rae's page, or open Coach for help."), none more for that place while it
  stays broken; a later delivery clears it. The request shows **Send again** (the same task, now)
  and Coach's words to open it with (`open_coach`, `coach_words`);
- **another taf**: the "Hand-off" section the taf ended its result with becomes a run of the
  receiving taf, as its work under its own Card (`handed`, with the run), once per run (a dedup
  key), fenced as data with where it came from, never as the owner's words: what reached the
  first taf from outside is in it, and nothing in it is an instruction to the second. The receiver is checked again then: removed, or Coach, is `not_sent`. A result with no hand-off
  section is `not_sent`, with Coach to open and nothing to send again.
  - **The task tells the receiver to look again.** The hand-off says how things stood when the
    sending taf wrote it. Before it changes anything, the receiving taf reads what the hand-off
    names as it is now. If that has changed, or KIFF refuses the change because another taf has
    it, it changes nothing and says why.
  - **A taf with nothing to hand over says so.** It writes "Nothing to hand over" as the
    section's first line, then why. The delivery is `none`: "Nothing to hand to Invoices: none of
    the deals was won." No run starts, nobody is told, and Coach isn't offered. A result with no
    section at all is still `not_sent`: that is a taf not following its instructions.
  - **Unless its record shows it acted.** "Nothing to hand over" from a run whose record shows
    calls that went through (kiff/agents' `done`) isn't believed: the record decides, as with a
    run's count. The receiving taf gets the record's own lines, one a call, its tool and what it
    acted on ("- stripe_close_dispute on T-2002", `recordLines`), never the model's words. What it
    acted on is KIFF's `resource` when KIFF names one, else the call's own id-like arguments, at
    most two ("- close_dispute (dispute: T-2002)"): an argument named `id` or ending in it, or a
    short string with a digit and a letter, never an amount, a message or a note. The
    request's line says so: "Handed to Invoices at 12:00 · handed from its record: it said there
    was nothing to hand over, but its record shows it acted" (`from_record` on the delivery).
  - **Work is handed on twice at most** (`DELIVERY.maxHops`): taf to taf to taf. A run that came
    as a hand-off may hand its own result on once. Where the work has been is read from each
    run's `source` in kiff/agents' record (`chainOf`), never from a taf's words.
    - **A third hand-off is refused and said:** `not_sent`, "Not delivered to Books: this work was
      already handed on twice", with Coach to open. Nothing is dropped silently.
    - **Never to a taf that already had the work,** so nothing circles. Rae hands to Invoices, and
      Invoices' results go to Rae: that stops there, as `none`, "Not handed back to Rae: it already
      had this work". Calm, since two tafs that hand to each other are meant to stop. The taf
      itself counts: the dial refuses a taf as its own place, and a record that names it anyway
      starts no run of it.
    - **When the run before it is no longer kept,** how far the work came isn't known, so it goes
      no further. A read that only fails is tried again later, and nothing is handed on a guess.
  - **How far a result goes is tafs cloud's rule, not the taf's.** The taf's instructions only ask
    for the section.

**What became of a hand-off comes back to the sending taf.** "Handed" only says the receiving run
started. tafs cloud follows that run to its end (`settleHandoffs`), on the sweep and on every read
of the sending taf's page, and writes the outcome on the sending run's delivery. It reads
kiff/agents' record of the run, never either taf's report: the run's state, and its `done` (the
writes that went through) and `sent` (the writes it sent, one whose outcome isn't known included).

| The receiving run | The sending request's delivery says | Who is told |
|---|---|---|
| still works | "Handed to Invoices at 12:00" | nobody |
| is held for the owner (a call KIFF holds) | "Handed to Invoices · waiting for you" | Needs you already has the held call |
| is paused on a connected app's event, or on a card in its chat | "Handed to Invoices at 12:00": it is still at work | nobody |
| ended, and a write of its went through | "Handed to Invoices · done" | nobody |
| ended with `sent: 0` | "Handed to Invoices · nothing was done", with its own first line | nobody; one quiet note at the third in a row for that place |
| ended having written nothing itself, and handed its own result on | "Handed to Invoices · it handed it on to Ledger" | nobody, and it doesn't count toward that note |
| ended with more sent than went through | "Handed to Invoices · it may have been done: check Invoices's page" | nobody |
| ended, on a kiff/agents that doesn't count `sent`, with no write through | "Handed to Invoices · it finished", with its own first line | nobody |
| failed, was stopped, was cut off, is past its retries, or never started within 15 minutes | "Invoices didn't finish what Rae handed it · It failed", as `not_sent` with `send_again` and `needs_you` | the people who manage the sending taf, once |

- **A dead hand-off is the sending taf's Needs you.** The sending request reads `needs_you` with
  that line, never "Done", and it counts in the taf's summary on its page and on the list
  (`deadHandoffs`), for a week or until it's sent again.
- **Send again** (`POST …/deliveries/{key}/send`, the hand-off's `key`) hands the same section once
  more, through the hand-off's own start with a dedup key for that sending. Two taps at once start
  one receiving run. Once it's handed again the delivery is no longer dead, so a later tap is 400.
- **One that died after it had acted, or may have, isn't sent again:** its `done` isn't empty, or
  its `sent` counts a write whose answer was lost. The delivery then has no `send_again`, and says
  "it had already acted" or "it may have already acted", with "check its page before anything is
  sent again". Sending it again could do the same thing twice.
- **Send again for one that never started stops the old run first,** so a run that was only slow
  to start can't work beside the new one.
- **Any read settles the outcome, a viewer's too:** it's an observation, and starts no run. A dead
  hand-off's one note to the managers may come from it.
- **Whether the receiving run handed its result on** is read from the receiving taf's own
  deliveries for that run (`passedOn`). A manager's read or the sweep settles those first, so the
  second hand-off starts then. So a manager's read of Rae's page can start Ledger's run, through
  Invoices: once per run (the `settling` claim), and no deeper than the hop limit. A viewer's read
  starts nothing: the line stays "Handed to Invoices" until one of those has run, for as long as a
  run has to start.
- **A run that was held for the owner and is back at work** reads "Handed to Invoices" again,
  not "waiting for you".
- **The note for a dead hand-off that had acted, or may have,** doesn't say "Send it again": it
  says to check the receiving taf's page first, as the request does.
- **A run that only can't be read** (kiff/agents unreachable) is read again later, never called dead.
- **The receiving request says where its work came from:** `from` is "Handed by Rae", or "Handed by
  another taf" once the sending taf is gone.
- **A write KIFF refused,** such as one on an object another taf holds, didn't go through: with
  nothing else through, that run reads "nothing was done".

The run's report in the thread carries the outcome in one line above the taf's words
(`delivered` on the message, `docs/chat.md`): "Sent to Google Sheets · Refunds 2026 and handed
to Invoices. Not sent to Slack · #ops yet: trying again."

Nothing here moves money: a place is a write, and the taf's Card decides it as any call.

## The taf's view

**Landed:** `work` on `GET /v1/home`'s tafs and `GET /v1/tafs/{id}/work` (`runtime/src/cloud/work.ts`), from the runs' `state` as kiff/agents gives it, in the account's time zone from KIFF. **Send again** too (`sendAgain` in `doors.ts`): a door reply given up on is kept for 30 days, and its delivery says `send_again: true`.

tafs cloud returns one view of a taf for both apps. The apps show it as it is.

- **Words and fields both.** Each state comes with `line`, the words the app shows, with every
  time already written in the account's time zone ("Trying again at 14:20, 2 of 3"). The
  fields (`state`, `try`, `of`, `next_at`, `reason`) are there for sorting and badges only. An
  app never formats a time or builds a line from them.
- **In the list:** each taf in `GET /v1/home` gains `work: {status, line, needs_you}`. That's
  the taf's status (`working`, `retrying`, `needs_you`, `paused` or `idle`), its line, and how
  many of its requests need the owner. The list needs no read per taf. What went wrong last comes
  before the next run, which would read as all's well: its latest firing's check that couldn't read
  (a Run now's too), then its health, then its last run stopped at a cap or failed. Such a line comes
  with `attention: true`, and an app draws it in place of "Working" or the next run.
- **On the taf's page:** `GET /v1/tafs/{id}/work` gives the counts per state, the next run, and
  the recent requests, newest first, each with its `line` and its deliveries.
- **A run is done only when it answered.** kiff/agents ends a run that hit its turn cap or its
  cost cap as COMPLETED, with its `stop` (`turn_cap`, `cost_cap`): it was cut off, so its state is
  `stopped`, and its line says which limit stopped it ("Stopped at 14:20: it reached its limit
  of steps for one run", "Stopped at 14:20: it reached its cost limit for one run"), on the
  request page too, whose last step is "Stopped" with that line. Any other stop that isn't
  `answered`, one tafs doesn't know included, reads "Stopped at 14:20", never "Done". The list's
  line for a scheduled taf whose last run stopped so says the same, with `attention`, before its
  next run; one that works from its chat says so, as before. The push for a run Coach handed a
  taf says it stopped before it finished, and at which limit, never "It finished"
  ([coach.md](coach.md#coachs-own-tools)); so does Coach's line for it.
- **A scheduled time that didn't end in finished work** is in `skipped` on the same view: up to
  5 from the last 7 days, newest first, each `{due, why, words}`. `why` is one of:
  - `missed` or `busy`: the schedule skipped it, because it came more than an hour late or
    while its last run was still working ("Skipped the 09:00 run: the last one was still
    working"). It isn't a failure, because nothing ran;
  - `switching`: skipped while the taf switched versions;
  - `check_failed`: its check of a connected app couldn't read the app;
  - `retrying`: its run didn't finish, and its work goes to the next run;
  - `unfinished`: its run stopped part-way, and what it was working on won't be tried again.

  A skip for a halt or for credits is said elsewhere, and isn't listed.

### Its health

`health: {level, lines, fixes?}` is on the same view and on each taf of the list
(`runtime/src/cloud/health.ts`). `level` is `healthy`, `at_risk` or `failing`, and each line
says what happened and what the owner can do, in their language. It's read from what
kiff/agents records, each time, so a taf never stays failing on old history: only the last 7
days count, of its last 10 runs that ended and its schedule's last 20 times.

| Level | When |
|---|---|
| `failing` | two or more scheduled runs in a row couldn't start; its last two or more checks in a row found only work too big for one run, so nothing ran (that work is set aside, not done); its check couldn't read the app two or more times in a row; a scheduled run stopped part-way and its work was dropped; its last three runs failed |
| `at_risk` | one scheduled run couldn't start, or one check couldn't read the app; its last check found only work too big for one run; its newest scheduled times waited for credits ("Waiting for credits since 09:30"); its last run didn't finish and its work goes to the next run; two or more of its last ten runs failed while its newest didn't work (a run that worked after them puts them behind it, so a fixed taf doesn't read as failing), or were cut short by a limit (its turns, its cost, or its reply running out of room); three or more of its last twenty scheduled times were skipped as late or busy |
| `healthy` | none of these |

- **A run of failures ends at the first time that worked:** a run that started, or a check
  that read the app and found nothing new. An empty queue is healthy, however long it lasts.
- **Skipped work is never healthy:** a check that found only work too big for one run, and a
  scheduled time that waited for credits, started no run. Both show on the taf's `skipped` list
  (`why: too_large | credits`) and in its health, with the way to fix it.
- **`fixes`** are the buttons for the lines that have one, decided here:
  `[{line, kind: coach | credits, words, say?}]`, `line` being the index in `lines`.
  - `coach` opens Coach with `say` in its message box, to narrow what the taf reads, so each piece fits one run.
  - `credits` opens Plan & usage: "Add credits" for someone with `billing.manage`, "Ask an admin for credits" for anyone else.
  - A line with no fix has none, and an app that reads only `lines` sees what it always did.
- **Never counted:** a skip for a halt or a version switch; a run paused for its
  owner; a run its owner stopped; a run already shown as needing them or as retrying; and an
  outcome this code doesn't know.
- **Our own outage is never the taf's:** a run that failed because the AI provider wasn't
  serving us (`code: "ai_unavailable"`), and the scheduled time that started it, count for
  nothing, toward the level or the alert.
- **Said once:** a scheduled run that never started leaves a failed run with no turn behind
  it. Its scheduled time already says it couldn't start, so that run isn't counted again
  among the failed runs.
- **Where it shows today:** when the taf is otherwise idle, `summary.line` (and the list's
  `work.line`) says the first reason, where it would say the next run. A taf that is halted,
  needs its owner, is retrying, working, waiting on an app or paused says that first.
- **When it can't be read** (kiff/agents down, or slower than 1.5 seconds for the list),
  `health` is left out and the taf shows as before.
- **The alert** is in [approvals.md](approvals.md): one for each bad spell, when a taf turns
  failing.
- **A delivery that needs you** is a request whose work is done but whose result didn't reach
  one place after its tries. Its line says where and why. Its action is **Send again**
  (`POST /v1/tafs/{id}/requests/{request}/deliveries/{place}/send`, for someone who manages the
  taf). It sends what the taf already wrote, once, and does no work. It isn't a KIFF action, so
  nothing is signed.
- **The `app` place's alert** is the alert tafs cloud already sends, through APNs and FCM. It has
  `open: "taf"` and `taf_id`, which every door alert now carries, so tapping it opens the taf.

### Its oldest waiting case

`oldest_waiting: {since, words, late}` is on the taf's view and on each taf of the list, only when a case
waits (`runtime/src/cloud/waiting.ts`). It's read each time from what kiff/agents records, with no session:

- **A case waiting for the owner:** a run paused on a call KIFF holds, unanswered, or one past its last try
  (`needs_you`), since it paused.
- **Found work that couldn't start:** work its schedule found but couldn't start (waiting for credits, the
  run before still working, or too late), since the first such scheduled time after the last one that ran or
  was quiet. Work too big for one run isn't waiting, since it was set aside: its health says so.
- **The oldest of these** is the one shown: "Oldest case waiting 5 h".

**The promise.** `respond_within`, how many whole hours a case may wait, is the owner's to set per taf
(`PUT /v1/tafs/{id}/promise {hours: 1–168 | null}`, for someone who manages it). Coach may suggest one from a
recipe; the owner sets it. With a promise, `promise: {hours}` is on the same views, and past it `late` is
true: "Oldest case waiting 5 h, past the 4 h you set". With none, nothing is late and no alert is sent.

**The alert.** The sweep reads each taf that has a promise. When its oldest waiting case passes it, the
phones that answer its holds hear once for that case, in each phone's language: "A case has waited 5 h,
past the 4 h you set." It opens the taf. A halted, paused or removed taf says nothing, and a case that ends
and another that waits later is another alert.

## How it's proven

One end-to-end test (kiff/agents `cmd/e2e`, `crashMidRefund`): Coach sets up a taf that refunds
payments, a message arrives at its door, the invocation stops once the vendor has the refund
and before the run records it, and the door sends the message again. The run carries on from
its last safe step, the refund's outcome is reconciled through KIFF by its operation id and
never sent again, and the customer gets **exactly one refund and exactly one reply**; the second
send starts no second run. It runs in kiff/agents' nightly E2E against its simulated vendor,
and on demand against a vendor's test mode.
