# Where tafs data lives

Every place tafs keeps data, what is in it, for how long, and who deletes it. It is
written from the code and the live setup, not from policy. Where a fact couldn't be
checked, the row says **unverified**.

tafs makes no data-residency claim. Everything tafs runs itself is in AWS
`us-east-1`. We'll revisit this when a customer asks, with this page as the input.

**Anthropic, from its documentation (checked 15 October 2026):**
- Data is stored in the US: `us` is the only workspace geo
  ([data residency](https://platform.claude.com/docs/en/manage-claude/data-residency)).
- Model inference may run in the US, Europe, Asia or Australia unless an agent
  pins `inference_geo: "us"`. tafs's agents don't pin it
  ([where are your servers](https://privacy.claude.com/en/articles/7996890-where-are-your-servers-located-do-you-host-your-models-on-eu-servers)).
- Managed Agents sessions are kept until deleted, and aren't eligible for zero
  data retention. Content Anthropic's safety systems flag can be kept longer under
  its own terms.

## The table

| What | Where | Region | What's in it | How long | Who deletes it |
|---|---|---|---|---|---|
| tafs cloud logs | AWS CloudWatch, `/aws/lambda/tafs-cloud` | `us-east-1` | Lambda start and end lines, and the error a failed request hit (`console.error` in `handler.ts`; push and removal steps log a short reason). Bodies, keys, tokens and secrets are not logged on purpose. An error from Anthropic or KIFF can include a taf's name or a session id. | 30 days (`infra/aws.sh`, checked live) | CloudWatch, on expiry |
| Taf names | DynamoDB `tafs-cloud-names`, item `tenant#name` | `us-east-1` | The KIFF account id and the taf's name | Until the taf is removed (#34). Without #34, forever | tafs cloud when a taf is removed or fails to start |
| Chat flood slots | Same table, `rate#tenant#taf#window#n` | `us-east-1` | Account id, taf id, a 10-minute window number | About 10 minutes; DynamoDB's ttl deletes it, usually within 48 hours | DynamoDB ttl |
| Memory set-up slot | Same table, `memory#taf` | `us-east-1` | The taf id | 10 minutes, ttl | DynamoDB ttl |
| Claimed drafts | Same table, `draft#tenant#draft` | `us-east-1` | Account id, KIFF draft id, a sha256 of the draft's claim secret, the taf's name, the KIFF agent id, the Anthropic vault id. Never the key | Until the taf starts, else 30 days (ttl) | tafs cloud on start; DynamoDB ttl |
| Phones for alerts (with #32) | Same table, `push#tenant` | `us-east-1` | Each phone's APNs token and environment, the KIFF person id that registered it, whether they may answer held calls; the account's KIFF alert-target signing secrets (newest 5) | Until the person signs out, Apple reports the token gone, or a manager removes it. Secrets: until 5 newer replace them | tafs cloud |
| Delivered alerts (with #32) | Same table, `alert#tenant#event#token` | `us-east-1` | Account id, KIFF event id, phone token | 72 hours, ttl | DynamoDB ttl |
| The Anthropic API key | AWS Secrets Manager `tafs/anthropic-api-key` | `us-east-1` | The key tafs cloud runs tafs with | Until rotated | A person |
| The APNs key | AWS Secrets Manager `tafs/apns-key` | `us-east-1` | The "tafs push" key (`3THAT9GWCS`) | Until rotated or revoked in the Apple Developer portal | A person |
| Agents, environment, KIFF skill uploads | Anthropic, Claude Managed Agents | Stored in the US | One agent per job: model, instructions, KIFF's skill. No customer data | `setup` updates the agents and the environment in place; Anthropic keeps their earlier versions (retention **unverified**). Each new pin of KIFF's skill is a new upload, and older uploads stay | Nobody: `setup` makes the current configuration active and deletes nothing |
| A taf's deployment | Anthropic | Stored in the US | Name, schedule, budget, the KIFF account id and agent id as metadata, the opening instruction | Until removed (#34 archives it; Anthropic keeps archived deployments, **retention unverified**) | tafs cloud on remove |
| Sessions and transcripts | Anthropic | Stored in the US; inference may run elsewhere (above) | Every run and chat: the owner's messages, the taf's replies and reports, the tool calls through KIFF's gateway and their results (which can include customer data from the company's tools) | 90 days (`RETENTION_DAYS`): tafs cloud deletes older ones every day (EventBridge rule `tafs-cloud-expire`). Removing the taf deletes all of them at once: its runs (they carry its deployment) and chats (they mount its memory store), with their events and sandboxes | tafs cloud, daily and on remove |
| Memory stores | Anthropic | Stored in the US | The notes a taf writes about how the owner wants the job done (and the owner's first note), and every earlier version of each note | Until the owner forgets a note, or the taf is removed. The store keeps earlier versions of a note for up to 30 days, deleted or not, so Forget also redacts them. Removing the taf deletes the store with all its versions | The owner (Forget), tafs cloud on remove |
| Vaults | Anthropic | Stored in the US | The taf's KIFF agent key. Never in the agent's sandbox | Until the taf is removed (#34) | tafs cloud on remove |
| Cards, held calls, answers, receipts | KIFF (`api.kiff.dev`) | KIFF's | Who may do what, what was held, the phone's signed answers, receipts | KIFF's terms | KIFF: [kiff.dev/privacy](https://kiff.dev/privacy) |
| Push alerts (with #32) | Apple Push Notification service | Apple's | "A taf needs you", the tool name, the held call's id. No amounts or request details: KIFF never sends them | Until delivered, or until the hold expires | Apple |
| The phone | iPhone Keychain, this device only | The phone | The KIFF session, the account id, the pending alert secret (with #32) | Until sign-out | The app on sign-out |
| The phone's signing key | Secure Enclave (a software key in the Simulator) | The phone | The P-256 key that signs answers. Never leaves the phone | Until the app is deleted | iOS |
| Sample data | The app's Application Support, `sample.json` | The phone | Sample tafs, held calls and the record. Sample mode only; nothing from KIFF | Until sign-out from sample mode | The app |

## What goes where

```
phone ──(KIFF session)──────────────▶ KIFF        Cards, held calls, signed answers
phone ──(KIFF session)──────────────▶ tafs cloud  start, chat, memory, remove, push token
tafs cloud ──(who is this?)─────────▶ KIFF        /v1/me with the person's session
tafs cloud ──(Anthropic key)────────▶ Anthropic   deployments, sessions, memory, vaults
taf (Anthropic) ──(agent key)───────▶ KIFF gateway ──(Card checked)──▶ the company's tools
KIFF ──(signed alert)───────────────▶ tafs cloud ──(APNs)──▶ phone     (with #32)
```

- **tafs cloud stores no accounts.** Every request carries the person's KIFF session, and tafs cloud asks KIFF who that is.
- **What the company's tools return** reaches the taf's session at Anthropic. It reaches tafs cloud only when the phone reads a report or a chat, and tafs cloud keeps none of it.
- **The agent's key** goes from KIFF to tafs cloud to an Anthropic vault in one request. The phone never holds it.

## Open

- **Archived deployments and earlier agent versions.** How long Anthropic keeps them
  after tafs archives or replaces them is still **unverified**.
- **Chats from before memory (#21).** They mount no memory store, so removal can't
  find them by the store. They predate any customer.
- **Inference region.** tafs doesn't pin `inference_geo`. Pinning `"us"` keeps
  inference in the US at 1.1x the price. Decide when a customer asks.
