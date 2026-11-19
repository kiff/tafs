# tafs on Apple Watch

A small companion to the iPhone app, on the watch. It shows what needs you, lets you talk
to Coach by voice, and can stop things. It uses the same account and words as the phone,
drawn smaller, and has no sign-in of its own.

## What it does in v1

| On the watch | v1 | Why |
|---|---|---|
| Read Needs you (the count, and each held call in the phone's words), your tafs, Coach's answers | Yes | Reading |
| Send Coach a message by voice | Yes, after the AI-use consent given on the phone | The same as typing on the phone |
| Refuse a held call, Halt a taf, Halt all | Yes | These only take authority away |
| Approve anything (a Card, a payment, a connection, extra room) | No: "Approve on your phone" | An approval is signed by the phone's approval key, behind Face ID |
| Pair a device, register a passkey, change a Card | No | The same |

## How it talks to tafs cloud and KIFF

- **Through the iPhone.** The phone holds the session and the device key, makes the calls,
  and signs the only answers the watch may ask for: Refuse and Halt.
- **No keys and no tokens on the watch.** A lost watch can read and stop things, never approve.
- **Sending needs a passcode on the watch.** Refuse, Halt, Halt all and Coach send nothing from a
  watch with no passcode, and it says so ("To refuse or halt from this watch, set a screen lock on it
  and unlock it.", `watch.needs_lock`), as on Wear OS. A locked watch doesn't show the app at all.
  Reading Needs you and your tafs needs no passcode (`WristLock`).
- **Standalone** (a cellular watch with its phone away) isn't in v1.

## The relay

What the watch asks and the phone answers is `ios/WatchShared/WatchMessage.swift`, which both
apps compile. The phone's end is `ios/Tafs/Services/WatchRelay.swift`, and the watch's is
`ios/TafsWatch/PhoneLink.swift`.

- **Ten requests:** Needs you, your tafs, Refuse (naming its held call), Halt (naming its
  taf), Halt all, Coach (the words dictated on the watch, never audio), Your brief (a read,
  behind the watch's switch), and the watch v2 pages below (reads). Nothing else decodes, so an
  approval can't even be asked for.
- **Refused before anything is read:** an unknown or malformed request, or one missing what
  it names: a taf's page with no taf or an id over 128 characters, a calendar day that isn't
  `YYYY-MM-DD`. Nothing on this path signs.
- **The watch v2 pages** (reads only, the same as Wear OS's, `docs/watch-wear.md`), answered with the session the
  phone keeps (`KeptWatchReading`):
  - `taf`, by tafs cloud's id: its state (Halted when it or the account is), its job, each Card as the Card tab
    draws it (title, the face's figure and line, a line per rule) and how many of KIFF's held calls are its agent's.
    A taf tafs cloud doesn't list says "Open tafs on your phone".
  - `calendar`, `day` as `YYYY-MM-DD`, or today in the account's zone (the phone's when it can't be read): tafs cloud's `GET /v1/calendar` for that day
    alone, its line and that day's events in time order with each taf's name.
  - `account`: who is signed in, the account's name (else its slug) and the plan's line, as Account shows it.
- **Live only:** each request goes with WatchConnectivity's message data and a reply, never
  queued. It carries the time it was made, and the phone drops one more than 30 seconds old,
  either way.
- **Signed out:** the phone answers only that, and the watch shows nothing of the account.
- **With tafs closed:** the phone answers with the session it keeps, as a hold's notification
  is filled in (`HoldNotification`), so the phone can be locked in a pocket.
- **Refuse** is the notification's own refusal (`NotificationRefuse.refuse`): signed with the
  current key, never the approval key, and only if KIFF still holds the call. Unlike the
  notification's, it may be signed while the phone is locked in a pocket: the only presence is
  the watch's own unlock, on the wrist. That's accepted because Refuse only takes authority
  away, as Halt all does. One already
  answered or lapsed says so, with no second call to KIFF.
- **Halt and Halt all** are the app's and Siri's (`HaltAll`): tafs cloud's request with the
  session, no signature and no Face ID. Resuming stays on the phone, behind Face ID.
- **The wrist shows the phone's result:** "Refused" or "Halted" appears only once KIFF or tafs
  cloud has answered. If it didn't go, it says why, in the phone's words.
- **Coach** gets text only: the words the person dictated or wrote on the watch, shown there
  before they're sent. The phone sends them to Coach's current chat with the session it keeps,
  then reads the thread for up to 12 seconds for Coach's answer to them, so the watch always hears
  back before it gives up; past that, "The rest is on your phone".
- **Coach's words carry the message's id** (`message`, a UUID the watch makes). The phone sends it to tafs cloud
  as `client_message_id`, so words sent again after no answer came are acted on once. The watch keeps the id while
  the same words are sent again, and makes a new one for other words or once Coach has answered. If a watch from
  before sends none, or one that isn't a UUID, the phone gives the message an id of its own.
- **v2's Send in two steps (W2):** `coach_send` sends the words as `coach` does and is answered as soon as tafs cloud has
  them, with a ticket: Coach's chat, the conversation and tafs cloud's id for the message. `coach_read` names that ticket
  and is answered with Coach's answer so far and whether its chat says it's still working, read once, never sending. The
  phone reads only Coach's own chat by a ticket: one it sent the watch's words to, or the one tafs cloud gives as
  Coach's now; any other is refused unread.

## Coach

- **In:** the watch's own text input, dictation or Scribble. No audio leaves the watch through
  tafs, and nothing heard is kept. The watch's dictation is the system's: it may be processed by
  Apple, so the watch never says the voice stays on the device, as the phone's dictation can.
- **Out:** Coach's words, cut at about 300 characters with "The rest is on your phone". If the
  answer shows any card or step, the wrist says "Approve on your phone" and nothing of the card:
  nothing started from the wrist can grant anything.
- **At most 2,000 characters,** as on Wear OS: longer is refused before anything is sent.
- **Refused:** without the AI-use consent, "allow AI use in tafs on your phone"; past the
  credits, the app's own words; anything else, "Not sent" and why.

## Your brief (behind a switch)

Where the phone shows Your brief in Needs you's place, the watch can show it too, in tafs cloud's words, as
Wear OS's (`docs/watch-wear.md`). It's built and **off**: `NeedsYouModel.briefOnWatch` is `false`, and turns on
with the owner's yes, as that one line. Off, the watch asks and draws exactly what it did before
(`NeedsYouModelTests.testTheBriefIsOffAndOffChangesNothing`).

With it on (`NeedsYouModel(briefOn: true)` in the tests):
- **The read.** After Needs you, the watch asks `brief`. The phone reads `GET /v1/brief` with the session it
  keeps, as Needs you's read is made, since tafs may be closed (`KeptWatchReading.brief`). A route tafs cloud
  doesn't serve (404), or a brief with nothing in it, is no brief: the watch shows Needs you. So does a failed
  read, a phone that doesn't know the request, and no answer. The phone answers `brief` whatever the watch's
  switch, as answering a read is harmless.
- **What's sent** (`WatchBrief`, `KeptWatchReading.watchBrief`): the title and its `when` line; the decisions
  the phone draws, every one tafs cloud sends (it caps them itself and keeps every urgent one), in its order, each with its question, its taf by the phone's name for it,
  its deadline, why it can't wait, what it's about (`ref` kind and id), and the held call's id when it's about
  one (`holdID`). **No answers are sent:** decisions are answered on the phone. Then the line for more, Done's
  heading and its first two cases by name, and the rest line.
- **What's drawn** (`BriefList`): the brief in Needs you's place, then Your tafs with Halt, unchanged. Each
  decision says **Approve on your phone**. **Refuse** shows only where the watch's own Needs you read lists the
  same held call, as Needs you's own row (`HeldCallRow`): the same request, confirmed, behind the passcode.
  Under it, Needs you's count of what the brief doesn't show, matched item by item, never by number (as the phone's
  `needsYouBeyondBrief`): each held call no decision names, by KIFF's id, plus the rest of Needs you's count less
  the brief's steps. A held call the brief names but Needs you no longer lists never hides one it does.
- **Unchanged:** no keys and nothing signed on the watch; kept in memory and forgotten when the app leaves the
  screen; no new words (the brief's are tafs cloud's, the rest the watch's own).

## Version 2 (behind a switch, off)

Watch v2 follows the owner-approved mockup v2.1, as Wear OS's does (`docs/watch-wear.md`), one step at a time behind
`WatchV2.on` (`ios/TafsWatch/WatchPages.swift`), which is `false`: a watch shows v1 whole until every step is in. It
turns on, as that one line, once the last step lands and the owner has tried the build.

**Step (ii), the pages and Coach's home** (`WatchPagesView`, tested in `WatchPagesTests`):
- **The pages,** one under the other, moved between with the crown or a swipe: Coach (home), Inbox, Your tafs and
  Account; Brief after Your tafs only with `NeedsYouModel.briefOnWatch` on, which still waits for the owner's yes. Each
  is named with the phone's own word where the phone has the page.
- **Coach's home:** "Ask Coach" first, which opens v1's Coach screen; then "N unread in Inbox", only once the phone has
  answered and only while something waits (never "0 unread"), a tap going to the Inbox; the line that Coach gets words, never a
  voice; and **Menu** at the foot.
- **The menu:** every page by name, and Halt at the very bottom, apart from the rest, offered only while a taf the phone
  listed runs. It opens Choose which to halt (step v).
- **Until their own steps:** the Inbox is Needs you as v1 reads it (with the brief in its place when that switch is on);
  Your tafs is each taf with its state and no Halt; Account is the trust line.
- **Nothing new** reaches the phone, and nothing is stored on the watch. The last chats come with tafs cloud's chats read.

**Step (iii), Check before sending** (`CoachAsk`, `CoachModel.review`, tested in `CoachModelTests`), as Wear OS's first half.
Nothing leaves the watch before Send:
- **Ask Coach** on the home opens the ask: the system's input (Type: dictation, Scribble or the keyboard, which watchOS
  offers with dictation first) and **Quick reply**'s four lines, in Wear OS's order. watchOS has no speech recognizer an
  app can drive, so the system's input is the microphone: there's no Start, Stop, timer or recording of the app's own, and
  no Play. Only the words leave, as text ("Coach gets your words as text, never your voice.", as Wear OS says it).
- **Check before sending** shows the words in full, scrolled with the crown, and under them, pinned at the foot in one row
  (watchOS's bottom bar, as Wear OS's W9), **Delete** ✕, **Re-record** ↻ (the system's input again; the new words take
  the old ones' place, and a cancelled input keeps them) and **Send** →, so Send is never below the words however long
  they are. Delete shows "Deleted. Nothing was sent.", which closes by itself after 2 s. Blank is nothing; the words are cut at 2,000 characters, as a
  message to Coach is.
- **Send** sends the words as v1's does: the same id when the same words go again after no answer, and the lock
  checked at Send. Since W2 it's two steps (`coach_send`, then `coach_read`, below); a phone from before them refuses
  `coach_send`, and the same words then go v1's way (`coach`) under the same id. While it's in flight, Re-record and Delete do nothing (in the model as on the screen): the words
  shown are the words on their way, so the watch never says "Nothing was sent." about words that may have reached Coach. A Send that fails keeps the words in the review, with the reason under them; an answer shows as v1's.
- **Not in this step:** Keep for later (it stores the words on the watch, which v2 doesn't yet), and Ask tafs
  (`ask` instead of `coach`, below), which waits on the Wear OS slice and tafs cloud's iPhone push.
- **v1 is unchanged:** with the switch off, Coach is v1's screen, its words shown before Send.
- **Words:** all already shared (`watch.type`, `watch.type_hint`, `watch.quick_reply`, `watch.qr.*`, `watch.review`,
  `watch.rerecord`, `watch.deleted_nothing_sent`, `watch.voice_text_only`; Send and Delete are the phone's).

**Step (iv), the Inbox kept on the watch** (`Inbox.swift`: `InboxModel`, `FileInboxStore`; `InboxScreen`; tested in
`InboxModelTests`), as Wear OS's:
- **The lines:** each held call Needs you lists, and each answer Coach gives words sent from this watch, newest first, kept until
  dismissed: its taf (Coach for an answer), its words, and a blue dot while it's unread. A held call's words are what it asks
  **without its amount** (the phone's `summary`, as Wear OS's, with the call's own amount taken out of it in the ways it's
  written (a bare amount with no unit too), then any figure with a currency sign, code or word beside it, złoty, crowns,
  Swiss francs and the Swiss apostrophe included; Needs
  you's title from a phone from before it): the amount
  shows only while Needs you holds the call, in memory, and is never kept on the watch. Coach's answer comes in read, since
  the person saw it arrive, and is kept with its amounts taken out the same way (`WatchAmount.strip`, which the phone's
  summary uses too), so no line kept on the watch has an amount; on screen it shows whole, in memory. Coach's home counts the unread lines ("2 unread in Inbox"), and shows no line with none.
- **Opened, it's read.** A held call shows whole while Needs you still holds it, with Refuse and Approve on your phone as Needs
  you has them; else "Already answered, or it ended.". Coach's answer shows whole.
- **Dismiss** on each line (a swipe, or at the foot of the opened line), and **Clear read** for every read line. Either stays
  gone while the phone still lists it (its id is kept, and dropped once the phone no longer lists it), so a read never brings
  it back.
- **Only with a passcode.** The lines are kept in their own file in the app's container on the watch (`tafs.inbox.json`, the
  lines and the ids taken away), written with complete file protection: unreadable whenever the watch is locked, not only
  before its first unlock (which is all the app's defaults would get). Both halves are
  written together, and a file there that can't be read (the watch locked) is never written over; an Inbox opened while it
  couldn't be read takes in what the file kept once the watch is unlocked, before it writes. With no passcode nothing is stored or added, the lines go
  when the app is opened or put away (the bare ids taken away stay, so a dismissed line doesn't come back unread), and the page
  is Needs you as the phone answered now, with the home counting those calls: a call that needs the person is never hidden
  behind "Nothing new". A locked Apple Watch doesn't show the app, so the passcode is what's checked.
- **Signed out** on the phone: nothing of the Inbox stays, the ids taken away included.
- **Read and dismissed stay on this watch** until tafs cloud keeps them in step with the phone (server piece c).
- **Words:** all already shared (`watch.dismiss`, `watch.clear_read`, `watch.already_answered`, `watch.inbox_empty`,
  `watch.inbox_unread`).

**Step (v): a taf's page, its settings, and choosing which to halt** (`TafPage.swift`: `TafPageModel`, `TafPageScreen`,
`TafSettingsScreen`; tested in `TafPageModelTests`), as Wear OS's. Halt is never on the main screens:
- **Your tafs:** each taf with its state, in the phone's words; a tap opens **its page**, read from the phone (`taf`, by its
  id), in memory only. Before the phone answers it shows the taf's name from Your tafs and "Asking your phone…"; signed out,
  no phone, the phone's own words, or "Update tafs on your phone" for a phone that doesn't know the read, each with Try again.
  An answer for a page since closed, or for another taf, is never drawn.
- **A taf's page:** its name and state (Halted in red), **What it does** and its job, **Its Card** with each Card's title, its
  face's figure and line, and each rule on a line, as the phone's Card tab draws them, and **Waiting for you** with how many of
  its calls wait ("2 need you"), which goes to the Inbox, or "Nothing waits for you.". Halted, it says "Halted. Resuming starts
  only new work." and **Resume on your phone**: resuming asks for Face ID, so it's the phone's. Then **Its settings**.
- **Its settings:** "{taf} · settings" and **Halt this taf**, in red, asked once more ("Halting stops it until you resume. A
  call already sent may still arrive.", Halt now). It sends v1's Halt for that taf, only from a watch with a passcode, shows
  "Halting {taf}…" until the phone has tafs cloud's word, then the phone's word for the result, and the page is read again. A
  halted taf's settings offer Resume on your phone instead.
- **Not on Apple Watch:** Wear OS's "Swipe left for its settings" (Its settings is a row here) and Open on phone (watchOS has
  no way for a watch app to open the phone's app).
- **Choose which to halt** (`HaltPick.swift`, tested in `HaltPickModelTests`), from the menu's Halt: **All your tafs**,
  then each taf not halted, each a switch (a taf halted already isn't offered), and **Halt now · N**, off with none
  picked. It's asked once more: every taf offered picked is "Halt all your tafs?" and sends one Halt all; one or a few are
  named ("Rae, Ola"), with the line for one or for several, and send one Halt per taf picked, one after the other, each to
  the end, so one that fails never keeps the next from halting. "Halting …" shows until the last answers, then what came
  of each, by name, in the phone's words. Nothing is sent before Halt now, and nothing from a watch with no passcode.
- **Words:** all already shared (`watch.what_it_does`, `watch.nothing_waits`, `watch.its_settings`, `watch.settings_of`,
  `watch.halt_this`, `watch.resume_on_phone`, `watch.halting`, `watch.needs_you_count`, `watch.asking_phone`,
  `watch.update_phone`, `watch.halt_pick_title`, `watch.all_your_tafs`, `watch.halt_now_count`, `watch.halt_all_q`,
  `watch.halting_all`; `card.its_card`, `chat.waiting_for_you`, `halt.explain.halted`, `halt.explain.running_one`,
  `halt.explain.running_all`, `halt.halting_stops_no_run_starts`, `halt.now`).

**Step (vi): a held call whole, a calendar day, and Account** (with Sign out on this watch since 19 Nov; `CalendarDay.swift`, `AccountPage.swift`, `WatchSignIn.swift`; tested in
`CalendarDayModelTests` and `AccountPageModelTests`), as Wear OS's:
- **A held call,** opened from the Inbox while Needs you holds it: whole, as the phone's Needs you words it (its taf, what
  it asks with its amount, KIFF's reason, by when), Refuse where Needs you offers it, Approve on your phone, and "Nothing is
  sent until you approve.". Approving is never on the watch. watchOS has no way for a watch app to open the phone's app, so
  there's no Open on phone.
- **Today's calendar,** at the foot of Your tafs, reads the phone's `calendar` for today in the account's zone: tafs cloud's
  line for the day (in red when the account is halted), then each event in tafs cloud's order with its time, its taf, its
  words and "×3" when it happens more than once. **Previous day** and **Next day** read the day before or after the one
  shown; the title is Today only for the day the phone answered as today, else the date in the watch's language. In memory
  only, forgotten on Close, and an answer for a day asked before the last, or after Close, is never drawn.
- **Account,** read from the phone (`account`) each time the page is shown, in memory only: Signed in (the email and the
  account's name), Plan (the plan's line as the phone's Account shows it), the watch's language and the app's version, and
  the trust line. With the phone out of reach, or signed out, it says so with Try again, and the watch's own lines stay.
- **Sign out on this watch** (the owner's choice, 19 Nov, as Wear OS's; `WatchSignIn`, tested in `WatchSignInTests`): at the
  foot of Account, asked once more ("Sign out on this watch?", "Your phone stays signed in. This watch forgets your tafs, chats
  and inbox."). It's on this watch alone: nothing is sent to the phone, and it works with the phone out of reach. The watch
  forgets everything it holds: its Inbox (the ids taken away too) and what each page showed. It keeps one flag, an empty file
  `tafs.signed_out_here` in its Application Support, and shows only "Signed out on this watch. Your phone stays signed in."
  with **Sign in with your phone**, across restarts, until the person taps it. Meanwhile it reads and sends nothing. Sign in
  takes the flag away and reads the phone again. The privacy text names the flag ("…and whether you signed out on the
  watch", legal-i18n's clause), which ships with the switch.
- **Words:** all already shared (`watch.calendar_today`, `watch.nothing_sent_until`, `watch.signed_in`, `watch.trust`,
  `watch.sign_out_q`, `watch.sign_out_sub`, `watch.signed_out_here`; the phone's `calendar.today`, `calendar.previous_day`,
  `calendar.next_day`, `calendar.reading`, `calendar.times`, `settings.plan`, `settings.language_title`, `settings.version`,
  `settings.sign_out`, `sign_in.with_phone`).

**W2, Sent and Coach is answering** (`CoachModel.sendReviewed`, `CoachAsk`; tested in `CoachModelTests` and
`WatchRelayTests`), the owner's Watch 5 test, as Wear OS's 0.4.1:
- **Sent:** the moment tafs cloud has the words, they leave the review and show above "✓ Sent".
- **Coach is answering:** the watch reads Coach's answer every 1.5 s for the first 6 s, then every 3 s; while Coach's
  chat says it's working, "Coach is answering" with a progress mark. Coach's answer shows once it's written and the chat no longer says working.
- **About 30 s at most** (`answerReads`, 12 reads): then what Coach wrote so far, with "The rest is on your phone.". A read
  the phone can't answer is read again: the words were sent, so the watch never says they weren't.
- **Leaving the screen stops the reads,** and nothing from them is kept. The Inbox keeps the answer once, as before.
- **Not sure it arrived:** a Send the phone doesn't answer stays in the review with "the phone isn't in reach" and the same
  id, as before.
- **The words are the app's own, not `watch_ask`'s:** Apple Watch's Coach is the iPhone's Coach chat, never tafs cloud's
  watch ask, so it shows the chat's line for that state (`chat.taf_is_answering` with Coach's name) and `watch.sent`. No
  new strings. Moving onto `watch_ask` waits on tafs cloud's push to the iPhone (Ask tafs, below).

**W3, Coach's answer you can't miss** (`CoachFollow`, tested in `CoachFollowTests`), the owner's Watch 5 test,
option A (19 Nov):
- **With the app open,** the answer shows in place (W2).
- **Once the watch app is put away,** watchOS stops its reads within seconds, and the watch posts no notifications of its
  own (the privacy text: its only notifications are the iPhone's). So after a watch Send, the iPhone follows Coach's answer
  itself, inside the background time iOS gives it: 10 reads, stepped as the watch's (24 s). Once Coach has answered, if
  the watch hasn't read for 5 s (it reads at most 3 s apart while open), the iPhone posts a local notification: "Coach", then the answer
  with no amount in it (the Inbox's rule, `WatchAmount.strip`, at legal-i18n's ask: iOS keeps a notification until it's
  dismissed), then "Approve on your phone", or "The rest is on your phone." where it was cut or something was taken out. watchOS shows it on the
  wrist while the iPhone is locked; a tap opens Coach's chat on the iPhone.
- **Never twice:** an answer the watch read itself, or a watch still reading, posts nothing; nor does an iPhone with tafs
  open on screen, where Coach's chat already shows the answer.
- **Its limit:** iOS doesn't promise the background time, and an answer past the follow is only in Coach's chat. tafs
  cloud's push to the iPhone (piece d, step (vii)) lifts it.
- **Words:** none new (`coach.title`, `watch.approve_on_phone`, `watch.more_on_phone`). No change to the privacy text.

**W4, Ask Coach as one conversation** (`CoachModel.turns`, `CoachHome`, `CoachTurnRow`; tested in `CoachModelTests`), the
owner-approved Watch Coach mockup, as Wear OS's:
- **On Coach's home, above the microphone:** each thing said from this watch since the app was opened, oldest first, so the
  page opens on the microphone with the newest turn just above it. A turn is when it was said, the person's words on the
  right, then Coach's side on the left: "✓ Sent" until Coach starts, "Coach is answering" while its chat says so, then its
  answer, which opens whole. VoiceOver hears Coach's side as "Coach: …".
- **Closing the ask keeps the conversation,** and an answer on its way still lands in it (and in the Inbox, once). Words still
  on their way to the phone stay in the review, so a failure can be sent again under the same id.
- **Memory only:** the turns go when the app is put away and on Sign out on this watch. Nothing new is kept on the watch, so
  the approved text's "It keeps only your Inbox" still holds. Until tafs cloud keeps the watch's conversation (W11), it's what
  this watch said.
- **Colours:** W7's: the person's words on `mineGround`, Coach's on `coachGround`, "Sent" in `done`, "Coach is answering" in `coach`.
- **Words:** none new (`watch.sent`, `chat.taf_is_answering`, `coach.title`, `watch.more_on_phone`, `watch.approve_on_phone`).

**W7, colour by meaning** (`WatchLook`, tested in `WatchLookTests`), the owner-approved Watch Coach mockup: one palette, Wear
OS's `Look` value for value (#1451), so both watches read alike.
- **Send and your words orange** (`send` #E8531D): the app's tint in v2, so the microphone, Send → and links. Your words in
  the conversation on `mineGround` #3A2A22.
- **Coach blue** (`coach` #7FA8FF, on `coachGround` #1E2231): Coach's answers, "Coach is answering", the Inbox's unread dots.
- **What waits for you amber** (`held` #FFB547): Approve on your phone, and why a Send didn't go (the review's line).
- **Done green** (`done` #3FCF7E): the Sent check.
- **Delete, halt and sign out red** (`danger` #FF5A5F): the review's ✕, Halt now, Sign out, a halted taf's or day's line.
- **Everything else** stays the system's grey. v1 is unchanged: it keeps the system's tint, and Needs you its own colours.

**W11, "From your watch" on the iPhone and iPad** (`WatchDaysModel`, `WatchDaysSection`, `WatchDayView`; tafs cloud's reads
in `TafsCloud+WatchDays.swift`; tested in `WatchDaysTests`), the owner-approved mockup, behind its own switch
(`WatchDaysFeature.on`, on since 19 Nov, once tafs cloud's routes were live; tafs cloud's own switch still decides):
- **In Coach's Previous conversations,** a section "From your watch": the days the person asked from a watch, newest first,
  each with how many (`GET /v1/watch/days`), apart from the chats started on the phone. One a day, as the owner chose.
  "Today" is the account's today as tafs cloud gives it (`today`), so someone on another time zone than the account's sees
  it on the right day; a tafs cloud that doesn't give it leaves the account's zone's day, else the phone's (agentgabo's read).
  A turn's time is in the account's zone too, as the day is and as Android shows it (qa-kiff's read).
- **A day** (`GET /v1/watch/days/{date}`) lists its turns oldest first: "⌚ Sent from your watch" and when, the person's
  words, then the taf that took it and its line, or tafs cloud's words for where it is. A reply sits under what it replies
  to. Read only: a reply from the phone goes on in a Coach chat, as any other.
- **The person's own only,** as tafs cloud serves them: another member never sees them, the account's owner included.
- **Nothing kept on the phone:** each is read when shown, a day is forgotten when it's left, and an answer for a day asked
  before the last is never drawn. A read that fails, or a tafs cloud without the routes (404 `watch_off`), shows no section,
  never an error.
- **Apple Watch days** come once the iPhone sends the Apple Watch's words through `POST /v1/watch/asks`, as Android's relay
  does; until then the section lists only what came through that route.
- **Words:** `coach.chats.from_your_watch` and `coach.chats.sent_from_your_watch` (legal-i18n's, in all 8). The rest are
  `calendar.today` and tafs cloud's own.

## Ask tafs: the plan for the Apple Watch (not built yet)

The Wear OS watch asks tafs by voice ([watch-wear.md](watch-wear.md), tafs cloud's side in
[watch-ask.md](watch-ask.md)). The Apple Watch gets the same product on the same contract, once the Wear OS
slice is proved on a real watch. Nothing below is built yet. Each **verify** is a fact to check on a real
Apple Watch before the iOS PR.

**Unchanged from the contract:**
- **The phone signs; the watch holds no keys.** The phone signs the ask with its **device key, no prompt**
  (`signConfirmed`, as the wrist's Refuse) and sends `POST /v1/watch/asks` `{client_id, text, reply_to?}`. The
  approval key is never used on this path.
- **What the ask is:** tafs cloud's route alone marks it as the watch's, and Coach's watch-only skill picks the one
  taf. The checked line (140 characters at most) and its state's words come from tafs cloud.
- **The watch's own `client_id`** (`w_` and 24 random hex digits), kept for a retry, so a retry starts nothing.
- **Only from a watch with a passcode, unlocked** (`WristLock`, `watch.needs_lock`), as Refuse and Halt.
- **Kept in memory only.** A locked watch doesn't show the app.
- **Words:** every line the watch needs is already a shared key, so there are no new strings: `watch.ask`,
  `sending`, `sent`, `not_sure`, `retry`, `hear`, `reply`, `result_ready`, `didnt_catch`, `mic_needed`,
  `ask_needs_consent`, `ask_not_on`, `update_phone`, `ask_text_only`, `timing`.

**The relay (`WatchMessage.swift`, `WatchRelay.swift`, `PhoneLink.swift`):**
- **Two new requests:**
  - `ask`, with the text, the `client_id` and an optional `reply_to`;
  - `asks`, the last day's asks, read when the app opens.
- **Their answers:**
  - the request as tafs cloud answers it (`accepted`, `working`, `done`, `no_taf`, `failed`);
  - `unsure` when the phone or tafs cloud didn't answer (no reply, a 5xx);
  - a definite refusal in tafs cloud's or the phone's words.
- **The old `coach` request stays** for a watch from before, and this watch never sends it, as on Wear OS.
- **A result after the ask:** the phone reads the one ask (`GET /v1/watch/asks/{id}`) and sends it to the watch with
  WatchConnectivity's `transferUserInfo`, which is queued and delivered when the watch can take it, not only while
  the app is open. **Verify:** how soon a backgrounded watch app gets it, and whether it can then post its own
  notification ("A result is ready" while locked; the taf and its line otherwise).

**Two gaps on the service's side, before iOS can match:**
1. **The result push reaches Android only.** `watch-ask.md` sends each change to the person's **Android** phone
   (`change: watch_ask`, `ask_id`). iOS needs the same as a silent push to the iPhone (`content-available`, the ask's
   id alone). iOS throttles silent pushes, so the watch also reads `asks` whenever it opens, and a missed push costs
   only time. This is one tafs cloud change, and the phone's side in `AppDelegate`'s remote notification handler.
2. **Nothing new in KIFF or planque.**

**Where watchOS differs:**
- **Listening that sends on its own.**
  - watchOS has no speech recognizer an app can drive (no `Speech` framework), so the watch uses the system's
    dictation: SwiftUI's `TextField` input, or `presentTextInputController` with no suggestions, which can open
    straight on dictation.
  - **Verify:** that it ends on silence and hands the text back with **no** extra tap. If watchOS insists on a
    **Done**, that one tap is the only difference from Wear OS, and the screen says nothing else.
  - **The alternative, recording on the watch** (`AVAudioRecorder`) and having the phone transcribe it, is a
    change to where the voice goes, and so to the privacy policy: it isn't in this plan.
  - The words leave as text only, as `watch.ask_text_only` says. The system's dictation may be processed by Apple,
    so the watch never claims the voice stays on the device.
- **Hear: `AVSpeechSynthesizer`,** in the result's language, on the watch's speaker or the AirPods it's connected
  to. **Verify:** the speaker route with no headphones, silent mode, Low Power Mode, and that a speech failure still
  leaves the line on screen.
- **The one-gesture entry.** watchOS has no tiles, so:
  - a **complication** and a **Smart Stack widget** (WidgetKit accessory families) that open the app on listening
    (`widgetURL`);
  - on a watch with an **Action button** (Ultra), an App Intent that starts listening.
  
  **Verify:** each on the models and watchOS versions we support, before any is promised. The complication shows
  no count of what's waiting while the watch is locked, as the existing one.
- **Sent, Not sure it arrived, Retry, the result card (Hear, Reply), Halt pending until the phone confirms:** the
  same screens and states as Wear OS, drawn in SwiftUI. An older state never replaces a newer one.
- **Timing readout:** debug builds only (`#if DEBUG`), as `watch.timing` on Wear OS. A TestFlight build for the
  owner's test is a separate scheme, never the App Store one.

**Order:** the Wear OS slice is proved on the owner's watch, then the tafs cloud iOS push (gap 1), then one iOS PR:
the relay's `ask` and `asks`, the watch's microphone-first home and result card, Hear, the complication. The entry by
the Action button comes after, if verified.

## The illustration

Needs you with nothing waiting shows the approved board 11 (`AllClearArt`): a calm check in a green
circle above "Nothing waiting". It's drawn in code with shapes and one symbol, and holds no letter,
number or word. VoiceOver skips it, since the line under it says the same. It shows only once the
phone has answered, never in place of loading. A locked watch doesn't show the app at all.

## What the watch keeps

- **In memory only:** the list, the amounts and Coach's answers. They go when the app leaves the
  screen, and are read again when it's back.
- **v2, behind its switch (step iv):** the Inbox's lines (each held call's taf and what it asks, never its amount, and
  Coach's answers, also with no amount) are kept on the watch until dismissed, only while it has a passcode, in a file with complete file
  protection, and cleared on sign-out.
- **v2, behind its switch:** after Sign out on this watch, one empty file, `tafs.signed_out_here`, until Sign in with your
  phone.
- **On disk:** only the Needs you count and when it was read, for the complication. It's cleared
  when the phone says it's signed out. (This comes with the complication.)
- **Never:** a token, a key, or an amount, a merchant or a person's name in a log, on the watch
  or in the relay.

## Before the watch app: what the phone's notifications already do

The iPhone's notifications show on the watch by themselves. On a held call's notification:

- **Refuse** runs on the phone, signed with the current key, and needs no Face ID. It works
  from the wrist.
- **Approve once** only opens tafs on the phone. Nothing can be approved from the wrist.

## Where it lives

- `ios/TafsWatch/`: the watchOS app, with a single target (watchOS 10 and later).
- `ios/TafsWatchTests/`: its unit tests, on a watch simulator.
- `ios/project.yml`: the `TafsWatch` and `TafsWatchTests` targets, and the `TafsWatch` scheme.
  The phone's `Tafs` scheme doesn't build the watch app.
- The words are the phone's (`app/strings`, 8 languages). The watch compiles the generated
  `Words.swift` and its catalog, and its own lines are the `watch.*` keys.
- CI: the iOS workflow's `watch` job builds and tests it on a watch simulator, only when what it's
  built from changes.

## Store

TestFlight first, inside the iPhone app. Its App Store screenshots and listing are store
listing changes, made separately.

- **Only the TestFlight build embeds it.** `ios/watch-embed.yml` adds the watch app to the
  iPhone app's Watch folder, and `project.yml` includes it only when `TAFS_EMBED_WATCH` is
  set, as the `testflight` job sets it. The `watch` job checks the generated project both ways.
- **Its App ID and profile** (`bot.tafs.app.watchkitapp`, "tafs watch App Store CI") are made
  by `ios/scripts/asc-profiles.rb` through the App Store Connect API, like the extensions'.
- **Its icon** is the phone's, one 1024-point image. Its privacy manifest declares no tracking,
  and one kind of data collected: the words the person sends Coach from the watch (other user
  content, linked, for the app's function), which go through the phone to tafs cloud.
