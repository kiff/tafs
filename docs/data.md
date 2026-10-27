# Where tafs data lives

Every place tafs keeps data, what is in it, for how long, and who deletes it. It is
written from the code and the live setup, not from policy. Where a fact couldn't be
checked, the row says **unverified**.

tafs makes no data-residency claim. What tafs keeps is in AWS `eu-west-1` (Ireland):
**kiff/agents** (the runtime: definitions, runs, chats, notes, schedules, the tafs'
KIFF keys) and, since 25 October 2026, **tafs cloud**'s table and push key. tafs
cloud's functions (the phone's API, push) still run in `us-east-1`, reading and
writing that data in `eu-west-1`; their logs stay in `us-east-1` until they move too.
Anthropic only serves the model.

The move: until 25 October 2026, tafs cloud's table was in `us-east-1`. It was copied
to `eu-west-1` at the cutover, and the `us-east-1` table is deleted the same day, once
the copy is checked, so nothing erased in `eu-west-1` stays behind in `us-east-1`.

**Anthropic, from its documentation (checked 15 October 2026):**
- kiff/agents calls the Messages API. It keeps no session at Anthropic: each model
  turn is a request, and Anthropic keeps requests 30 days by default, with its
  legal, usage-policy and terms exceptions.
- Model inference may run in the US, Europe, Asia or Australia unless a request
  pins `inference_geo: "us"`. kiff/agents doesn't pin it
  ([where are your servers](https://privacy.claude.com/en/articles/7996890-where-are-your-servers-located-do-you-host-your-models-on-eu-servers)).

## The table

| What | Where | Region | What's in it | How long | Who deletes it |
|---|---|---|---|---|---|
| tafs cloud logs | AWS CloudWatch, `/aws/lambda/tafs-cloud` and `…-stream` (also two old test functions' logs, `tafs-cloud-smoke` and `tafs-live-test`, set to 30 days on 22 Oct) | `us-east-1` | Lambda start and end lines, and the error a failed request hit (`console.error` in `handler.ts`; push, alert and removal steps log a short reason). Bodies, keys, tokens and secrets are not logged on purpose. An error from kiff/agents or KIFF can include a taf's id or name. | 30 days (`infra/aws.sh`, checked live) | CloudWatch, on expiry |
| Tafs | DynamoDB `tafs-cloud-names`, item `tafs#tenant` | `eu-west-1` | Per taf: its id, name, job, KIFF agent id, the claim on its name, when it started; its run budget, the owner's start notes and a digest of its definition, so a new definition can be built for it (#69); its colour in the app (#91) | Until the taf is removed | tafs cloud on remove |
| Taf names | Same table, `tenant#name` | `eu-west-1` | The KIFF account id and the taf's name | Until the taf is removed (#34) | tafs cloud when a taf is removed or fails to start |
| Chat flood slots | Same table, `rate#tenant#taf#window#n` | `eu-west-1` | Account id, taf id, a 10-minute window number | About 10 minutes; DynamoDB's ttl deletes it, usually within 48 hours | DynamoDB ttl |
| Coach's tool calls (kiff/tafs#206) | Same table, `tenant#coach-jti#…`, `tenant#coach-op2#person#…` | `eu-west-1` | Account id; KIFF's permit id and operation id; a tool's answer to Coach, kept for the person whose Coach chat asked it | Permits 5 minutes, answers an hour | DynamoDB ttl, and account deletion |
| Coach's tasks and lines (kiff/tafs#206) | Same table, `tenant#coach-task#ref`, `tenant#coach-notes#person` | `eu-west-1` | Account id; the taf and run Coach handed a task to and the task's first words; a line on how it went, for Coach's next turn | Until the run ends, at most 7 days; a line until Coach reads it, at most 7 days | DynamoDB ttl, Coach reading it, and account deletion |
| A taf's doors | Same table, `tenant#doors#taf` | `eu-west-1` | Per door: its app, whether it's open, its address (a tafs email address or a WhatsApp number), and its keys: each paired sender's email address or number, and the name the owner gave it | Until the door or the taf is removed | tafs cloud on remove, and account deletion |
| A door's route | Same table, `door#<route>` | `eu-west-1` | Which account, taf and door an address or number is. A removed email address is kept retired, naming no account, so no other taf gets it | Until the door or the taf is removed; a retired address a year (ttl) | tafs cloud on remove; DynamoDB ttl |
| A door's messages seen, counts and threads | Same table, `tenant#door-seen#…`, `tenant#door-day#…`, `tenant#door-thread#…` | `eu-west-1` | A hash of each message's id; each door's count of messages per day, from paired senders and from others; which chat a thread goes on in. Never the text | Message ids 30 days, counts 2 days, threads 30 days (ttl) | DynamoDB ttl, and account deletion |
| A door's knocks | Same table, `tenant#door-knocks#taf` | `eu-west-1` | Messages from people who aren't keys, for the owner to answer: who wrote (address or number, and name), the subject, the text (cut at 20,000 characters), how many attachments it had, whether the sender check passed. Never headers, HTML or attachments | 30 days after the latest knock (ttl), the newest 50 | DynamoDB ttl, tafs cloud on remove, and account deletion |
| Activity's events (kiff/tafs#236, items 15 and 19) | Same table, `tenant#events` | `eu-west-1` | Per account, what happened in tafs itself for the app's Activity: a halt and its resume, a knock and what the owner did with it, a pairing, a door's removal; each with its time, the taf's id and name, and the door's app. Who wrote only by the name their message gave; a paired sender by that name, else their address (as on the door's keys). Never a stranger's address, a message's text or a key | 90 days after the latest event (ttl), the newest 200 | DynamoDB ttl, and account deletion |
| A door's replies | Same table, `tenant#door-reply#ref`, `tenant#door-pending#taf`, `tenant#door-sending#ref`, `tenant#door-sent#taf` | `eu-west-1` | Where a run's answer goes: the sender, the email's subject and message ids, or the WhatsApp message id; which replies are still owed, and a claim while one is sent; then whether it was sent, and why not | Until the reply is sent, at most 30 days; a claim 10 minutes; how it went, 30 days | DynamoDB ttl, and account deletion |
| A door's AI bound | Same table, `tenant#door-ai` | `eu-west-1` | The account's AI fair-use period and bound, as last read with the owner's session, for the runs a door starts without one | 40 days (ttl) | DynamoDB ttl, and account deletion |
| A WhatsApp number's token | AWS Secrets Manager `<DOORS_SECRETS_PREFIX><tenant>/whatsapp/<number>` | `eu-west-1` | The business token WhatsApp's signup gave for the number | Until the door or the taf is removed: deleted at once, without the recovery window | tafs cloud on remove |
| A door's message text | DynamoDB `agents` (kiff/agents), in the taf's chat | `eu-west-1` | A key's message, as the taf reads it: the door, who wrote, the subject and the text | As a chat message (above) | As a chat message |
| Email and WhatsApp in transit | The email provider; WhatsApp | Theirs; WhatsApp's in the region the number is registered with | The messages to and from a door. tafs cloud reads an email once and keeps only what's above | Their terms | The provider |
| Archived chats (#82, #97) | Same table, `archived#tenant#taf` | `eu-west-1` | Account id, taf id, the session ids of the chats the owner archived, listed under Archived until put back. Never their text | 31 days after the latest archive (ttl), past when kiff/agents deletes the chats; or until the taf is removed | tafs cloud on remove; DynamoDB ttl |
| Claimed drafts | Same table, `draft#tenant#draft` | `eu-west-1` | Account id, KIFF draft id, a sha256 of the draft's claim secret, the taf's name and id, the KIFF agent id. Never the key. A taf Setup made has no claim secret: tafs cloud claims its draft from kiff/agents with its own role (`from_setup`) | Until the taf starts, else 30 days (ttl) | tafs cloud on start; DynamoDB ttl |
| Phones for alerts (with #32) | Same table, `push#tenant` | `eu-west-1` | Each phone's APNs token and environment, the KIFF person id that registered it, whether they may answer held calls; the account's KIFF alert-target signing secrets (newest 5) | Until the person signs out, Apple reports the token gone, or a manager removes it. Secrets: until 5 newer replace them | tafs cloud |
| Delivered alerts (with #32) | Same table, `alert#tenant#event#token` | `eu-west-1` | Account id, KIFF event id, phone token | 72 hours, ttl | DynamoDB ttl |
| The APNs key | AWS Secrets Manager `tafs/apns-key`, replicated to `eu-west-1`, where it's read | `us-east-1`, `eu-west-1` | The "tafs push" key | Until rotated or revoked in the Apple Developer portal | A person |
| The Anthropic API key | AWS Secrets Manager `tafs/anthropic-api-key`, replicated to `eu-west-1` | `us-east-1`, `eu-west-1` | The model key. Only kiff/agents reads it (its replica); tafs cloud doesn't | Until rotated | A person |
| A taf's KIFF agent key | AWS Secrets Manager `agents/<tenant>/kiff/<taf>` | `eu-west-1` | The key the taf calls KIFF's gateway with. tafs cloud writes it and can't read it; kiff/agents reads it for each run | Until the taf is removed: deleted at once, without the recovery window | tafs cloud on remove |
| An account's alert secret | AWS Secrets Manager `agents-accounts/<KIFF account>` | `eu-west-1` | The account id and its newest KIFF alert-target signing secret, so kiff/agents can check KIFF's alerts too | Until a newer one replaces it | tafs cloud, on each new target |
| Definitions, runs, chats, notes, schedules | DynamoDB `agents` (kiff/agents) | `eu-west-1` | Per taf: its definition (instructions with KIFF's skill and the owner's first words, model, cost cap; its key by reference only); each run and chat with the owner's messages and the taf's replies and reports; the tool calls through KIFF's gateway and their results (which can include customer data); the notes the taf writes; its schedule; a paused run's held calls; in a chat, what it read (below) and the summary of a long chat's earlier part, written by the model | Each kind of event has a keep rule (`runtime/src/cloud/tafdef.ts`): tool calls and results only until their run ends, everything else 30 days, the summary too; DynamoDB's ttl deletes it. **What a chat read** (a read-only call and its answer, such as a payment) is sealed with the account's own key before it is stored, and only that chat's next messages use it: while the owner keeps writing, until the chat has been idle an hour, and never more than 4 hours after the read. From then it is never read, and DynamoDB's ttl deletes it, usually within 48 hours. Writes' answers are never kept, and scheduled runs keep no reads. A run waiting on the owner keeps what it needs until it ends. Notes until forgotten | kiff/agents' ttl; the owner (Forget); tafs cloud on remove, which deletes the agent and everything kept for it, after withdrawing its held calls |
| Files attached to a chat message (#209) | S3 `kiff-agents-attachments` (kiff/agents), with a record per file in DynamoDB `agents` | `eu-west-1` | A photo, PDF, text, Word or Excel file a person attached to their own message in their own chat: its bytes, its name, type, size and SHA-256, and the message it was sent with; a Word or Excel file's text too. Only the person who attached it can open it: not an admin, the account's owner or a door. Never kept for the taf or in its notes. The phone uploads and downloads it straight to and from S3, through links that work for minutes; tafs cloud never holds it | As its message: 30 days from when it was sent; with the chat when the person deletes it, the taf when it's removed, the account when it's deleted. An upload no message named, an hour. The bucket removes anything left 38 days on | kiff/agents' sweep; tafs cloud on remove and account deletion; S3's lifecycle rule |
| AI use per period (#193) | DynamoDB `agents` (kiff/agents), `U|<tenant>` | `eu-west-1` | Per account and billing period: the model cost of its chats and Setup messages, in millionths of a US dollar, added as each run pauses or ends. Only the period's name (its start) and the total: no message, no run, no taf. Scheduled runs aren't counted. tafs cloud reads it for the app's AI use and sends the bound with each chat; the bound itself (half the plan's price × seats × (go-ons + 1)) is worked out from KIFF's usage read and never kept | 70 days after its last change (ttl), past any billing period's end | kiff/agents' ttl; erased with the account (`DELETE /tenant`) |
| The account's sealing key | AWS Secrets Manager `agents-sealing/<tenant>` | `eu-west-1` | 32 random bytes per account, which seal what its chats keep of their reads. kiff/agents makes it the first time it needs it, and only it reads it | Until a person deletes it; deleted, every read sealed under it can't be opened | A person |
| Schedules | EventBridge Scheduler, group `agents` | `eu-west-1` | When each taf runs: cron, time zone, the job's instruction | Until the taf is paused (off) or removed | kiff/agents |
| A taf draft's claim | AWS Secrets Manager `agents-drafts/<tenant>/<draft>` | `eu-west-1` | Per draft Setup submits: its claim secret until the taf's key is claimed, then what the claim did (the agent, where its key went, when). The key itself only for the moment between KIFF minting it and its storing | Until a person deletes it; deleting the account will delete it once kiff/agents#180 is live | A person |
| kiff/agents logs | AWS CloudWatch, `/aws/lambda/agents*` | `eu-west-1` | Start and end lines, and errors: ids (the account, a run, a schedule), a status, and the error's text, which can quote what an upstream service answered (Anthropic, KIFF), never a request's body. Checked in code (every log call) and in the last 3 days of logs (22 Oct) | 30 days (kiff/agents `infra/aws.sh`, checked live); the Eval's Stripe stand-in, 7 | CloudWatch, on expiry |
| Backups | none | | tafs cloud's table (`tafs-cloud-names`) and kiff/agents' (`agents`) have point-in-time recovery off and no backups, so a deleted record is gone at once (checked live, 22 Oct) | | |
| What the model sees | Anthropic, Messages API | Inference may run elsewhere (above) | Each model turn: the instructions, the session's recent history (with the summary of a long chat's earlier part), where its connections and drafts stand now, what the chat read earlier (up to 4 hours), tool calls and results, and the files attached to the chat's messages, sent inline with the request (newest first, up to 20 MB), never into Anthropic's file storage | 30 days by default (Anthropic's terms, above) | Anthropic |
| Cards, held calls, answers, receipts | KIFF (`api.kiff.dev`) | KIFF's | Who may do what, what was held, the phone's signed answers, receipts | KIFF's terms | KIFF: [kiff.dev/privacy](https://kiff.dev/privacy) |
| Push alerts (with #32) | Apple Push Notification service | Apple's | "A taf needs you", the tool name, the held call's id. No amounts or request details: KIFF never sends them | Until delivered, or until the hold expires | Apple |
| The phone | iPhone Keychain, this device only | The phone | The KIFF session, the account id, the pending alert secret (with #32) | Until sign-out | The app on sign-out |
| Chat messages dealt with (#67) | The app's UserDefaults, `tafs.chat.settled` | The phone | SHA-256 hashes (run, time and text) of the unread messages of finished runs the app has shown or sent on, the last 500. Never the text | Until sign-out (another account signs in only after one) | The app on sign-out |
| The tafs list shown last (#91) | The app's UserDefaults, `tafs.list` | The phone | For the account signed in to: each taf's name, state, colour and its Cards' summary (rules, limits, use), the same for the account's other agents with Cards, and which KIFF agent each is. So the next launch shows the list in its first frame. Never a chat, a memory or a held call | Until sign-out or a new session (another account's list is never shown) | The app on sign-out |
| The phone's signing key | Secure Enclave (a software key in the Simulator) | The phone | The P-256 key that signs answers. Never leaves the phone | Until the app is deleted | iOS |
| The phone's approval key | Secure Enclave, usable only after Face ID or the passcode (a software key in the Simulator) | The phone | The P-256 key that signs what grants authority: Approve, issuing a Card (submitting or confirming a draft), starting a trial, Resume. Its public half is registered with KIFF once per session. Never leaves the phone | Until the app is deleted | iOS |
| Sample data | The app's Application Support, `sample.json` | The phone | Sample tafs, held calls and the record. Sample mode only; nothing from KIFF | Until sign-out from sample mode | The app |
| A file opened full size (#209) | The app's temporary folder, `tmp/attachments` | The phone | A copy of a file the person attached, downloaded when they open it in Quick Look | Until sign-out or an account switch, or sooner when iOS clears the folder | The app on sign-out and account switch; iOS |

## What goes where

```
phone ──(KIFF session)──────────────▶ KIFF        held calls, signed answers, revoking a Card
phone ──(KIFF session)──────────────▶ tafs cloud  the home list, start, chat, memory, remove, push token
tafs cloud ──(who is this?)─────────▶ KIFF        /v1/me with the person's session
tafs cloud ──(person's session)─────▶ KIFF        Cards and held calls, read for the home list, never kept
tafs cloud ──(person's session)─────▶ KIFF        the usage read (plan, seats, period, go-ons) for each chat's AI bound, never kept
tafs cloud ──(its AWS role)─────────▶ kiff/agents definitions, runs, chats, notes, schedules, attached files' records
phone ──(a link for minutes)────────▶ S3          an attached file's bytes, up and down (#209)
kiff/agents ──(model key)───────────▶ Anthropic   one model turn at a time
kiff/agents ──(agent key)───────────▶ KIFF gateway ──(Card checked)──▶ the company's tools
KIFF ──(signed alert)───────────────▶ tafs cloud ──(APNs)──▶ phone
                                      tafs cloud ──(as signed)──▶ kiff/agents  (a paused run goes on)
```

- **tafs cloud stores no accounts.** Every request carries the person's KIFF session, and tafs cloud asks KIFF who that is.
- **What the company's tools return** reaches the taf's run in kiff/agents, kept only until that run ends. What a chat only read is kept for that chat's next messages, sealed, while the owner keeps writing: up to an hour idle, and never more than 4 hours after it was read. Nothing shows it: not the app, a report or an export. It reaches tafs cloud only when the phone reads a report or a chat, and tafs cloud keeps none of it.
- **The agent's key** goes from KIFF to tafs cloud to Secrets Manager in one request. The phone never holds it, and tafs cloud can't read it back.

## Deleting the account (#185)

Settings → *Delete account*, behind Face ID, deletes everything above that is the account's. A step that fails stops the rest, and asking again goes on from there:
1. **tafs cloud** (`POST /v1/account/delete`, someone who may delete the account): removes every taf, Setup included, as removing one does (its kiff/agents agent and everything kept for it, after withdrawing its held calls; its KIFF agent key; its name). Then it asks kiff/agents to erase the account (`DELETE /tenant`): the account's own halt and skills, its AI use per period, its sealing key and its drafts' claim records, at once, with no recovery window. Then it deletes its own items whose id names the account (`runtime/src/cloud/account.ts`: the tafs, phones and alert secrets, removals, archives, claimed drafts, flood slots, delivered alerts, names, and what doors keep) and the account's alert secret. Removing each taf first removed its doors' routes and their WhatsApp tokens.
2. **planque**, signed by the phone: every connection disconnected, and the enrolment forgotten.
3. **KIFF**, signed by the phone, last, since it ends the phone's session: the account's keys stop at once, and KIFF erases its records after its 30-day grace window ([kiff.dev/privacy](https://kiff.dev/privacy)).
4. **The phone** signs out, which clears what it kept (above).

What Anthropic, Apple and CloudWatch keep follows their own expiry, as above.

## Open

- **Managed Agents, being retired (#42).** tafs ran on Claude Managed Agents
  before this. No tafs code uses it any more, and nothing creates anything
  there: the CLI and CI's `setup` job are gone. Checked on 2026-10-17: no
  tafs deployments, sessions, memory stores or vaults are left, so no taf's
  KIFF agent key is held there. The Bill pay, Access and Deploys agents are
  archived (archived, not deleted). Until kiff/agents#3's baseline is graded,
  the Refunds and Chargebacks agents stay active, with the `tafs` environment
  and the KIFF skill upload. None of these hold customer data or credentials,
  and all of them are deleted as #42's last step.
- **Inference region.** tafs doesn't pin `inference_geo`. Pinning `"us"` keeps
  inference in the US at 1.1x the price. Decide when a customer asks.
