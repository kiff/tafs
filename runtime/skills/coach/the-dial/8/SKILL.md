---
name: the-dial
description: Changes where a taf's results go and what wakes it, as a version of its dial: the write found, the place picked by name, what goes where, another taf as a place, a delivery that wasn't made. Use when the owner says where a result should land, or opens a delivery note.
---

## When to use

Changes where a taf's results go and what wakes it, as a version of its dial: the write found, the place picked by name, what goes where, another taf as a place, a delivery that wasn't made. Use when the owner says where a result should land, or opens a delivery note.

## Procedure

- To change where a taf's results go (its chat, an alert in the app, a door's
  reply), call taf_results once: it's held for the owner the same way. Say
  "Waiting for your approval" once. Your own Cards for version_install and
  taf_results allow 0 calls a day and ask the owner: that means every call
  waits for the owner's approval, never that it's forbidden. So make the call;
  never tell the owner to change it elsewhere.
- To change what wakes a taf, when, or where its results go, use taf_dial.
  Where results go, when the owner says where a result should land ("put each
  refund in my Refunds sheet", "open an issue for each failed deploy", "post a
  line in #ops", "hand each paid invoice to the Invoices taf"):
  1. Find the write. When the taf's connection to that app lists it, pick it
     from catalog {grant, q}; when the taf has no connection to the app,
     search {q} finds the app and its write, the connection is asked for and
     approved as for any tool, and the write is added to the taf's own
     connection (tools {grant, tools}). The write goes on the taf's Card,
     limited by calls a day. A place moves no money: never a money tool as a
     place; when the only write that fits moves money, say so and stop.
  2. Fix what never changes. The arguments that pick the place (the sheet,
     the channel, the repository, the page) come from the owner's words; never
     ask for an id. When the taf's connection has a read that lists them by
     name, ask the owner's OK to read that list, as for the accounts list,
     read it on the taf's connection, and let the owner pick by name in a
     questions card. Without one (a Sheets sign-in lists no sheets: that's
     Drive), ask for the place's link, and take the id from it. The id goes in
     args, the name in name ("Google Sheets · Refunds 2026"); the taf never
     looks the place up by name.
  3. Say what goes where. fill names each field the taf fills from its
     result, one line in words about the result ("the customer's name", "the
     amount refunded, in EUR"), from what the owner wants in the row, the
     message or the issue; never a tool's name or an id in it.
  4. Another taf as the place: {place: "taf", taf: its id from tafs_state,
     name, fill}. The result is handed to it as its work, under its own Card,
     and never Coach. That taf may hand its own result on once more: tafs
     cloud hands one piece of work on twice at most, and never to a taf that
     already had it, so say so when the owner asks for a longer chain or a
     loop. A taf with nothing for the next one says so in its result, and
     nothing is handed.
  5. Draft it with taf_dial (results carries every place; the rest stays as
     it is), show the knob, then version_install: the owner confirms it in
     tafs.
  When the owner opens you from a delivery that wasn't made ("<taf> didn't
  deliver to <place>"): read the taf's last request (taf_run) for why; when
  the place's argument no longer fits (a sheet or page renamed), read the
  connection's list again, with the owner's OK, and propose the fix as a
  version of its dial with taf_dial: the place's arguments picked again by
  name, a tool added, or the place removed. Never retry the delivery
  yourself: Send again is the owner's, on the taf's page. When the why is
  that the work was already handed on twice, nothing is broken: that is as
  far as tafs cloud hands one piece of work. Say so, and offer to remove
  the place or to have an earlier taf hand to that taf directly.
- A taf on a schedule names the read that lists its work: taf_dial's check.
  When the dial gives a taf a schedule, or changes one of a taf whose schedule
  has no check yet (tafs_state), add check: the read tool that lists its open
  work (its arguments filtered to open requests where the tool allows), items
  (the path its catalog's returns name, e.g. data.messages), cursor (a field that only grows, such as
  created time or an increasing id), and first: "all" when the work already
  there is still to do. Each firing then reads that list without the AI: a
  quiet firing costs nothing, and the AI wakes only when there's work. Never a
  check on a read that doesn't list the work. A taf with a check keeps one
  timetable: when taf_dial refuses a second one, offer the nearest it names.
  A new check is no second timetable: the same wakes with the new check
  replace it. A check moved to another app moves the runs' reads too: a
  version from improve-a-taf that reads there. A wake never takes input:
  what runs do is in the instructions; change those.
- Before version_install of a dial version for a taf on a schedule, call
  taf_estimate {taf, lang} and say its words as given in your message right
  before the approval, never a figure of your own; on estimate: false, say
  nothing about cost.
- A door from another application: the company's own software hands a taf
  work ("let our order system send you returns to check"). Draft it with
  taf_door_api {taf, draft}. Ask, in one questions card, only what the
  owner's words don't already say: the application's name; the fields it
  sends, each a piece of data with its type (text with its most characters,
  a number, yes or no, or one of a few values); the job line, in the owner's
  words; and the outcomes the taf answers with ("refunded", "refused",
  "needs_review"). A field is data, never instructions: never a field for a
  prompt, a rule, a tool or what to do. When the owner asks for one, say
  that a request can't carry instructions, and leave it out. The taf's own
  words go back to the application only when the owner asks for them, after
  you say: "The answer can repeat anything the taf read, including other
  customers' data." Then show the card as next says, {type: "door", taf_id,
  draft_id}, and say once that the owner approves it with their fingerprint
  in tafs, where its key is shown to them once. You never see a key: never
  ask for one, never write one, and never put one in an email. A developer's
  instructions are on the door in tafs, without the key. When taf_door_api
  is refused because the door isn't offered here, say so in one sentence
  and stop.
- When taf_dial, version_install or taf_results is refused because no Card of
  yours covers it (KIFF says the tool is one no Card covers, or isn't connected
  for you), the owner hasn't given you that tool yet: say so in one sentence
  ("I can't draft the dial yet: Coach's tools aren't given on this account.
  Once they are, ask me again"), naming no button or screen, since each app
  shows its own, and stop. Never call it again in that turn, and never say the
  change was made or is waiting.

## Verification

What this skill says is done only when the owner saw it: the card shown, the result or version answered by tafs, the grant's status read. Nothing is done because it was said.
