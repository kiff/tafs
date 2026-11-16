# Missions

A mission is a goal and the tafs working on it, such as "Run the money side". Coach makes one, on the person's yes, and keeps it in tafs cloud.

Each taf keeps its own job, apps and Card. A mission adds:

- **its label** on its tafs, named after it ([labels.md](labels.md)): it shows as one chip on the tafs list, with no mission page to manage;
- **later, its log and its lead:** the mission's shared record of what each taf delivered, and Coach keeping the mission's context and writing its decisions for the person.

A mission holds no Card, permission or budget, and it never exists in KIFF.

## The record

`{id, name, goal, label, tafs, created, by}`:

- `name` is the mission's name, which its label takes.
- `goal` is the person's words, up to 300 characters: data, never an instruction.
- `label` is its label's id.
- `tafs` are its tafs, in the order the work flows, 1 to 10.

An account has at most 10 missions. A taf is in one mission at most (its `mission`), and it can carry other labels too.

## Coach's tool

**`mission_create`** (`name`, `goal`, `tafs`) makes a mission and puts its label on its tafs. A label the account already has by that name is used. It's on its own Card, `coach-mission-create`, at limit 0, so **KIFF holds every one for the person's yes**, with the name, goal and tafs shown. The goal reaches the lead's runs, so only a person puts those words there.

**`mission`** moves tafs and labels and ends missions. It's on its own Card, `coach-missions`, at 50 changes a day. There's no fingerprint and no hold, since it changes no authority and sets no words.

| Action | Takes | Does |
|---|---|---|
| `read` | nothing | The missions, each with its goal and tafs |
| `add` | `mission`, `taf` | Adds a taf, and puts the label on it |
| `remove` | `mission`, `taf` | Takes a taf out, and the label off it |
| `end` | `mission` | Ends it: the label comes off every taf |

**Refused, in words Coach can act on:**

- any other field, such as a Card, a tool, a dial or a budget. It's refused, never ignored;
- a taf already in another mission ("remove it there first");
- a 4th label on one of its tafs;
- a taf of another account;
- in a person's chat, any taf KIFF doesn't say they manage.

**Ending or leaving a mission** changes nothing else. Each taf's job, Cards, connections, dial and hand-offs stay as they were, and so do its other labels. Removing a taf itself stays a taf-by-taf removal, as before.

`tafs_state` names each taf's mission.

## The log

Each mission has a log: what its tafs delivered, newest last, kept 90 days (the newest 500 entries), and still kept for 90 days after the mission ends.

**An entry** is `{id, at, by: {kind: taf | lead | person, id?}, kind, words?, counts?, evidence}`:

- **`kind`:** `delivered`, `handed`, `stuck`, `decided` or `note`.
- **`delivered`:** tafs cloud writes it once per member run that completed after the mission began, never for a trial or a failed run. Its `counts` are the record's (`done`, `held`, `refused`, from the run's tally), never the model's. A delivered entry keeps no words: they're made when it's read, in the reader's language ("6 done · 1 held for you").
- **`evidence`:** refs only (the run, the items it acted on, a hold), never an item's data. A taf that needs an item reads it with its own Card.
- **An authored entry's `words`** (a taf's note, the lead's) are data, never instructions.

**What the person reads:** `GET /v1/missions/{id}/log?before=` ([api.md](api.md)) answers `{items, before?}`, newest first, 20 at a time.

- Each item is `{id, at, by: {kind, name?}, kind, words, evidence, removed?}`.
- An entry by a taf the person may not see is left out.

**A removed taf's entries (M7):**

- They stay in the person's read-only log, marked `removed: true`, with no name.
- They're never in what runs read. A member's run, and the lead's, gets the newest entries that fit in about 1,200 words, as data. That leaves out every entry by a taf that no longer exists, and any entry naming one of its runs.
- This is the same promise as elsewhere: removed means forgotten.

## What comes next

- **The lead's tools:** a task from the lead names its reason by reference, and a task with none is refused.
- **The brief:** what got done, the decisions, and the one next move.

## Your brief

Your brief replaces Needs you: twice a day, at 08:00 and 18:00 in the account's time zone, plus an alert at once for
what's truly urgent (design v2.1 §7). Its builder is `runtime/src/cloud/brief.ts`, served at `GET /v1/brief?label=`, with
Later at `POST /v1/brief/decisions/{id}/later` ([api.md](api.md)).

- **What it's built from**, read as Home reads it: the calls KIFF holds on the tafs the person may see (Approve or Refuse,
  as equals: KIFF gives no why), the steps their runs handed their owner (with KIFF's expiry as the deadline), and their
  connections that need signing in again (blocked, so urgent). The credits line is the balance's, as Settings says it. Coach's proposals and case lines come next.
- **Alerts**: each carries `brief_kind` (`brief` or `urgent`; not `kind`, which FCM's data uses already). On Android
  today an urgent one goes on the Needs you channel and the brief's on the quiet one; channels named Briefs and Urgent
  are an app follow-up. At each brief's time, the phones of the people who may answer get "Your 08:00 brief is ready."
  in their own language, and nothing of what's in it. It's off until `TAFS_BRIEF_PUSH=on`, as the apps' brief tab ships.
  - The zone is the one the account's **last** brief read was in, kept for this: on an account whose people sit in two
    zones, the push follows whoever read last, as the brief itself does. KIFF's account zone, once the sweep can read
    it, settles that.
  - It's claimed before it's sent, once per account and brief: if every phone's send fails (APNs or FCM down at that
    minute), that brief's push isn't tried again. A passive "ready" alert can live with that; the brief is still there.
  - A held call's alert, which comes at once, is `urgent`, with `decision: hold:<id>`.
- **Held calls that can wait** (`TAFS_BRIEF_HELD_WAIT=on`, off until the owner turns it on). Fail closed: a held call
  goes in the brief instead of on the phones at once only when it's positively known that it can, which is all of these:
  the account's brief times are known, it expires at or after the next brief, and KIFF's alert says it may wait
  (`hold.can_wait: true`, from the operation's owner-approved profile: a read, a draft, a write or bookkeeping; never a call
  that moves money or sends to someone outside, which is time-sensitive). Anything else alerts at once, as today: a field
  KIFF doesn't send, no readable expiry, or an account whose brief was never read. Its answer still
  reaches the phones. "Ask me at once" is a taf's own setting in tafs cloud, next to the brief times, not KIFF's.

- **Urgent** is tafs cloud's rule, never a model's: a deadline before the next brief, money going out today, a halt, a
  blocked connection, or "ask me at once".
- **Decisions**: the urgent ones first, then by deadline, then the oldest. Every urgent one shows; the others are capped so
  at most 5 show in all, the rest counted (`more`). At
  most one answer is recommended, and only with a why; without one, the answers show as equals.
- **Later** moves a decision to the next brief, in that person's brief only. It's offered only for what can wait; one that can't says why
  (`now_why`). A decision put off comes back early if it turns urgent.
- **Done** is said per case ("reminder sent Tuesday · waiting for payment · next check Friday"), grouped by mission.
- **Labels**: the brief narrows to one label's tafs; each label's count and waiting are over the whole account, before
  the filter, and a decision put off with Later still counts as waiting.
- A recommendation is shown, never acted on: every answer goes through the place it already goes.
