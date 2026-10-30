# Changelog

User-visible changes and compatibility notes are recorded here. Dates and
version headings refer to releases, not merely to merged pull requests.

## Unreleased

### Added

- **Android, a connection its vendor signed out:** its taf waits on the owner at the top of the
  home list, its row in planque's words ("Signed out by Stripe · sign in again"), and its Card
  tab offers **Sign in again**. planque's grants are read on every refresh and live update.

- **Android, a taf's page:** a compact header (back, name, state) whose Share and Card line
  fold as it scrolls; a chat opens at its newest message, with a door's knocks at their own
  times; and a paired sender's waiting message reads "Paired · Do it or Ignore this message".
  The app reads again what tafs cloud's live updates name, instead of polling an open chat,
  and shows each door's words. A vendor sign-in reads "Signed in" only once planque says the
  connection is connected, and says it's checking meanwhile. The home list's order holds while
  it's on screen, so a refresh never moves a taf under the owner's finger. A connection its
  vendor signed out (`needs_reconnect`) reads as planque says ("Signed out by Stripe · sign in
  again") and is signed in again from its card, instead of being refused.

- **Android, Coach's screen:** a compact header (back, Coach, a menu with its conversations),
  the plan in tafs cloud's one line that folds away as the thread scrolls, and one calm live
  line from tafs cloud's stream instead of the model's thinking and a list of steps. Alerts
  that tafs cloud didn't take at first are handed over again at the next launch.

- **tafs for Android** (`android/`, Android 11 and later), with what the iPhone app has
  ([docs/android-app.md](docs/android-app.md)):
  - tafs, Needs you and the Record, with KIFF's receipts checked on the phone;
  - a taf's page, with its runs, last report, memory, Revoke and Remove;
  - the live chat, with photos, files and dictation;
  - Coach, with its conversations, given its tools and Card exactly as tafs cloud drafts them.
    A taf Coach hands over is set up by tafs cloud, and its card shows tafs cloud's answer.
    Coach's cards are answered through tafs cloud's route and show the answer the thread
    holds; after an answer, Coach's next words show without leaving the conversation;
  - Doors, Versions and trials, Halt, Usage and asking an admin for tasks;
  - People and groups, Devices, the time zone and Delete account.

  Every approval is signed with an approval key that needs a fingerprint for each
  signature. What didn't go through is said in the app's own words, with what the owner can
  do, never a server's error text, and no tool's id is shown. Dictation works in a variant of
  the phone's language (English in France dictates in British English), and a phone without a
  microphone can install the app. A new fingerprint turns it off until the next sign-in. Signing out ends the
  session at KIFF before the phone shows signed out. Buying through Google Play is built
  but off. On a tablet, the list sits beside what's open, with Overview, and a taf's page
  has its second column in landscape.
- tafs cloud can alert Android phones through Firebase Cloud Messaging. `POST /v1/push` takes
  `platform: "android"` with the phone's Firebase token; an iPhone registers as before.
  Android alerts are data messages the phone builds its notification from, with no call
  parameters; a door's alert carries its taf (`taf_id`) and opens it. They are off until `FCM_PROJECT_ID` and `FCM_SECRET_ID` are set, and an Android
  phone's registration is refused (503) until then.

### Fixed

- A taf's work steps say what it did, in words, both while it works and after: KIFF's title for
  a tool through KIFF ("List your charges"), and what Coach's and the agents' own tools do
  ("Showing you a card"). A step showed the tool's name before ("show", "list_charges_ijkp").
- Sign out of KIFF ends the session at KIFF first. When KIFF doesn't confirm, the app stays
  signed in and says so, instead of showing signed out while the session still works.
- When a change to groups or sharing fails, the app says why. The reason used to be cleared by
  the list being read again.
- A Card's total over all time, or over a rolling hour or 24 hours, no longer reads as "a day".
  The home list gives each total KIFF's own window and its words ("in total", "in any hour").
- A chat that opens with your answers to Setup's questions card is named by those answers,
  as the card shows them ("Test mode · Monday, Tuesday"), not by their raw JSON.
- Coming back to the app no longer shows "Not done: Couldn't reach KIFF" (the 930 test).
  A refresh the app makes by itself tries again on its own. While KIFF can't be reached, a
  quiet line under the account's name says so, with a link to KIFF's status page.
- A held call's card, in the chat, Needs you and the taf's page, says "Nothing is sent until
  you approve." while it waits, and so does its notification. The taf's message about it no
  longer repeats that, and a chat answer no longer ends with a summary of what wasn't done.
- Setup, for a taf whose work arrives somewhere it can't connect (an email inbox), tells you
  to pass each request in the taf's chat in your own words. It used to show an example with a
  charge id to type.
- A taf's answer when KIFF holds a call is one sentence: it waits for your approval and nothing
  was sent, the one limit that holds it, and until when. It used to add what was left on the
  payment and the other limits.
- A Card's amount with no known currency (#309) says so: "30 000 left today of 30 000 ·
  no currency", and "≤ 5 000 (no currency) each". It read like money, though the number
  is the vendor's own, often in cents.
- The iPad's tafs list (the 901 test): a tap anywhere on a taf, its icon and name
  included, opens it. Only the empty end of the row did.
- Coach (the 870 test): a step whose call came back refused or failed shows as failed in
  its plan, never done, and Coach says what went wrong. Before, a hand-off the tool refused
  showed as done. Its rules say a read tool's call count as its Card will have it, where
  they said "no limit" and the Card then counted the reads.
- Coach mode (the 870 test) works like a taf's chat. The card you answered goes to the top
  and stays, with Coach's work and answer under it. The work block sits under what started
  it and folds in place, each answer lists only its own steps, and folded steps read in
  Coach's words, not "Asking KIFF to run planque".
- A taf's page (the 870 test): Share uses the system's share mark, larger, beside who uses
  the taf. A Card that acts for real no longer says "Live", which read as live money beside
  a test-mode connection; a Card scoped to another environment still names it. Someone
  with no connections no longer sees an empty Connections box.
- The tafs list sets apart the tafs shared with you, under "Shared with you" (#306).
- Overview (the 870 test): each heading sits over its column, the Card reads as on the taf's
  page ("300 US$ left today of 300 US$", where it read "0 of 30000 today"), and who uses a
  taf shows only to whoever manages it.
- Refunds: the job's instructions say to look up the payment and read its result before
  asking for a refund (the 859 and 870 tests). The taf twice sent the look-up and the
  refund together, then said it had checked the payment; kiff/agents now refuses a
  refund sent beside a read, and this keeps the taf from spending a turn on it.
- Chat (#249): a run that fails after a call went through names what went through and
  how much moved, for example "Refunds stopped part-way, after this went through: create
  refund on ch_3001, US$5.", from the run's own record and KIFF's Record. Before, it only
  said it stopped part-way, so the owner had to check the Record to learn the money had
  moved.
- Chat: a call kiff/agents refused back unmade, because the taf sent an act beside a read,
  shows as "not made · read first", not as a failed step. Made again, it's one row
  with its tries. tafs cloud's `step` event may say `not_made`, and a message's `work`
  steps may carry `not_made`. A run's work is split where kiff/agents marks a turn
  `resumed`, the first after a pause, rather than guessed from the calls it waited on.
- Launch (the 870 test): the taf used last leads the list from the first frame, and the iPad
  opens on it. The list's order is settled once KIFF's record is read, and kept with the
  remembered list, so the app no longer opens on the first taf made and then jumps.
- Chat (the 859 test): when a run ends, the view stays on the pinned message, even as tafs
  cloud's copy takes the place of the one shown while it worked. If the message is gone, the
  chat goes to the latest rather than blank. A step the stream sends again keeps its status,
  so a finished step never shows as pending. On iPad, the app opens on the first taf in the
  list, selected, until the owner picks another, and a tap on the open taf counts as a pick.
- Chat (the 859 test): a run that paused on a held call and went on once the owner answered
  shows its work in parts. The answer written before the hold keeps what was done before it,
  and the time it took, not the time spent waiting for the owner. The work after the approval
  shows on its own, and the steps from before aren't listed again as new.
- Chat (the 845 test): a sent message goes to the top at once and stays there while the taf
  thinks, works and answers. The work block sits under it, with the answer streaming below,
  and folds in place to "Worked for" when the run ends; a run's work shows once, on its
  first answer. Coming back from another app shows the chat, not a blank screen. On iPad,
  the app opens on the taf used last. The sample taf now streams its answer like a real
  one, and a UI test pins all of this on a simulator.

### Added

- Deleting your account finishes even if the app closes partway: you confirm once, and tafs
  cloud deletes the account in tafs, its connections and KIFF, trying again on its own until
  it's done. It can't be undone.
- A card that waits for your answer (confirm, questions, approval, sign in) gets it as data:
  `POST /v1/tafs/{id}/components/{component}/answer`, and the thread shows each card's
  answer. Your choice reaches the taf as you made it, never as a line of chat text.
- Settings, **Devices**: the devices signed in as you on the account, when each was last
  used, and Sign out for any but this one, behind Face ID.
- Settings, **Notifications & haptics** (the 901 test): **Sound** off makes this device's
  alerts arrive quietly, in Notification Center and Needs you, with no sound or banner;
  **Haptics** off stops the app's taps. Each is for this device only. tafs cloud's
  `POST /v1/push` takes `sound`.
- Accounts (the 870 test, kiff/tafs#294): a person who uses a shared taf is offered only what
  their role allows. No Approve or Refuse without the right to answer held calls (a line says
  who answers instead), no Delete account without that right, and no Doors tab or share line
  on a taf they don't manage. "You" marks only their own door key, and the Card says the
  account issued it. The account's name shows next to Settings, and Settings has Switch account.
- Usage: after an account moves from Solo in the App Store to Team, which KIFF bills,
  the usage screen says Solo still renews with Apple on its date, with a Cancel Solo
  button that opens Apple's own subscriptions page. The usage read's `billing` may
  carry `apple_solo_until`.
- Chat: while a taf or Coach works, the chat shows the step in progress, one line
  of what it's thinking (once it has thought for a few seconds, changing at a pace
  you can read), and the steps done under it. A step tried again shows once, with
  its number of tries. Once the answer is in, the steps fold into "Worked for
  12 s", which opens them again, and that line stays with the message. Leaving the
  chat or the app mid-answer and coming back follows the work to its end. The
  answer appears at an even pace, with formatting still being written never shown
  as symbols. A message you sent can be sent again, edited first, or copied from
  its menu. tafs cloud's message stream gains `thinking` and `step` events and
  a `call_id` on `activity`; `GET /v1/tafs/{id}/runs/{run}/stream` picks your own
  run back up; conversation messages may carry `work`. The definitions of tafs and
  Coach set `thinking: "summarized"`, which needs kiff/agents with it.
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

- On iPhone and iPad, a card's answer goes to tafs cloud as data, not as a message in the chat,
  and the card shows the answer as it was recorded: your choice, Approved, Declined, or why it
  didn't go through. An answer that doesn't go stays on the card, so you can answer again.
- Dictation in a language whose own form the device doesn't have uses its best form: British,
  then American English for English; a language's own country's form for the others.
- On iPhone and iPad, a taf Coach hands over is set up by tafs cloud, not by the app. Its card
  shows tafs cloud's answer: set up, with a button to open it; why it wasn't, with Coach handing
  it over again; or "Setting up…" until the answer comes. The card no longer offers Try again.
- Deleting the account the earlier way, where the app itself asks tafs cloud, planque and KIFF
  in turn, still works for installed builds. It goes one release after the build that signs one
  intent for tafs cloud to carry.
- The app's earlier way of answering a card, a message `[<type> <id>] <answer>`, still
  works while that card waits, and is taken as the card's answer. It goes one release after
  the app build that uses the answer route.
- You see only what your role lets you do: halting, versions, revoking or removing a taf,
  its doors' controls and Coach are hidden unless you may use them, instead of failing
  once tapped (or after Touch ID).
- Coming back to the app, a chat stays where you left it instead of showing blank space.
- A chat whose task stopped at the taf's cost or step limit says so, and who can raise
  the limit, instead of staying on "…".
- No one reads another person's chats: People's work, the notice that a chat can be read,
  and Settings → Who read my work are gone. Your chats with a shared taf are yours alone.
- From the device test of build 793, on an iPad:
  - A held call's card waits in the chat for the taf's words that explain it, at
    most 45 seconds, instead of showing alone under your message first. Needs you
    and the alert still show it at once.
  - Opening a taf with a call waiting lands at the latest when the call is near the
    end, instead of on blank space with the Needs you pill and the arrow.
  - People & groups' button reads "+ Group" on the iPad too.
- The app no longer crashes on iPad while a chat or a sheet updates (build 793):
  SwiftUI on iPadOS 27 builds some views on a background render thread, and the
  app's runtime main-thread checks stopped it there.
- From the device test of build 789, on an iPad:
  - The app no longer crashes a few seconds after launch: the offer to turn
    on alerts shows over the screen instead of in a sheet.
  - The tafs list keeps its order while you look at it, and settles again when a taf
    comes or goes or the app comes back.
  - A held call's card comes after the taf's words that ask for it. When KIFF won't
    take your answer, the card says why in words instead of KIFF's code: for a call you
    asked for, someone else approves it and you can still refuse it; for other reasons
    the buttons that can't work go. An expired hold says so at once, Needs you reads
    KIFF again when it expires, and its Record row reads "no longer waiting · nothing
    sent" instead of "waiting for you".
  - A message you send goes to the top at once, and coming back from another app the
    chat returns to it, or to its latest, instead of blank space.
  - People & groups reads again while it's open, so someone who joins shows; its
    button says "Group".
  - Signing in shows the approval link, with a Copy button, to open in another browser.
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
  - The Card shows its own total and the extra room apart, as KIFF says them
    ("300 US$ a day · +30 US$ extra room until 00:00"), not one sum. tafs
    cloud's home list may carry KIFF's `extra_room`, `base_limit` and
    `room_window_end` on a rule and on a Card's total.
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
