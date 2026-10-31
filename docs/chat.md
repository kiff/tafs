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

- their **live chat**, which kiff/agents picks: the latest one, while it's recent;
- a chat they **reopened** from the list, where messages then go on;
- or a **new chat**, when they ask for one.

Chats are each person's own: nobody else on the account reads them, an admin or the
account's owner included.

The app sends the message on the stream route (`POST /v1/tafs/{id}/messages/stream`) so
the answer shows as it's written. When the stream can't open, it sends it plainly
(`POST /v1/tafs/{id}/messages`) and reloads the thread while the answer is owed: every 4
seconds, at most 30 times for each unanswered message.

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
`words`, on the plain route and the streamed one alike (Android: `sendFailure`); the app's
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
next time.

The recognizer's models are per variant, so a phone set to English in France (`en-FR`)
dictates in a variant of its language (`SpeechLanguage.choose`, from Android 13
`checkRecognitionSupport`), in the same order as iOS: the phone's own locale; else, for
English, `en-GB` then `en-US`; for another language, its own country's (`fr-FR`); then
alphabetically. One on the phone now comes before one to download. A language the
recognizer offers but hasn't downloaded is fetched (`triggerModelDownload`) and the mic
says so; a phone with nothing in its language says the mic doesn't work there, and that
typing does. The microphone isn't required to install the app.

## The answer as it comes

On Android, the block under the owner's message is one line (`LiveWork`, `Chat.liveLine`): the
stream's `state` once tafs cloud sends it (`event: state {state, words?}`), "Working…" lines in
the step's words, "Waiting for you", and nothing at `done`, where it folds into the message's
"Worked … · N steps". Before tafs cloud sends `state`, the step at work stands in, else
"Thinking…". The model's thinking text and a list of steps are never drawn.

tafs cloud trims kiff/agents' stream to what the phone shows. Tool inputs and results
never reach the phone.

| Event | Data | What the app does |
|---|---|---|
| `session` | `{session, new_session, run, message: {id, at}}`, first | Notes the chat and the run, for Stop and for steering. `message` is the owner's message as the thread names it, so the app matches its send by id; a message that steers a run already at work has none |
| `thinking` | `{text}` | Adds to the line of what the taf is thinking |
| `text` | `{text}` | Adds to the answer |
| `activity` | `{tool, step?, call_id}` | A step begins, in words: "Asking KIFF: List payments…" |
| `step` | `{call_id, status}` | That step ended: `done`, `error`, or `not_made` (refused back unmade, below) |
| `held` | `{exception, invocation}` | KIFF held the call just made: its card goes here in the answer |
| `taken` | `{}` | The run read a message the owner wrote meanwhile: the thread is reloaded |
| `component` | `{component}` | Something the agent showed: the thread has it |
| `part` | `{at}` | A resumed run went on after a held call was answered: a fresh live answer starts (resumed streams only) |
| `end` | `{status, stop, done?}` | The run stopped. A failed run (`ERROR`) adds `done`: each call that acted and went through, as `{tool, resource?, invocation?}`, never its input (kiff/agents#249) |
| `error` | `{error}` | The stream broke; the thread has the rest |

What a step says comes from tafs cloud, never the tool's name (`steps.ts`): for a tool
through KIFF, KIFF's title for it on the taf's Cards (`grants[].title`, read as the person
and kept a minute); for Coach's and the agents' own tools, what they do ("Drafting the
Card…", "Showing you a card…"). A tool nothing names has no words, and the app shows the
step without them.

### The work block

While the taf works, a block sits under the owner's message (`LiveWork`): the step in
progress on top, one calm line of what it's thinking (shown after a few seconds, changed
at most every second and a half), and the steps done under it. The answer streams below
the block.

When the run ends, the thread is reloaded and the stored answer takes the live one's
place. The block folds in place to "Worked for 12 s" (`WorkSummary`), which opens the
steps again. tafs cloud puts a run's work on its first message: the time from the run's
start to its last words, and the tools it called, in order, with the ones that failed
(`conversation`, from kiff/agents' usage records). Each step is `{tool, words?, error?,
not_made?}`: the app shows `words`, the same as the live step's without the "…", and never
`tool`. On Android the block says how long and how many steps, with how many failed
("Worked 4.2 s · 3 steps, 1 failed", `Chat.workLine`), and opens to the steps that have
words (`Chat.steps`); a step without words is left out, and a live step without `step`
words has no line.

Tries of the same step after a failed one are one row, "3 tries", with the last try's
outcome, and a failed step is left out once something worked (`WorkLog.rows`).

**A step not made.** When a taf sends a call that acts in the same turn as one that
reads, kiff/agents refuses the act back, unmade, and tells the taf to read first. Nothing
reached KIFF or the tool, so it isn't a failure. The step ends `not_made` and shows
"not made · read first" with a ↩ mark; made again, it's one row with its tries; not made
again, it stays in the list.

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

On Android, while a run works, the dots and then its answer are one row at the thread's end, at
least as tall as either, so the answer taking the dots' place never moves the thread back. A new
item at the end is glided to; an answer growing word by word is kept at its end without an
animation, since one started on every word was cut off by the next. `ChatFollowTest` holds
this: an earlier message only ever moves up the screen while a reply streams, in a taf's chat
and in Coach's.

## Writing while the taf works

A message written while the taf works goes to the run at work
(`POST /v1/tafs/{id}/steer`), which reads it at its next step, before its next turn. It
shows under the answer: "It reads this at its next step", or "when it carries on" while
the run waits on a held call. When the run reads it, the stream says `taken`.

If the run ended meanwhile (409), the message waits, and goes as a new message once the
taf is done. A second message written while one waits joins it. A message with files
always waits: files go with a message of their own, never into a run.

What a run never read before it ended, as kiff/agents says, goes as the next message
after the answer.

## Stop, caps and failures

- **Stop** cancels the run at work (`POST /v1/tafs/{id}/stop`). One already ended stays
  as it ended.
- A run that stopped at a **cap** says so, never "…": "stopped: this task would cost more
  than its limit for one task…" (`cost_cap`), or "…took more steps than its limit…"
  (`turn_cap`) (`capWords`).
- A run that **failed** part-way says it stopped part-way, with Retry, which sends its
  message again. If a call that acts went through before it stopped, the line names each
  one by its tool, what it acted on, and how much moved: "Refunds stopped part-way, after
  this went through: create refund on ch_3001, US$5." The list is the run's own record
  (`end`'s `done`), never the model's words; the amount is KIFF's, from the call's Record
  line (by `invocation`), once the Record is read (`failedWords`, kiff/agents#249).

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
  taf working again.

## Cards that wait for an answer

A `confirm`, `questions`, `approval` or `sign_in` card waits for the person's answer: the
run pauses on it, and the answer comes back to the run as data, never as text in the chat.

- The app answers it with `POST /v1/tafs/{id}/components/{component}/answer` `{answer}`
  (Android: `AppModel.answerCard`, with the card's own chat as `session`). Until the thread
  shows the answer, the card says it's on its way; the run that goes on is followed on its
  stream to its end, which reads the thread again, so Coach's next words show in the open
  view. A card that doesn't wait any more (409) is read again as it is.
  Each type takes its own answer: confirm, one of its options' values; questions,
  `{"<question>": {"value"} | {"values"} | {"own"}}`; approval, `approved`, `declined` or
  `failed: <why>`; sign_in, `signed in`, `not now` or `failed: <why>`. The run goes on with it.
- Writing a message instead while a card waits answers it with those words: the run that
  waits goes on, and no second run starts beside it.
- In the thread, each answered card carries `answer` `{state: answered|wrote|no_answer,
  words, at}`: what was chosen, as the card shows it; what was written instead; or that it
  stopped waiting with no answer. Each card draws its answered state from this alone, in
  kiff/agents' words (Android: `CoachWords.answered`); the app keeps no answer of its own. Words written instead also show as the owner's message,
  with `answers` naming the card, so they never vanish from the thread.
- On Android, an answered approval keeps one line saying what it was, "Approved · Connect
  Stripe" (`CoachWords.answeredApproval`): Coach's title for the card, else planque's words for
  the connection, else the draft's Card, else its service. A card waiting for the owner sits
  after what Coach wrote with it, so the open question is at the end; once answered, it goes
  back to its own time in the thread (`CoachWords.items`).
- A card's answer belongs to that card in that conversation. On Android, a card on its way or
  taken, and its place in the thread, is known by tafs cloud's `key` for it, else its
  conversation and id (`ShownComponent.ref`), never the bare id; it is the thread's again
  once the thread holds its answer; a new card Coach sends under an id it used before waits
  for its own answer (`CoachWords.fold`), never shows the earlier one's.
- The app's earlier form, a message `[<type> <id>] <answer>` from the person's own app, is
  still taken as the card's answer when that card waits. Any other text, a door's message, or
  a card that doesn't wait goes as a message. This form goes one release after the app build
  that uses the route.

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

## The thread and the chats list

`GET /v1/tafs/{id}/conversation` is the thread: the taf's last 5 job runs and the
person's last 5 chats, oldest first, at most 100 messages. Each message is
`{at, from: owner|taf, kind: kickoff|message, text, session}`, with `work`, `door` and
`attachments` where they apply. With it come the components, whether the taf is at work
(`working`), what the owner wrote to the run that it hasn't read (`steering`), and where
the latest run stopped at a cap (`capped`).

- A job run's first message is the job's instruction, shown as a small note.
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

## Message ids

Every message in the thread has a stable `id` (`conversation` in `handler.ts`). A run's first
owner message is `<run>.in`, and that's the id `POST …/messages` answers for the message just
sent. Every other message is `<run>.<seq>`. An app matches what it sent to the thread by that id,
and never by text or time.

## The live line

While a taf works, the stream sends one line for the app to show (`event: state`, `relay` in
`handler.ts`). It has one `state`: `thinking`, `working` with the step's `words`, `waiting_for_you`,
or `done`. It's sent only when it changes, and a line holds at least a second (`STATE_HOLD_MS`),
so it doesn't flicker. Waiting for the person and the end go at once. The model's thinking text is
never in it. An app shows this line and doesn't draw the `thinking` events. When the run ends, the
line becomes the message's "Worked … · N steps".

## The thread in one order

The thread also comes as one ordered list, `items` (`threadItems` in `handler.ts`). Each entry
is `{type: "message", message}` or `{type: "card", card}`, by time. A card shown again (the same id
in the same chat, in any run of it) replaces its earlier version where it first appeared, while it waits. Coach's
model names its cards, and may name a new one as it named one already answered: that is a new card, waiting, with
no answer of the old one's. Each card carries `key`, unique in the thread, and an app keys a card, and what it
took or is answering, by `key`, never by the model's `id` (which it still sends to answer). A call KIFF held is
`{type: "held", held: {exception, invocation?, run, session, at}}`, at the point it was held: only
KIFF's keys, and the app reads the call itself from KIFF. An app draws `items` as
they come, and places or folds nothing itself. `messages` and `components` stay, for apps from
before.
