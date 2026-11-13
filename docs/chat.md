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
| `end` | `{status, stop, done?, code?, words?}` | The run stopped. A failed run (`ERROR`) adds `done`: each call that acted and went through, as `{tool, resource?, invocation?}`, never its input (kiff/agents#249). One kiff/agents stopped with a code adds `code` and `words`, its words for people, whole, which the apps show as they are: `ai_unavailable` is the AI provider not serving us, on our side, with none of the person's credits used. It isn't a credits refusal, so a chat keeps Not sent with Retry. An app shows a code it doesn't know by its `words`. A failure with no code has neither, and kiff/agents' error text is never passed on as words (`runFailure` in `runtime/src/cloud/work.ts`) |
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
- A run that stopped at a **cap** says so, never "…": "stopped: this task would cost more
  than its limit for one task…" (`cost_cap`), or "…took more steps than its limit…"
  (`turn_cap`) (`capWords`). A reply cut off at its length limit (`max_token`) says "stopped: its reply ran out of
  room…", and the AI declining to go on (`refused`) says "stopped: the AI declined to continue…". The thread's
  `stopped` names any of the four; an older tafs cloud sends only `capped`, with the two caps, which still works.
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
  `{"<question>": {"value"} | {"values"} | {"own"}}`; approval, `declined` from the phone, and
  otherwise what tafs cloud decided ([coach.md](coach.md#approving-tafs-cloud-decides)): `approved`
  only after a signature it checked, `already approved: <vendor> is connected and its Card is
  confirmed` for a connection and Card approved earlier with their own signature, or `failed: <why>`;
  sign_in, `signed in`, `not now` or `failed: <why>`. The run goes on with it.
- Writing a message instead while a card waits answers it with those words: the run that
  waits goes on, and no second run starts beside it.
- In the thread, each answered card carries `answer` `{state: answered|wrote|no_answer,
  words, at}`: what was chosen, as the card shows it; what was written instead; or that it
  stopped waiting with no answer. Each card draws its answered state from this alone, in
  kiff/agents' words (Android: `CoachWords.answered`); the app keeps no answer of its own. Words written instead also show as the owner's message,
  with `answers` naming the card, so they never vanish from the thread.
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
  answers its result), the thread is read every 2.5 s for about 6 minutes, then every 15 s for about
  25 more, while tafs cloud says a run is at work (`followChat`).
- A run those reads find at work that this phone hasn't followed yet is picked back up by its stream,
  which takes over from the reads.
- While a stream is open, the thread is still read every 30 s (`STREAM_CHECK_MS`). A stream can stay
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
Shippo with its key instead, or to use another app."). It's taken before a retry folds the step away, so
a later read that went through (the catalog, say) doesn't hide it; a later request for the same server, description or grant (or the same app by a known name)
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
  (`AppModel.answerTafCard`). The run that goes on is followed as after any card. iOS follows in its batch after 1.0.
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
- Sent, the sheet says "Thanks. Someone at KIFF will read your report.", or "You've already
  reported this answer." when tafs cloud answers `new: false`. A report that didn't go says why
  in the app's words and keeps the sheet open to send again.
- tafs cloud keeps the answer as kiff/agents holds it, never the app's copy. A run's report
  (`GET /v1/tafs/{id}/runs/{session}`) carries no message id, so it isn't reported from there.

## Message ids

Every message in the thread has a stable `id` (`conversation` in `handler.ts`). A run's first
owner message is `<run>.in`, and that's the id `POST …/messages` answers for the message just
sent. Every other message is `<run>.<seq>`. An app matches what it sent to the thread by that id,
and never by text or time.

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
