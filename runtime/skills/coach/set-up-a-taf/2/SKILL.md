---
name: set-up-a-taf
description: Sets up a new taf from the owner's words: understanding the job, the plan, checking the taf can find its work, the result, and the closing message. Use for a conversation with no taf yet, a recipe, or when the owner asks for a new taf.
---

## When to use

Sets up a new taf from the owner's words: understanding the job, the plan, checking the taf can find its work, the result, and the closing message. Use for a conversation with no taf yet, a recipe, or when the owner asks for a new taf.

## Procedure

- Understand first. Ask what you need to know: what the job is, where the work
  comes from (where customers ask, where meetings get booked), which systems
  are involved, what it may change or pay, and what the owner expects.
  What they already told you, don't ask again. Ask what's left together, in one
  questions card when the answers are choices; otherwise ask in one short
  message. When you ask where the work comes from and the owner named no app
  for it, the first option is that they hand it to the taf in its chat (in
  their own words, the taf finds the item with its reads); the apps that could
  bring it come after, from search, and one is connected only when the owner
  picks it. A connection the owner didn't ask for is proposed, never assumed.
- Show your plan as steps (show, type steps, id "plan") once you know it: after
  the first answers, never while you still ask what the job is. Its steps are
  the work ahead, never your message or a question in other words. Keep it
  current: show "plan" again as each step starts, ends or fails. When the
  owner answers a card, approves or signs in, show "plan" first that turn,
  with that step done, before anything else. Once
  shown, its steps stay the same, in the same words: only their status changes.
  If the owner changes what they want, say so in a message before you show a
  new plan, with a new id (such as plan-2). A step the owner declined is
  skipped, not failed: failed is for what went wrong. A step that waits on
  one call names it (call: the tool's name): it can't be shown done until that
  call went through, and show refuses it otherwise. A step whose call came
  back refused or failed is failed, never done, even when you go on: say in
  one sentence what went wrong. A step that waits for the owner (to approve,
  sign in or answer) stays active until they do: it
  never shows as failed for waiting.
- Each approval is a new component, with a new id: the owner answers each once.
  An approval carries only its grant and draft, never a title or words of
  yours: what the owner reads as they approve comes from planque and KIFF.
  Every card you show has an id not used before in this conversation (rules-2
  after rules, approval-3 after approval-2), never one an earlier card had.
- A taf needs a way to find its work, not only a way to act.
  Before the result, check that its tools cover the whole job: find the work,
  check an item, act on it, and say what it did. It sees each piece of work
  through a connected tool that reads (the open tickets, the day's bookings), or
  from the owner, who can tell it in its chat. If the work arrives somewhere you can't
  connect in this conversation, don't try: say in one sentence what the taf
  won't see, and that the owner can tell it in its chat meanwhile, in their own
  words (who asked, what for, when): it finds the item itself with its reads.
  Never show how with an example holding an id or a placeholder for one (a
  tool's id, an order or ticket number). When none
  of its tools lists the work itself (the requests to act on, not only the
  things they're about), set unscheduled on the result's
  taf: a schedule would only ask it to work a queue it can't see, so it works
  from its chat instead, and the result's sentence says so. Never ask
  the owner for a server address, a URL or anything technical to connect.
  Never call a taf ready when it can only act.
- When everything is connected, show result: the taf (its agent id, its name,
  its job, summary: one short line on what this taf does, in the owner's
  language, such as "Offers a free month to customers who cancel", never the
  job's general words, the draft the owner confirmed, and unscheduled when it
  works from its chat), one sentence on what was set up,
  and tools: the tools' names in KIFF as planque's status lists them
  (post_reply_k3f9), never their titles or the owner's words for them. Its name is one no other taf has (tafs_state): if it's taken,
  the first free "<name> 2", "<name> 3"…, as tafs would name it; call it that.
  Decide unscheduled by the rule above, for any vendor: a taf whose tools list
  payments but not the requests to refund them is one; its sentence says the
  owner hands it each piece of work in its chat. Show plan again with every step
  done or skipped. The job is the one you drafted from the owner's words, any
  job at all, never one of a fixed list: the result's taf carries it as kickoff
  (what each run is asked to do, in one or two sentences, the first thing it does
  when it starts: "Work the refund queue: every open request since your last
  run"), instructions (how it works, from what you agreed with the owner: what it
  does with each piece of work, what it offers instead, and its never rules, in
  plain sentences; the Card still decides every call) and, when its tools find
  its work by themselves, cron (when it runs, as five POSIX cron fields in the
  account's time zone, at most every 15 minutes, as often as the work comes:
  "*/30 * * * *", "0 9 * * 1-5"); otherwise unscheduled. tafs cloud sets the taf
  up from that draft and answers your result itself.
- When tafs cloud answers your result with the taf set up, always write one
  closing message, even when nothing more is needed, also when that result was
  refused once and shown again: the taf is set up, what it
  does now, and how the owner gives it work (its chat, or its schedule). Never
  end the chat on the result without it. In that turn, show the plan with its
  last steps done first, then end the turn with the closing message as text,
  with no tool call after it: a sentence written before a tool call isn't shown
  to the owner, and a turn that only updates the plan is never the end. A
  component (steps, plan, a notice) never stands in for it. The same goes for an
  improvement's version and a "[taf … version N live]" line: the plan first,
  the sentence last.
- If the answer to your result is failed ("[result …] failed: the taf
  wasn't created: …"), no taf exists: say so in one sentence, and never say it
  was created, renamed or started. Its draft is confirmed already and nothing
  claimed it, so never submit a second draft for that agent.
  When the name was taken, show result again at once with the same agent id
  and draft under a new name. For any other reason, say what failed and ask
  the owner what to do; show result again only once they answer. A draft
  that's confirmed but not claimed means the taf didn't start; it doesn't mean
  the owner's approval failed.

## Verification

What this skill says is done only when the owner saw it: the card shown, the result or version answered by tafs, the grant's status read. Nothing is done because it was said.
