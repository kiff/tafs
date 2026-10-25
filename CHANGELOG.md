# Changelog

User-visible changes and compatibility notes are recorded here. Dates and
version headings refer to releases, not merely to merged pull requests.

## Unreleased

### Added

- Doors: a taf can have an email address of its own. It has none until you turn
  it on in the taf's Doors tab, where a dial picks the door and pressing the taf
  opens or closes it. A paired sender's email works like a chat message, under the
  taf's Cards, and the answer goes back in the same thread; the chat marks both.
  Anyone else knocks: you're told who wrote and what they want, and answer Do it,
  Pair or Ignore. Your own email is paired from the start, and an email counts as
  a paired sender's only when it passes the sender check. Each door takes 20
  emails a day from you and paired senders and 10 from anyone else, then one line
  says how many more were dropped. 20,000 characters an email, text only. tafs
  cloud gains `/v1/tafs/{id}/doors` and the email door's webhook; the home list
  may carry `doors_offered` and each taf's `doors`, conversation messages `door`,
  and the conversation `knocks`.
- Security reporting, contribution and agent guidance, issue and pull-request
  templates, code owners, and dependency-update configuration.
- Halt: one tap stops a taf, or every taf with Halt all,
  until you resume it with Face ID. It shows Halted only once tafs' runtime, KIFF
  and planque all confirm, and Halting… until then. Nothing from before a halt
  runs again after a resume. tafs cloud gains `POST`, `GET` and `DELETE` on
  `/v1/tafs/{id}/halt` and `/v1/halt`.
- AI fair use (#193): chats with your tafs and Setup are included up to half
  your plan's monthly price in AI use each period. Past it, your tafs ask before
  going on, and again at each further full bound; AI use is never billed. tafs
  cloud sends the bound with each chat and gains `GET /v1/usage/ai`.
- Task packs: when your tafs' tasks run out, add a pack (Pico, Vonk, Rayo, Forte
  or Maxi) in the app. A pack's tasks last 12 months; your plan's tasks are used
  first each period, then packs, oldest first. Usage shows the tasks the period
  has, what's used and what's left, the packs you hold, and one line on who bills
  your plan, with Manage when Apple does.
- Solo in the app, through the App Store: a monthly subscription that starts
  with a free week, for an account with no plan billed elsewhere. Every offer
  shows its terms. Restore purchases brings back what this Apple account bought.
- Ask an admin: someone who can't add tasks asks for a pack, with a reason, and
  sees it waiting until an admin approves (buys the pack) or declines it with a
  note; they can withdraw it. tafs cloud tells the phones of people who may add
  tasks, and only theirs.

### Removed

- The monthly ceiling row on Usage: tasks past what the plan includes come from
  packs.

### Changed

- From the device retest of build 755, on an iPad and an iPhone:
  - The tafs list puts the taf waiting on you first, then the one used most
    recently.
  - Alerts are offered once, right after signing in, with a short screen that
    says why, so the first held call reaches you. Needs you still offers them.
  - A sent message moves to the top as soon as you send it, not once the answer
    has rendered. A chat opens at its latest message.
  - An answered hold sits where you answered it: after the taf's "waiting for
    your approval", before what it did next.
  - Stop takes effect at once in the app, and leaves no "Stopped" line.
  - A "Not sent" kept because you wrote too many messages too fast goes once a
    later message goes through.
  - The Card shows its own total and today's extra room apart ("300 US$ a day ·
    +30 US$ extra room today"), not one sum. tafs cloud's home list may carry
    `extra_room` on a rule and on a Card's total.
  - A hold's receipt reads "held for you by the Card" while it waits, not
    "allowed by the Card".
  - Usage on the Card updates as the taf works, not only once Usage is opened.
  - Needs you reads again when a hold's alert comes in before KIFF lists it.
  - Ending a trial goes back to the taf with a cross-fade, and "This taf is
    gone" no longer flashes. Starting a trial beside the sidebar selects it there.
  - Usage, Versions and Halt open wherever their panel is tapped.
  - Forget asks first, and says it can't be undone.
  - Do it on a knock says why it wasn't sent in its own sheet.
  - The ring of a taf with an open door runs along the avatar's edge.
  - Coach makes the new version a card hands over, even when the card has the
    same id as one from an earlier conversation. Before, it showed the earlier
    version and made none.
  - Coach says when extra room ends: when that total resets.
- Coach knows holds, tasks and packs, and halting (#218): why a call is held or
  refused and what the owner can do about it, what counts as a task and what
  happens when tasks run out, and what a halt stops. It no longer says a refused
  call waits for you. They're in its instructions: when they were skills it could
  read on demand, it read none of them in the Eval.
- Dictation uses any language the device dictates in (#208), the owner's first,
  and the switch while dictating goes through those. Before, it knew Spain's
  Spanish and US English only: a device with French dictation, or Spanish from
  Colombia, said Spanish wasn't available. With none on the device, it says how
  to turn dictation on.
- Dictating goes on through pauses (#208): after a pause the next words go after
  what was said, where before they replaced it, and the recognizer ending by
  itself no longer stops dictation. The mic stays in the message bar when the
  box has text, so you can carry on dictating after typing.
- While dictating, a bar under the message box shows your voice as it comes, so
  it's plain that the mic is on, with cancel (the box goes back as it was) and
  stop (what you said stays, unsent).
- The doors no longer show "Couldn't read the doors: cancelled" when their
  screen closes before the read ends.
- A taf that didn't start reaches Coach (#183): when tafs cloud refuses to start
  the taf Coach handed over, Coach hears it once and says plainly that no taf was
  created. When the name was taken, it hands it over again with the same Card under
  a new name; for any other reason, it asks you what to do first. It no longer
  claims a rename or a start that didn't happen, or drafts a second Card. A server
  error or an expired sign-in still offers Try again.
- Tables in a chat are drawn as tables (#211): a header, a line between rows,
  amounts aligned on the right, and a wide table scrolls sideways. VoiceOver reads
  each row with its column names. Before, a table showed as text with its pipes.
- Extra room on a held call (#211): a call over a total the Card keeps across
  calls, such as a day's, which approving once can't pass, now offers **Extra
  room** with Face ID. KIFF gives the Card just the room this call needs, until
  that total resets, and the call goes on; the Card is as it was after that.
  Changing the limit for good stays in Coach mode. The hold card and the Card
  also say how many calls the vendor refused today still counted toward it.
- Every time the app shows is in your account's time zone (#211): holds'
  deadlines, chats, the Record's days, Usage, versions and a taf's next run, as
  KIFF's Card days are. Without an account (sample mode), it's the device's. When
  the account's clock isn't the device's, a hold's deadline and the Record's times
  name the zone ("14:00 CET").
- Words show once while an answer streams (#214): when the chat reloads mid-answer
  (a taf read what you wrote meanwhile, or Setup's conversation after a step), the
  words it already recorded no longer show a second time as the live answer.
- Setup says each thing once and keeps its plan with you (#214): a question's card
  isn't repeated in its message, the plan comes once the job is known and never
  restates a message, a step waiting for you stays in progress instead of failed,
  and the plan moves first once you answer, approve or sign in.
- A taf's answer only grows as it streams (#206): its words stay as written at
  each tool call, and a call KIFF holds shows its card where the call was made,
  after the words before it, and stays there once answered, in your words
  ("Refused by you · …"). tafs cloud's message stream gains a `held` event with
  KIFF's ids for the call.
- A message you send stays at the top of the chat while the answer arrives
  below it, in a taf's chat and in Coach mode (#206): the chat no longer jumps
  to the end when the answer is short.

Earlier history is available in [merged pull requests](https://github.com/kiff/tafs/pulls?q=is%3Apr+is%3Amerged)
and [releases](https://github.com/kiff/tafs/releases). This file does not
retroactively assign release dates or claim that merged work was deployed.
