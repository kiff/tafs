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
| `GET /v1/me/suggestions`, `PUT /v1/me/suggestions` | a person; manages, to change | The account's "Show suggestions" setting, kept in tafs cloud: `{suggestions: {show, reason: on | off | live, words}}`. On by default; `PUT {show: false}` turns them off for good (`{show: true}` on again); 403 for someone who doesn't manage the account's tafs. `words` are tafs cloud's for the apps to show beside the switch |
| `GET /v1/jobs` | anyone | The five starter jobs, kept for the tafs that have one: `{jobs: [{id, name, summary, cron}]}`. A new taf carries its own job (`POST /v1/tafs`), and the five are recipes (`GET /v1/recipes`) |
| `GET /v1/home` | a person | The app's list: each taf's state, colour, Card summary, its `job_summary` (what Coach said it does, else its job's line), its `last_run` (its own latest, scheduled or from its chat, never a trial's), doors (each with its `label`) and open trial, and the account's other agents with Cards ([tafs.md](tafs.md#the-home-list)). Also `limits: {text, trial_idle_days}`, the longest message and how long a trial waits unused, which the apps check and keep no copy of |
| `GET /v1/tafs` | a person | The account's tafs the person may use or oversee, and removals not finished |
| `GET /v1/calendar?from=YYYY-MM-DD&to=YYYY-MM-DD` | a person | The calendar (kiff/tafs#534): the tafs' scheduled work still to come on those dates (inclusive, in the account's zone, at most 62 days; 400 past that or for a date that isn't one), for the tafs the person may use or oversee, as home lists them. `{time_zone, from, to, events: [{taf, name, color, kind, at, day, time, minute, words, count}], days: [YYYY-MM-DD], more, halted, words}`: `kind` is `schedule` (a live schedule's firing, read as kiff/agents fires it: its cron in its own zone, a wall time a DST change removes left out, a skipped firing left out, the month's last day where the schedule says so), `follow_up` (one the taf set itself) or `retry` (a run's next try); `at` is the instant (RFC 3339 UTC), `day`, `time` and `minute` its date, time of day and minutes since midnight in the account's zone, for the apps' slots, so neither app converts a zone; `words` what it is ("Every day at 09:00", "Try 2 of 3"); `days` the dates with an event, so the apps mark them. A schedule that fires more than 12 times on a day is one event that day, at its first firing, with `count` the firings it stands for and `words` saying from when to when; one read answers at most 2,000 events, and `more` says when some were left out. While the account is halted nothing runs, so it answers no events, `halted: true` and `words` saying so; a halted taf shows none beside running ones. Only what's to come: nothing past, nothing invented. A paused taf's schedules are off, so it has none; a taf whose schedules can't be read today shows none. Read-only; computed by tafs cloud so both apps draw the same |
| `POST /v1/tafs` | `cards.manage` | Start a taf: `{name, draft_id + claim_secret \| from_setup + draft_id \| agent_key, summary?, kickoff, instructions?, cron \| unscheduled, timezone?, budget_cents?, notes?}`. Its job is its own, as Coach drafted it from the owner's words (kiff/tafs#476): `summary` is what it does in one line (shown as its `job_summary`), `kickoff` what each run is asked to do (2,000 characters at most; the first run's too), `instructions` how it works (2,000 at most), and `cron` when it runs, or `unscheduled` when it works from its chat; a taf of its own with neither is refused. `job` names one of the five starter jobs instead, with their kickoff, instructions and default schedule, for the apps from before ([tafs.md](tafs.md#its-job)); never with `from_setup`: a taf Coach sets up carries the job it drafted, and a starter job there is refused (400) |
| `GET /v1/setup` | a person | `{setup, tools}`: the account's Coach (Setup), or `null`; and Coach's tools as KIFF has them. `tools.state` is `given` (every tool connected here and each of Coach's Cards in force; a revoked Card doesn't count), `to_give`, `taken` (`taken`: the name a vendor's tool holds; one of Coach's tools at tafs cloud's earlier address is connected again instead, its entry marked `replace: true` and its line saying it replaces the earlier connection) or `unknown`. With `to_give`: `connect {body, lines[]}`, the batch for KIFF's `POST /v1/connections/batch`, or, once every tool is connected (KIFF refuses a Card naming an action no tool declares yet), `card {body, card_title, cards[]}`, Coach's Cards' draft for `POST /v1/me/agent-drafts` with KIFF's own words for it from its preview, unchanged. The phone signs and sends each body as it is; the signed submit is the owner's one confirmation |
| `POST /v1/setup` | `cards.manage` | Start Coach once: `{draft_id, claim_secret}` from a draft with no tools ([coach.md](coach.md#starting-coach-once-per-account)) |
| `POST /v1/setup/check-call` | a person | `{proposal}`: "Not sure, help me check" on a "Did this happen?" card (KIFF's `not_sure` answer first, which marks the call as being checked by someone who manages the taf): a new conversation with Coach on that one call, opening with its record as KIFF carries it (the taf, the action and the vendor, the amount, what it acted on, when, why KIFF can't tell, the vendor's record address), so Coach reads the vendor through the taf's connection and says which answer fits; the owner still answers the card, signed. Answers `{setup, session, new_session, run}`; 404 for a call KIFF doesn't list for this person |
| `GET /v1/events` | a person | Halts, resumes and door events, and Coach's drives (`drive_started`, `drive_step`, `drive_ended`, each with tafs cloud's own `title` and `detail`), newest first, for Activity. Who wrote to a door shows only for the tafs the person manages |
| `GET /v1/usage/ai` | a person | The period's AI use as a share of the bound, where the tafs ask next, whether they're asking now, whether this person may go on (`can_go_on`), when it starts again (`starts_again`), and `words` saying all that for the apps to show ([accounts.md](accounts.md#ai-use)) |
| `GET /v1/usage/credits` | a person | The period's credits, read-only ([accounts.md](accounts.md#credits)): `{period: {start, end, current}, credits, runs, words, tafs: [{taf, credits, runs, words}], coach, not_counted: {runs, words, lines: [{why, runs, words}]}, note}`. `?period=previous` for the period before the current one. `tafs` lists those that used credits, most first. `taf` is the taf's id, as `GET /v1/tafs` gives it (`taf-` and 20 hex digits, its id on kiff/agents too), never a KIFF agent's; it's `null` on the one row for tafs removed since, which doesn't open. `coach` is `null` while Coach is within its fair use, else `{credits, runs, words}`. `why` is `coach`, `trial` or `failed`, and an app shows one it doesn't know by its `words`. Account-wide, as the tasks are. 503 with `words` when KIFF can't be read |
| `GET /v1/usage/credits/tafs/{id}` | a person | One taf's runs in the period, newest first, 50 a page: `{period, taf, credits, runs, words, items: [{run, at, credits, counted, why, words, opens}], next}`. `{id}` and `taf` are the taf's id, as above; `run` is the run's id on kiff/agents. `?period=previous`; `?after=` with the `next` of the page before. `at` is when the run ended; `why` is `null` on a counted run; `opens` says whether the row opens the run's request (`GET /v1/tafs/{id}/requests/{run}`), which it can until kiff/agents no longer keeps the run (a trial's row never opens). The person's own runs, and everyone's on a taf they manage. 404 for a taf they can't see |
| `GET /v1/me/chats/export` | a person | Their own data in tafs, for their rights of access and portability (GDPR Articles 15 and 20). It covers their own chats and trials with every taf, Coach's included and archived ones too, with what kiff/agents still keeps (30 days), the names, types and sizes of the files they attached, and their own notes. Never another person's chats, a door's chats or a taf's job runs, and no one exports it for someone else. Answers `{exported_at, account, person, tafs: [{id, name, chats: [{session, kind, started, updated, archived, messages}], notes}], readable}`, where `readable` is the same as text. Each file is downloaded through the attachments route, as in the chat ([data.md](data.md#your-own-copy)) |
| `POST /v1/account/delete` | `account.delete` | `{intent, signature}`: delete the account from the phone's one signed intent. KIFF accepts it first, within its 10 minutes, or nothing is deleted; planque accepts on KIFF's word. tafs cloud then erases its own, planque's, then KIFF's, and what doesn't finish now its sweep finishes. Answers `{done, failed, pending}`. Without an intent, it erases only tafs cloud's and kiff/agents' (the app's earlier way) ([accounts.md](accounts.md#deleting-the-account)) |
| `GET /v1/halt` | a person | The whole account's halt, as KIFF and kiff/agents hold it |
| `POST /v1/halt` | `cards.manage` | Halt every taf, in KIFF and kiff/agents at once ([approvals.md](approvals.md#halting)) |
| `DELETE /v1/halt` | `cards.manage` | Resume kiff/agents, once KIFF says it runs again (409 until the phone resumed it in KIFF) |
| `POST /v1/coach/drive` | a person | Start their drive for one request: `{session, task, component?}`, signed with the approval key (`KIFF-Device-Signature`); KIFF must say `key: "critical"` outside any window, else 403 `critical_key_required`. With `component`, Coach's drive card is answered `approved: the drive is on`. Answers `{drive: {id, session, task, started}}` ([drive.md](drive.md)) |
| `GET /v1/coach/drive` | a person | Their drive as it stands (`ended {why, at, words}` once over) and its `steps`, for the "What I did" card; `{drive: null}` when none ([drive.md](drive.md)) |
| `POST /v1/coach/drive/stop` | a person | `{drive, why}`: end their drive; `why` is `owner_took_over`, `left_app`, `stopped`, `locked` or `signed_out`. Ending one already over changes nothing ([drive.md](drive.md)) |
| `POST /v1/coach/drive/{drive}/results/{request}` | a person | The app's answer to one request of their drive: `{outcome, line, image?, tree?, diagnostics?, touched?, waited?}` (image at most 200,000 bytes, else 413); 404 for another drive, 409 once it's over ([drive.md](drive.md)) |
| `GET /v1/coach/drive/{drive}/stream` | a person | On the streaming function: the drive's requests as server-sent events, `drive`, `request {id, tool, args, at}`, `ended {why, words}` or `reopen`, until it ends ([drive.md](drive.md)) |

### Connect apps

The catalog of apps a taf can use, and a connection started for one taf. tafs cloud asks planque with the person's own session and keeps nothing. The approval, with KIFF's words for the connection signed on the phone, and the vendor's sign-in after it, are the phone's and planque's, as for a connection Coach starts.

| Route | Who | What |
|---|---|---|
| `GET /v1/connect/apps?q=&provider=&cursor=` | a person | One page of the catalog, searched by `q`: `{apps: [{entry, vendor, title, icon?, how: oauth \| key, state: available \| connected \| needs_reconnect \| needs_setup, words?, grant?}], by_key, provider?, next_cursor?, skipped?, partial?}`. `needs_setup` is an app an operator has to set up before anyone can connect it: it carries `words`, the line the apps show in place of Connect. `partial` says the catalog read stopped with pages left, so an app that isn't listed isn't absent. Reviewed entries come first, then a page of the managed provider's apps (`entry` like `connector:composio:gmail`). Within a page, the account's own connections come first (one to sign in again before one connected), then, for a search, apps whose name starts with `q`, then whose name has it. Ask for the next page with the same `q`, `provider` and `next_cursor` as `cursor`; there's no `next_cursor` on the last page. `skipped` counts entries planque left out as malformed or unsupported. `by_key` says Coach can connect a vendor that isn't listed |
| `POST /v1/connect/apps/{entry}` | `cards.manage`, managing the taf | `{taf}`: starts a connection for that taf's KIFF agent, named by the taf's name in the words the owner signs ("Connect Gmail for Refunds"). Answers `{grant, needs_key?}`, the grant to approve. Nothing is signed here, and there's no sign-in URL until the phone approves |

### Recipes

| Route | Who | What |
|---|---|---|
| `GET /v1/recipes` | a person | The recipes a new Coach conversation opens to ([coach.md](coach.md#recipes)): `{categories: [{id, title}], recipes: [{id, title, category, line, say, connections: [[vendor…]…], card: {allows[], holds[], never[]}}], filtered, shown, suggestions: {show, words}}`. Shown only while the account has no live taf and its suggestions are on (the owner's decision): otherwise `categories` and `recipes` are empty, `shown` is false and `suggestions.words` say why; the apps draw only what comes. The recipes in `recipes/`, filtered to those whose every connection has a vendor the account's catalog lists, read from planque with the person's session, page by page. `categories` are the ones with a recipe, in order, with the title the apps show. `say` is what a tap puts in Coach's message box; the person edits it before sending. When the catalog can't be read, or has more pages than tafs cloud reads, every recipe is listed and `filtered` is false: a recipe is never hidden for a vendor that wasn't seen, and the page is never blank for that |

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
| `POST /v1/tafs/{id}/knob/subscriptions` | manages | `{trigger, subscription, version}`: records planque's subscription for app event `trigger` (a stop id, `trg_event_N`) of `version`, the knob's `version` the phone read it from, after the phone approved it. Answers the dial; idempotent. 400 for an id planque wouldn't give or no `version`; 409 when `version` is no longer live (read the dial again) or `trigger` isn't one of its app events; 503 when it couldn't be recorded (try again). Filters match by meaning, not key order. A version going live again keeps the subscription of the same event and filters |
| `GET /v1/tafs/{id}/knob` | a person | Its dial, three rings the same on every taf: `rings: [{key, name}]` = When (`wakes`), What (`what`) and Where (`results`), inner to outer, and `why`, the face's word when nothing is chosen (the taf's purpose, as Coach wrote it, at most 60 characters). `what: [{id, step, label, words, tool?}]` is the taf's recipe, its steps in order, as Coach's `taf_dial` wrote it; a taf with none shows each of its tools by KIFF's title. `when` is kept for older apps and isn't drawn. A stop may have `size: {label, index, choices: [{label, setting}]}`, its one choice for the dial's − and +: a schedule's time or how often, a place on or off (the last place tafs delivers has none). `index` is the value now, and each choice's `setting` is the whole `PUT` body that makes it, so the apps send it as it is. `draft.what` lists the draft's recipe, one line per step. Rules the apps share, sent so neither writes them: each stop's `ok`, what pressing the face does (`door` opens or closes it, `run` runs once, `remove_follow_up`, `coach` opens Coach; absent, nothing); a door stop's `look` (`open`, `closed`, `resting`, `knock`); When ends with an `add_door` stop for whoever manages the taf, and an empty recipe shows one `what_ask` step, both `ok: coach`; and `pending: {version, changes}`, the version Make it live switches to and how many stops it changes, or `null`. `draft.groups: [{grant?, name, wakes, what, results}]` has the same lines by connection for Coach's card: each connected app's events and actions under it, then This taf (no `grant`) with doors, schedules, the recipe and the places tafs delivers. `?version=N` shows a dial change waiting to go live (a version after the live one with a dial) instead of the live dial: its `setting`, stops and sizes, with `shown: N` and `version` still the live one, so a second − or + builds on the first. Any other version is 404. `{does: {label, line}, wakes, when, results, rim: {done, working, retrying, needs_you, line}, can_pause, draft}`; `can_pause` is false for a taf that works from its chat, which has no schedule to pause (the remote turns ⏻ off). An `app_event` stop has `approval: {state, grant, event, config, subscription?}`: `needed` until the owner's phone approves the subscription at planque (the stop's `ok` is `approve`; the phone makes planque's signed `POST …/grants/{grant}/triggers` with `{account, slug: event, config}`, then tells tafs cloud), `approved` with planque's id after. Until then the stop is off and the taf doesn't wake on it. Each ring item has `id`, `label` (on the ring), `words` (its line), `on`, and for the dial's face `state` (its big word) and `tap` (what tapping the face does, absent when nothing). `wakes[].kind` is `door` (with its `door` id: the face opens or closes it through the doors routes), `schedule` or `run_now` (the face runs it, `POST …/run`); `when[].kind` is `arrivals`, `missed` or `busy`; `results[].place` is a result place. A trigger's stop also has `trigger`, its form exactly as `PUT` takes it (none on `run_now`, nor on a schedule the dial can't set), and the knob has `setting: {wakes: [trigger], busy, results: [place]}` for the live version: a tap copies `setting`, changes one thing, sends it to `PUT`, then switches. `results` lists every place, on or off; the If busy stop and each place have a `tap` (turning it is a change, as above; the last place on has none). A schedule's form and adding or removing triggers go through Coach's draft for now. A follow-up the taf set itself (kiff/agents' `follow_up` tool: once, within seven days, at most three waiting) shows on `wakes` as `kind: "follow_up"`, with its `follow_up` id, `expires` (when it fires) and `words`; it's never part of `setting`. `draft` is the newest dial change made after the live version and not live yet, from a tap or Coach's `taf_dial`: `{version, words, wakes: [line], when: [line], results: [line]}`, its words in one list and by ring. Coach shows it in its chat as `{type: "knob", version, taf_id}`, which the apps draw from `draft`; a card whose version no longer matches `draft.version` was replaced. Confirming is the usual switch. `null` when nothing waits. `connections: [{grant, name, wakes, results}]` lists the connections the stops come from, in order, and each connected app's stop carries its `grant`: the apps show chips under the dial (All · each connection · This taf, the stops with no `grant`), and picking a chip shows only that connection's stops on the rings. There's no limit on connections, triggers or places. The rim counts today's requests in the account's zone. Doors show only to someone who manages the taf. Every word is tafs cloud's; the apps draw them as they are |
| `DELETE /v1/tafs/{id}/follow-ups/{fid}` | manages | Removes a follow-up the taf set itself, by the `follow_up` id its dial gives. Nothing else changes follow-ups: kiff/agents bounds them. `{removed}`; 404 for anything but a follow-up of this taf |
| `PUT /v1/tafs/{id}/knob` | manages | The dial changed, from a tap or Coach's draft: `{wakes: [trigger], when: {busy: skip \| latest}, results?: [place], what?: [{label, words, tool?}], why?}`: `what` is the recipe in order (a short label each), `why` the purpose in a few words; left out, the last ones stay. A place is `door_reply`, `app` or `owner_email` (at least one; tafs cloud delivers them), or any number of actions of the taf's connected apps, which the taf calls itself, a write through KIFF inside its Card: `{place: "action", grant, action, args, name}`, where `grant` is the connection (planque), `action` the tool's name as KIFF registered it, `args` the arguments the owner fixed (a page, a channel, a sheet: text, numbers or true/false) and `name` what the owner reads ("Notion · Support log", one line, at most 100 characters), with `fill?: {field: words}` saying what each field the taf fills takes, in words ("the amount refunded, in EUR"; at most 20 of 200 characters), so every run fills it the same way. tafs cloud names no vendor: any connection's action can be a place, except one the taf's Cards count in money (a summed quantity on the Card or the action): a place moves no money, so it's refused (400, in words Coach reads), checked from KIFF under the person's session at the dial and again when a version goes live, where Coach's draft reaches; Cards that can't be read refuse the change (503). Another taf can be a place too: `{place: "taf", taf, name, fill?}`, whose work the result becomes (a task under that taf's own Card, handed over by tafs cloud when the run ends; one hop, never the taf itself). These go into the version's instructions by ids, arguments and `fill` only (a taf place as a "Hand-off" section the taf ends its result with), and show on the Results ring while live (with `grant` and `action`, or `taf`), where turning one takes it off. A trigger is `{kind: "schedule", form, at?, days?, times?, every_minutes?, between?, date?, skip?}` (`form` is `daily`, `weekdays`, `weekly`, `monthly`, `monthly_last_day`, `times_a_day`, `every` or `once`, in the account's zone), `{kind: "app_check", operation, args, items, cursor, every_minutes}`, `{kind: "door"}`, or any connected app's event: `{kind: "app_event", grant, event, config?, subscription?, name}`, where `grant` is the connection, `event` the provider's event as the connection lists it, `config` the owner's filters, `subscription` planque's subscription (made on the owner's signed approval) and `name` what the owner reads ("HubSpot · new deal"). tafs cloud names no vendor. An app's event is scheduled nowhere: planque hands it to `POST /v1/events/planque` (`planque.event.v1`: `{id, tenant, agent, grant, provider, account, subscription, event, payload, timestamp}`, signed as the Slack door's handover), and it starts a run, the event as data, only while the taf's live version wakes on it; the same event id starts nothing twice. An event that carries a `key` (the handle of a call one of the connection's writes sent, whose outcome comes later: planque's `completes`) first looks for the taf's paused run waiting on exactly that (grant, event, key); found, the event answers that run's wait in kiff/agents (`waits/{call}/answer`), the run goes on with the whole payload as the call's result (past kiff/agents' 64 KB, `{truncated: true, payload: <the JSON text, cut>}`, never an empty answer), and no run starts for it (`{outcome: "answered", run}`); a wait that closed meanwhile (409) counts as none waiting; a delivery repeated with the same event id within ten minutes answers the same and starts no run. Not found, it wakes the taf as any other event, and is kept ten minutes for a run that pauses on it right after (a vendor faster than the pause), answered from the run's pause note. Every N is 15, 20 or 30 minutes or whole hours, and a window starts and ends on the hour: anything else is 400, naming the nearest form. Answers `{version, words}`: the taf's next version, made as an improvement makes one, and what it changes in words. Nothing runs from it until the switch makes it live (`…/versions/{n}/switch`, `…/live`); then its triggers become the taf's schedules in kiff/agents and its places where results go |
| `GET /v1/tafs/{id}/work` | a person | Where its work stands ([reliability.md](reliability.md#the-tafs-view)): `{summary: {status, line, needs_you}, next_run, counts, requests: [{id, at, state, line, waiting_for?, deliveries}], skipped: [{due, why, words}]}`, every line and time written in the account's zone (`time_zone`). `deliveries` has the door's reply (`{place: "door_reply", state, line, send_again?}`) and each place of the taf's own (`runtime/src/cloud/delivery.ts`): `{place: "action" \| "taf", key, name, state: sent \| handed \| retrying \| not_sent, line, send_again?, open_coach?, coach_words?}`, checked by tafs cloud from the run's record when the page is read (an action's call made and taken, a hand-off's run started), never from the taf's report; `not_sent` carries Send again (an action place) and Coach's words to open it with `summary.status` is `halted`, `needs_you`, `retrying`, `working`, `waiting_on_app` (a run waits for a connected app's event, the slow-tools design: `line` is the connection's words for the wait, as the request's `waiting_for`; apart from the home item's `state: "waiting"`, a call held for the owner), `paused` or `idle`; an app that doesn't know a status draws its `line` plainly |
| `GET /v1/tafs/{id}/requests/{run}` | a person; someone else's request, or a door sender's, only to who manages the taf | One request from the dial's rim, step by step: what `GET …/work` gives for it, plus `from` ("By email", "Its schedule", "From its chat", "Run now") and `steps: [{label, line?, state, waits_on?}]`, the time inside `line`, `state` being `done`, `failed`, `not_made`, `waiting` or `next`, and `waits_on: "app"` on a waiting step when the run waits for a connected app's event (the request's `waiting_for`), not for the owner: the apps draw that step as the app's, never as needing them, and match on the field, not on words. The steps are: Received; each call in KIFF's title for the tool ("Worked on it" for one nothing names); a call held for approval; where its reply went; then how it ended or what comes next ("Next try 23:15", "If it fails again, this needs you"). Every line and time is in the account's zone, and the apps draw them as they are. 404 for a request of another taf |
| `POST /v1/tafs/{id}/requests/{run}/deliveries/{place}/send` | manages | Send again. `door_reply`: the door reply of that run, given up on after its tries, sent once more with new tries; does no work, signs nothing; `{state: sent \| not_sent \| waiting}`; 404 when nothing of that run waits to be sent; 409 while it's being sent, or when the taf no longer answers through its door; a send that fails keeps the reply for the next tap. A place of the taf's own, by its `key` (`<grant>:<action>`, URL-encoded): the taf's retry task, now, by hand, the same task its own retry runs (the result, the action by its id, never the owner's name for it); `{state: "retrying"}`; 400 when nothing of that run waits to be sent. A delivery that can be sent again has `send_again: true` in `GET …/work` |
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
| `POST /v1/tafs/{id}/steer` | a person | `{run, text, hold?}`: to the run at work, read at its next step. Without `hold`, 409 when the run took nothing (it ended, or holds as many as it takes): send it as a new message. With `hold: true`, tafs cloud holds it for that chat instead (202): `{sent: {run, session}}` when it went at once as the next message there, and `{held: {text, at}}` for what still waits in the chat until the taf is done. 409 with `code: held_full` when what waits would pass one message (10 messages, 2,000 characters): nothing is held, and `error` says so in words the app shows ([chat.md](chat.md#writing-while-the-taf-works)) |
| `POST /v1/tafs/{id}/stop` | `cards.manage` | `{session}`: cancel the session's latest run |
| `POST /v1/tafs/{id}/holds/{exception}/continue` | `holds.answer` | The run paused on that held call goes on now, once KIFF has answered it. `waiting` when KIFF still holds a call of the run |
| `POST /v1/tafs/{id}/components/{component}/answer` | a person, their own card | `{answer, session?}`: the answer to a card that waits for it, as data ([chat.md](chat.md#cards-that-wait-for-an-answer)). 404 for no card of theirs by that id, 409 once it doesn't wait, 400 for an answer the card doesn't take |
| `POST /v1/tafs/{id}/components/{component}/approval` | a person, their own approval card | Approve tapped ([coach.md](coach.md#approving-tafs-cloud-decides)): `{grant, shown_digest?, draft?, shown_draft?, words?: {profile_sha256, words_sha256}, session?}`, what the card showed, with the phone's signed read of its connections in `Planque-Device-Signature`, forwarded unchanged. tafs cloud reads planque's grant and KIFF's draft and answers one of: `{outcome: "answered", answer, note, session, run, status}`, it answered Coach itself (`already approved: …` or `failed: …`) and `note` is the card's line; `{outcome: "read_again", note}`, the card is out of date (its terms or draft changed, or KIFF's words for the connection aren't on the phone), nothing told; `{outcome: "sign", ask: {id, step: connect \| cards, message, reason, key: window \| critical, planque?: {grant, body}, kiff?: {draft, version}}}`, the one message for the device's key under biometrics, the prompt's words, which key the call takes (`window`: the device key inside an open approval window does, a tap; `critical`: the approval key behind the fingerprint; a hint, since the service the call goes to decides for itself, kiff/tafs#494), and the call it goes into, as the phone must make it (planque's approve body as given, or KIFF's draft confirm). 400 for no grant, 404 for no approval card of theirs by that id, 503 without the read proof or where Connect apps isn't set up |
| `POST /v1/tafs/{id}/components/{component}/approval/{ask}` | a person, their own approval card, signed | What came of the call: `{result: connected \| listed \| confirmed \| sign_in_closed \| no_code \| not_connected \| failed, status?, why?, session?}`, signed as a setup call to KIFF (`kiff.request.v1` over this request, in `KIFF-Device-Signature`). tafs cloud asks KIFF whether this phone signed it (`POST /v1/me/device-signature`, with the phone's session), then reads what holds: planque's grant connected or listed, or KIFF's draft confirmed, is `approved`; anything else `failed: <why>`, in tafs cloud's words, whatever was reported. Answers `{outcome: "answered", answer, note?, session, run, status}`. 403 for a report this phone didn't sign; 403 with `code: critical_key_required` for a critical ask whose report was signed inside an approval window (the report is the device key's as always, v1, since the fingerprint goes on the call it reports; a phone that took the window path for a critical call is sent back), and `code: window_required` for a report signed inside a window that has ended (both with tafs cloud's words in `error`, kiff/tafs#494); 409 for a signed request KIFF already took, 404 once the ask lapsed (20 minutes), was taken by an earlier report, or belongs to another account |
| `GET /v1/tafs/{id}/conversation` | a person | The thread: messages, components, `working`, `steering` (with `held` and `not_sent` for messages written with `hold`), `capped`, Coach's lines, and the open knocks for someone who manages it ([chat.md](chat.md#the-thread-and-the-chats-list)). What waits in each of the person's chats goes first, as their next message there, where the taf is done |
| `GET /v1/tafs/{id}/conversation/{session}` | a person, their own chat | One chat of it. What waits in this chat goes first, where the taf is done |
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
| `GET /v1/tafs/{id}/doors` | The taf's doors, each with its `words` (`state`, `tap`, `settings`, and `pair_hint`, `pair_from_knock?` on a door that holds keys), the apps a door can open on here (a bot door's with `bot: {provider, words: {none, pick}}`), and its open knocks, each with its door's `label` |
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
| `POST /v1/runs/ended` | kiff/agents | A run Coach handed a taf paused or ended. Unsigned: an unknown ref is dropped, and the run is read back from kiff/agents. A pause note's `waits` (the vendor events the run waits on, by grant, event and key) are matched against events kept by `POST /v1/events/planque` that came before the pause, and answered |
| `POST /v1/doors/email`, `POST /v1/doors/whatsapp` | the providers | The doors' webhooks, each checked by the provider's signature |
| `GET /doors/whatsapp/connect` | the owner's browser | The page WhatsApp's signup runs in |

## Keeping this page right

A route added or changed in `handler.ts` changes this page in the same pull request, and
the route list at the top of `handler.ts`.

## Paged Activity and setup views

`GET /v1/time-zones?q=` returns `{zones: [{id, title}]}`, from the service's IANA list,
searched on the service. Selecting a zone still uses KIFF's signed
`PUT /v1/me/time-zone`; tafs cloud never signs for the person.
After KIFF confirms it, `POST /v1/account/time-zone` (no body) applies the zone
KIFF reports to every recurring schedule of this account's tafs. It requires
`cards.manage`, accepts no zone or account from the caller, and returns
`{time_zone, updated}`. It preserves the schedules' cron, pause, busy and check
settings. Absolute one-off times and follow-ups do not move. A partial failure
returns 503 with words explaining that the account changed but some schedules
need retry; repeating it updates only schedules still in another zone.
New tafs use the account's zone when no explicit zone was supplied at creation.
Home tafs carry `time_zone`, `time_zone_words` and `schedule_time_zone`, so the
apps can name the account's clock and identify a schedule still using another.
`schedule_time_zone_words` is present only for that mismatch and gives the next step
in words. A failed sync sends its sentence in both `error` and `words`. The cloud
rechecks KIFF's zone after schedule writes: if another confirmed zone overtook the
request, it applies the newer one before reporting success; repeated changes or
unfinished writes return the retryable 503.

`GET /v1/tafs/{id}/activity?filter=all&limit=20&cursor=…` returns `entries`, day
`groups`, `filters: [{id, title}]`, the selected `filter`, `card_unit_exceptions`, a `revision`, and an
optional `next_cursor`. Filters are `all`, `done`, `held`, or `refused`, applied
on tafs cloud before a page is made. Held means a call waiting for an owner's answer; an allowed call still sending or with an unknown result stays in All. An allowed call with an unknown tool outcome
is never labelled done. Entries carry their ready-to-draw `title`, `detail`,
`time`, `day_title` and optional `amount_text`, plus KIFF's original call and
signed evidence. `tools` (comma-separated action names) and `from` (an ISO date)
can narrow an app's seven-day Activity. The server supplies that query on the app's
setup view; the phones do not identify apps from tool names.

A connection refusal is typed by KIFF as `tool_outcome.status: refused_by_tool`,
`refused_by: connection` and a machine `code`. Its `detail` is drawn as supplied,
or composed from that code if absent. Text prefixes never establish provenance.
A vendor's refusal shows the words KIFF recorded for it, quoted as the app's:
`The app refused it: "Agent Mail refused it (it answered 403): Forbidden."`, or
"The app refused it and gave no reason". Those words never decide whether
anything was done. The call's status, amounts, approval flags, receipts and
counting remain KIFF's unchanged evidence.

A `not_done` entry may carry `next`, what the owner can do about it
(kiff/tafs#476, gap 10): `{kind, words, tool, taf?, message?}`.
- `reconnect`: a vendor's 401 or 403, or the connection's `operation_changed`.
  The app's button opens that connection, the one whose `tools` include `tool`.
- `ask_coach`: a 400, 404, 405, 410 or 422, or the connection's `not_granted`,
  `definition_invalid`, `definition_unread`, `bad_arguments` or
  `permit_invalid`. The button opens Coach on the taf with `message` written in.
- `try_later`: a 429 or a 5xx, KIFF's `not_sent`, or the connection's
  `halt_unknown`, `record_unavailable`, `permit_expired` or
  `profile_not_applied`. `words` is a line, with no button.

The status comes from the "answered NNN" in KIFF's recorded words, as KIFF's
gateway reads it. An app draws `words` as a line for a `kind` it doesn't know.

A page is 20 entries by default, 1–50 when specified. Pass `next_cursor` unchanged.
It is bound to the account, person, taf and filters. A changed snapshot returns
409: refresh from the first page rather than joining inconsistent pages. A page
can be empty with a cursor when a bounded scan has not reached a matching call yet.
Only the final page supplies `end_words` ("Nothing older in this view"). Empty
intermediate pages keep their cursor and never claim that history has ended.

`GET /v1/tafs/{id}/work/runs?limit=20&cursor=…` pages the recent retained runs as
`requests`, with a `next_cursor` when another page remains. A preview requests
`limit=3`. This view reads the runtime's latest 100 runs; it is not an archive of
all past runs. The older `/runs` report endpoint is unchanged. Both new page
routes supply `end_words` at the end of their available history; for runs it
says "Nothing older in the retained runs" to make the retention boundary clear. Both page
routes send an `ETag`; repeating a read with `If-None-Match` returns 304 with no
body when unchanged. Apps keep previously read pages per account, taf and filter.

`GET /v1/tafs/{id}/setup` returns:

- `connections`: each app's display `name`, `action_count`, `action_words`,
  `actions` (each with its title, Card-limit lines and workflow-step numbers),
  the app's `steps`, `running`, and `stats` for the last seven days. Stats include
  `calls`, `done`, `held`, ready-to-draw `words`, `complete`, and an `activity_query`.
  Incomplete source reads are explicitly marked; they are never shown as a complete total.
- `workflow`: its `title`, `summary`, numbered `steps` with app/grant and running
  state, and `running_step` only while that step's tool call has no result. A step's `words`
  is empty when it would only repeat its `label`, so the apps don't show the title twice.
- `memory`: its note `count`, `title` and newest note as `latest`.
- `versions`: `Changes to how it runs`, the live version, earlier count and summary.

Optional `card_unit_exceptions` on Home and Setup, and the always-present array on Activity, contain the same display rows from
KIFF's `GET /v1/me/card-exceptions?status=held`, filtered by the visible taf's subject.
Each is `{id,kind:"card_unit",taf,name,title,words,history,action_title,coach_text,
dismiss_title,can_approve_once:false}`. `id` is KIFF's exception id; history carries
its count and first/last attempts. The Coach action opens that taf with the proposed
unit limit; it changes no Card. Dismiss re-reads the current KIFF exception and signs
its ordinary `reject` answer against the current accounting version. Approve once is
never offered for this kind. Ordinary holds remain separate, and every refused
decision remains in the Record. KIFF owns deduplication and closure when the action's
Card gains the currency or a call in that currency is allowed. An unreadable KIFF
response fails the read instead of claiming no exception waits.

Activity collapses the refused calls in a still-open currency episode beneath that
exception, matching only KIFF's typed `amount_unit_mismatch` reason, subject, action,
observed currency and episode times (or first proposal id). Other decisions and past
episodes remain visible. The original Record is unchanged. The panel is read afresh
with each page, independently of the history cursor revision: changes to its count or
closure do not discard older pages. Apps draw the latest page's panel metadata.
An empty `card_unit_exceptions` array clears an older row. If exceptions cannot be read
or their data is unreadable, Activity still returns its decisions with an empty panel
and `card_unit_exceptions_notice`; it does not claim that no requests wait. Normal
Activity failures still fail the page. Home and Setup retain their existing read errors.

The phone forwards one signed read of planque's account grants using the
`Planque-Device-Signature` header, signed for planque's `GET
/v1/accounts/{tenant}/grants`. tafs cloud forwards that proof unchanged; it does
not hold a device key, sign or approve anything. A missing or refused proof yields
`connections_error`; an unreadable connection list is not presented as an empty one.

`GET /v1/tafs/{id}/memory?all=true` returns all shared notes and this person's own
notes, newest first, plus their `summary`. The older request without `all=true`
keeps its 100-note limit. Forgetting a shared note still requires managing the taf.

## This phone's alert choices

`GET /v1/push` with `Tafs-Device-Token: <this phone's token>` returns `enabled`,
`sound`, `kinds: [{id, title, words, enabled}]`, and `answering: {title, tafs}` for
the per-taf results link. It reads only a registration belonging to the signed-in
person on this account. Tokens are never displayed or included in request logs.

`POST /v1/push` also accepts `kinds`, an object with boolean choices for
`needs_you`, `halted`, `answered` and `not_sent`. Missing choices default on; a token
refresh omitting `kinds` preserves that person's previous choices. They are
checked before either push provider is called. Disabled kinds count as handled,
so retries cannot bypass an opt-out. Background synchronization still clears
previously shown holds. Turning off the whole phone removes its registration.

`DELETE /v1/push` takes `{device_token, platform, environment}` and removes this
person's matching phone, idempotently, leaving other phones and the account's
KIFF alert secret in place. The older `DELETE /v1/push/{token}` is unchanged.

Each setup connection also carries `status_words` from planque (or cloud's fallback)
and `reconnect`; `custody_words`, one line on who holds the sign-in, as planque says
it (the connection service, or tafs' own), left out when planque doesn't say; and
`on_demand: true` when the app's sign-in was set up on the owner's pick rather than by
an operator (kiff/planque#87); and `account_words`, which vendor account it works in and
in which mode, planque's `account_name` and `mode_words` in one line ("Demo Shop ·
Live", "… · Mode not known yet: treated as live"), the name cut to 60 characters, left
out when planque says neither. A connected browser connection (planque's `provider: "browser"`)
also has `site_sign_in: {title, words}`: the action the apps offer to open planque's live sign-in
view (kiff/planque#86). A held sign-in stays visible with zero usable actions until its
actions are approved. Signed-out connections retain their source status and can
open the existing device approval and vendor sign-in flow; the cloud never signs
that approval.
