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

So a Card shows "€212.00 of €2,000.00 today" without a statement call per Card.

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

## 7. One fingerprint approves Coach's install and its Card — open

Tracked on KIFF's side, privately. Installing a version Coach asked for answers
Coach's held `version_install` and, when the version brings one, confirms its
Card. On Android the approval key signs once per fingerprint, so this took two.

- **Asked of KIFF:** the draft's confirm may name the hold it answers,
  `{"version", "hold": {"id", "accounting_version"}}`. The request's signature
  covers the body, so it binds that hold at that version.
- **KIFF's checks:** the hold is in the same account, still held, at that
  version, and its call is a `version_install`. The same gate as approve-once applies (role, separation of duties).
  Only a confirm signed with the approval key takes it, never one signed inside a
  window, and never on a trial confirm (400).
- **KIFF's answer:** the draft, plus `hold: {id, status}`, with `detail` when the
  hold isn't approved. The statuses are `approved`, `not_this_install`,
  `answered`, `expired`, `not_found`, `approval_key_required`, `refused` and
  `failed`. The draft is never refused
  for the hold, and the answer's proof on the receipt is the confirm's own
  signature.

**tafs's part — built (Android).** The app sends `hold` for Coach's held install
only, and treats the hold as answered only on `approved` for that id. A KIFF
that ignores the field answers with no `hold`, and the app answers the hold as
before, so there's no deploy order. iOS already signs both under one Face ID.

## 8. A receipt's steps in the person's language — open

A receipt's **What happened** shows each step of KIFF's signed `causal_chain`
by its `message`, which KIFF writes in English ("Refused"). The apps can't
reword it: it's inside what KIFF signed, and words KIFF didn't sign mustn't read
as KIFF's.

- **The need:** a documented, stable vocabulary of step `kind`s. A new kind is
  a new field or value, and receipts already issued still verify.
- **tafs's part — not built.** Once the vocabulary is published, both apps word
  the kinds they know in the person's language, and show KIFF's signed `message`
  as it is under Details. A kind they don't know shows its `message`, as now.

## 9. Pairing a computer: the routes, and words for each refusal — open

Tracked on KIFF's side, privately.
- **The need:** `POST /v1/me/pairings` as KIFF's `docs/desktop-client.md` §5.3
  sets it out, and a `words` line on each of its refusals (`pairing_expired`,
  `pairing_used`, `key_registered`, `invalid_pairing`) in the person's language,
  as KIFF's other refusals carry.
- **tafs's part — built on Android, not live.** Devices → Add a computer scans
  the QR, shows the check code, and signs the pairing with the approval key
  ([android-app.md](android-app.md#pairing-a-computer)). Until KIFF sends
  `words`, the app words those four codes itself. iPhone and iPad follow.
