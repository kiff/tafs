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
  watch with no passcode, and it says so ("To send, refuse or halt from this watch, set a screen lock on
  it and unlock it.", `watch.needs_lock`), as on Wear OS. A locked watch doesn't show the app at all.
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
- **The Inbox's marks** (row 21): `inbox_marks` reads the ids the person read and dismissed (`GET /v1/watch/inbox`), and
  `inbox_mark` sends 1 to 100 ids to mark (`POST /v1/watch/inbox`), each checked on both ends as tafs cloud takes it
  (1 to 100 of `A-Za-z0-9_-`); anything else is refused before tafs cloud is asked. Ids only, with the phone's session and
  nothing signed: a mark changes no one's power. A phone from before them answers `not_yet`.
- **Live only:** each request goes with WatchConnectivity's message data and a reply, never
  queued. It carries the time it was made, and the phone drops one more than 30 seconds old,
  either way.
- **Signed out:** the phone answers only that, and the watch shows nothing of the account.
- **A read tafs cloud doesn't answer:** the phone says "Your phone couldn't reach tafs cloud. Try again."
  (`watch.phone_could_not_reach_cloud`). The notification's own Refuse keeps its line about KIFF.
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
- **Since 19 Nov, `coach_send` is a watch request of its own** (`KeptWatchReading.ask`, docs/watch-ask.md), as Android's
  relay sends one: the phone signs the exact body `{client_id, text, reply_to?}` with its **device key, no prompt**
  (`signConfirmed`, never the approval key) and sends `POST /v1/watch/asks`. `client_id` is the watch's id for the words,
  so a Send again gets the same request back and starts nothing. The ticket names the request (`chat` `watch-ask`,
  `session` its id), and `coach_read` reads it back by its id (`GET /v1/watch/asks/{id}`): at work, "still answering";
  ended, its line, else tafs cloud's words for its state; a call held for the owner, its line with "Approve on your phone".
  - **A reply:** the next Send of the same visit carries the last request's ticket, and the phone sends it as `reply_to`, so
    Coach goes on in that request's conversation. One still at work (409 `reply_too_soon`), or gone (404 `no_ask`), makes
    the words a request of their own. Leaving the screen ends the conversation, as the turns go.
  - **Where tafs cloud's switch for it is off** for the account (404 `watch_off`), or on a tafs cloud without the route,
    the words go to Coach's chat as before.
  - W3's follow-up (`CoachFollow`) reads the request the same way when the watch is put away.
- **Chatting with a taf (20 Nov, the phone's half; the wrist's screens follow the owner's pick).** A
  `coach_send` that names a `taf` sends that taf's id as `taf_id` in the same signed body (`{client_id, text, reply_to?,
  taf_id}`), so the words go into that taf's own chat as the person, behind tafs cloud's `TAFS_WATCH_TAF_CHAT`
  (`KeptWatchReading.tafSend`). They **never go to Coach**: with the switch off (404 `watch_taf_chat_off`), a taf the
  person may not use (404 `no_taf`) or Coach's own taf (400 `coach_taf`), the watch says to open the phone. A follow-up
  replies to the taf's last request. A request that ends waiting on the person (`held: true`) says "Approve on your
  phone"; an answer past 400 characters (`more: true`) says the rest is on the phone. The watch keeps nothing of a taf's
  chat.
  On the wrist, `CoachModel` takes the taf's id: its Send names it, a phone from before it never sends the words to
  Coach by v1's send, and its answers never go to the Inbox.
- **The wrist's screens (the owner's picks 1A and 2A, behind `WatchTafChat.on`, on since 20 Nov once the privacy paragraph was live; tafs cloud's
  `TAFS_WATCH_TAF_CHAT` still gates each account).**
  - **1A:** Your tafs opens a taf's own chat (`TafChatScreen`), with its page one swipe below. Each taf's chat is one
    `CoachModel` for the visit (`TafsModel.chat`), gone when the app leaves the screen.
  - **The chat:** the taf's state, this visit's turns (your words orange, the taf's green, `WatchLook.taf`), then "Message
    {taf}", which opens the same ask as Coach's (Speak, Type, Quick reply, Check before sending) titled for the taf.
  - **2A:** a paused taf (`WatchTaf.paused`, sent by the phone) still takes the message, and answers it now; a halted one
    takes none and says "Resume on your phone".
  - **Read more (the owner's 01:10):** a long answer shows its first part with Read more. Only the tap reads the whole
    answer (`ask_full`, `GET /v1/watch/asks/{id}?full=1`, its `text`), shown in place and forgotten with the turns. If it can't be
    read, "The rest is on your phone."
  - **The last turns:** the chat opens on the last six turns of the person's own chat with the taf (`taf_chat`), read
    through the phone from `GET /v1/tafs/{id}/conversation` for a taf it lists, messages only, each cut as an answer is.
    This visit's turns show below them, once. Nothing is kept on the watch.
  - **Read more on a line from before this visit (row 10, as Wear OS's `line-full`):** a taf's cut line has Read more, with
    View on phone still under it. One tap asks the phone for that message whole (`line_full`: the taf and the line's id,
    `CoachModel.readMoreLine`). The phone reads the taf's chat with its own session and answers the taf's words only, never
    the person's, at most 16 KB (`KeptWatchReading.wholeLine`). It's shown in place, once, and forgotten when the app leaves.
    The person's own cut line, Coach's chat, or a phone that can't give it: "The rest is on your phone."
  - **"From your watch" on the phone (W11)** leaves out a message to a taf (`kind: "taf"`): it's already in
    that taf's own chat on the phone.
  - **The words:** `watch.paused_still_answers` ("Paused: its schedule is off. It still answers what you send.", true to
    what `message()` does: only the schedule is paused) and `watch.read_more`, in 8 languages.

## Coach

- **In:** the watch's own text input, dictation or Scribble. No audio leaves the watch through
  tafs, and nothing heard is kept. The watch's dictation is the system's: it may be processed by
  Apple, so the watch never says the voice stays on the device, as the phone's dictation can.
- **Out:** Coach's words, cut at about 300 characters with "The rest is on your phone". If the
  answer shows any card or step, the wrist says "Approve on your phone" and nothing of the card:
  nothing started from the wrist can grant anything.
- **Hear** (`HearButton`, `WatchHear`, as Wear OS's `Speaker`): under Coach's answer, on Coach's screen and on an opened
  Inbox line, it reads the words aloud with the watch's own speech (`AVSpeechSynthesizer`), by a voice of the app's own
  language, on its speaker or the headphones it's connected to. Nothing is sent or kept. The voice is looked for by the
  catalog's full tag first (pt-BR; zh-Hant as zh-TW, then zh-HK; pt as pt-PT, since Brazil has its own), then the bare
  language (`WatchHear.candidates`). **A language the watch has no voice for isn't spoken, and Hear isn't shown**: never
  another language's voice reading it. The synthesizer keeps its own audio session, so it never takes the recorder's. It
  stops when its screen goes, and a speech that fails leaves the words on screen. `WatchHearTests`. **To verify on a
  watch:** the speaker with no headphones, silent mode and Low Power Mode.
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

## Version 2 (on since 19 Nov)

Watch v2 follows the owner-approved mockup v2.1, as Wear OS's does (`docs/watch-wear.md`), built one step at a time behind
`WatchV2.on` (`ios/TafsWatch/WatchPages.swift`). It's `true` since 19 Nov, once every step was in and the privacy text for
Sign out on this watch was live in 8 languages; turned off, a watch shows v1 whole.

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
  Your tafs is each taf with its state and no Halt (halted, paused, its next run, else Home's state in the iPhone's words:
  Ready, Stopped, Working; never no line); Account is the trust line.
- **Nothing new** reaches the phone, and nothing is stored on the watch. The last chats come with tafs cloud's chats read.

**Step (iii), Check before sending** (`CoachAsk`, `CoachModel.review`, tested in `CoachModelTests`), as Wear OS's first half.
Nothing leaves the watch before Send:
- **One tap to talk** (`WatchDictation`, `WatchDictationTests`; row 44, A, as Wear OS's tafs#1582): **Ask Coach** on the
  home, and a taf chat's microphone, open the system's dictation at once (`presentTextInputController` with no suggestions
  and plain text, which watchOS opens on the microphone), and the words go straight to Check before sending. Cancelled,
  the ask opens on Type and Quick reply. It doesn't listen without a passcode (as Wear OS without a screen lock), while
  words are on their way, or while words kept after a failed Send wait in the review: the ask opens as before, on them.
  **To verify on a real watch:** that watchOS opens on listening, not on its own choices first (the simulator has no
  dictation); if it does show them, it's still one tap to the input.
- **The ask** (Re-record, and Ask when it doesn't listen first): the system's input (Type: dictation, Scribble or the
  keyboard, which watchOS offers with dictation first) and **Quick reply**'s four lines, in Wear OS's order. watchOS has no speech recognizer an
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
- **Not in this step:** Ask tafs (`ask` instead of `coach`, below), which waits on the Wear OS slice and tafs cloud's
  iPhone push. Keep for later came later, behind its own switch (below).
- **Keep for later** (row 8, as Wear OS's `keepForLater`; `CoachModel.keepForLater`, `FileDraftStore`; tested in
  `CoachModelTests`), **on** (`CoachModel.keepForLaterOn`): it keeps the person's words on the watch, so it was switched on
  with its sentence in the privacy page's Apple Watch paragraph and the owner's yes. Off, no store is given, and nothing is
  offered or kept.
  - **Offered** in the review, beside Send, when a Send to Coach got no reply from the phone (no answer, the link failed, or
    not in reach, as Wear OS's "Not sure it arrived"), with the words still the ones sent. Never for a taf's message: nothing
    of a taf's chat is kept on the watch, so that's Send again or close.
  - **Kept:** one message, text only: its words, its message id and the chat it continues (the ticket it replies to), in
    `tafs.draft.json` in Application Support, written with complete file protection. A newer one replaces it. The review
    closes, and Coach's home shows the words with "Kept on this watch. It's sent when your phone is in reach again." and
    **Delete**.
  - **Sent** after the next Needs you read that reaches the phone (the app opened, Coach's home shown, signed in again
    here), from a watch with a passcode only, **under its own id**, so tafs cloud starts it at most once if the first Send
    did arrive. Taken, it's gone from the watch and is a turn of the conversation, with Coach's answer in the Inbox as any;
    it never takes over the ask on screen. Anything else (no answer, an older phone, a failure) leaves it for the next read.
  - **Deleted** on Delete, on Sign out on this watch, and when the phone says it's signed out. Also, unsent, on a watch
    without a passcode, checked as the Inbox's lines are (`CoachModel.checkLock`): when the app starts, opens or is put
    away. So the watch keeps nothing without a passcode. Putting the app away on a watch with one keeps it, since the
    person asked for it to be sent.
  - **Words:** `watch.keep_for_later` and `watch.kept_to_send`, already in every language; Delete is the phone's.
- **v1 is unchanged:** with the switch off, Coach is v1's screen, its words shown before Send.
- **Words:** all already shared (`watch.type`, `watch.type_hint`, `watch.quick_reply`, `watch.qr.*`, `watch.review`,
  `watch.rerecord`, `watch.deleted_nothing_sent`, `watch.voice_text_only`; Send and Delete are the phone's).

**Step (iv), the Inbox kept on the watch** (`Inbox.swift`: `InboxModel`, `FileInboxStore`; `InboxScreen`; tested in
`InboxModelTests`), as Wear OS's:
- **The lines:** each held call the phone lists **on top**, then each answer Coach gives words sent from this watch, each newest
  first (`InboxModel.ordered`): its taf (Coach for an answer), its words, and a blue dot while it's unread. An answer is kept
  until dismissed. A held call is kept until it's dismissed, answered on any device, or past its time: once the phone no longer
  lists it (its read, or its `needs_you` push, below), or its end time has passed on the watch's own clock, it leaves the
  Inbox and its file and isn't counted. Above the lines, how much waits for the person, as the phone last said it (Needs
  you's count, held calls past their time left out); like each call's end time, it's held in memory only, never in the file. A held call's words are what it asks
  **without its amount** (the phone's `summary`, as Wear OS's, with the call's own amount taken out of it in the ways it's
  written (a bare amount with no unit too), then any figure with a currency sign, code or word beside it, złoty, crowns,
  Swiss francs and the Swiss apostrophe included; Needs
  you's title from a phone from before it): the amount
  shows only while Needs you holds the call, in memory, and is never kept on the watch. Coach's answer comes in read, since
  the person saw it arrive, and is kept with its amounts taken out the same way (`WatchAmount.strip`, which the phone's
  summary uses too), so no line kept on the watch has an amount; on screen it shows whole, in memory. Coach's home counts the unread lines ("2 unread in Inbox"), and shows no line with none.
- **Needs you on screen** leaves out a held call past its end on the watch's own clock, and its count, as the Inbox does,
  before the phone's next push or read (`NeedsYouModel.shown`).
- **Opened, it's read.** A held call shows whole while Needs you still holds it, with Refuse and Approve on your phone as Needs
  you has them; else "Already answered, or it ended.". Coach's answer shows whole.
- **Approve on your phone** is a button wherever a held call is shown whole (Needs you, the Inbox, the brief's held call). It
  asks the phone to open that call (`open_on_phone` with `hold`, under the account mark): the phone reads KIFF's held calls
  fresh with its own session and opens Needs you at that call's own card with **no answer chosen**, in front, or with "From
  your watch" whose tap opens it (`WatchOpen.holdDestination`). Answered, lapsed, another account's, or a read that failed:
  Needs you with nothing chosen. Sent under another sign-in's mark, the phone opens nothing and answers signed out. Only the
  hold's id goes; the approval is the phone's card, signed behind Face ID as always. The watch never approves.
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
- **Read and dismissed, in step across the person's watches** (row 21; `InboxModel.sync`, `take`; tested in
  `InboxModelTests` and `WatchInboxMarksTests`): tafs cloud keeps the lines' ids read and dismissed per person
  (`GET`/`POST /v1/watch/inbox`, docs/api.md), by id alone: a held call's KIFF id, or the id of the message whose answer
  the line is. Never a line's words, its taf or an amount.
  - **Read** after each Needs you read that reaches the phone (the app opened, Coach's home or the Inbox shown), only while
    the Inbox is kept (a passcode). A line dismissed on any watch goes, and a held call's id is kept with the ids taken
    away, so the call stays away while the phone lists it (an id the phone doesn't list goes at the next read, so the
    list never grows); a line read on any watch is read here. Nothing is ever brought back.
  - **Sent:** opening a held call's line (Coach's answer comes in read, so it marks nothing), Dismiss, and Clear read (in
    chunks of 100), one after another. A mark that doesn't arrive changes nothing on this watch, which keeps its own.
  - **A phone from before the marks**, or no answer, leaves the lines as this watch has them.
  - **Not here:** the push `change: watch_inbox`. The watch reads the marks when it opens, and iOS throttles background
    pushes anyway.
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
  account's name, the name only when it isn't the email itself, as a personal account's is), Plan (the plan's line as the phone's Account shows it), the watch's language and the app's version, and
  the trust line. With the phone out of reach, or signed out, it says so with Try again, and the watch's own lines stay.
- **Sign out on this watch** (the owner's choice, 19 Nov, as Wear OS's; `WatchSignIn`, tested in `WatchSignInTests`): at the
  foot of Account, asked once more ("Sign out on this watch?", "Your phone stays signed in. This watch forgets your tafs, chats
  and inbox."). It's on this watch alone: nothing is sent to the phone, and it works with the phone out of reach. The watch
  forgets everything it holds: its Inbox (the ids taken away too) and what each page showed. It keeps one flag, an empty file
  `tafs.signed_out_here` in its Application Support, and shows only "Signed out on this watch. Your phone stays signed in."
  with **Ask your phone to sign this watch in**, across restarts, until the phone's owner says yes (row 18, below). Meanwhile it reads and sends nothing, and the
  phone's pushes, which keep coming since the phone isn't told, are dropped as they arrive: a thread, the Inbox's marks or a
  held call is never kept again (`WatchSignIn.takesData`, in `PhoneLink` and the Inbox's `mayDraw`). Nothing from the phone
  signs it back in; only the phone's own sign-out or account change is still heard, since it can only wipe.
- **Signing this watch in again needs the phone's yes** (row 18, since 26 Nov; `WatchSignIn.askPhone`,
  `WatchSignInAsk`; tested in `WatchSignInTests`, `AlertAnswersTests`, `WatchInboxMarksTests`). A watch is signed out so that
  whoever holds it next can't use the account, so a tap on the watch alone never signs it in:
  - **The watch** sends `sign_in_watch` with a one-time id and nothing of the account, and shows "Waiting for your phone…".
    The flag stays.
  - **The phone** asks its owner with a notification, "Sign this watch in as you?". **Allow** is let through only once the
    phone is unlocked (`.authenticationRequired`); **Deny** is a plain no, and so is a swipe away (`.customDismissAction`).
    A tap that only opens tafs answers nothing. A signed-out phone asks no one, and a phone whose tafs notifications are
    off says so to the watch ("Allow notifications for tafs in Settings, then try again.") rather than asking no one.
    The question and its id live in the notification itself (`userInfo`), so they survive tafs being killed. An Allow that
    launches tafs is made before WatchConnectivity has finished activating, so it's held (`WaitingWatchPushes`) and sent
    once activation completes, the mark checked again then, rather than dropped.
  - **Only Allow signs it in:** the phone's answer (`WatchPush.Kind.signIn`) is taken only for the id the watch asked with,
    within ten minutes (`answerWithin`), and with a mark the phone makes. Its mark is heard as an account change, so another
    account's wipes first. Then the flag goes and the watch reads fresh: nothing from before the sign-out comes back.
  - **Deny** says "Not signed in. This watch stays signed out." Out of reach, a phone signed out, or an older phone: the
    watch says why and stays signed out. **Never stuck waiting:** the button stays while it waits (a new ask, a new id), and
    an ask with no answer lapses after ten minutes (`lapse`), so the wait goes and a late Allow is ignored. An app ended
    mid-ask asks again.
  - **The demo** has no phone, so its watch signs in on the tap. The privacy text names the flag ("…and whether you signed out on the
  watch"), which ships with the switch.
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
  id, as before. **Every ask to the phone ends within 20 s** (`PhoneWait.first`): WatchConnectivity can neither reply nor
  fail (seen in the simulator on 19 Nov, its `wcd` dropping the message), and without a limit the watch stayed on its send
  screen with no words. Past it, the phone counts as not in reach; a Send tried again goes under the same id, which tafs
  cloud takes once.
- **Reaching the phone** (`PhoneRoute`, `PhoneRouteTests`; row 44, D, as Wear OS's tafs#1588), so a phone that's there
  isn't called unreachable: the link can say "not reachable" for a moment after the app starts or wakes, so it's looked at
  once more half a second later; and a **read** whose send failed goes once more. A **write** (Refuse, Halt, Halt all, a
  Coach message) is sent once, since a failed send may still have arrived; the person decides whether to try again, under
  the same id. All of it stays inside the same 20 s.
- **Each failure says which**, with Try again: the phone not in reach (`watch.phone_not_reachable`), no answer within the 20 s
  (`watch.phone_no_answer`), tafs not on the phone (`watch.phone_no_app`), or the link's own error or a reply that can't be
  read (`watch.phone_link_failed`).
- **A phone that can only view** (KIFF's `view_only`: signed in with no approval key, as the simulator is) can't sign a watch
  request, and tafs cloud refuses it (403 `view_only`). The watch says so, `watch.view_only_phone` ("This phone can only view,
  so you can't ask from the watch. Sign in on a phone that can approve."), never "a reply wasn't sent".
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
  with no amount in it (the Inbox's rule, `WatchAmount.strip`), then "Approve on your phone", or "The rest is on your phone." where it was cut or something was taken out. watchOS shows it on the
  wrist while the iPhone is locked; a tap opens Coach's chat on the iPhone.
- **Never twice:** an answer the watch read itself, or a watch still reading, posts nothing; nor does an iPhone with tafs
  open on screen, where Coach's chat already shows the answer.
- **Its limit:** iOS doesn't promise the background time, and an answer past the follow is only in Coach's chat. tafs
  cloud's push to the iPhone lifts it for a watch request (below).

**Row 11, an answer you can't miss, with the app put away** (`WatchAskPush`, tested in `WatchAskPushTests`): W3 past its
follow, for a watch request (`coach_send` and a taf's chat).
- **tafs cloud's push wakes the iPhone** each time a watch request changes: a silent push, `change: watch_ask` and the
  request's id alone (`PushDelegate`). However long the answer takes, the iPhone reads that one request
  (`GET /v1/watch/asks/{id}`) with the session it keeps, so it works with tafs not open and the iPhone locked.
- **Only a request this iPhone sent for its watch.** The relay keeps the ids it sent (`WatchAskPushes`, ids only, never
  words, each a day, at most 50): a push for any other request (an Android's watch, an iPad) reads nothing.
- **Posted as W3 posts it,** once the request has ended or a call of it waits for the owner: "Coach", the answer with no
  amount in it, then "Approve on your phone" or "The rest is on your phone.", under W3's identifier for the request.
- **Once at most, and never twice:**
  - a request is posted once, by W3's follow or by the push, whichever has it first;
  - nothing is posted for an answer the watch read itself (kept past the follow), while the watch is reading (5 s), or
    with tafs open on the iPhone;
  - a request still at work posts nothing: the next push says when it changes.
- **What it can't promise:** iOS may hold back silent pushes (Low Power Mode, a phone that's been closed by hand), so a
  request is posted only once the iPhone is woken. Nothing new reaches the watch: it shows the iPhone's notification.
- **Words:** none new. No change to the privacy text: the watch posts nothing of its own, and only ids are kept.
- **Words:** none new (`coach.title`, `watch.approve_on_phone`, `watch.more_on_phone`). No change to the privacy text.

**W4, Ask Coach as one conversation** (`CoachModel.turns`, `CoachHome`, `CoachTurnRow`; tested in `CoachModelTests`), the
owner-approved Watch Coach mockup, as Wear OS's:
- **On Coach's home, above the microphone:** each thing said from this watch since the app was opened, oldest first, so the
  page opens on the microphone with the newest turn just above it. A turn is when it was said, the person's words on the
  right, then Coach's side on the left: "✓ Sent" until Coach starts, "Coach is answering" while its chat says so, then its
  answer, which opens whole, with when it came under it, small. VoiceOver hears Coach's side as "Coach: …, 14:32".
- **Times and days as a taf's chat reads them** (row 44, C): every time in the account's zone (`WatchAccountZone`, learned
  with Your tafs), and a header where a day starts (Today, Yesterday, else the date), as Wear OS shows them. An answer from a
  phone too old to say when it came shows no time.
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
in `TafsCloud+WatchDays.swift`; tested in `WatchDaysTests`), the owner-approved mockup, on since 19 Nov, once tafs cloud's routes were live,
with no app switch since row 35 (tafs cloud's own switch still decides):
- **In Coach's Previous conversations,** a section "From your watch": the days the person asked from a watch, newest first,
  each with how many (`GET /v1/watch/days`), apart from the chats started on the phone. One a day, as the owner chose.
  "Today" is the account's today as tafs cloud gives it (`today`), so someone on another time zone than the account's sees
  it on the right day; a tafs cloud that doesn't give it leaves the account's zone's day, else the phone's.
  A turn's time is in the account's zone too, as the day is and as Android shows it.
- **A day** (`GET /v1/watch/days/{date}`) lists its turns oldest first: "⌚ Sent from your watch" and when, the person's
  words, then the taf that took it and its line, or tafs cloud's words for where it is. A reply sits under what it replies
  to. Read only: a reply from the phone goes on in a Coach chat, as any other.
- **The person's own only,** as tafs cloud serves them: another member never sees them, the account's owner included.
- **Nothing kept on the phone:** each is read when shown, a day is forgotten when it's left, and an answer for a day asked
  before the last is never drawn. A read that fails, or a tafs cloud without the routes (404 `watch_off`), shows no section,
  never an error.
- **Apple Watch days** come with the iPhone sending the Apple Watch's words through `POST /v1/watch/asks` (19 Nov, below), as
  Android's relay does; what was asked before then went to Coach's chat and isn't listed.
- **Words:** `coach.chats.from_your_watch` and `coach.chats.sent_from_your_watch`. The rest are
  `calendar.today` and tafs cloud's own.

**Row 44, A on Apple Watch: not cut off by a pause, and the phone turns it into text (built, behind `WatchVoice.on`, off;
as Wear OS's tafs#1587 and #1596).** W12's path below, without Play and Pause. `VoiceModel`, `AudioRecorderMic`,
`VoiceRecordScreen` on the watch; `WatchVoiceInbox`, `OnDeviceWatchTranscriber` and `WatchLink`'s file delegate on the phone;
`VoiceModelTests`, `WatchVoiceInboxTests`.
- **Ask Coach and a taf chat's microphone** open the recording at once (a watch with a passcode, nothing in flight or kept in
  the review; otherwise the ask as before): "● Recording", the time against 0:30, and ✓. It ends on ✓, at 30 s, or after
  about 8 s with nothing said (`QuietRule`: the level under -40 dBFS, counted from the start or the last word), never on a
  shorter pause.
- **To the phone:** `transferFile` with the recording's id (a UUID) in its metadata, then `transcribe` by that id, 45 s at
  most for the file's way and the recognizer. "Turning it into text on your phone…" with Cancel. The phone moves the file
  in before its delegate returns, waits for it up to 20 s if the request comes first, and reads it with its on-device
  recognizer only (`requiresOnDeviceRecognition`), in the language the person dictates in on the phone, else the app's.
  Only the text comes back, to **Check before sending**: nothing is sent before Send.
- **Can't, and says why:** the microphone not allowed (`watch.mic_needed`); speech recognition not allowed on the phone
  (`voice.speech_recognition_off_tafs_turn`); no on-device model (`watch.no_offline_speech`); nothing heard
  (`watch.didnt_catch`); the file never arrived (`watch.recording_not_received`, new); a phone from before
  (`watch.update_phone`); no answer (`watch.phone_not_reachable`). Try again; Type stays offered.
- **Deleted:** on the phone as soon as it's read, whatever the outcome, and otherwise 2 minutes after it arrived, even if no
  request comes (`WatchVoice.keepFor`), and anything a process left when the phone's link starts; on the watch once the
  text is back, on Cancel or when the screen closes (any transfer still on its way is cancelled first), and when the app or
  a recording starts. The phone's copy is protected until the phone's first unlock, so it can be read while the phone is
  locked in a pocket; only a recording's id (a UUID) names a file, and the recognizer gets 20 s at most, then its task is
  cancelled.
- **Switched on only** with the privacy page's Apple Watch paragraph saying so (today: "It makes no recording"), its
  microphone prompt (`NSMicrophoneUsageDescription`, in English as the phone's) read for its wording, the owner's yes, and the
  real-device checks: that iOS runs the on-device recognizer while tafs on the phone is in the background, and that a
  `transferFile` reaches a backgrounded phone app within the 20 s the phone waits for it. Both decide whether it works with
  the phone in a pocket.

**W12, Play and Pause on Apple Watch: the plan (not built; held for the owner's yes)**, as the owner asked for the next
version ("on the watch first", 19 Nov). On Apple Watch only the phone's path exists: watchOS gives an app no speech
recognizer (no `Speech` framework), and the system's dictation hands back text, never the sound. So words and a recording
can't be one capture on the watch as they are on Wear OS (docs/watch-wear.md, Play and Pause).
- **Start, on the watch:** the app records itself (`AVAudioRecorder`, AAC, 16 kHz mono) into its own container, at most
  30 s, with v2's screens: Ready, Recording, Stop.
- **Stop:** the file goes to the paired iPhone over the watch's own link (`WCSession.transferFile`), which queues it and
  wakes tafs on the phone. Nothing goes to tafs cloud.
- **On the iPhone:** the phone's on-device recognizer turns it into words (`SFSpeechURLRecognitionRequest`,
  `requiresOnDeviceRecognition`), in the person's Coach language, as the phone's own microphone does (`Dictation.swift`).
  The phone's copy is erased the moment the words are made, whether or not they were. Only the words go back.
- **Check before sending, on the watch:** the words, and **Play** (▶) and **Pause** (‖) with "0:03 / 0:12", from the watch's
  own file. Send sends the words only, through the relay, as today.
- **The watch's file is erased** when the review is left (Send, Delete, Re-record, Cancel, put away, signed out), after
  any transfer of it still queued to the phone is cancelled (`WCSessionFileTransfer.cancel()`), so the phone never gets a
  recording the person already threw away; and any
  file no screen holds is swept when the app starts and when it's put away.
- **When it can't, nothing is sent anywhere, and the watch says why:** the phone out of reach (the file waits on the watch
  only until the review is left); no on-device model for the person's language on the phone; speech recognition never
  allowed on the phone (iOS asks only while tafs is open there, so the watch asks the person to open tafs on the iPhone once).
  Dictation and Type stay offered.
- **Checked on the owner's devices, the owner present, before it ships:** that the phone turns a file into words while tafs
  is in the background, and how long 30 s takes there and back. If iOS refuses it in the background, the plan stops there
  and the watch keeps the system's dictation, with no Play.
- **Privacy:** the watch now makes a recording, and it goes to the person's own iPhone; no new recipient. The Apple Watch
  sentence ("It makes no recording") changes with it, held for the owner's yes with the build. New words:
  the watch app's microphone prompt (`NSMicrophoneUsageDescription`) and the three "can't" lines.
- **Switches:** behind `WatchV2.on` (off); v1 and the system's dictation are unchanged.

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
1. **The result push reaches the iPhone too** (done in tafs cloud): each change goes to the person's phones, Android
   and iPhone, as a silent push (`content-available`, `change: watch_ask`, the ask's id alone). iOS throttles silent
   pushes, so the watch also reads `asks` whenever it opens, and a missed push costs only time. The phone's side is
   row 11's (`WatchAskPush`, above): it reads the request and posts it as W3 does. A build from before ignores the push.
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

## The complication (row 24, step 1)

"Ask tafs" on the watch face and in the Smart Stack (`TafsWatchWidgets`, a WidgetKit extension inside the watch app): the
microphone, with "Ask Coach" where the family has room, in the circular, corner, rectangular and inline families.
- **A tap opens the app listening** (`ComplicationLink.ask`, `tafs-watch://ask`): Coach's home, then the system's dictation
  as its microphone starts it, once per tap. Nothing is sent before the person's Send. Signed out on this watch, it opens
  nothing. A tap that opens the app cold (the usual case) is kept until Coach's home appears (`CoachModel.askPending`),
  which takes it once and listens; so does a tap from another page, or with Coach's home already on screen.
- **It shows nothing of the account**: no count, no taf, no words. So there's nothing to hide while the watch is locked,
  and it never asks the phone or refreshes (one entry, `.never`).
- **Signing:** its own bundle id, `bot.tafs.app.watchkitapp.widgets`, and CI's profile "tafs watch widgets App Store CI",
  made by `asc-profiles.rb` in the `testflight` job. Creating that App ID is the owner's call, so this waits for their yes.
- **Step 2, later:** the count of what waits, which needs an App Group shared by the watch app and the widget (a capability
  on the App IDs, so the owner's yes first), and the count kept on disk only while the watch has a passcode, cleared on
  sign-out. Until then the count is on the Inbox page, in memory (step iv).

## Kept and pushed (the owner's eight calls on the watch)

The watch follows the phone's thread without asking it each time, and never asks the person to try again.

- **Pushed, in step with the phone (calls 2 and 3).** tafs cloud's silent push wakes the iPhone (`change: watch_ask`,
  `change: watch_inbox`, and `change: taf_chat`, `taf` or `knock` for a taf's chat: `taf_chat` comes with every message
  in it, from any device, and when its run answers). The iPhone reads the change with its own session
  and hands it to the watch as a `WatchPush` over WatchConnectivity's `transferUserInfo`. That transfer is queued and
  delivered in order, even when tafs isn't open on either device. A push still waiting for an older state of the same
  request or chat is cancelled, so a watch out of reach gets each thread's newest state, not every step. On the watch,
  `WatchPushes` hands it to what's listening:
  - Coach's thread and a taf's thread finish the turn at once and stop reading (`CoachModel.heard`).
  - A taf's chat reads its turns again.
  - The Inbox takes the marks.
  - **Held calls (`change` with a `hold_id`, a hold's alert, or the app's own read finding other held calls):** the iPhone
    reads Needs you with the session it keeps and hands it on as `needs_you` (`WatchHolds`, as Wear OS's `kept/needs-you`):
    the count, and each held call's id, taf, what it asks **with its amount taken out**, and when it lapses. Never the
    amount, KIFF's reason or the time in words. It goes only when it differs from what the watch was last told under that
    mark (`WatchPushRelay.needsYou`). The Inbox puts each call on top and drops one no longer listed, only once this open has
    heard the account is the phone's (`WatchAccountMark.mayDraw`). Needs you on screen drops a call no longer listed at once,
    and reads a new one live, amount and all. With the app put away it reads nothing.
  Only a request this iPhone sent for its watch is handed on, and never one whose answer the watch already read
  (`WatchAskPushes`). A thread opens on its newest message and stays there as messages come in (`WatchThread`).
- **Sent from the thread (call 4).** Your message is a bubble on the right, with when it went and ✓ under it (`MineBubble`).
  The microphone and the keyboard sit side by side right under the newest message (`ThreadInput`). Both open Check before
  sending, as before.
- **View on phone (call 5).** Where the rest of an answer is on the phone, or a call waits to be approved there, View on phone
  sits inline (`ViewOnPhone`). The watch can't bring an app to the front of the iPhone. So it asks the phone
  (`open_on_phone`), and the phone opens the chat if tafs is in front. Otherwise the phone posts "From your watch", and a tap
  opens the chat (`WatchOpen`). Nothing is read or changed. Once the phone answers that it did, View on phone says
  **"Opened on your phone"** for a moment (row 28, as Wear OS's confirmation; `WatchOpenedNotice`), and so does Approve on
  your phone, on that held call. Not reached, nothing is said and the button stays.
- **Linking tafs, never Try again (call 6).** When the phone is out of reach, slow to answer, or the link fails, the watch
  says one calm line: "Linking tafs. Keep your phone nearby and connected." (`PhoneFailure.waiting`). Then it reads again by
  itself after 3 s, then twice as long each time, every 30 s at most (`WatchRetry`). A Send the phone didn't answer goes
  again by itself under the same id, `CoachModel.linkTries` times in all, 2, 4 and 8 s apart. tafs cloud takes it once. Only
  tafs missing from the phone is said as it is, since only installing it helps. Refuse and Halt still offer Try again: each
  is an act of the person's own.
- **A notification you can't miss (call 7).** The answer to a request sent from the wrist is posted as time-sensitive, so it
  comes through a Focus, as a hold does (`CoachFollow.content`). A taf chat's answer is posted the same way, under the taf's
  name, and a tap opens that taf (`WatchTafName`; the taf's id is kept with the request in `WatchAskPushes`).
- **The whole answer from the preview (call 8).** Tapping an answer in Coach's thread opens it whole. A cut answer is read
  whole on that tap (`ask_full`), as a taf's Read more is. The iPhone's From your watch day (W11) offers Read more on a cut
  line too (`more` on the day's turn): the whole answer is read on the tap, shown in place, and kept only while the day is
  open (`WatchDaysModel.readMore`).
- **Kept on the watch (call 1), behind `WatchThreads.on` (on, with Wear OS's `KEEP_THREADS`).** Coach's thread and each taf's thread opened on the watch.
  At most 20 threads, each its last 30 messages, as text with any amount taken out (the Inbox's rule, as on Wear OS), with
  when each was sent and whose it is. Never an attachment, an
  image, audio, a held call's details or a key. One file (`tafs.watch_threads`) with complete file protection, so it can't
  be read while the watch is locked. It's cleared on either sign-out and when the app opens or is put away without a
  passcode, and a thread not opened for 30 days goes (`WatchThreadsTests`). A thread opens at once on this copy, then the
  phone's read and pushes move it on. **On since its sentence went live on the privacy page** (the Apple Watch paragraph),
  with the owner's yes. Switched off, nothing is written or read.

**At 10,000 users:** nothing new polls. Each change tafs cloud already pushes to an iPhone becomes at most one read of that
one request or chat by that iPhone, and one queued transfer to its own watch. A watch that's away gets each thread's
newest state once, not a backlog. The watch's own retries go to its phone only, at most every 30 s while a screen is shown,
and never to tafs cloud directly.

## What the watch keeps

- **In memory only:** the list, the amounts and Coach's answers. They go when the app leaves the
  screen, and are read again when it's back.
- **v2, behind its switch (step iv):** the Inbox's lines (each held call's taf and what it asks, never its amount, and
  Coach's answers, also with no amount) are kept on the watch until dismissed (a held call also until it's answered or lapses),
  only while it has a passcode, in a file with complete file protection, and cleared on sign-out. The count of what waits
  and each held call's end time are held in memory only.
- **v2, behind its switch:** after Sign out on this watch, one empty file, `tafs.signed_out_here`, until Sign in with your
  phone.
- **Keep for later, behind `CoachModel.keepForLaterOn` (on; row 8, as Wear OS's):** `tafs.draft.json`, one message to
  Coach the phone didn't answer: its words, its message id and the chat it continues. Complete file protection; gone once
  the phone takes it, on Delete, on either sign-out, and, as the Inbox, when the app opens or is put away without a passcode. **On with its sentence on the privacy page** and the owner's
  yes.
- **So it opens at once, behind `WatchCache.on` (off; row 44, D, as Wear OS's tafs#1594):** `tafs.watch_cache`, Your
  tafs as last read: each taf's id, name, and whether it's halted or paused. Never a taf's state line (it can name what a
  held call wants), a held call's details, an amount, or a chat. Needs you isn't kept: with a passcode the Inbox already
  opens at once with its count. Complete file protection, so it can't be read while the watch is locked or off the wrist;
  cleared on either sign-out (any reply saying the phone is signed out), when the app opens or is put away without a
  passcode, and after 24 hours; a file that won't read is none (`WatchCacheTests`). Your tafs draws it at once with a small
  dot ("Loading" to VoiceOver) while the phone is asked, and the phone's read replaces it. **A short drop keeps the last
  read** (`ReadGrace`, on whatever the switch): when the phone doesn't answer, what's shown stays with the dot for 30 s, the
  phone is asked once more, and only then is it unreachable. **Switched on only with its sentence on the privacy page**
  (the Apple Watch paragraph says it keeps nothing apart from the Inbox) and the owner's yes.
- **Threads, behind `WatchThreads.on` (on):** see "Kept and pushed" above.
- **On disk:** only the Needs you count and when it was read, for the complication. It's cleared
  when the phone says it's signed out. (This comes with the complication.)
- **Never:** a token, a key, or an amount, a merchant or a person's name in a log, on the watch
  or in the relay.
- **Whose it is (the account mark, as Wear OS's):** everything above that stays on disk is one account's. The iPhone
  makes a random mark at each sign-in (`PhoneWatchMark`; 32 hex digits from nothing about the account, kept in the
  Keychain beside the session, gone at sign-out) and puts it on every reply (`WatchReply.mark`) and every push
  (`WatchPush.mark`). A push read under a session that has since ended is never handed on. At sign-in and at sign-out the
  phone cancels every push still waiting, tells the watch at once and as the application context (which a watch out of
  reach gets as soon as it's back), and takes its own alerts away, delivered and waiting. The watch keeps the last mark it
  heard (`WatchAccountMark`), and checks each reply, push and context before using it:
  - **another account's mark** wipes everything kept (the threads, the Inbox, a message kept for later, Your tafs as last
    read, and the watch's alerts) and each page forgets what it showed, before the answer is used;
  - **no mark once one was kept** (signed out, or a reply that doesn't say whose it is) wipes the same way, and nothing
    kept is drawn;
  - **nothing kept is drawn** before this open of the app has heard the kept mark in a live reply, as on Wear OS: each
    time the app is put away the mark must be heard again (`WatchAccountMark.leave`), since the app can go on running
    while the phone changes accounts. A lowered wrist keeps the open for up to two minutes (`WatchAccountMark.phase`,
    `inactiveKeeps`), so a glance stays as it was; back after longer, it's a new open, since watchOS can keep an app
    inactive on screen for up to an hour, and the phone can change accounts in it. A new open's pages forget what they
    showed and read again; an account change (a push or the context) can wipe, but never lets what's kept be
    drawn, since it may have waited;
  - **account changes are taken in the order the phone made them** (each carries when): one older than the last change
    or live reply heard is ignored, whichever WatchConnectivity hands over first;
  - **a data push** (a thread's change, the Inbox's marks) never changes the account: it's used only with the kept mark,
    and otherwise dropped, since it may have waited on the watch since before a switch;
  - **a message kept for later carries its mark**, and is sent only under that same mark: under another, or none, it's
  deleted unsent, so one person's words never become a turn in someone else's Coach (`WatchAccountMarkTests`).

## Before the watch app: what the phone's notifications already do

The iPhone's notifications show on the watch by themselves. On a held call's notification:

- **Refuse** runs on the phone, signed with the current key, and needs no Face ID. It works
  from the wrist.
- **Approve once** only opens tafs on the phone. Nothing can be approved from the wrist.

## The demo phone

The watch app launched with `-watch-demo` asks a phone of fixed answers (`WatchDemo`, `DemoPhone`) instead of the paired
iPhone, with a passcode set (`DemoLock`), and keeps its Inbox and Sign out on this watch in a folder emptied at each
launch. Everything else is the app as shipped. It's compiled into Debug builds only (`#if DEBUG`, checked by the `watch` job),
so a release build has no demo phone and no way to take the passcode away. The demo phone answers with three tafs (Refunds; Late parcels, paused; Lead follow-up, halted), two held calls,
today's calendar, the account and Coach, and Refuse and Halt change what it answers next, as the phone's relay does.
`-watch-demo-no-passcode` takes the passcode away, `-watch-demo-unreachable` puts the phone out of reach,
`-watch-demo-cloud-down` has the phone fail to reach tafs cloud, and `-watch-demo-taf-page` opens a taf on its page,
as with taf chat off, since a UI test can't reliably turn the vertical page from a taf's chat down to its page.

`TafsWatchUITests` walks the rows a simulator can show with it, in the TafsWatch scheme, so CI's `watch` job runs them:
Coach's home and its unread line, the Menu, the Inbox's held calls with Approve left to the phone, Refuse, Your tafs, a
taf's page and Halt this taf, Halt without a passcode, Account and Sign out on this watch, the phone out of reach, and the
phone not reaching tafs cloud. They need no Simulator app and no signed-in iPhone. What only a real watch shows (dictation,
Hear on the speaker, the system's passcode prompt, the relay to a real phone) stays a check on a real watch.

## Where it lives

- `ios/TafsWatch/`: the watchOS app, with a single target (watchOS 10 and later).
- `ios/TafsWatchTests/`: its unit tests, on a watch simulator.
- `ios/TafsWatchUITests/`: its UI tests, on the demo phone ([The demo phone](#the-demo-phone)).
- `ios/project.yml`: the `TafsWatch`, `TafsWatchTests` and `TafsWatchUITests` targets, and the `TafsWatch` scheme.
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
