# tafs cloud API

tafs cloud is the phone's API: an AWS Lambda in eu-west-1 at
`https://cloud.tafs.kiff.dev`, with CloudFront in front of its function URL. A chat's
streamed answer comes straight from the streaming function's URL. The code is
`runtime/src/cloud/`: `handler.ts` routes every request (`route`), `handleStream` serves
the two streams, and `lambda.ts` wires the stores.

## Who is calling

- **A person:** `Authorization: Bearer <KIFF session>`. tafs cloud asks KIFF who that is
  (`whoIs`): the account, the person, and their capabilities. A KIFF agent key is refused
  (403, "sign in as a person, not with a key"). tafs cloud keeps no accounts of its own.
- **Only the account's tafs:** another account's taf answers exactly like a missing one
  (404, `owned`).
- **Managing a taf** (marked *manages* below) takes `cards.manage` and KIFF saying the
  person manages that taf (`needTaf`, `GET /v1/me/access` on KIFF). An editor manages
  only the tafs they made.
- A few routes take no sign-in; each says what checks it instead.

Capabilities are KIFF's: `cards.manage`, `holds.answer`, `members.manage`,
`billing.manage`, `account.delete` ([accounts.md](accounts.md#roles-and-what-a-person-may-do)).

**Errors** are JSON, `{"error": "<words for a person>"}`, sometimes with more fields
(a version switch blocked by a run names it: `blocking`). 404 also covers what isn't the
caller's; 409 a state that doesn't allow it now; 429 a flood guard; 502 KIFF or kiff/agents
not answering. An unexpected error is 500, "something went wrong on tafs cloud", and the
details go only to tafs cloud's own log.

## Routes

### The account

| Route | Who | What |
|---|---|---|
| `GET /v1/jobs` | anyone | The five jobs: `{jobs: [{id, name, summary, cron}]}` |
| `GET /v1/home` | a person | The app's list: each taf's state, colour, Card summary, doors (each with its `label`) and open trial, and the account's other agents with Cards ([tafs.md](tafs.md#the-home-list)). Also `limits: {text, trial_idle_days}`, the longest message and how long a trial waits unused, which the apps check and keep no copy of |
| `GET /v1/tafs` | a person | The account's tafs the person may use or oversee, and removals not finished |
| `POST /v1/tafs` | `cards.manage` | Start a taf: `{name, job, draft_id + claim_secret \| from_setup + draft_id \| agent_key, cron?, timezone?, budget_cents?, notes?, unscheduled?}` ([tafs.md](tafs.md#starting-a-taf)) |
| `GET /v1/setup` | a person | `{setup, tools}`: the account's Coach (Setup), or `null`; and Coach's tools as KIFF has them. `tools.state` is `given` (every tool connected here and each of Coach's Cards in force; a revoked Card doesn't count), `to_give`, `taken` (`taken`: the name a vendor's tool holds; one of Coach's tools at tafs cloud's earlier address is connected again instead, its entry marked `replace: true` and its line saying it replaces the earlier connection) or `unknown`. With `to_give`: `connect {body, lines[]}`, the batch for KIFF's `POST /v1/connections/batch`, or, once every tool is connected (KIFF refuses a Card naming an action no tool declares yet), `card {body, card_title, cards[]}`, Coach's Cards' draft for `POST /v1/me/agent-drafts` with KIFF's own words for it from its preview, unchanged. The phone signs and sends each body as it is; the signed submit is the owner's one confirmation |
| `POST /v1/setup` | `cards.manage` | Start Coach once: `{draft_id, claim_secret}` from a draft with no tools ([coach.md](coach.md#starting-coach-once-per-account)) |
| `GET /v1/events` | a person | Halts, resumes and door events, newest first, for Activity. Who wrote to a door shows only for the tafs the person manages |
| `GET /v1/usage/ai` | a person | The period's AI use as a share of the bound, where the tafs ask next, whether they're asking now, and `words` saying all that for the apps to show ([accounts.md](accounts.md#ai-use)) |
| `POST /v1/account/delete` | `account.delete` | `{intent, signature}`: delete the account from the phone's one signed intent. KIFF accepts it first, within its 10 minutes, or nothing is deleted; planque accepts on KIFF's word. tafs cloud then erases its own, planque's, then KIFF's, and what doesn't finish now its sweep finishes. Answers `{done, failed, pending}`. Without an intent, it erases only tafs cloud's and kiff/agents' (the app's earlier way) ([accounts.md](accounts.md#deleting-the-account)) |
| `GET /v1/halt` | a person | The whole account's halt, as KIFF and kiff/agents hold it |
| `POST /v1/halt` | `cards.manage` | Halt every taf, in KIFF and kiff/agents at once ([approvals.md](approvals.md#halting)) |
| `DELETE /v1/halt` | `cards.manage` | Resume kiff/agents, once KIFF says it runs again (409 until the phone resumed it in KIFF) |

### Alerts

| Route | Who | What |
|---|---|---|
| `POST /v1/push` | a person | `{device_token, platform?, environment, sound?, alert_target?}`: this phone gets the account's alerts. `platform` is `ios` (the default; `device_token` is APNs's, in hex, with its `environment`) or `android` (`device_token` is Firebase's registration token, case kept; `environment` is `production` or left out; 503 where Google push isn't set up); `sound: false` sends them to it quietly, with no sound or banner. Only someone with `holds.answer` may hand over the target's secret, and only their phones are alerted. 50 phones an account at most |
| `DELETE /v1/push/{token}` | a person | That phone stops (an Android phone's token comes percent-encoded, and keeps its case): a person removes their own; someone with `cards.manage`, any of the account's |
| `GET /v1/alerts` | a person | Whether tafs cloud holds KIFF's alert secret for the account |
| `POST /v1/alerts` | `holds.answer` | `{alert_target}`: the account's alert target, with no phone's alerts |
| `POST /v1/kiff/alerts` | KIFF, signed | A held call or its answer. The `KIFF-Signature` is checked against the account's secrets, the alert handed to kiff/agents, then pushed to the phones. 502 asks KIFF to send it again ([approvals.md](approvals.md#after-the-answer)) |

### A taf

`{id}` is the taf's id from `GET /v1/tafs` or `/v1/home`.

| Route | Who | What |
|---|---|---|
| `DELETE /v1/tafs/{id}` | manages | Remove it for good, each step safe to repeat: `{done, removed, failed}`. The phone revokes its Cards in KIFF first |
| `POST /v1/tafs/{id}/pause`, `resume` | manages | Its schedule off or on. 409 for a taf that works from its chat |
| `GET /v1/tafs/{id}/work` | a person | Where its work stands ([reliability.md](reliability.md#the-tafs-view)): `{summary: {status, line, needs_you}, next_run, counts, requests: [{id, at, state, line, deliveries}], skipped: [{due, why, words}]}`, every line and time written in the account's zone (`time_zone`) |
| `POST /v1/tafs/{id}/requests/{run}/deliveries/door_reply/send` | manages | Send again: the door reply of that run, given up on after its tries, sent once more with new tries. Does no work, signs nothing. `{state: sent \| not_sent \| waiting}`; 404 when nothing of that run waits to be sent; 409 while it's being sent, or when the taf no longer answers through its door. A send that fails keeps the reply for the next tap. A delivery that can be sent again has `send_again: true` in `GET …/work` |
| `PUT /v1/tafs/{id}/results` | manages | `{places}`: where its results go, one or more of `door_reply`, `app`, `owner_email` ([doors.md](doors.md#where-results-go)). The taf's view lists them as `results`; a taf that never set them answers through its door alone |
| `POST /v1/tafs/{id}/run` | manages | Run it now: `{session}`. 409 for a taf that works from its chat |
| `GET /v1/tafs/{id}/runs` | a person | Its recent job runs: `{runs: [{session, at, error}]}` |
| `GET /v1/tafs/{id}/runs/{session}` | a person | A run's report: the agent's last answer, and whether it's still working |
| `GET /v1/tafs/{id}/halt`, `POST`, `DELETE` | as `/v1/halt` | The same, for this taf only |
| `GET /v1/tafs/{id}/memory` | a person | The shared notes and the person's own: `{notes: [{id, path, content, updated_at}]}` |
| `DELETE /v1/tafs/{id}/memory/{note}` | a person for their own (`@…`); manages for a shared note | Forget it; gone for good |
| `GET /v1/tafs/{id}/calls` | a person | What its calls through KIFF acted on: `{calls: [{invocation, value, at}]}` |
| `POST /v1/tafs/{id}/improve` | manages | `{text}`: a new Coach conversation on this taf ([coach.md](coach.md#improving-a-taf)) |

### A taf's chat

See [chat.md](chat.md) for how the app uses these.

| Route | Who | What |
|---|---|---|
| `POST /v1/tafs/{id}/messages` | a person | `{text, attachments?, session?, new_chat?}`: a run in the person's live chat, a chosen one, or a new one. Text is 1 to 2,000 characters, or none with files. 429 past the flood guard. Answers `{session, new_session, run, message: {id, at}}`: the thread gives the same message that `id`, so the app matches its send without guessing |
| `POST /v1/tafs/{id}/messages/stream` | a person | The same message, its answer streamed as server-sent events ([chat.md](chat.md#the-answer-as-it-comes)) |
| `GET /v1/tafs/{id}/runs/{run}/stream?after=N` | a person, their own run | Pick a run back up: its recorded steps after event N (with `part` where it went on after a held call), then the rest until it stops |
| `POST /v1/tafs/{id}/steer` | a person | `{run, text}`: to the run at work, read at its next step. 409 when it has ended: send it as a new message |
| `POST /v1/tafs/{id}/stop` | `cards.manage` | `{session}`: cancel the session's latest run |
| `POST /v1/tafs/{id}/holds/{exception}/continue` | `holds.answer` | The run paused on that held call goes on now, once KIFF has answered it. `waiting` when KIFF still holds a call of the run |
| `POST /v1/tafs/{id}/components/{component}/answer` | a person, their own card | `{answer, session?}`: the answer to a card that waits for it, as data ([chat.md](chat.md#cards-that-wait-for-an-answer)). 404 for no card of theirs by that id, 409 once it doesn't wait, 400 for an answer the card doesn't take |
| `GET /v1/tafs/{id}/conversation` | a person | The thread: messages, components, `working`, `steering`, `capped`, Coach's lines, and the open knocks for someone who manages it ([chat.md](chat.md#the-thread-and-the-chats-list)) |
| `GET /v1/tafs/{id}/conversation/{session}` | a person, their own chat | One chat of it |
| `GET /v1/tafs/{id}/chats` | a person | Their chats, newest first, each named by what they asked |
| `GET /v1/tafs/{id}/chats/archived` | a person | Their archived chats |
| `POST /v1/tafs/{id}/chats/{session}/archive`, `unarchive` | a person, their own chat | Out of the list and the thread, or back |
| `POST /v1/tafs/{id}/attachments` | a person | `{media_type, name, size, sha256}`: declare a file, and get where to upload it ([attachments.md](attachments.md)) |
| `GET /v1/tafs/{id}/attachments/{att}` | a person, their own file | A download link for a minute |

Coach's chat uses the same routes, with Coach's id from `GET /v1/setup`.

### Versions and trials

See [coach.md](coach.md#versions). Not for Coach itself (404).

| Route | Who | What |
|---|---|---|
| `GET /v1/tafs/{id}/versions` | a person | Its versions, oldest first, the live one marked, and a switch under way. Each says who made it in `by_words` ("By Coach mode", "By you", "By someone on your account"); one kiff/agents wrote itself has none |
| `POST /v1/tafs/{id}/versions` | manages | `{note, notes?, draft?, from?, agent?}`: its next version, as Coach's result made it, once per result. Nothing goes live |
| `POST /v1/tafs/{id}/versions/{n}/switch` | manages | `{redraft?, stop_blocking?}`: begin the switch to version n, with the Card draft it waits for. Nothing of the taf runs until it ends. A run in the way is 409, naming it (`blocking`), with `words` saying what's in the way for the app to show |
| `DELETE /v1/tafs/{id}/switching` | manages | Drop a switch whose Card isn't confirmed yet |
| `POST /v1/tafs/{id}/versions/{n}/live` | manages | Make version n live, once its Card revision is confirmed in KIFF (409 before) |
| `POST /v1/tafs/{id}/versions/{n}/try` | manages | The KIFF draft to confirm as version n's trial Card |
| `POST /v1/tafs/{id}/versions/{n}/trial` | manages | `{input?}`: a trial run of version n, its writes decided by KIFF, nothing sent |
| `GET /v1/tafs/{id}/trials/{run}` | manages | That trial so far: its run and what KIFF decided for each write |
| `PUT /v1/tafs/{id}/trial` | manages | `{n, draft}`: open the trial, once KIFF has that draft trying |
| `GET /v1/tafs/{id}/trial` | a person | The open trial, or none |
| `GET /v1/tafs/{id}/trial/conversation` | manages | The trial's chat |
| `POST /v1/tafs/{id}/trial/messages` | manages | `{text}`: one turn of the version being tried |
| `POST /v1/tafs/{id}/trial/install` | manages | Once KIFF confirmed its draft live: that version goes live, the trial ends |
| `DELETE /v1/tafs/{id}/trial` | manages | Go back: KIFF ends its trial Cards, the trial ends, the live version stays |

### Doors

See [doors.md](doors.md). Reading needs a session; changing, managing the taf.

| Route | What |
|---|---|
| `GET /v1/tafs/{id}/doors` | The taf's doors, each with its `words` (`state`, `tap`, `settings`), the apps a door can open on here, and its open knocks, each with its door's `label` |
| `POST /v1/tafs/{id}/doors` | `{app, code?, phone_number_id?, waba_id?}`: turn a door on |
| `PATCH /v1/tafs/{id}/doors/{door}` | `{open}` |
| `DELETE /v1/tafs/{id}/doors/{door}` | Remove it |
| `POST /v1/tafs/{id}/doors/{door}/keys` | `{id, name?}`: pair a sender |
| `DELETE /v1/tafs/{id}/doors/{door}/keys/{key}` | Unpair one |
| `POST /v1/tafs/{id}/doors/knocks/{knock}` | `{action: do \| pair \| ignore, say?}` |

### Called by other services

| Route | Who | What |
|---|---|---|
| `POST /v1/mcp` | Coach, through KIFF's gateway | Coach's tools. Each call's KIFF permit is checked: signature, issuer, audience, tool, the exact arguments' hash and lifetime ([coach.md](coach.md#coachs-own-tools)) |
| `POST /v1/runs/ended` | kiff/agents | A run Coach handed a taf paused or ended. Unsigned: an unknown ref is dropped, and the run is read back from kiff/agents |
| `POST /v1/doors/email`, `POST /v1/doors/whatsapp` | the providers | The doors' webhooks, each checked by the provider's signature |
| `GET /doors/whatsapp/connect` | the owner's browser | The page WhatsApp's signup runs in |

## Keeping this page right

A route added or changed in `handler.ts` changes this page in the same pull request, and
the route list at the top of `handler.ts`.
