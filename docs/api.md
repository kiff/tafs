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
(a version switch blocked by a run names it: `blocking`). A refusal kiff/agents gave carries
its `code` (`ai_fair_use`, `halted`, `switching`, `run_changed`) and `words`, what it means and
what to do, for the app to show as they are: an AI fair use refusal says the day the AI use
starts again, in the account's zone. 404 also covers what isn't the
caller's; 409 a state that doesn't allow it now; 429 a flood guard; 502 KIFF or kiff/agents
not answering. An unexpected error is 500, "something went wrong on tafs cloud", and the
details go only to tafs cloud's own log.

## Routes

### The account

| Route | Who | What |
|---|---|---|
| `GET /v1/jobs` | anyone | The five jobs: `{jobs: [{id, name, summary, cron}]}` |
| `GET /v1/home` | a person | The app's list: each taf's state, colour, Card summary, its `job_summary` (what Coach said it does, else its job's line), its `last_run` (its own latest, scheduled or from its chat, never a trial's), doors (each with its `label`) and open trial, and the account's other agents with Cards ([tafs.md](tafs.md#the-home-list)). Also `limits: {text, trial_idle_days}`, the longest message and how long a trial waits unused, which the apps check and keep no copy of |
| `GET /v1/tafs` | a person | The account's tafs the person may use or oversee, and removals not finished |
| `POST /v1/tafs` | `cards.manage` | Start a taf: `{name, job, draft_id + claim_secret \| from_setup + draft_id \| agent_key, cron?, timezone?, budget_cents?, notes?, unscheduled?, summary?}`. `summary` is what the taf does in one line, from Coach's result: kept, and shown as its `job_summary` ([tafs.md](tafs.md#starting-a-taf)) |
| `GET /v1/setup` | a person | `{setup, tools}`: the account's Coach (Setup), or `null`; and Coach's tools as KIFF has them. `tools.state` is `given` (every tool connected here and each of Coach's Cards in force; a revoked Card doesn't count), `to_give`, `taken` (`taken`: the name a vendor's tool holds; one of Coach's tools at tafs cloud's earlier address is connected again instead, its entry marked `replace: true` and its line saying it replaces the earlier connection) or `unknown`. With `to_give`: `connect {body, lines[]}`, the batch for KIFF's `POST /v1/connections/batch`, or, once every tool is connected (KIFF refuses a Card naming an action no tool declares yet), `card {body, card_title, cards[]}`, Coach's Cards' draft for `POST /v1/me/agent-drafts` with KIFF's own words for it from its preview, unchanged. The phone signs and sends each body as it is; the signed submit is the owner's one confirmation |
| `POST /v1/setup` | `cards.manage` | Start Coach once: `{draft_id, claim_secret}` from a draft with no tools ([coach.md](coach.md#starting-coach-once-per-account)) |
| `GET /v1/events` | a person | Halts, resumes and door events, newest first, for Activity. Who wrote to a door shows only for the tafs the person manages |
| `GET /v1/usage/ai` | a person | The period's AI use as a share of the bound, where the tafs ask next, whether they're asking now, whether this person may go on (`can_go_on`), when it starts again (`starts_again`), and `words` saying all that for the apps to show ([accounts.md](accounts.md#ai-use)) |
| `GET /v1/me/chats/export` | a person | Their own data in tafs, for their rights of access and portability (GDPR Articles 15 and 20). It covers their own chats and trials with every taf, Coach's included and archived ones too, with what kiff/agents still keeps (30 days), the names, types and sizes of the files they attached, and their own notes. Never another person's chats, a door's chats or a taf's job runs, and no one exports it for someone else. Answers `{exported_at, account, person, tafs: [{id, name, chats: [{session, kind, started, updated, archived, messages}], notes}], readable}`, where `readable` is the same as text. Each file is downloaded through the attachments route, as in the chat ([data.md](data.md#your-own-copy)) |
| `POST /v1/account/delete` | `account.delete` | `{intent, signature}`: delete the account from the phone's one signed intent. KIFF accepts it first, within its 10 minutes, or nothing is deleted; planque accepts on KIFF's word. tafs cloud then erases its own, planque's, then KIFF's, and what doesn't finish now its sweep finishes. Answers `{done, failed, pending}`. Without an intent, it erases only tafs cloud's and kiff/agents' (the app's earlier way) ([accounts.md](accounts.md#deleting-the-account)) |
| `GET /v1/halt` | a person | The whole account's halt, as KIFF and kiff/agents hold it |
| `POST /v1/halt` | `cards.manage` | Halt every taf, in KIFF and kiff/agents at once ([approvals.md](approvals.md#halting)) |
| `DELETE /v1/halt` | `cards.manage` | Resume kiff/agents, once KIFF says it runs again (409 until the phone resumed it in KIFF) |

### Connect apps

The catalog of apps a taf can use, and a connection started for one taf. tafs cloud asks planque with the person's own session and keeps nothing. The approval, with KIFF's words for the connection signed on the phone, and the vendor's sign-in after it, are the phone's and planque's, as for a connection Coach starts.

| Route | Who | What |
|---|---|---|
| `GET /v1/connect/apps?q=&provider=&cursor=` | a person | One page of the catalog, searched by `q`: `{apps: [{entry, vendor, title, icon?, how: oauth \| key, state: available \| connected \| needs_reconnect, grant?}], by_key, provider?, next_cursor?, skipped?}`. Reviewed entries come first, then a page of the managed provider's apps (`entry` like `connector:composio:gmail`). Within a page, the account's own connections come first (one to sign in again before one connected), then, for a search, apps whose name starts with `q`, then whose name has it. Ask for the next page with the same `q`, `provider` and `next_cursor` as `cursor`; there's no `next_cursor` on the last page. `skipped` counts entries planque left out as malformed or unsupported. `by_key` says Coach can connect a vendor that isn't listed |
| `POST /v1/connect/apps/{entry}` | `cards.manage`, managing the taf | `{taf}`: starts a connection for that taf's KIFF agent. Answers `{grant, needs_key?}`, the grant to approve. Nothing is signed here, and there's no sign-in URL until the phone approves |

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
| `POST /v1/tafs/{id}/pause`, `resume` | manages | Every schedule that starts it off or on: its own, the dial's others and its follow-ups still to come. The taf keeps that it's paused. 502 when one couldn't be switched (it may still wake): ask again. 409 for a taf that works from its chat |
| `GET /v1/tafs/{id}/knob` | a person | Its dial, three rings the same on every taf: `rings: [{key, name}]` = When (`wakes`), What (`what`) and Where (`results`), inner to outer, and `why`, the face's word when nothing is chosen (the taf's purpose, as Coach wrote it, at most 60 characters). `what: [{id, step, label, words, tool?}]` is the taf's recipe, its steps in order, as Coach's `taf_dial` wrote it; a taf with none shows each of its tools by KIFF's title. `when` is kept for older apps and isn't drawn. A stop may have `size: {label, index, choices: [{label, setting}]}`, its one choice for the dial's − and +: a schedule's time or how often, a place on or off (the last place tafs delivers has none). `index` is the value now, and each choice's `setting` is the whole `PUT` body that makes it, so the apps send it as it is. `draft.what` lists the draft's recipe, one line per step. Rules the apps share, sent so neither writes them: each stop's `ok`, what pressing the face does (`door` opens or closes it, `run` runs once, `remove_follow_up`, `coach` opens Coach; absent, nothing); a door stop's `look` (`open`, `closed`, `resting`, `knock`); When ends with an `add_door` stop for whoever manages the taf, and an empty recipe shows one `what_ask` step, both `ok: coach`; and `pending: {version, changes}`, the version Make it live switches to and how many stops it changes, or `null`. `draft.groups: [{grant?, name, wakes, what, results}]` has the same lines by connection for Coach's card: each connected app's events and actions under it, then This taf (no `grant`) with doors, schedules, the recipe and the places tafs delivers. `?version=N` shows a dial change waiting to go live (a version after the live one with a dial) instead of the live dial: its `setting`, stops and sizes, with `shown: N` and `version` still the live one, so a second − or + builds on the first. Any other version is 404. `{does: {label, line}, wakes, when, results, rim: {done, working, retrying, needs_you, line}, draft}`. Each ring item has `id`, `label` (on the ring), `words` (its line), `on`, and for the dial's face `state` (its big word) and `tap` (what tapping the face does, absent when nothing). `wakes[].kind` is `door` (with its `door` id: the face opens or closes it through the doors routes), `schedule` or `run_now` (the face runs it, `POST …/run`); `when[].kind` is `arrivals`, `missed` or `busy`; `results[].place` is a result place. A trigger's stop also has `trigger`, its form exactly as `PUT` takes it (none on `run_now`, nor on a schedule the dial can't set), and the knob has `setting: {wakes: [trigger], busy, results: [place]}` for the live version: a tap copies `setting`, changes one thing, sends it to `PUT`, then switches. `results` lists every place, on or off; the If busy stop and each place have a `tap` (turning it is a change, as above; the last place on has none). A schedule's form and adding or removing triggers go through Coach's draft for now. A follow-up the taf set itself (kiff/agents' `follow_up` tool: once, within seven days, at most three waiting) shows on `wakes` as `kind: "follow_up"`, with its `follow_up` id, `expires` (when it fires) and `words`; it's never part of `setting`. `draft` is the newest dial change made after the live version and not live yet, from a tap or Coach's `taf_dial`: `{version, words, wakes: [line], when: [line], results: [line]}`, its words in one list and by ring. Coach shows it in its chat as `{type: "knob", version, taf_id}`, which the apps draw from `draft`; a card whose version no longer matches `draft.version` was replaced. Confirming is the usual switch. `null` when nothing waits. `connections: [{grant, name, wakes, results}]` lists the connections the stops come from, in order, and each connected app's stop carries its `grant`: the apps show chips under the dial (All · each connection · This taf, the stops with no `grant`), and picking a chip shows only that connection's stops on the rings. There's no limit on connections, triggers or places. The rim counts today's requests in the account's zone. Doors show only to someone who manages the taf. Every word is tafs cloud's; the apps draw them as they are |
| `DELETE /v1/tafs/{id}/follow-ups/{fid}` | manages | Removes a follow-up the taf set itself, by the `follow_up` id its dial gives. Nothing else changes follow-ups: kiff/agents bounds them. `{removed}`; 404 for anything but a follow-up of this taf |
| `PUT /v1/tafs/{id}/knob` | manages | The dial changed, from a tap or Coach's draft: `{wakes: [trigger], when: {busy: skip \| latest}, results?: [place], what?: [{label, words, tool?}], why?}`: `what` is the recipe in order (a short label each), `why` the purpose in a few words; left out, the last ones stay. A place is `door_reply`, `app` or `owner_email` (at least one; tafs cloud delivers them), or any number of actions of the taf's connected apps, which the taf calls itself, a write through KIFF inside its Card: `{place: "action", grant, action, args, name}`, where `grant` is the connection (planque), `action` the tool's name as KIFF registered it, `args` the arguments the owner fixed (a page, a channel, a sheet: text, numbers or true/false) and `name` what the owner reads ("Notion · Support log", one line, at most 100 characters). tafs cloud names no vendor: any connection's action can be a place. These go into the version's instructions by ids and arguments only, and show on the Results ring while live (with `grant` and `action`), where turning one takes it off. A trigger is `{kind: "schedule", form, at?, days?, times?, every_minutes?, between?, date?, skip?}` (`form` is `daily`, `weekdays`, `weekly`, `monthly`, `monthly_last_day`, `times_a_day`, `every` or `once`, in the account's zone), `{kind: "app_check", operation, args, items, cursor, every_minutes}`, `{kind: "door"}`, or any connected app's event: `{kind: "app_event", grant, event, config?, subscription?, name}`, where `grant` is the connection, `event` the provider's event as the connection lists it, `config` the owner's filters, `subscription` planque's subscription (made on the owner's signed approval) and `name` what the owner reads ("HubSpot · new deal"). tafs cloud names no vendor. An app's event is scheduled nowhere: planque hands it to `POST /v1/events/planque` (`planque.event.v1`: `{id, tenant, agent, grant, provider, account, subscription, event, payload, timestamp}`, signed as the Slack door's handover), and it starts a run, the event as data, only while the taf's live version wakes on it; the same event id starts nothing twice. Every N is 15, 20 or 30 minutes or whole hours, and a window starts and ends on the hour: anything else is 400, naming the nearest form. Answers `{version, words}`: the taf's next version, made as an improvement makes one, and what it changes in words. Nothing runs from it until the switch makes it live (`…/versions/{n}/switch`, `…/live`); then its triggers become the taf's schedules in kiff/agents and its places where results go |
| `GET /v1/tafs/{id}/work` | a person | Where its work stands ([reliability.md](reliability.md#the-tafs-view)): `{summary: {status, line, needs_you}, next_run, counts, requests: [{id, at, state, line, deliveries}], skipped: [{due, why, words}]}`, every line and time written in the account's zone (`time_zone`) |
| `GET /v1/tafs/{id}/requests/{run}` | a person; someone else's request, or a door sender's, only to who manages the taf | One request from the dial's rim, step by step: what `GET …/work` gives for it, plus `from` ("By email", "Its schedule", "From its chat", "Run now") and `steps: [{label, line?, state}]`, the time inside `line`, `state` being `done`, `failed`, `not_made`, `waiting` or `next`. The steps are: Received; each call in KIFF's title for the tool ("Worked on it" for one nothing names); a call held for approval; where its reply went; then how it ended or what comes next ("Next try 23:15", "If it fails again, this needs you"). Every line and time is in the account's zone, and the apps draw them as they are. 404 for a request of another taf |
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
| `POST /v1/tafs/{id}/messages/{message}/report` | a person who may read the answer | `{reason, note?, session?}`: reports an answer Coach or the taf wrote, from inside the app. `reason` is `offensive`, `harmful`, `wrong` or `other`; `note` is at most 1,000 characters. tafs cloud finds the answer in the person's thread, or in the chat (or trial) `session` names, and keeps and emails it as kiff/agents holds it, never the app's copy. Answers `{report: {id, at, new}}`; reporting the same answer again answers the first report, with `new: false`. 404 for anything but an answer of the taf's the person may read |
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
| `GET /v1/tafs/{id}/versions` | a person | Its versions, oldest first, the live one marked, and a switch under way. Each says who made it in `by_words` ("By Coach mode", "By you", "By someone on your account"); one kiff/agents wrote itself has none. Version 1's `created` is when the taf was set up |
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

See [doors.md](doors.md). Reading and changing both need managing the taf: someone it's only shared with reads no doors, paired senders or knocks (`{doors: [], apps: [], knocks: []}`).

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
