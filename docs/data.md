# Where tafs data lives

Every place tafs keeps data, what is in it, for how long, and who deletes it. It is
written from the code and the live setup, not from policy. Where a fact couldn't be
checked, the row says **unverified**.

tafs makes no data-residency claim. Its two parts run in two AWS regions:
**tafs cloud** (the phone's API, push) in `us-east-1`, and **kiff/agents** (the
runtime: definitions, runs, chats, notes, schedules, the tafs' KIFF keys) in
`eu-west-1`. Anthropic only serves the model.

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
| tafs cloud logs | AWS CloudWatch, `/aws/lambda/tafs-cloud` | `us-east-1` | Lambda start and end lines, and the error a failed request hit (`console.error` in `handler.ts`; push, alert and removal steps log a short reason). Bodies, keys, tokens and secrets are not logged on purpose. An error from kiff/agents or KIFF can include a taf's id or name. | 30 days (`infra/aws.sh`, checked live) | CloudWatch, on expiry |
| Tafs | DynamoDB `tafs-cloud-names`, item `tafs#tenant` | `us-east-1` | Per taf: its id, name, job, KIFF agent id, the claim on its name, when it started | Until the taf is removed | tafs cloud on remove |
| Taf names | Same table, `tenant#name` | `us-east-1` | The KIFF account id and the taf's name | Until the taf is removed (#34) | tafs cloud when a taf is removed or fails to start |
| Chat flood slots | Same table, `rate#tenant#taf#window#n` | `us-east-1` | Account id, taf id, a 10-minute window number | About 10 minutes; DynamoDB's ttl deletes it, usually within 48 hours | DynamoDB ttl |
| Claimed drafts | Same table, `draft#tenant#draft` | `us-east-1` | Account id, KIFF draft id, a sha256 of the draft's claim secret, the taf's name and id, the KIFF agent id. Never the key. A taf Setup made has no claim secret: tafs cloud claims its draft from kiff/agents with its own role (`from_setup`) | Until the taf starts, else 30 days (ttl) | tafs cloud on start; DynamoDB ttl |
| Phones for alerts (with #32) | Same table, `push#tenant` | `us-east-1` | Each phone's APNs token and environment, the KIFF person id that registered it, whether they may answer held calls; the account's KIFF alert-target signing secrets (newest 5) | Until the person signs out, Apple reports the token gone, or a manager removes it. Secrets: until 5 newer replace them | tafs cloud |
| Delivered alerts (with #32) | Same table, `alert#tenant#event#token` | `us-east-1` | Account id, KIFF event id, phone token | 72 hours, ttl | DynamoDB ttl |
| The APNs key | AWS Secrets Manager `tafs/apns-key` | `us-east-1` | The "tafs push" key | Until rotated or revoked in the Apple Developer portal | A person |
| The Anthropic API key | AWS Secrets Manager `tafs/anthropic-api-key`, replicated to `eu-west-1` | `us-east-1`, `eu-west-1` | The model key. Only kiff/agents reads it (its replica); tafs cloud doesn't | Until rotated | A person |
| A taf's KIFF agent key | AWS Secrets Manager `agents/<tenant>/kiff/<taf>` | `eu-west-1` | The key the taf calls KIFF's gateway with. tafs cloud writes it and can't read it; kiff/agents reads it for each run | Until the taf is removed: deleted at once, without the recovery window | tafs cloud on remove |
| An account's alert secret | AWS Secrets Manager `agents-accounts/<KIFF account>` | `eu-west-1` | The account id and its newest KIFF alert-target signing secret, so kiff/agents can check KIFF's alerts too | Until a newer one replaces it | tafs cloud, on each new target |
| Definitions, runs, chats, notes, schedules | DynamoDB `agents` (kiff/agents) | `eu-west-1` | Per taf: its definition (instructions with KIFF's skill and the owner's first words, model, cost cap; its key by reference only); each run and chat with the owner's messages and the taf's replies and reports; the tool calls through KIFF's gateway and their results (which can include customer data); the notes the taf writes; its schedule; a paused run's held calls | Each kind of event has a keep rule (`runtime/src/cloud/tafdef.ts`): tool calls and results only until their run ends, everything else 30 days; DynamoDB's ttl deletes it. A run waiting on the owner keeps what it needs until it ends. Notes until forgotten | kiff/agents' ttl; the owner (Forget); tafs cloud on remove, which deletes the agent and everything kept for it, after withdrawing its held calls |
| Schedules | EventBridge Scheduler, group `agents` | `eu-west-1` | When each taf runs: cron, time zone, the job's instruction | Until the taf is paused (off) or removed | kiff/agents |
| kiff/agents logs | AWS CloudWatch, `/aws/lambda/agents*` | `eu-west-1` | Start and end lines and errors. Whether an error can carry part of a request body is **unverified** | 30 days (kiff/agents `infra/aws.sh`, checked live) | CloudWatch, on expiry |
| What the model sees | Anthropic, Messages API | Inference may run elsewhere (above) | Each model turn: the instructions, the session's recent history, tool calls and results | 30 days by default (Anthropic's terms, above) | Anthropic |
| Cards, held calls, answers, receipts | KIFF (`api.kiff.dev`) | KIFF's | Who may do what, what was held, the phone's signed answers, receipts | KIFF's terms | KIFF: [kiff.dev/privacy](https://kiff.dev/privacy) |
| Push alerts (with #32) | Apple Push Notification service | Apple's | "A taf needs you", the tool name, the held call's id. No amounts or request details: KIFF never sends them | Until delivered, or until the hold expires | Apple |
| The phone | iPhone Keychain, this device only | The phone | The KIFF session, the account id, the pending alert secret (with #32) | Until sign-out | The app on sign-out |
| Chat messages dealt with (#67) | The app's UserDefaults, `tafs.chat.settled` | The phone | SHA-256 hashes (run, time and text) of the unread messages of finished runs the app has shown or sent on, the last 500. Never the text | Until sign-out (another account signs in only after one) | The app on sign-out |
| The phone's signing key | Secure Enclave (a software key in the Simulator) | The phone | The P-256 key that signs answers. Never leaves the phone | Until the app is deleted | iOS |
| Sample data | The app's Application Support, `sample.json` | The phone | Sample tafs, held calls and the record. Sample mode only; nothing from KIFF | Until sign-out from sample mode | The app |

## What goes where

```
phone ──(KIFF session)──────────────▶ KIFF        Cards, held calls, signed answers
phone ──(KIFF session)──────────────▶ tafs cloud  start, chat, memory, remove, push token
tafs cloud ──(who is this?)─────────▶ KIFF        /v1/me with the person's session
tafs cloud ──(its AWS role)─────────▶ kiff/agents definitions, runs, chats, notes, schedules
kiff/agents ──(model key)───────────▶ Anthropic   one model turn at a time
kiff/agents ──(agent key)───────────▶ KIFF gateway ──(Card checked)──▶ the company's tools
KIFF ──(signed alert)───────────────▶ tafs cloud ──(APNs)──▶ phone
                                      tafs cloud ──(as signed)──▶ kiff/agents  (a paused run goes on)
```

- **tafs cloud stores no accounts.** Every request carries the person's KIFF session, and tafs cloud asks KIFF who that is.
- **What the company's tools return** reaches the taf's run in kiff/agents, kept only until that run ends. It reaches tafs cloud only when the phone reads a report or a chat, and tafs cloud keeps none of it.
- **The agent's key** goes from KIFF to tafs cloud to Secrets Manager in one request. The phone never holds it, and tafs cloud can't read it back.

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
