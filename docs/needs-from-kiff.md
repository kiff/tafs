# What tafs needs from KIFF

tafs uses KIFF like any customer, through the public API. These are the gaps
found while building the iPhone app, in the order they block the product. Each
one is tracked on KIFF's side, privately; none should be worked around inside
tafs.

## 1. Verify the phone's signature on an answer — shipped, in use

Tracked on KIFF's side, privately.
- The app binds its Secure Enclave key at sign-in and signs KIFF's
  `kiff.answer.v1` message on every answer.
- It signs its one setup call, the draft that starts a taf (item 3).
- KIFF refuses an answer from the phone's session without a valid signature.
- The settled receipt keeps the proof: the phone's name, its public key, the
  signed text and the signature.

## 2. Tell the phone when a call is held — shipped, in use

Tracked on KIFF's side, privately.
- KIFF sends a signed alert to a registered target when a call is held, and
  again when it's answered (`hold_answered`).
- It retries failed deliveries for about a day, then switches the target off.

**tafs's part — shipped.** tafs cloud's `POST /v1/kiff/alerts` checks KIFF's
signature, hands the alert to kiff/agents so a paused run goes on, and sends an
Apple push to the account's phones; the app registers its push token with tafs
cloud (`POST /v1/push`). How it works: [approvals.md](approvals.md#alerts-while-tafs-is-closed).

**Not built:** the push doesn't start or update the Live Activity's countdown.
The app starts it while it runs, from KIFF's list of held calls.

**Registering from the phone — shipped.**
The phone registers its account's alert target with a signed setup call, like
the draft. KIFF takes only URLs on its allowed list (tafs cloud by default) and
alerts that phone only about held calls. A new registration replaces the
account's other targets at that URL.

## 3. Start an agent and issue its Cards from the phone — shipped, in use

Tracked on KIFF's side, privately.
1. The app submits one signed draft: the new agent and one Card per tool. From
   someone who may issue Cards and give keys, it's confirmed at once. An
   editor's draft waits for an admin to confirm it in KIFF.
2. tafs cloud claims the agent's key with the draft's one-time secret and puts
   it straight into Secrets Manager, where kiff/agents reads it. The key never
   reaches the phone.

Still open in KIFF: a Card per operation inside a generic tool, like Stripe's
`stripe_api_write`.

## 4. A cheap way to list a Card's use, in its units — shipped, in use

Tracked on KIFF's side, privately.
- `GET /v1/me/mandates` gives every limit its `unit` and `scale`.
- `?include=use` adds each Card's `used` and `remaining`.

So a Card shows "$212.00 of $2,000.00 today" without a statement call per Card.

## 5. An agent key for a taf, without the dashboard — shipped, in use

Covered by the draft claim in item 3. `POST /v1/tafs` still takes an
`agent_key` for a taf started by hand.

## 6. A held call's result names the limit it went over — open

Tracked on KIFF's side, privately. When the gateway holds a call, the text the
taf reads says only that the call is outside its Card. The taf then tells the
owner it can't say which limit was hit, while the app shows "40 US$ would go
over the 250 US$ total" for the same hold (the 755 device retest). The held
result's text, or its `dev.kiff/call` meta, should name the limit in the same
words the owner sees: one call's limit, or a total kept across calls with what's
left of it.
