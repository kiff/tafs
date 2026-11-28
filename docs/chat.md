# A taf's chat

The owner talks to a taf in its chat: asks what it did, or tells it what to do. A
message steers the taf within its job. It can't widen its Card, and KIFF still checks
every action the taf takes.

Code: in the app, `TafChatView`, `WorkLog`, `MicButton`, `AttachViews`, and the Chat and
"A taf's chat, live" sections of `AppModel` (`deliverChat`, `submitChat`,
`followChatStream`, `resumeChat`, `chatItems`); `TafsCloud.streamMessage` and
`TafsCloud.resumeRun`. In tafs cloud, `handleStream`, `relay`, `message`, `chatAt`,
`conversation`, `latestRun`, `steer`, `stop`, `chats`, `archive` and `unarchive` in
`runtime/src/cloud/handler.ts`. Coach's chat (Setup's) works the same way and shares
most of this ([coach.md](coach.md)).

## Sending a message

A message is 1 to 2,000 characters, or files alone ([attachments.md](attachments.md)).
It is a run in one of the person's chats with the taf:

- their **live chat**, which kiff/agents picks: the latest one, while its last message is under
  30 minutes old (kiff/agents' `DefaultLiveChat`); after that, a message starts a new chat;
- a chat they **reopened** from the list, where messages then go on;
- or a **new chat**, when they ask for one.

A chat shows the taf only what was done in it: other chats, its scheduled runs and the owner's
own changes don't show there. So every taf is told (`SHARED_INSTRUCTIONS` in `runtime/src/jobs.ts`)
to answer a question about a time ("what did you refund today?") from a record that covers it:
its Card's use from `kiff_card`, or the app's own list through its reads. It says which it read,
and never puts work this chat doesn't show down to someone else. `kiff_card` gives each window's
limit and what remains, so what was used is the difference, in the Card's unit (often cents); a time
no window covers, such as a week, needs the app's list.

Chats are each person's own: nobody else on the account reads them, an admin or the
account's owner included.

The app sends the message on the stream route (`POST /v1/tafs/{id}/messages/stream`) so
the answer shows as it's written. When the stream can't open, it sends it plainly
(`POST /v1/tafs/{id}/messages`) and reloads the thread while the answer is owed: every 4
seconds, at most 30 times for each unanswered message.

**One message, one id.** On Android each message the owner sends gets a `client_message_id`
(a UUID), sent on the stream, on the plain send that takes its place and on a steer, and kept
with a message that wasn't sent so its Retry goes under the same id. That holds only where
nothing was taken or it isn't known (no answer, a timeout, tafs cloud's 5xx or 408): after any other
refusal, a 4xx, the message gives up its id and Retry is a new message (`Unsent.keepsId`), since the
same id would only be refused again. A stream that reached tafs
cloud before it failed, or a send that dropped with no answer, is then the same message, and
tafs cloud answers a repeat with the run it already started, never a second one. A message
written after it, or edited, is a new one with its own id. If tafs cloud ever refuses an id as
already used for other words (409 `dedup_key_conflict`), the message is marked not sent with the
app's `error.sent_with_other_text` ("This message was already sent with different text…"),
never "It changed in the meantime" (`ErrorWords`).

The sent message shows at once, faded until the thread has it. The thread's copy is found by
tafs cloud's id for it: `POST …/messages` answers `message: {id}`, and so does the stream's first
event, and each thread message carries its `id` (Android: `SentMessages.merge`). Only a message
that steers a run at work, which has no id, or one from an older tafs cloud, is matched by its
words. On Android, an owner's message is told apart by that id too (`ChatMessage.id`), so two
with the same words at the same moment stay two. A chat shows each item once
(`AppState.chatItems`): the same message twice in a thread shows once and never crashes the list.
An owner's message written while a card waited carries `answers`, and shows "Answered the
card above" under it. An older Coach thread still holds answers in the form the apps once sent,
"[<type> <id>] <answer>"; Android reads them only to show them in the owner's words
(`CoachWords.ownerWords`), and sends nothing in that form. A message that didn't go
stays where it was written, with why and Retry (`UnsentBubble`). kiff/agents allows each
person 10 messages in 10 minutes. Past that, the message is kept with "That's a lot of
messages in a few minutes. Give {taf} a moment, then try again." A run kiff/agents refused
(the period's AI use spent, the taf halted, a version switching) says why in tafs cloud's
`words`, on the plain route and the streamed one alike, and so does any other refusal that
carries them, such as Run now or Send again (Android: `ErrorWords.of`); the app's
own line for the status is only for a refusal that comes without them.

A sent message's menu offers **Send again**, **Edit and send** and **Copy**. The mic in
the message bar dictates into the box, recognised on the device: no audio leaves the
phone, and nothing is sent until the owner sends it (`Dictation`).

On Android the mic (`MicButton`, `voice/Dictation.kt`) uses the phone's on-device
recognizer (`createOnDeviceSpeechRecognizer`, Android 12 and later), in the phone's
language. The microphone is asked for at the first tap. A phone without an on-device
recognizer doesn't dictate and says so, rather than use one that sends the voice away.
Words go into the box as they're heard; listening goes on after a pause and stops after
two quiet ones, a tap on the mic, or Send. As on iOS, where the phone dictates in more than
one of the owner's languages, a switch before the mic shows the one in use ("FR"); each tap
goes to the next, and while dictating, listening starts again in it after what was heard.
The owner's languages are their keyboards', then the phone's, among those its recognizer has
on the phone (`SpeechLanguage.owners`, Android 13 and later), and the one chosen is kept for
next time. On iPhone and iPad, until the owner switches, dictation starts in the app's
language when the device has it (`Dictation.starting`), so a French iPad whose first keyboard
is English starts in French.

The recognizer's models are per variant, so a phone set to English in France (`en-FR`)
dictates in a variant of its language (`SpeechLanguage.choose`, from Android 13
`checkRecognitionSupport`), in the same order as iOS: the phone's own locale; else, for
English, `en-GB` then `en-US`; for another language, its own country's (`fr-FR`); then
alphabetically. One on the phone now comes before one to download. A language the
recognizer offers but hasn't downloaded is fetched (`triggerModelDownload`) and the mic
says so; a phone with nothing in its language says the mic doesn't work there, and that
typing does. The microphone isn't required to install the app.

### Coach reads the page you're on

A message to **Coach** may carry the page the person had in front as they wrote it: the Chrome side panel sends
it beside the words, read once on Send ([chrome.md](chrome.md), "Coach reads the page you're on"), as
`page: {url, title, text, truncated}` on either send route.

- **Only https, only with words.** A page with no words beside it (files alone), an address that isn't https or
  has a name or password in it, a page that isn't `{url, title, text, truncated}`, or one past 24,000 bytes as the
  run reads it, is `400 page_refused`, and nothing starts.
- **A taf, only on its own sites (Chrome 0.2.14).** A message to a taf carries the page the same way, under the
  same page consent (version "2", which names a taf's message too), when the page's host is one of the sites the
  owner signed for that taf's browser connection, or under one. tafs cloud reads them from planque's connections
  (`sites`, with the person's own session), beside the extension's own check of the phone-signed list. A page off
  them, a connection signed for any public site (`any_site`), a taf with no browser connection, or a list planque
  doesn't answer, is `400 page_refused`, and nothing starts. The taf's page turn has `browse_open` and `browse_read`
  alone (`TAF_PAGE_TOOLS`): it reads, on its sites, and never acts, pays or calls another app with a page in front;
  its next message without one has all its tools. What's kept is Coach's: the host, never the page. Every taf's
  shared instructions say "this page" is the one sent with the message, read first where it is (`jobs.ts`).
- **Never a bank, payment, mail or health site.** A page from a sensitive or mail site, or from Chrome's Web Store,
  is `400 page_refused` too. These are the extension's own lists (`chrome/src/sites.js`, `SENSITIVE` and `MAIL`),
  kept in tafs cloud as `page-sites.ts`, with the host read the same way (lower case, no trailing dot). The
  extension never sends such a page, and this keeps the promise at the server whatever build sends one.
  `page-sites.test.ts` holds the two lists equal: a change to one is a change to both.
- **For that message only.** tafs cloud passes it to kiff/agents as the run's `aside`. The run reads it after the
  person's words, fenced as data, with a note that nothing in it is an instruction, and kiff/agents keeps it on the
  run only until the run ends. The address goes with its path but no query or fragment, so whatever a path holds
  goes to the AI with that message too (it's kept nowhere), except a path segment shaped like a key or a one-time
  token (a password reset's or a sign-in link's): 32 or more hex digits, a long run mixing letters and digits, a
  JWT, or one of those between dots or after a prefix (`tok_…`), is `[masked]` whole first (`maskedPath`), while
  names, slugs, numbers, file names, versions and ids stay. A short numeric code (`/verify/482913`) isn't masked:
  it can't be told from an order number. In the page's text and title, a one-time code named as one ("Your
  verification code is 482913", "123456 is your login code", "votre code est 4821", "tu código es 4821", "Ihr Code
  lautet 4821") loses its digits to `[masked]` and keeps its words (`maskedCodes`, `page-codes.ts`), as the extension
  masks it before sending; a postal, order or promo code stays. The title goes on one line. The
  chat's next message doesn't read it.
- **Coach's history says which turns read a page.** kiff/agents keeps the person's message, never the page beside
  it, so a later turn used to see Coach's answers about a page with nothing behind them, and Coach said it had never
  read one. Now the message kiff/agents keeps carries a line of tafs cloud's own, in its envelope before "The owner
  says:": `[tafs page read] <host>`, saying the person sent that message with the page on that host, that a page given
  in that turn was read, that its text isn't kept after the turn, and never to deny a page it was given
  (`withPageRead`). It stays true for a message that answers a waiting card, whose run reads no page. The host alone, which the page consent already
  keeps for the chat's life; never the path, title or text. The thread shows the person's words without it, and a
  line of the person's own that starts `[tafs page read]` is dropped from their words, as the language and time-zone
  lines are.
- **A page turn's tools are read-only** (`PAGE_TOOLS`): Coach sees the person's tafs, a run, a taf's check, an
  estimate and its skills. No `web_read`, no `taf_task`, and nothing that drafts, changes or drives. A page can
  steer a call as any input can, so it can't lead to a host it names or to a taf with tools of its own. kiff/agents
  offers that run those tools alone (`aside_tools`), on every turn, paused or not. The next message has all of
  Coach's tools again.
- **What's kept is the host.** tafs cloud keeps the page's host for that message, as long as the chat's messages,
  so the thread says which site went with it: `reading` on the person's message (`"shop.example"`), and on the
  plain send's `message`. Its address, title and text are kept nowhere in tafs cloud, and the log says
  `coach_page_read` with the run and the host alone. Coach's answer is a message like any other, and can say what
  was on the page. The hosts are one item per person (`pageReadsKey`), their last 200 page messages, each with its
  own time: a host is shown and kept only for 30 days from its own message, however often pages are sent since. A
  page send reads and writes it once, dropping hosts past their 30 days, and a thread read reads it once, whatever
  the thread's length (none when no message in view started a run).
- **Coach's answers from a page turn carry `page_turn: true`**, in the thread and on the stream's `session` event while
  the answer is still coming, from the same kept record as `reading` (so for 30 days from the page message). A link in
  such an answer may come from the page, so the apps show its full address and ask before opening it. It's never the
  page or its host, and no other answer carries it, an answered card's included.
  On Android, a taf message with `pageTurn` (`ChatMessage.pageTurn`, from the thread) draws its words with
  `PageTurnText(asks = true)`: a tap on a link opens no browser but asks "Open this link?" (`chat.link_confirm_title`),
  with the link's whole address in the body (`chat.link_confirm_body`, set apart left to right with U+2066 … U+2069 in
  every language, its own direction characters taken out, so a right-to-left sentence can't reorder it), Open and Cancel.
  Open is offered only for an `https` address with a host and no spaces, controls or direction characters
  (`linkOpens`); anything else (another app's scheme, `tel:`, `sms:`, plain `http`) shows its address and Cancel alone,
  so a page can't steer Coach into starting another app's action. Open opens it; Cancel, or a tap outside, opens nothing. An answer still being written asks too, whatever its turn: a resumed or
  repeated stream sends no `session` event, so it can't yet say, and the thread read that follows settles it. The
  question is held once per chat (`LinkConfirm`), so an answer that finishes while it's up doesn't take it away.
  In any other answer a plain `https` link opens on the tap, and any other link (`tel:`, `sms:`, another app's scheme,
  plain `http`) asks too, with no Open (`linkAsks`): no link in a chat starts another app's action on a tap. Each
  question says what's true of its link (`linkQuestionBody`): one that won't open is "This link won't open"
  (`chat.link_blocked_title`, `chat.link_blocked_body`); one in an answer still being written says so
  (`chat.link_confirm_body_live`); a page turn's says it came from a page Coach read (`chat.link_confirm_body`).
  `MarkdownText(onLink = ...)` hands a link's address to the caller instead of opening it; with none, links open as
  before (the chat always passes one).
- **A message that answers a card waiting in the chat** goes on in that card's run with the person's words alone
  (kiff/agents answers the card with them). It reads no page, and no message says `reading` for it: tafs cloud
  keeps a host only for a run the send started, one kiff/agents created once the send began (a card's run is older,
  by a model turn at least).
- **The confirm, on the iPhone and iPad** (`PageLinks`, `PageLinkGuard`). A link in an answer, a taf's or Coach's:
  - **One that isn't a clean `https` address** opens nothing, in any answer: "This link won't open", "tafs opens only
    secure web pages from a chat. This link goes to: {address}", and **Cancel** alone (`chat.link_blocked_*`). Clean
    means `https` with a host and no space, control or direction character in it, as written or percent-decoded
    (`PageLinks.opens`). So another app's deep link, `tel:`, `sms:`, `mailto:`, plain `http`, or an address with an
    override in it can't start another app's action, or show one address and open another.
  - **A clean `https` link** opens on a tap, except in a message with `page_turn`, or in Coach's answer still arriving (a
    stream picked back up or sent again has no `session` event to say it, so it asks until the thread's read settles
    it). There, a tap shows "Open this link?" with "It came from a page Coach read. It goes to: {address}", or, still
    arriving, "This answer is still being written. This link goes to: {address}" (`chat.link_confirm_*`), then **Open**
    or **Cancel**. A taf's answer still arriving asks only about a link that won't open.
  - **The address** is shown whole, never shortened, and always left to right, in its own isolate, in every language.
  - **The owner's own words** open their links as before.
  - **Only a tap reaches the question,** through `openURL`. A UI test pins it (`LinkLongPressUITests`, on the sample's
    answer with a `tel:` and an https link, `-sampleReplyLinks YES`): after a long-press tafs stays in front and nothing
    opens, a system opener it offers for the `tel:` link (Open Link, Call…) still asks first, and a tap asks with Cancel
    alone.

## The answer as it comes

On Android, the block under the owner's message is one line (`LiveWork`, `Chat.liveLine`): the
stream's `state` once tafs cloud sends it (`event: state {state, words?}`), "Working…" lines in
the step's words or Coach's own progress note ("Miro las últimas ejecuciones de Reembolsos…"),
"Waiting for you", and nothing at `done`, where it folds into the message's "Worked … · N
steps". Before tafs cloud sends `state`, the step at work stands in, else "Thinking…". The
model's thinking text and a list of steps are never drawn. iOS draws the same line on top of
its work block (`LiveWork.line`), and no thinking text either.

tafs cloud trims kiff/agents' stream to what the phone shows. Tool inputs and results
never reach the phone.

| Event | Data | What the app does |
|---|---|---|
| `session` | `{session, new_session, run, message: {id, at}}`, first | Notes the chat and the run, for Stop and for steering. `message` is the owner's message as the thread names it, so the app matches its send by id; a message that steers a run already at work has none |
| `thinking` | `{text}` | More of a summary of the model's thinking, for a definition that shows it. Neither app draws it; tafs' and Coach's definitions ask for progress notes instead (`thinking: "updates"`), which come as the live line's words |
| `text` | `{text}` | Adds to the answer |
| `activity` | `{tool, step?, call_id}` | A step begins, in words: "Asking KIFF: List payments…" |
| `step` | `{call_id, status, failed?, why?}` | That step ended: `done`, `error`, or `not_made` (refused back unmade, below). An `error` step says `failed: true` and, when the tool said something readable, `why`: one line for the owner (below) |
| `held` | `{exception, invocation}` | KIFF held the call just made: its card goes here in the answer |
| `taken` | `{}` | The run read a message the owner wrote meanwhile: the thread is reloaded |
| `component` | `{component}` | Something the agent showed: the thread has it |
| `part` | `{at}` | A resumed run went on after a held call was answered: a fresh live answer starts (resumed streams only) |
| `end` | `{status, stop, done?, code?, words?, retry?}` | The run stopped. A failed run (`ERROR`) adds `done`: each call that acted and went through, as `{tool, resource?, invocation?}`, never its input (kiff/agents#249), and `retry`: whether the app offers Try again, by the thread's own rule for `failed.retry` (`retryOffered`: safe to send again, and not at a day's AI limit before its `resets_at`). One kiff/agents stopped with a code adds `code` and `words`, its words for people, whole, which the apps show as they are: `ai_unavailable` is the AI provider not serving us, on our side, with none of the person's credits used. It isn't a credits refusal, so a chat keeps Not sent with Retry. An app shows a code it doesn't know by its `words`. A failure with no code has neither, and kiff/agents' error text is never passed on as words (`runFailure` in `runtime/src/cloud/work.ts`) |
| `error` | `{error}` | The stream broke; the thread has the rest |

What a step says comes from tafs cloud, never the tool's name (`steps.ts`): for a tool
through KIFF, KIFF's title for it on the taf's Cards (`grants[].title`, read as the person
and kept a minute); for Coach's and the agents' own tools, what they do ("Drafting the
Card…", "Showing you a card…"), in the person's language (`Accept-Language`), so a Polish chat
reads "Przygotowuję Card…". KIFF's title stays the vendor's own words. A tool nothing names has
no words, and the app shows the step without them.

A sign-in card's buttons are the app's chrome: tafs cloud writes `yes` and `no` in the app's
language ("Se connecter", "Pas maintenant"), whatever language Coach wrote the card in. Its
`title`, `text` and `note` are Coach's message, as written.

Its `service` is the app the person signs in to, for the apps' "Sign in to {service}" and "Opens
{service}": the app's name as planque gave it in its answer to a connection call earlier in the run
("Google Sheets"), else Coach's `service` when it's a plain name ("Stripe"). It's never a host: a link's
host can be the service a managed sign-in goes through rather than the app, and the person connects the
app, not the service in between. With no name for the app, `service` is left out, and the apps say "the
vendor".

### The work block

While the taf works, a block sits under the owner's message (`LiveWork`): the live line on
top (tafs cloud's `state`, as on Android; before it comes, the step in progress, else
"Thinking…"), and the steps done under it. The answer streams below the block.

When the run ends, the thread is reloaded and the stored answer takes the live one's
place. The block folds in place to "Worked for 12 s" (`WorkSummary`), which opens the
steps again. tafs cloud puts a run's work on its first message: the time from the run's
start to its last words, and the tools it called, in order, with the ones that failed
(`conversation`, from kiff/agents' usage records). Each step is `{tool, words?, error?,
failed?, why?, not_made?}`: the app shows `words`, the same as the live step's without the
"…", and never `tool`. On Android the block says how long and how many steps, with how many failed
("Worked 4.2 s · 3 steps, 1 failed", `Chat.workLine`), and opens to the steps that have
words (`Chat.steps`); a step without words is left out, and a live step without `step`
words has no line. A failed step is in the failure colour, with tafs cloud's `why` under it
when one is sent; Android adds no word of its own for a failure.

Tries of the same step after a failed one are one row, "3 tries", with the last try's
outcome, and a failed step is left out once something worked (`WorkLog.rows`).

**Why a step failed.** A failed step carries `failed: true` and, when there is one, `why`:
the first line of what the tool answered, in the owner's words, with a service's "answered
403:" prefix, ids, codes and hashes left out, at most 160 characters (`whyOf`, `steps.ts`).
The name of the thing a left-out id was stays, in words: Stripe's "No such payment_intent:
'pi_…'" reads "No such payment intent", never "No such: 'it'". The connection service's
name is left out, and its possessive reads as KIFF's: the owner's Chrome refusing a step "as
not planque's, expired or malformed" reads "as not KIFF's, expired or malformed", never "as not 's".
The live `step` event has it, and the thread's step has the same line: tafs cloud keeps a
run's reasons while the run streams (by run and call, for as long as the run is kept), since
kiff/agents keeps a result only until its run ends and its usage record says only that the
call failed. The app draws `why` under the step and nothing else for it: no " · failed" of
its own, since `failed` is the service's. A step with `failed` and no `why` shows that it
failed, no more. A scheduled run nobody streamed has no reasons kept, so its thread's failed
steps have none.

**A step not made.** When a taf sends a call that acts in the same turn as one that
reads, kiff/agents refuses the act back, unmade, and tells the taf to read first. Nothing
reached KIFF or the tool, so it isn't a failure. The step ends `not_made` and shows
"not made · read first" with a ↩ mark; made again, it's one row with its tries; not made
again, it stays in the list. A connection call the connection server refused for how Coach
wrote it (its order, "read the catalog before…", or its shape, "reusing … takes agent, agent_name and
tools: [] alone", a missing grant id), or a tool Coach named that the catalog doesn't hold by that name ("has no tool named …", "lists no tool named …"), ends `not_made` too, never "Coach couldn't connect …" in red:
nothing was made, and Coach asks again. A reread of the thread says the same (the kept reason is marked
`not_made`).

**A run that paused on a held call** has its work in parts, one for each stretch between
pauses. Each part's work goes on the first message written in it, and its time leaves out
the wait for the owner: the answer that asked for approval keeps what was done before it,
and the work after the approval gets its own "Worked for". A part that wrote nothing goes
on in the next one, less the wait between them. tafs cloud finds a pause from kiff/agents'
usage timing: the first turn after a pause says `resumed` (`turnTiming`, `startsPart`).
Turns recorded before kiff/agents said so (`RESUMED_SINCE`) are read by the rule that came
first: every turn after a tool call lists the calls it waited on, so a timed turn that
waited on none, after the run's first, was the first after a pause.

### The sent message stays on top

Sending puts the message at the top of the chat at once, and it stays there while the
taf thinks, works and answers, with the work block and the answer under it. The thread
changing under it doesn't move it. Scrolling hands control to the owner, and a "Jump to
the latest" button (with a dot when something new came) goes to the end.
`ChatPinUITests` holds this behaviour.

A chat opens at its newest: the end of the thread, once it has loaded, never the top of
whatever came last. On Android a door's knocks sit at their own times among the messages
(`Chat.withKnocks`), so a reply newer than a knock comes after it, and the app's own scrolling
to the end never counts as the owner reading back.

On Android (`TafChatView`), sending glides the owner's message to the top of the list, with
room under it the height of the screen less its turn (the message, the work and the answer),
so even a short one reaches the top and the answer grows into the room without moving it.
The pin follows the message from the copy shown on sending to tafs cloud's (matched by its
words among the rows that are new since the send), and to the words held for a run at work.
It's put back at once when the thread changes under it or the screen grows as the keyboard
goes down, unless the owner has scrolled since; and it stays once the run ends, until the
next message. "Jump to the latest" lets it go and goes to the end. A message that couldn't
go at all lets it go too. `ChatPinTest` holds this, in a taf's chat and in Coach's, with a
long thread and a short one.

On iPhone and iPad (`TafChatView`, and `SetupChatView` for Coach), step by step, for an app
that does the same:

1. **Sending** empties the box and puts the keyboard down. The app keeps the thread's row ids
   as they were (`sentBefore`) and the sent words, trimmed (`pinText`). An earlier pin goes at
   once: the keyboard going down puts a pin back, and it went back to the earlier message.
2. **The first row to show takes the pin**, glided to the top in a quarter of a second. It can
   be the owner's message in the thread: its words match (`SetupThread.matchesSent`), and it's
   not in `sentBefore`, so a reply that came first or an older copy of the same words never
   takes it. It can also be the words held for a run at work (`steer|i`), or the ones queued
   behind it (`queued`). In Coach's chat, an answered card also takes the pin, since its answer
   starts Coach's next answer (the 870 test). A message that couldn't go takes no pin: the chat
   goes to its end, where its Not sent bubble and Retry are, as on Android.
3. **The room** under the thread is the screen's height less the turn: the pinned row's top to
   the thread's last row, measured as laid out (`PinnedTurn`). It's a whole screen until the
   turn is measured. As the work and the answer grow, the room shrinks by as much, so nothing
   moves. It stays once the run ends, until the next message.
4. **The pin moves with the message, without moving the view.** When the pinned row leaves the
   thread, the pin goes, unanimated, to the newest owner message with the same words
   (`pinnedWords`). That happens when tafs cloud's copy takes the place of the one shown, under
   another id. If no row has those words, the pin goes, and the chat goes to its end rather
   than stay past it, blank.
5. **It's put back at the top, unanimated,** when the thread changes under it, or the screen's
   height changes as the keyboard goes down. It isn't put back once the owner has dragged the
   thread since it was pinned (`touched`).
6. **Scrolling waits for the room.** The room is laid out only once the pin is set, so a scroll
   in that same pass stopped at the old end, in the blank under it (the iPad, 21 Oct).
   `pinToTop` scrolls on the next pass, then again at 0.1, 0.35 and 0.7 s, unless the owner has
   scrolled meanwhile.
7. **Letting it go.** "Jump to the latest" lets the pin go and goes to the end. It shows while
   the end is off screen and the thread doesn't follow, with a dot when something new came.
   Seeing the end under a pinned message doesn't make the thread follow.
8. **Coming back from another app changes nothing.** Taking the room away left a shorter
   thread scrolled past its end, blank (the 845 test).

`ChatPinUITests` holds steps 2 to 5 and step 8 in a taf's chat, and a message that couldn't go
(`-sampleSendFails`).

Both apps pin Coach's answered card: on Android, answering a card in Coach's chat keeps its ref (`coachAnsweredCard`) and the chat pins that card's row, as a sent message's (`ChatPinTest`).

With no message pinned (a chat just opened, or a reply that came without the owner sending),
the thread follows its end. While a run works, the dots and then its answer are one row at
the thread's end, at least as tall as either, so the answer taking the dots' place never
moves the thread back. A new item at the end is glided to; an answer growing word by word is
kept at its end without an animation, since one started on every word was cut off by the
next. `ChatFollowTest` holds this: an earlier message only ever moves up the screen while a
reply streams, in a taf's chat and in Coach's.

## Writing while the taf works

A message written while the taf works goes to the run at work
(`POST /v1/tafs/{id}/steer`), which reads it at its next step, before its next turn. It
shows under the answer: "It reads this at its next step", or "when it carries on" while
the run waits on a held call. When the run reads it, the stream says `taken`.

In Coach's chat, the message carries Coach's unread lines in front of the owner's words,
as a new message does, and the thread shows only the owner's words. A message with files
always waits: files go with a message of their own, never into a run.

**tafs cloud holds what the run doesn't take.** It's one contract, for every app. An app
opts in with `hold: true` on `steer`:

- **Sent once.** The app sends a message once, and never resends it on a refusal.
- **Kept per chat.** What waits is kept for the chat it was written in, and only ever goes
  there. Two chats with the taf each wait for their own run.
- **What the run doesn't take is held.** When the run has ended, or holds as many
  messages as it takes, tafs cloud keeps the message, and answers `202`:
  - `{sent: {run, session}}`: the chat's run was over, so it went at once, as the person's
    next message in that chat;
  - `{held: {text, at}}`: what waits in that chat now, this message included.

  Both can come at once: `sent`, with `held` for what didn't fit (below). Neither means
  another read of the thread dealt with it meanwhile: the thread shows how.
- **A second message joins it.** One written while another waits is added to it, after a
  blank line.
- **Never cut.** What waits in a chat is at most one message's worth: 10 messages, and
  2,000 characters in all. A message that would pass either isn't held: `409` with
  `code: "held_full"`, `held` (what waits), and `error` in words the app shows as they are:
  "Up to 10 messages, 2,000 characters in all, can wait for the taf. Send this once it's
  done." What waits is unchanged, and the app keeps the person's words to send later.
- **It goes once the taf is done.** tafs cloud sends it as the person's next message in
  that chat, with Coach's unread lines, as a message they sent. That happens at the first
  of these to find the chat's latest run over:
  - the end of an answer's stream, for each of the person's chats where something waits;
  - the person's next read of the thread (every chat where something waits), or of that
    chat (that chat only).

  One read sends a chat's next message, so two reads never send twice. A send that fails,
  such as the flood guard's 429, puts it back to wait.
- **What the run never read goes too.** For a run written to with `hold`, what it hadn't
  read when it ended joins what waits in its chat, before what was held after it, in the
  order written, and goes as the next message. It joins once: a failed send and a retry
  never send it twice.
- **Whole messages, in order.** When what waits is more than one message can be (what a
  run never read, with what was held), as many whole messages as fit go first, and the
  rest goes as the next message, once that answer is over.
- **Stopped: not sent, with why.** When the person stops the run, what it never read, and
  what was written for it by the stop, isn't sent. A message written after the stop is
  the person's next message, as any other, even when the app still writes it to the stopped
  run because it hadn't seen the run end.
- **The thread's `steering` says all of it,** for its latest chat:
  - `untaken`: what the run at work hasn't read yet;
  - `held: {text, at}`: what waits in that chat to go once the taf is done;
  - `not_sent: [{text, at, why}]`: what a stopped run never read, and what was written for
    it by the stop. `why` is the words the app shows as they are: "You stopped it before it
    read this." The app offers Retry, which sends it as a new message.

Without `hold`, nothing changes for an older app:
- a run that took nothing is `409` ("the run has ended: send it as a new message");
- a run that's over lists what it never read under `untaken`;
- the app keeps its own queue, as before.

**What the app shows (Android):** it sends `hold: true` on every steer, and never resends.
- **A `202`:** the message's pending copy goes. The thread shows it as sent, or as what waits:
  `held`'s words, dimmed as the person's own, with "Waits until it's done, then goes as your
  next message" under them.
- **`409 held_full`:** tafs cloud's `error` shows as it is, and the person's words go back in
  the box to send later. It's not a "Not sent" row.
- **`not_sent`:** each one becomes its own Not sent row once, where it was written, with "Not
  sent · " and its `why`, then Delete (gone for good, nothing sent), Edit (the words back in
  the box) and Retry (sent as a new message). Once Delete or Edit takes it, or Retry sends
  it, the app remembers it by a hash of its run, time and words, never the words, across
  launches (the latest 500, as iOS), so it never comes back, and a second Retry has nothing
  to send. A Retry that doesn't send leaves it Not sent and still owed. Until then a restart
  shows it again: the rows live in memory, and tafs cloud still lists it.
- **An older tafs cloud's `409` with no code:** the message goes as a new one, as before.

iOS drops its own queue the same way, with the same words.

## Stop, caps and failures

- **Stop** cancels the run at work (`POST /v1/tafs/{id}/stop`). One already ended stays
  as it ended.
- **In the apps,** while a run is at work, the round button is Stop when the box is empty.
  With words in it, the button is Send, which writes to the run. Stop names the run's session:
  the live stream's, or else the run tafs cloud says is at work. iOS also falls back to the
  thread's last session. When tafs cloud has taken the stop, the chat is ready again at once:
  the live answer and "…" go, the stream closes, the run reads as stopped, and the thread is
  read again. The run itself ends at its next step, and does nothing after it. A taf's chat
  shows no line for a stop. Coach's says "Stopped". The pin stays: the thread read again under
  it puts it back at the top. A stop tafs cloud refused says so under the thread, and nothing
  else changes. The words differ for now: iOS says "It didn't stop: why" (`work.did_not_stop`),
  and Android says "Not stopped. why" (`chat.not_stopped`). iOS `stopChat` and `stopSetup`,
  Android `stopChat`.
- A run that stopped at a **cap** says so, never "…": "stopped: this task would cost more
  than its limit for one task…" (`cost_cap`), or "…took more steps than its limit…"
  (`turn_cap`) (`capWords`). A reply cut off at its length limit (`max_token`) says "stopped: its reply ran out of
  room…", and the AI declining to go on (`refused`) says "stopped: the AI declined to continue…". The thread's
  `stopped` names any of the four; an older tafs cloud sends only `capped`, with the two caps, which still works.
- A run that **ended failed with nothing to tell it** (one an answer to a card resumed, which has no
  stream) is said in the thread too: `failed: {run, code?, words}` on the conversation, from the
  latest run, in the person's language, and that run is neither at work nor waiting. A run kiff/agents
  stopped with a code has its `code` and tafs cloud's words for it. `ai_unavailable` says it stopped and
  used none of your credits (a failed run is free); one that had already sent something, so
  acted, says it stopped partway instead (`code_ai_unavailable_partway`), with no claim about credits.
  A day's AI limit says when it goes on: `ai_limit_account` (this account's share of the day's AI is
  spent, `code_ai_limit_account`) and `ai_limit_service` (the whole day's capacity, `code_ai_limit_service`)
  take kiff/agents' `resets_at` and say it in the person's zone and language (`timeIn`: "18:00", or "Fri
  01:00" when it isn't today), with partway lines once the run had acted. With no `resets_at` that can be
  read, kiff/agents' own words pass through. The same words reach the stream's `end` and a request's line on
  Its work. Neither offers Try again before `resets_at` (nor without one to read): sent then, it would
  only stop at the limit again. A failure
  with no code says kiff/agents' reason in the person's language, or a plain "It failed", never
  silence (`latestRun` and `runFailure`).
- A run that **failed** part-way says it stopped part-way, with Retry, which sends its
  message again. If a call that acts went through before it stopped, the line names each
  one by its tool, what it acted on, and how much moved: "Refunds stopped part-way, after
  this went through: create refund on ch_3001, €5." The list is the run's own record
  (`end`'s `done`), never the model's words; the amount is KIFF's, from the call's Record
  line (by `invocation`), once the Record is read (`failedWords`, kiff/agents#249).
  When tafs cloud's `end` names why (`code` and `words`, such as `ai_unavailable`: the AI
  provider isn't serving us, and no credits were used), the line is its words as they are,
  then "This went through: …" when something did, still with Retry (both apps).

## Held calls in the chat

A call KIFF holds shows as a card in the chat where the taf made it: after the words the
taf wrote before it, never by clock alone (`chatItems`, `placed`, `heldAfterItsWords`).

- The taf usually writes "waiting for your approval" after the call is held. The card
  waits for those words, at most 45 seconds after the hold, then shows anyway. Needs
  you and the alert show it at once.
- Answering it is the same as anywhere ([approvals.md](approvals.md)). The answer and
  what became of the call ("Approved once by you · 22:41 · Refund €150.00") stay where
  the card was.
- The run paused on the call goes on once it's answered. The app asks tafs cloud to
  continue it at once (`POST /v1/tafs/{id}/holds/{exception}/continue`), and shows the
  taf working again. When tafs cloud answers **404 `run_ended`** (no run waits on that call: the run had
  ended, so the call never ran), the app says so in its `words`, never silence: the chat shows the
  line a run that failed part-way shows, with Retry on the owner's last message, and the screen they
  answered on shows the same words. Any other refusal is left to the alert and the hold's expiry, as before.
- **A version that goes in can stop a task that waits on the owner's answer** (`POST …/versions/{n}/switch` with `stop_blocking`, from the owner's approval
  of a version's Card): tafs cloud's answer then names it (`stopped: [{run, session}]`, with `words`), and the app says so in the same
  line, with Retry on the owner's last message of that session, never silence. A switch that stopped nothing waiting says nothing.

## Cards that wait for an answer

A `confirm`, `questions`, `approval` or `sign_in` card waits for the person's answer: the
run pauses on it, and the answer comes back to the run as data, never as text in the chat.

- The app answers it with `POST /v1/tafs/{id}/components/{component}/answer` `{answer}`
  (Android: `AppModel.answerCard`, with the card's own chat as `session`). Until the thread
  shows the answer, the card says it's on its way; the run that goes on is followed on its
  stream to its end, which reads the thread again, so Coach's next words show in the open
  view. A card that doesn't wait any more (409) is read again as it is.
  Each type takes its own answer: confirm, one of its options' values; questions,
  `{"<question>": {"value"} | {"values"} | {"own"}}`; approval, `declined` from the phone, and
  otherwise what tafs cloud decided ([coach.md](coach.md#approving-tafs-cloud-decides)): `approved`
  only after a signature it checked, `already approved: <vendor> is connected and its Card is
  confirmed` for a connection and Card approved earlier with their own signature, or `failed: <why>`.
  From the person, `POST …/components/{id}/answer` on an approval card takes only `declined` or
  `failed: <why>`; `approved` and `already approved: …` are refused (400), so a session alone, a
  view-only device's too, never makes the card read "Approved", and the old form's `[approval <id>]
  approved` goes as a message;
  sign_in, `signed in`, `not now` or `failed: <why>`. The run goes on with it.
- Writing a message instead while a card waits answers it with those words: the run that
  waits goes on, and no second run starts beside it.
- In the thread, each answered card carries `answer` `{state: answered|wrote|no_answer,
  words, at}`: what was chosen, as the card shows it; what was written instead; or that it
  stopped waiting with no answer. Each card draws its answered state from this alone, in
  kiff/agents' words (Android: `CoachWords.answered`); the app keeps no answer of its own. Words written instead also show as the owner's message,
  with `answers` naming the card, so they never vanish from the thread.
- An approval's or a sign-in's **outcome** comes from the stored `answer` beside `words`, which is
  in no language (tafs#1283): `approved`, `already approved` and `connected` (a connect step that
  worked, its Card next) read as done; `declined`; `rules first` (Coach checked the approval before the owner
  agreed to the rules: nothing approved, nothing failed; an app draws it as a step still to come, never as a failure);
  `failed: why`; or a sign-in's `signed in`. `words`
  are in the owner's language, so they're never read for an outcome, except tafs cloud's exact English
  while `answer` isn't sent. **Nothing reads as approved by default:** any other value or words read as
  not approved, in tafs cloud's own words (Android: `SetupShape.recorded`; iOS and the Mac:
  `SetupThread.answer`). Before this, a declined approval read "Approved · signed in to {vendor}" for
  an owner whose language isn't English.
- On Android, an answered approval keeps one line saying what it was, "Approved · Connect
  Stripe" (`CoachWords.answeredApproval`): the Card when one was signed (KIFF's draft confirmed, or its key claimed since, and holding tools:
  "Approved · Refunds' Card"), else Coach's title for the card, else planque's words for the
  connection, else the draft's Card, else its service, cut to one line however long those are. A card waiting for the owner sits
  after what Coach wrote with it, so the open question is at the end. Once answered, it stays
  after the words that asked for it, and what Coach wrote after the answer (`answer.at`) comes
  below it (`CoachWords.items`).
- A card's answer belongs to that card in that conversation. On Android, a card on its way or
  taken, and its place in the thread, is known by tafs cloud's `key` for it, else its
  conversation and id (`ShownComponent.ref`), never the bare id; it is the thread's again
  once the thread holds its answer; a new card Coach sends under an id it used before waits
  for its own answer (`CoachWords.fold`), never shows the earlier one's.
- The app's earlier form, a message `[<type> <id>] <answer>` from the person's own app, is
  still taken as the card's answer when that card waits. Any other text, a door's message, or
  a card that doesn't wait goes as a message. This form goes one release after the app build
  that uses the route.

**A connection already made says so.** Once tafs cloud sees a connection's grant connected (an approval
it settled, or one it found connected), it notes it, and every approval card in the thread that names that
grant carries `connection: {done: true, words}`: "Qonto is connected (read only)". The apps draw that line and
the card's Card alone, never the connection's text again, so the taf's Card after its key went in doesn't read
as if the key still waited ([secure-connection.md](secure-connection.md)).

**What the fingerprint signs is on the card.** The phone learns the step only from the ask after Approve, and
the fingerprint follows at once, so each approval card still waiting says beforehand what it signs:
`signs: {card: true}` when its fingerprint signs the Card (the `cards`, `key_and_cards` or `tools_and_cards`
step), and `signs: {card: false, words?}` when it signs the connection alone, with `words` ("Its Card: you
approve it next", in the reader's language) when the card carries a draft, or when it's an app's first
connection, before its tools: its Card can only be drafted over the tools the sign-in brings, so the card has
no draft yet, and a Card always follows. A sign-in again (its Card is in force) and tools added to a connected
app with no draft carry no `words`. The apps draw `words` with or without a draft. It comes from the same rule `decide`
asks by (`stepFor`), read as the phone: the chat's read carries `Planque-Device-Signature` and what the phone can
sign (`?can=tools_and_cards,key_and_cards`). Without the proof, or when planque refuses the
read, the cards carry no `signs` and the apps draw them as before. A read slower than 1.5 s doesn't leave a waiting card
unmarked: it's marked from the grants this person's cards were last marked from, kept in tafs cloud for 10 minutes
(`grantsForMarks`), while the late read refreshes that copy for the next one. A read planque refuses drops the copy
until a later read is held, so it only stands in for a device planque still accepts. With no copy that recent, the cards
carry no `signs`, as before. planque took 1.8 to 4 s on 27 Nov, and a connection's card inside an open approval window drew
"Approve with your fingerprint" for want of its mark. A phone that draws by the mark says so with
`signs` in `can` (on the read and on the ask): only then is a held sign-in's first tools offered with the Card
under one fingerprint ([secure-connection.md](secure-connection.md)). With `card: false`, an app never draws the
Card's limits as being signed now: they go under `words`.

**A sign-in wider than the job is said on the card.** When planque couldn't narrow an app's sign-in to what the
taf uses (`request.connector.usual_access` on its grant: the app's sign-in offers no narrower scope, such as Google Sheets with no
read-only one), each waiting approval or sign-in card for it carries `wider: {words}`, in the reader's language: who
connects, by the grant's route (Composio for a managed sign-in, tafs for its own app at the vendor, the owner for a key;
named by nobody when the grant says neither), then what the app may ask for, then what tafs enforces: "Composio
connects your Google Sheets account. Google Sheets may ask for broader access than this job needs, but your taf can
only use the tools allowed on its Card. tafs enforces those limits and blocks every other tool call." (`card_wider`,
`connectRoute`). Every app that can approve a card draws it above the card's buttons
(`contracts/coach-wider.json`). It's read with the same grants as `signs`, so only with the phone's proof
(`markWider`). It's planque's data, the same for every app: Coach is told nothing about it, and never describes a
sign-in's access itself (`connect-an-app@29`).

**Whether Approve is a tap is on the card too:** `signs.takes`, `"tap"` or `"fingerprint"`, from the key `decide`
then asks with (`takesOf`). Connecting an app (`connect`) is a tap inside an open approval window. The key or added
tools signed with their Card (`key_and_cards`, `tools_and_cards`) always take the fingerprint. A Card alone (`cards`)
takes the key KIFF names for its draft (`confirm_key`, read within the same 1.5 s), and the fingerprint while KIFF
names none. When KIFF's draft can't be read, `takes` is left out. The apps draw KIFF's window words ("No fingerprint
for the next 4 minutes", [approvals.md](approvals.md#one-fingerprint-then-taps-the-approval-window)) only on a
`"tap"` card: on any other, Approve asks for the fingerprint, and the card doesn't say otherwise. A card whose
Approve signs nothing is marked `{"card": false, "takes": "none"}`: `decide` answers it with no step (a connected app
or a signed-in one with no draft, already approved or nothing new; a connection past approving). No app names the
fingerprint over it. An app that doesn't know `"none"` draws the card as before.

## Coming back to a run at work

When the owner leaves the chat or the app and comes back while a run works, the app
picks the run back up once (`resumeChat`, `GET /v1/tafs/{id}/runs/{run}/stream`). That
stream sends the run's recorded steps (`activity`, `step`, `held`), `said` where the taf
wrote a message (the app reloads the thread), `part` `{at}` where the run went on after a
held call was answered, then `end`.

- A step the stream sends again keeps its status: a finished step never goes back to
  running.
- On `part`, the app starts a fresh live answer (`LiveAnswer.newPart`). The steps and the
  held card from before the pause stay with the answer written then, never shown again as
  the resumed run's work.
- The live answer leaves in the same update that brings its words into the thread, at a
  `part` and at the end (`readThread`'s `alongside`). Never both at once, nor neither: the
  chat's end would grow by a row and shrink again, and on Android the thread jumped back and
  forth while Coach wrote. Where the thread can't be read, the live answer goes all the same.
- On Android each row keeps one key from its first frame to its last, so the list updates it in
  place (`Chat.rowKeys`, `Chat.liveKey`): an owner's message by tafs cloud's id, which the
  stream gives at once (`<run>.in`); a taf's message `<run>.<seq>` by its run and its place
  among that run's messages. The live answer has the key its words will have in the thread, so
  the answer lands in the live answer's own row. The thread follows its end on the last row's
  height as well as on the words arriving, at once and only while the owner is at the end.

**A run Android follows to its end, never left as "…"** (Android 1.22):
- With no stream open (one couldn't open, or a run went on with none, as Coach's does once tafs cloud
  answers its result), the thread is read less and less while tafs cloud says a run is at work
  (`followChat`, `chatFollowDelay`): every 2.5 s for the first minute, 5 s to the third, 10 s to the
  sixth, then 15 s, for about 31 minutes in all.
  - Each wait is up to a fifth shorter at random, so phones don't read in step, and never longer than
    its step.
  - Nothing is read while the app is in the background. Coming back reads once, at once, so a run that
    ended meanwhile shows as ended; then the back-off goes on.
  - At 10,000 users each with a run followed this way, that's about 167 reads per run where it was 250,
    and none from phones in the background (docs/scale.md). The first minute is as before, so this
    trims a long run's tail, not the peak; the stream and the push are what lower the peak.
- A run those reads find at work that this phone hasn't followed yet is picked back up by its stream,
  which takes over from the reads.
- While a stream is open, the thread is still read every 30 s (`STREAM_CHECK_MS`), not while the app
  is in the background, and once at once when it comes back. A stream can stay
  quiet for many minutes after its run ended without saying so. Once tafs cloud says nothing is at
  work there, and the live answer has stood for more than 15 s, any read (that check, or coming back
  to the chat) drops the live answer, so "…" and Stop go and the thread shows what the run wrote.
  It also closes the quiet stream, so the next run is followed again.

## The thread and the chats list

`GET /v1/tafs/{id}/conversation` is the thread: the taf's last 5 job runs and the
person's last 5 chats, oldest first, at most 100 messages. Each message is
`{at, from: owner|taf, kind: kickoff|message, text, session}`, with `work`, `door`,
`attachments` and `delivered` where they apply (`delivered`: on a job run's report, where its
result went, in tafs cloud's words, "Sent to Google Sheets · Refunds 2026 and handed to
Invoices"; the app draws it above the taf's own words, `docs/reliability.md`). With it come the components, whether the taf is at work
(`working`: also while Coach's run is parked because the person has the app, with `parked: true`,
since it goes on in this chat at Continue; a run held for the owner's approval isn't, as its card
shows it), what the owner wrote to the run that it hasn't read (`steering`), and where
the latest run stopped without finishing (`stopped: {run, stop}`, where `stop` is `turn_cap`,
`cost_cap`, `max_token` for a reply cut off at its length limit, or `refused` for the AI declining to
continue; the app closes the thread with a line for it). `capped` carries the two caps alone, for apps
that read only those. A job run's report may carry `delivered`, tafs
cloud's one line on where its result went; Android draws it above the taf's words, as written
(`ChatMessage.delivered`), and nothing where none comes. It may carry `counted` too: the run's count
from its record (kiff/agents' `tally`, summed over its tools), in tafs cloud's words, "From the Record —
done: 5 · waiting for you: 1 · refused: 2", each part at 0 left out (`countedLine`). The taf gives no
totals of its own (kiff/agents#421), so this is where the owner reads them, never the model's
numbers. None for a run that counted nothing. `GET /v1/tafs/{id}/runs/{session}` gives the same line
on its `report`.

**A connection the server refused** (`refused`, on the message that carries the run's `work`): when a
connection Coach asked for in that run was refused by the connection server, and no request for the
same app went through later in the run, the message says so in three lines, all tafs cloud's words in
the person's language: `words`, the failed step's own ("Coach couldn't connect Shippo yet"); `why`, the
server's reason, scrubbed as any step's reason is (`whyOf`) and with any host name or phone number left out too (`shownWhy`: the server can quote a description a third party wrote), as a line ("Why: managed sign-in isn't set up
for this app"), left out when nothing readable is left; and `next`, what to do ("Next: ask Coach to connect
Shippo with its key instead, or to use another app."). A read Coach makes as itself while it sets up (`planque read` of a
`setup_reads` tool) that KIFF refuses for want of a Card (`no_mandate_issued`, `mandate_not_covered`) isn't the
connection failing: planque issues that Card when the owner approves, and a slow sign-in can leave it unissued while the
connection works. Its lines say so and never ask for a sign-in: "Coach's reads of Stripe couldn't be set up", "Stripe
is connected: only the reads Coach makes while it sets up weren't allowed.", "Next: ask Coach to go on without reading
Stripe first." (`step_reads_*`). It's taken before a retry folds the step away, so
a later read that went through (the catalog, say) doesn't hide it; a later request for the same server, description, catalog entry or reused grant (or the same app by a known name), or tools set on the same grant (even while they wait for the owner's approval),
that went through does, and so does any later connection call whose answer shows it `signed_in`, `listed` or
`connected`, so "couldn't connect" never stands under "signed in" (`Steps.connectedBy`); one showing it failed
clears nothing (`resolved`, `takeRefused` and `Steps.connectionRefusal` in
`runtime/src/cloud/steps.ts`). Both apps draw the lines under the taf's words, never folded, the first in
the failure colour, and add none of their own (`RefusedLines`). The live stream's `step` keeps only `why`.

- A job run's first message is the job's instruction, shown as a small note. When its schedule's check handed it
  items, the note says how many after the instruction ("Its check found 2 new items.", `checkStart`), never the
  items themselves: they're the app's data, kept whole in the run's input for the taf, not the owner's words.
- **The loop card**, kiff/agents' `confirm` with id `tafs-loop-N` and `loop: {tool, pages}`, shown when a run read
  page after page of one list, comes in tafs cloud's words in the person's language: "Still reading", the read by
  its KIFF title (else "the same list", never its tool name), and **Read on** / **Stop here**, whose values stay
  `tafs_loop_continue` and `tafs_loop_summarise` (`loopCardWords`).
  The run waits on it in the taf's own chat. Android draws it there as Coach's confirm card, with tafs cloud's
  words as sent (`isTafCard`: a `confirm` that carries `loop`; any other card but `ask_coach` stays Coach's), and the
  answer goes to the taf's route, `POST /v1/tafs/{id}/components/{component}/answer` with the card's own chat
  (`AppModel.answerTafCard`). The run that goes on is followed as after any card. iPhone and iPad draw it the same way
  (`TafsCloud.LoopCard`, `LoopCardView`), with tafs cloud's words, and answer it on the taf's route with the card's chat
  (`AppModel.answerLoopCard`); the run that goes on is followed again. Once answered, the card says tafs cloud's words
  for the answer, else "Answered." (`coach.answered`), never nothing where its buttons were.
- A message that came through a door shows with its door and sender ([doors.md](doors.md)).
- `GET /v1/tafs/{id}/chats` lists the person's chats, newest first, each named by the
  start of what they asked. One tap reopens a chat.
- Archiving a chat takes it out of the list and the thread, and messages no longer go
  on in it as the live chat. It isn't deleted: kiff/agents keeps it its 30 days.
  Archived lists them to reopen or put back.

## In sample mode

The sample taf answers as a real one does, streamed into the live answer: it thinks,
takes a step, then writes. Nothing reaches a model. `-sampleReplySeconds` slows it for
UI tests ([ios-app.md](ios-app.md#launch-arguments)).

## Reporting an answer

Any answer Coach or a taf wrote can be reported from inside the app, as Google Play asks of
apps that generate content with AI (`POST /v1/tafs/{id}/messages/{message}/report`,
[api.md](api.md)). It's for answers only, never the person's own messages, and only once tafs
cloud has an id for the answer (not in the sample).
- On Android, every finished answer from Coach or a taf (a taf's chat, Coach's and a trial's) has
  its text selectable, and under it **Copy**, which copies the whole answer and says "Copied", in
  the sample too. **More actions** (⋯) holds **Report this answer**, only where the answer can be
  reported. Opening the menu sends nothing (`AnswerWithActions`). Report opens a sheet with four reasons (Offensive, Harmful,
  Wrong, Other), an optional note of at most 1,000 characters, and **Send** (`ReportSheet`,
  `AppModel.reportAnswer`). The message's chat goes as `session`.
- On iPhone and iPad, the answer's context menu (a long press) shows **Report this answer**, in
  a taf's chat, a trial's and Coach's (`reportable`, `ReportAnswerSheet`, `AppModel.reportAnswer`).
  The same reasons, note and **Send**, with the message's chat as `session`.
- In the Chrome side panel (0.2.24, [chrome.md](chrome.md)), under each answer, **Copy** and ⋯ with **Report this answer**:
  the same reasons, note and **Send** on a screen over the panel, always with the chat's `session` (tafs cloud takes the
  panel's report only for an answer it may show there).
- Sent, the sheet says "Thanks. Someone at KIFF will read your report.", or "You've already
  reported this answer." when tafs cloud answers `new: false`. A report that didn't go says why
  in the app's words and keeps the sheet open to send again.
- tafs cloud keeps the answer as kiff/agents holds it, never the app's copy. A run's report
  (`GET /v1/tafs/{id}/runs/{session}`) carries no message id, so it isn't reported from there.
- tafs cloud keeps a report 180 days, with the answer and the person's note (`reports.ts`). **Deleting the chat erases
  the person's reports of its answers with it** (`deleteChat`), and deleting the account erases them all. The email
  that told KIFF's support of a report is the support inbox's.

## Message ids

Every message in the thread has a stable `id` (`conversation` in `handler.ts`). A run's first
owner message is `<run>.in`, and that's the id `POST …/messages` answers for the message just
sent. Every other message is `<run>.<seq>`. An app matches what it sent to the thread by that id,
and never by text or time.

**The app's own id for a message (`client_message_id`, [api.md](api.md)).** It's made by the app, before the send.
On iPhone and iPad, each message the owner composes, to a taf or to Coach, gets its own UUID, in lower case.
- **The same id goes on every path the message takes:**
  - the stream;
  - the plain send that takes its place when the stream can't open or is cut;
  - a steer to the run at work.
- **A message that wasn't sent keeps its id when nothing answered** (`UnsentMessage.messageID`): a dropped
  connection, a timeout, or a 5xx such as a 503. Retry then sends it under that id. If the first send reached tafs
  cloud after all, tafs cloud answers with what it already did, never a second run.
- **A new id:** Edit, Send again and a new message are new messages.
- **A refusal (a 4xx) gives up the id,** so Retry is a new message (`TafsCloud.messageID(_:after:)`). Among them is
  409 `dedup_key_conflict`, an id already used for other words. It comes with no words of its own, so the message
  is marked not sent with `error.sent_with_other_text`, which says to send it again as a new message. The same id
  would only be refused again. tafs cloud's own words, when it sends them, come first.
- **No id of the app's own:**
  - what a run never read (tafs cloud's `not_sent`), whose Retry sends it as a new message, under a new id;
  - an improvement's first message, which goes through `/improve`;
  - a trial's chat.
- **On the Apple Watch,** the watch makes the id for the words it sends to Coach. The phone sends it on unchanged.
  The same words sent again after no answer came keep it; other words, or the next message once answered, get
  another ([watch-app.md](watch-app.md)).

## The live line

While a taf works, the stream sends one line for the app to show (`event: state`, `relay` in
`handler.ts`). It has one `state`: `thinking`, `working` with `words`, `waiting_for_you`, or
`done`. It's sent only when it changes, and a line holds at least a second (`STATE_HOLD_MS`), so it
doesn't flicker. Waiting for the person and the end go at once.

`working`'s `words` are the step's, or the model's own short progress note before a tool call.
On Claude Opus 5.5 a line Coach writes before a tool call comes back as such a note, not as text
(kiff/agents' `update`, with the definition's `thinking: "updates"`). tafs cloud puts it on the
line as one line, cut at a word past 90 characters, without its closing mark, since the app adds
"…" after a working line's words (`noteWords`). A note replaces `thinking` at once, whatever the
hold: it's the owner's first words. The note is all of the model's the line may carry: its
thinking text is never in it, and an app doesn't draw the `thinking` events. When the run ends,
the line becomes the message's "Worked … · N steps".

The same line can come back as text instead of a note, run to run. So tafs cloud holds a turn's
text while it is still one short line (`heldLine`: no line break, at most 90 characters), and puts
it on the live line as it's written, the same as a note. If anything but a tool call comes in that
turn (more than a line, a component, the end), it was the answer: the line goes to `thinking` at
once, with no hold, then the held words go as `text`, so they never show twice, and the rest
streams. An answer waits for at most its first line. At a tool call the held text is kept as a
note: it's dropped if the run writes again after its tools or shows a card (the card speaks for
itself), and goes out as `text` only if the run ends first, and then only the last one. In the
stored thread a message its run's tool calls follow (`preToolLines`) is a note too, however long:
it isn't a message when anything follows those calls (the run writing again, a card, the owner's
answer to one, a pause). Only a run's final words reach the chat, so a retry or a self-correction
Coach wrote before a card never does. A run that ends with no words after its tools keeps its last
note as its message.

## The thread in one order

The thread also comes as one ordered list, `items` (`threadItems` in `handler.ts`). Each entry
is `{type: "message", message}` or `{type: "card", card}`, by time. A card shown again (the same id
in the same chat, in any run of it) replaces its earlier version where it first appeared, while it waits. Coach's
model names its cards, and may name a new one as it named one already answered: that is a new card, waiting, with
no answer of the old one's. Each card carries `key`, unique in the thread, and an app keys a card, and what it
took or is answering, by `key`, never by the model's `id` (which it still sends to answer). A call KIFF held is
`{type: "held", held: {exception, invocation?, run, session, at}}`, at the point it was held: only
KIFF's keys, and the app reads the call itself from KIFF. In Coach's chat, a drive's end is
`{type: "drive", drive: {id, session, task, why, words, steps, at}}`, at the time it ended: the request, why
it ended in the person's words, and each step from tafs cloud's record (kiff/tafs#568, [drive.md](drive.md)).
Every card carries `waits` when its run waits on it (`owner`, or `service` for a result tafs cloud answers), as
kiff/agents marks it; a card from before kiff/agents marked them gets the mark from its type, as kiff/agents'
lists were then (`waitsOf`). An app places a card by it, with no list of its own. An app draws `items` as
they come, and places or folds nothing itself. `messages` and `components` stay, for apps from
before.

## When each message was sent

Every chat shows when each message was sent (row 44, the owner's review; the placement follows hermes-agent's chat, a time per
message and a line where a day starts, with none of its code). Coach's chat is the same view as a taf's.

- **Under each message, its time, small:** the person's under their bubble on the right, the taf's or Coach's under its words on
  the left; a run's kickoff keeps its own line. It's `at` from the thread, in the account's zone, in the device's 12- or 24-hour
  setting and the app's language ("14:05", "2:05 PM").
- **Every other time of day on a screen follows the same setting** (kiff/tafs#2195): the decision line under a held call
  ("Approved once by you · 10:22 PM"), "next run at", "halted since" and the rest, so one screen never shows two clock styles.
  Android: `shortTime` (`model/AccountTime.kt`) reads the device's setting when the app starts and each time it comes back
  (`ShortTimeClockTest`); a line already stored keeps the time it was written with. iOS: `Date.shortTime`
  (`Design/Components.swift`), the hold alert's deadline (`HoldNotification.time`) and the alert extension's own
  `shortTime` all go through `ChatTime.time`, the chat's stamps' own clock, read each time it's drawn
  (`TimeZoneTests.testEveryTimeOfDayFollowsTheDevicesClockAsTheChatDoes`); a line already stored keeps its time too.
- **A header where a day starts:** over the chat's first message and each first message of a new day: **Today**
  (`calendar.today`), **Yesterday** (`chat.day.yesterday`), then the date in the language's own order from the platform's
  skeleton (`EEEMMMd`: "Wed, Nov 18", "Mi., 18. Nov."), with the year (`EEEMMMdy`) when it isn't this one. It replaces the
  time Android drew between parts of a thread an hour apart.
- **One rule for the phone and the watch:** `ChatTime` in `android/watchlink` (`ChatTimeTest`), with the patterns from
  `DateFormat.getBestDateTimePattern`; a pattern `java.time` can't read falls back to the language's short form. Android:
  `messageTime` and `chatDay` (`model/AccountTime.kt`), drawn in `TafChatView` (`ChatTimesTest`). The watch: docs/watch-wear.md,
  in the same account's zone, which the phone sends it (`WatchReply.Tafs.zone`), so a message reads the same time on both.
- **iPhone, iPad, the Mac and the Apple Watch:** `ChatTime` (`ios/WatchShared/ChatTime.swift`, `ChatTimeTests`), the same rule with
  `setLocalizedDateFormatFromTemplate` (`Hm`/`hm` from the device's `j`, `EEEMMMd`/`EEEMMMdy`), drawn as `ChatDayHeader` and
  `ChatSentTime` in `TafChatView` and Coach's `SetupChatView`. It replaces the time iOS drew between parts an hour apart.
  On the Apple Watch, a taf's chat (`TafChatPage`) shows the time under each earlier turn, the turn's time over each new
  one, and a header per day, in the account's zone, which the phone sends with the chat (`WatchReply.zone`), as Wear OS does;
  a phone too old to send it leaves the watch's own. A reply saying the phone is signed out drops it (`WatchAccountZone.heard`),
  so the next account reads in the watch's own zone until its first reply names one, as Wear OS does.
- **The Chrome side panel** (0.2.24, `chrome/src/chatrow.js`, [chrome.md](chrome.md)): the same time under each message and
  header per day, through `Intl` in the panel's language and the computer's zone, with the language's own 12- or 24-hour
  clock, since a browser doesn't say the computer's.
