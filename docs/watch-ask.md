# Asking from the watch

The owner taps the microphone on their Wear OS watch, says what they want, and lowers their wrist. The request goes to the
one taf that can do it, under that taf's own Cards, and its result comes back to the watch as one short line, shown and
spoken. Nothing on the phone's screens or in Coach's conversations on the phone changes.

This page is the service's half: tafs cloud's route, Coach's watch conversation, and the result. The watch and the phone's
relay are in [watch-wear.md](watch-wear.md).

## The path

1. **The watch** makes an id for the request (`client_id`) and keeps it for a retry. It sends the request's text to the
   phone over the Data Layer. The watch holds no keys.
2. **The phone's relay** signs the request with the phone's **device key** (`KIFF-Device-Signature`, as a taf's removal is
   signed, never the approval key) and sends it to `POST /v1/watch/asks` with the person's session.
3. **tafs cloud** has KIFF check the signature, then opens a **new Coach conversation of the watch's own** with one skill,
   `ask-a-taf`, and answers at once with a receipt: the request's id and its state, `accepted` ("Sent").
4. **Coach** reads the tafs (`tafs_state`) with what each **can** do: its own words and the tools it holds. It hands the
   request to the one taf that can do it (`taf_task`), or, when none can or more than one could, hands it to none and
   says so in one sentence.
5. **The taf's run** pauses (a call held for the owner) or ends, and Coach's watch conversation gets one more turn to say how
   it went in one line.
6. **tafs cloud checks the line** and keeps it on the request. Each change of state is a background push to the person's
   Android phone with the request's id alone; the relay reads the request and passes it to the watch.

## The watch mark

Only the route marks a conversation as the watch's: it is where the request came in, never a field, a header or a word a
message carries.

- `POST /v1/watch/asks` is the only way a conversation opens with the watch's skill. Its body is exactly
  `{client_id, text, reply_to?}`: anything else is 400.
- The conversation's entry point is set by the route. The entry point read from a phone message's text (`entryOf`)
  is never `watch`, so no message, whatever it says, loads `ask-a-taf`.
- `ask-a-taf` is **named-only** in Coach's definition. Coach's map of skills never lists it, `read_skill` never reads it in
  another conversation, and no tool adds it.
- The watch's conversation is held to what it was opened with (kiff/agents' session `only`):
  - `read_skill` reads only its own skill, so the phone's connect and Card skills are out of reach;
  - the model is offered only `tafs_state`, `taf_task` and `read_skill`;
  - a tool's skills are never added to it.

## What a watch request may do

- **One taf, per request.** A second `taf_task` in the same conversation is refused, whichever taf it names, and Coach's
  turn that writes the result line hands nothing over at all: its input is the taf's own result. The task
  starts a new run of the person's own, never into a run at work.
- **Under the taf's own Cards.** `taf_task` is Coach's, under `coach-taf-task` (`{limit: 50, asks: true}`), and the taf's
  run is under its own Cards. Nothing on this path creates a grant, approves a Card, widens one or signs anything. A
  call the taf's Cards hold waits for the owner on the phone, and the watch's line says so.
- **Only the tafs the person may use.** `tafs_state` in a watch conversation lists only those KIFF says they may use.
- **No guessing.** A request no taf can do, or more than one could, is never handed to a taf: it ends `done`, with Coach's
  sentence saying so (`no_taf` only when Coach said nothing).
- **Once.** A request is claimed once per person and `client_id`: a retry gets the same request back and starts nothing.
- **A reply waits for its request's end.** `reply_to` goes on in that request's conversation only once it's `done`, `no_taf` or
  `failed`, with no turn of Coach's pending; before that it's 409 `reply_too_soon`, so a hand-over made for the earlier
  request can never count as the reply's.
- **Cut off with the phone.** Removing the phone under Settings → Devices revokes its device key, and every watch request
  through it is refused.

Each request is logged with its id and that the device's signature was checked, never with its words.

## The request

`POST /v1/watch/asks` answers, and `GET /v1/watch/asks?since=` and `GET /v1/watch/asks/{id}` read back, the person's own
requests:

```json
{"id": "wa_…", "client_id": "…", "state": "working", "taf": {"id": "taf-…", "name": "Calendar"},
 "line": "Moved your call to 18:00.", "words": "Working", "at": "…", "updated": "…"}
```

| State | Means | `words` (en) |
| --- | --- | --- |
| `accepted` | tafs cloud has it, and Coach's turn has started | Sent · {name} has it |
| `working` | the one taf has it; `taf` names it. A call held for the owner keeps it here, with a line | {name} is answering |
| `done` | the taf finished, `line` saying what it did; or Coach answered it itself, `line` being Coach's sentence (its answer, why none can, or which could) | Done |
| `no_taf` | no taf took it, and Coach's turn ended with nothing to show (no `line`) | No taf took it |
| `failed` | the taf's run failed or was stopped, or Coach couldn't route it | That didn't work |

`words` is in the request's language, in all eight (`runtime/src/cloud/words`). `{name}` is who holds the request: its taf
once `taf` names one, else Coach (one word in every language). The watch shows `line` when there is one, else `words`.

Until 19 Nov, a request Coach answered itself ended as `no_taf` with Coach's sentence, so the watch read "No taf took it"
over a full answer (W5, the owner's Galaxy Watch 5 test). Requests kept from before keep their state.

**The line** is at most 140 characters of plain text on one line: markdown marks, links' targets and control characters
go, and a longer line is cut at a word with an ellipsis (`checkedLine`). With no line from Coach, the state's words stand
in; if Coach's turn can't be started, the taf's own two sentences, checked the same way, are the line.

## Watch days (W11)

The phone's "From your watch" (Coach's Previous conversations, [watch-app.md](watch-app.md)) reads the person's requests
one day at a time: `GET /v1/watch/days` and `GET /v1/watch/days/{yyyy-mm-dd}` ([api.md](api.md)).

- **A read, nothing new kept.** A day is the person's own requests whose `at` (when the request came in) falls on that
  date in the **account's time zone**, the one KIFF answers for the person's session (`time_zone`), UTC when it has none.
  `today` is today's date in that zone, so someone travelling sees "Today" on the account's day.
- **What they said** is the first message of the request's own Coach run, read as the person in the conversation the
  watch route opened and marked (`watch-session#`). Only tafs cloud's own lines are taken off it (language, time zone),
  as in any Coach chat. A conversation the route didn't mark is never read for it, whatever its messages say, and a reply's
  words are its own run's, not its request's.
- **A turn** is `{id, said, said_at, taf?, state, line?, words, reply_to?}`: the state, line and words of `{ask}` above, in
  the reader's language. Turns go oldest first, a reply straight after the request it replies to, so the apps can show it
  under it. `reply_to` is sent only when that request is listed the same day.
- **The person's own only.** Another member of the account never sees them, the account's owner included, and another
  account's are never read. Tenant and person come from the session.
- **30 days.** A request's conversation is a run's chat, kept 30 days by kiff/agents' keep rules. A request whose
  conversation is gone, or holds no message of its run any more, isn't listed or counted, so a day goes when its
  conversations do. tafs cloud keeps the request, the person's list (their last 500) and the conversation's mark 30 days
  from the request's last change, then DynamoDB's ttl deletes them. Until 19 Nov they were kept 7 days.
- **Read only.** Neither route sends, approves or reopens anything; a reply goes from the watch, or from the phone in a
  Coach chat of its own.

## The switch

`TAFS_WATCH_ASK` in tafs cloud's environment: `on` for every account, one account id, or several joined with `+`, for those
alone (a test on a real watch before it's on for everyone), anything else off. Never commas: `infra/aws.sh` passes the
environment to Lambda as a comma-separated list. It's set with a rerun of `infra/aws.sh`, which keeps every other flag:
`TAFS_WATCH_ASK=<account id> infra/aws.sh`. Off, every `/v1/watch` route, the watch days too, is 404 with `code: "watch_off"`. It goes on
only once kiff/agents holds a session to its `only` and keeps named-only skills out of `read_skill`.
