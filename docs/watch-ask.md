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

- **One taf, per request.** A second `taf_task` in the same conversation is refused, whichever taf it names. The task
  starts a new run of the person's own, never into a run at work.
- **Under the taf's own Cards.** `taf_task` is Coach's, under `coach-taf-task` (`{limit: 50, asks: true}`), and the taf's
  run is under its own Cards. Nothing on this path creates a grant, approves a Card, widens one or signs anything. A
  call the taf's Cards hold waits for the owner on the phone, and the watch's line says so.
- **Only the tafs the person may use.** `tafs_state` in a watch conversation lists only those KIFF says they may use.
- **No guessing.** A request no taf can do, or more than one could, ends as `no_taf`, with Coach's sentence.
- **Once.** A request is claimed once per person and `client_id`: a retry gets the same request back and starts nothing.
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
| `accepted` | tafs cloud has it; Coach is choosing the taf | Sent |
| `working` | the one taf has it; `taf` names it. A call held for the owner keeps it here, with a line | Working |
| `done` | the taf finished; `line` says what it did | Done |
| `no_taf` | no taf took it; `line` is Coach's sentence: none can, or which could | No taf took it |
| `failed` | the taf's run failed or was stopped, or Coach couldn't route it | That didn't work |

`words` is in the request's language, in all eight (`runtime/src/cloud/words`). The watch shows `line` when there is one,
else `words`.

**The line** is at most 140 characters of plain text on one line: markdown marks, links' targets and control characters
go, and a longer line is cut at a word with an ellipsis (`checkedLine`). With no line from Coach, the state's words stand
in; if Coach's turn can't be started, the taf's own two sentences, checked the same way, are the line.

## The switch

`TAFS_WATCH_ASK` in tafs cloud's environment: `on` for every account, a comma-separated list of account ids for those
alone (the owner's test on a real watch), anything else off. Off, every `/v1/watch` route is 404 `watch_off`. It goes on
only once kiff/agents holds a session to its `only` and keeps named-only skills out of `read_skill`.
