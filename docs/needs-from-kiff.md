# What tafs needs from KIFF

tafs uses KIFF like any customer, through the public API. These are the gaps
found while building the iPhone app, in the order they block the product. Each
one belongs on kiff/kiff-cloud as an issue or RFC; none should be worked around
inside tafs.

## 1. Verify the phone's signature on an answer

**Today:** the app does not answer KIFF's held calls. KIFF would accept an
answer on the device session alone, which does not prove the owner gave it, so
each held call from KIFF shows read-only with **Answer in KIFF**, which opens it
at `app.kiff.dev/needs-you/{id}`. The switch is
`KIFFClient.serverVerifiesSignatures`, off until this item ships.

The signing is built: in sample mode every answer is signed with a P-256 key in
the iPhone's Secure Enclave, behind Face ID, over
`tafs.answer.v1|<id>|<answer>`, where the id is KIFF's exception id for a held
call from KIFF.

**Needed:** register a device's public key against the person
(`POST /v1/me/devices`), and accept an answer only with a valid signature from a
registered key over a fixed message. KIFF should define the canonical message
(exception id, answer, next step, accounting version, a nonce or expiry), refuse
missing, invalid, wrong-hold and replayed signatures, and put the device key id
in the receipt. Then the app sends the signature with the answer and turns the
switch on.

**Why it matters:** without it, anyone holding the session token can approve a
refund. With it, the receipt proves the owner's own iPhone approved it.

## 2. Tell the phone when a call is held

**Today:** the app lists held calls when it opens or is pulled to refresh, and
posts a local notification for new ones. A closed app hears nothing.

**Needed:** a way for KIFF to reach the owner's phone the moment a call is held.
Either KIFF sends Apple push itself (device token registered with the device key
above), or KIFF calls a webhook on the tafs backend, which sends the push. The
push also starts or updates the Live Activity countdown.

## 3. Start an agent and issue its Card from the phone

**Today:** in KIFF mode the app shows the agents that already hold the person's
Cards. Starting a new taf works only in sample mode.

**Needed:** the draft → owner confirmation → activation flow (#1177) for a new
holder: the tafs runtime submits a draft Card that grants nothing; the owner
confirms the exact holder, tools and limits on the phone; only then does it
activate. Plus a Card per operation inside a generic tool (#1175) for tools like
Shopify's.

## 4. A cheap way to list a Card's use, in its units

The app shows "$212 of $500 today" in sample mode. In KIFF mode it would need
`/v1/me/mandates/{id}/statement` per Card on every refresh. A `used` and
`remaining` per limit on the mandates list would save a round trip per Card.

The mandates list also carries no units, so the app shows a Card's limits from it
as bare numbers (15000, not $150.00). The statement and held calls already say
the unit and scale (`unit`, `scale`, `parameter_units`); the list should too.

## 5. An agent key for a taf, without the dashboard

**Today:** the runtime (`runtime/`) needs the taf's KIFF agent key, copied from
KIFF's dashboard into `KIFF_AGENT_KEY`. The key goes into an Anthropic vault as
a static bearer token for `mcp.kiff.dev/mcp`.

**Needed:** with item 3, KIFF returns the new holder's credential to the
runtime once the owner confirms the Card, so starting a taf from the phone needs
no copy and paste. An OAuth grant (RFC 044) would fit too: Anthropic's vault
refreshes `mcp_oauth` credentials itself.
