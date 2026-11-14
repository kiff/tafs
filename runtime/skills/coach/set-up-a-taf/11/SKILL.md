---
name: set-up-a-taf
description: Sets up a new taf from the owner's words: understanding the job, the plan, checking the taf can find its work, the result, and the closing message. Use for a conversation with no taf yet, a recipe, or when the owner asks for a new taf.
---

## When to use

Sets up a new taf from the owner's words: understanding the job, the plan, checking the taf can find its work, the result, and the closing message. Use for a conversation with no taf yet, a recipe, or when the owner asks for a new taf.

## Procedure

- Understand first. Ask what you need to know: what the job is, where the work comes from (where customers ask, where meetings get booked), which systems, what it may change or pay, and what the owner expects. What they
  already told you, don't ask again. Ask what's left together, in one questions card when the answers are choices
  (its text says what they're about, never how many), else one short message. Offer "tafs creates it" where it can. If the job needs an app, search {q} first:
  say if it can't be connected, with a way forward, before other questions. When you ask where the work comes
  from and the owner named no app for it, the first option is that they hand it
  to the taf in its chat; apps from search come after.
  A connection the owner didn't ask for is proposed, never assumed.
- A recurring job: before connecting, propose its cadence, sources and
  volume ("weekday mornings, at most 1 a day"); ask only what you can't decide.
- A taf keeps its own records: when it works from the owner's sheet or list,
  propose it adds and updates its own columns there (written to, replied, do not contact), with a tool that writes to it. Ask only what only the owner
  knows, never how to track it.
- A taf finds things by id, never by name. What it makes and uses again (a
  sheet, a document), it keeps by the id the call gives back, as the guide's
  returns name it (data.spreadsheetId). For what exists, ask the owner for its
  link: a search by name often needs more than the connection gives (a Sheets
  sign-in doesn't search Drive).
- Show your plan as steps (show, type steps, id "plan") once you know it: after
  the first answers, never while you still ask what the job is. Its steps are
  the work ahead, never your message or a question in other words. Keep it current: show "plan" again as each step starts, ends or fails. When the owner answers, approves or signs in, show "plan" first that turn, with that step done. Once shown, its steps stay the same, in the same words: only their
  status changes. If the owner changes what they want, say so before you
  show a new plan, with a new id (such as plan-2). A step the owner declined is skipped, not failed. A step that
  waits on one call names it (call: the tool's name): it can't be shown done
  until that call went through. A step whose call came back refused or failed is
  failed, never done, even when you go on: say in one sentence what went wrong.
  A step that waits for the owner (to approve, sign in or answer) stays active:
  it never shows as failed for waiting.
- Each approval is a new component, with a new id.
  An approval carries only its grant and draft, never a title or words of yours.
  Every card you show has a new id.
- A taf needs a way to find its work, not only a way to act. Before the result,
  check that its tools cover the whole job: find the work, check an item, act on it, and say what it did. It sees its work through a tool that reads, or from the owner, who can tell it in its chat. If the work arrives somewhere you can't connect, say in
  one sentence what the taf won't see, and that the owner can tell it in its
  chat meanwhile, in their own words (who asked, what for, when): it finds the
  item itself with its reads. Never show how with an example holding an id or a
  placeholder for one (a tool's id, an order or ticket number). When none of its
  tools lists the work itself (the requests to act on, not only the things
  they're about), set unscheduled on the result's taf: it works from its chat instead. Never ask
  the owner for a server address, a URL or anything technical to connect. Never
  call a taf ready when it can only act.
- When all is connected, show result: the taf (its agent id, name, job, summary: one short line on what this taf does, in the owner's
  language, never the job's general words, the draft the owner confirmed), one sentence on what was
  set up, and tools: the tools' names in KIFF as planque's status lists them
  (post_reply_k3f9), never their titles or the owner's words for them. Its name
  is one no other taf has (tafs_state): if it's taken, the first free "<name> 2", "<name> 3"…, as tafs would name it; call it that. Decide unscheduled by
  the rule above, for any vendor: a taf whose tools list payments but not the
  requests to refund them is one. Show plan again with every step done or
  skipped. The job is the one you drafted from the owner's words, any job at
  all, never one of a fixed list: the result's taf carries it as kickoff (what
  each run is asked to do), instructions (how it works, as agreed with the owner: what it does with each piece and its never rules, plainly; it keeps the ids it makes and finds things by id, never by name; a refused read stops it; what it tells
  the owner goes to its chat, never to an address they didn't give) and, when
  its tools find its work by themselves, cron (when it runs, as five POSIX cron
  fields in the account's time zone, at most every 15 minutes: "0 9 * * 1-5") and check, the read tool that lists
  its open work: arguments (open ones only, where the tool allows), items (the
  path its catalog's returns name, e.g. data.messages), cursor (a field that only grows: created time, an
  increasing id) and first: "all" when its arguments limit it to open work, "new" when they can't. Each firing reads it without the
  AI: a quiet one costs nothing, and the AI wakes only for work. It reads
  through its Card at every firing (96 a day at every 15 minutes): the read's
  calls a day must allow that. Never a check on a read that doesn't list the
  work: with no such read, unscheduled. None over what its first run makes; schedule in the result, never a dial.
- Size the job first: make the check's read once (its arguments,
  limit 1, the total if given); never hand over a check you haven't seen read.
  Over 50 waiting, or no total: bound the kickoff and instructions (the 20 most
  recent, and the total) and say how many wait.
- A taf with cron: before its result, call taf_estimate (recipe, lang) and give its words as they are just before the result card, never your own figure; on estimate: false, nothing on cost.
- When tafs cloud answers your result with the taf set up, always write one
  closing message, even when nothing more is needed, also when that result was
  refused once and shown again: the taf is set up, what it does now, and how the
  owner gives it work (its chat or schedule). In that turn, show the plan
  with its last steps done first, then end the turn with the closing message as
  text, with no tool call after it: a sentence written before a tool call isn't
  shown to the owner, and a turn that only updates the plan is never the end. A
  component (steps, plan, a notice) never stands in for it. The same goes for an improvement's version and a "[taf … version N live]" line: the plan first, the sentence last.
- An answer with check: its first check couldn't read. Never call the taf
  ready or working: say what failed and fix it with taf_dial.
- If the answer to your result is failed ("[result …] failed: the taf
  wasn't created: …"), no taf exists: say so in one sentence, and never say it
  was created, renamed or started. Its draft is confirmed and unclaimed:
  never submit a second draft for that agent.
  Name taken: show result again, same agent and draft.
  For any other reason, say what failed and ask
  the owner what to do; show result again only once they answer. A draft that's confirmed but not claimed means the taf didn't start.

## Verification

What this skill says is done only when the owner saw it: the card shown, the result or version answered by tafs, the grant's status read. Nothing is done because it was said.
