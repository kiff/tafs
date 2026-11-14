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

`mission` has its own Card, `coach-missions`, at 50 changes a day. There's no fingerprint, since a mission changes no authority.

| Action | Takes | Does |
|---|---|---|
| `read` | nothing | The missions, each with its goal and tafs |
| `create` | `name`, `goal`, `tafs` | Makes the mission, and puts its label on its tafs. A label the account has by that name is used |
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
