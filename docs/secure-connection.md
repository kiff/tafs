# The secure connection sheet, and one signature per setup

An app that takes a key gets it on a sheet that looks and acts protected, and a setup ends
with one signature: the key and the taf's Card together. This is the contract. Each lane
says "I can build this" before any lane builds.

## What the owner sees

One sheet, full screen, outside the chat, from Coach's card (**Give the key on a secure
sheet**) or from Connect apps:

1. **The title:** the app's logo and name, with a lock: "Secure connection to Qonto".
2. **Three plain lines**, worked out from the grant by tafs cloud:
   - **what it may do:** "Read only: it can't pay, send or change anything in Qonto" when
     every one of the grant's tools is a read. Otherwise "It can change things in Qonto. Its
     Card decides each action". The phone never works this out;
   - **the key:** "Your key is sealed: it's never shown again, and the taf never sees it";
   - **leaving:** "You can disconnect it any time, in Connect apps".
3. **Paste the key**, a button, never a typing field. Once pasted, the sheet shows only the
   key's last 4 characters ("Key …ab12"), and the clipboard is cleared ("Clipboard cleared").
4. **Details**, collapsed: the technical lines. These are the specification's URL and its
   SHA-256, the host the key is sent to, the header that carries it, and the connection's own
   words from the connection server, verbatim. These are the words its digest covers.
5. **Its Card**, when the setup has one drafted: KIFF's own lines for the draft, as the
   approval card shows them today ("the 4 reads, 1,000 calls a day, then it asks you"). The
   phone reads the draft from KIFF itself (`GET /v1/me/agent-drafts/{draft}`) and draws KIFF's
   lines, and the `version` it signs is the one it read: never a draft or lines tafs cloud
   passed along, so one fingerprint never signs a Card the sheet didn't show.
6. **One legal line:** "By connecting, you agree to the Terms and the Privacy Policy", each
   a link.
7. **One button**, **Connect**, behind one fingerprint (or Face ID). It gives the key and
   confirms the Card with one signature (below).
8. **The receipt**, on the sheet: "Qonto connected · key …ab12 · Disconnect". The last 4 are
   the phone's, from the paste. Nothing keeps them after the sheet closes, so the
   connections list says "Connected", without them.

The sheet stays above the keyboard (with a Paste button there is no keyboard, except when
the person writes in a field of their own, such as search).

## tafs cloud: the sheet's words, by the grant

**`GET /v1/connect/grants/{grant}/sheet`**, for whoever manages the account's Cards. With the
person's session, tafs cloud reads the grant from the connection server and the catalog
entry it came from, and answers in the request's `Accept-Language`:

```json
{ "vendor": { "name": "Qonto", "logo": "https://…", "host": "thirdparty.qonto.com" },
  "title": "Secure connection to Qonto",
  "lines": [
    { "kind": "reads_only", "text": "Read only: it can't pay, send or change anything in Qonto" },
    { "kind": "sealed", "text": "Your key is sealed: it's never shown again, and the taf never sees it" },
    { "kind": "disconnect", "text": "You can disconnect it any time, in Connect apps" } ],
  "paste": { "button": "Paste the key", "what": "Copy it in Qonto, then paste it here",
             "pasted": "Key …{last4}", "cleared": "Clipboard cleared" },
  "details": { "title": "Details", "lines": [
    { "label": "Specification", "value": "https://…/openapi.json" },
    { "label": "SHA-256", "value": "…" },
    { "label": "Sent only to", "value": "thirdparty.qonto.com" },
    { "label": "Header", "value": "Authorization" } ] },
  "card_title": "Its Card",
  "legal": { "text": "By connecting, you agree to the {terms} and the {privacy}.",
             "terms": { "words": "Terms", "url": "https://kiff.dev/terms" },
             "privacy": { "words": "Privacy Policy", "url": "https://kiff.dev/privacy" } },
  "button": "Connect",
  "receipt": { "text": "Qonto connected · key …{last4}", "disconnect": "Disconnect" } }
```

- **The first line's `kind`** is `reads_only` only when the grant lists at least one tool
  and every one says `read_only: true`. Otherwise it's `may_change`, including a grant whose
  tools carry no flag. The phone draws `text`, and may use `kind` for an icon.
- **The phone checks the first line** against the grant it reads from the connection server
  to sign its digest: `reads_only` only with at least one tool, every one `read_only`. On a
  mismatch it shows no sheet to sign, and says the connection can't be checked. The line
  people trust most is never tafs cloud's word alone.
- **The placeholders** are `{last4}` (in `paste.pasted` and `receipt.text`) and `{terms}` and
  `{privacy}` (in `legal.text`, each drawn as its link's `words`). tafs cloud fills the
  vendor's name everywhere. The phone fills `{last4}` from the pasted key, and the key never
  leaves the phone except to the connection server, as today.
- **The legal links** are per language (`https://kiff.dev/de/terms`), as the apps' own legal
  links are.
- **The logo** is the catalog entry's, served by tafs cloud as Connect apps' logos are (a
  path on tafs cloud, never the vendor's host), so the sheet's requests stay on our own hosts.
  With none, `logo` is absent and the phone draws the name alone.
- **`paste.what`** says where the key comes from in a few words: "Copy it in Qonto, then paste
  it here". A key made on a computer reaches the phone through a shared clipboard or a
  password manager.
- 404 for a grant the connection server doesn't show this person, and 409 for one that takes
  no key.

## One signature: the key and its Card together

**Why two today:** each service checks a signature over the request it receives:
- KIFF over `kiff.request.v1` (tenant, method, path, the body's SHA-256, `signed_at`);
- the connection server over its own request message, the same form under its base URL,
  which it has KIFF check.

Giving the key is one signed request and confirming the Card's draft is another. KIFF names
that confirm `critical`, so each takes the approval key, and so the fingerprint, once.

**The shape: one signature over both requests, `kiff.requests.v1`.** When Coach's card carries
both the grant and the Card's draft (the approval component's `draft`, as it can today), tafs
cloud's approval answer gives one ask, step `key_and_cards`. The phone builds both requests
in full, with one `signed_at`:
1. the key to the connection server, as today (`POST …/grants/{id}/key`, the key in
   `Planque-Vendor-Key`);
2. the draft's confirm to KIFF, as today (`POST /v1/me/agent-drafts/{draft}/confirm` with
   `version`, and `hold` when the held call goes too).

For each request it computes the request message its service checks today, exactly those
bytes, and signs once, with the approval key behind the fingerprint:

```
kiff.requests.v1
{tenant}
{signed_at}
{sha256 hex of one request message}
{sha256 hex of the other request message}
```

The two hashes are in sorted order. The phone sends the same signature on both requests,
each in its service's signature header as today, with `v3=` for the signature and `with=`
naming the other request's hash: `KIFF-Device-Signature: t={signed_at},v3={signature},with=sha256:{hex}`,
and the same fields in the connection server's header.

- **Each service** rebuilds its own request message from the request it received, hashes
  it, forms the list with `with`, and checks the signature over it with the device's approval
  key. It never reads the other request: it knows only that its own was signed together with
  one other.
- **Nothing the person saw goes unsigned:** each request's body hash already binds what it
  carries. KIFF's binds the draft and its version (and the held call), and the connection
  server's binds the grant's digest and the key's SHA-256.
- **The time and once-only rules stay as they are:** `signed_at` within 5 minutes of each
  service's clock, and each service takes a signature once. A `kiff.requests.v1` signature
  is always the approval key's, never a window's tap.
- **It's generic:** any two requests under one fingerprint (a key and its Card today, a
  sign-in and its Card later) need nothing new in either service.
- **If the confirm fails after the key went in,** the app is connected and the Card still
  waits, and the card says so. A retry re-sends the same signed confirm only if KIFF never
  checked it (no answer at all), within its 5 minutes. Otherwise it takes one fresh
  fingerprint over KIFF's request alone (`kiff.request.v1`), as a Card confirm does today.
- **Without a draft** (an app that signs in at the vendor, whose tools are known only after
  the sign-in), the two steps stay as they are.

**Built in tafs cloud** (`approval.ts`, `decide` and `settle`): with `TAFS_KEY_AND_CARDS=on`, a grant that takes
a key, asked with its Card's draft by a phone that says it can sign the step (`can: ["key_and_cards"]` in the
approval request's body; an app from before leaves it out and gets the two steps, the flag on or off), gets `{step: "key_and_cards", scheme: "kiff.requests.v1", message: "", key:
"critical", planque: {grant, body}, kiff: {draft, version}}`, after the same checks as the cards step (the version
shown, on-demand writes, every tool on a Card). Its report answers Coach `approved` only when the connection server
says connected (a key grant has no `listed`) and KIFF says the Card is issued; a key that went in with a Card that didn't answers `failed: <app> is
connected; …`, so Coach's next card asks for the Card alone. The flag stays off until both services take
`kiff.requests.v1`, and the two steps work as before.

**The phone signs only what it read itself.** The ask's `planque.body.digest` and `kiff.version` are hints for
building the two requests: the phone puts in them the digest of the grant it read from the connection server, as
the connect step requires, and the version of the draft it read from KIFF (the rule above). If either differs from
the ask, it signs nothing and says the card is out of date. So a wrong digest or version from tafs cloud is never
signed without being shown. One test in each app pins it.

**Coach** drafts the Card before the key sheet opens, from the grant's tools, which the connection
server declares in KIFF at the request (`declared: true` on the grant) and which can't be used until
the key is in (`connect-an-app@10`, `draft-the-card@8`). Not declared, Coach asks for the key first,
then the Card, as before.

**A connection's added tools and its Card together (`tools_and_cards`).** The same shape, for an app already
signed in: Coach adds the taf's tools to a connected grant (the addition waits as `pending`, with what it had in
`connected`), drafts the Card over them, and shows one approval with the grant and the draft. With
`TAFS_TOOLS_AND_CARDS=on` and `can: ["tools_and_cards"]`, tafs cloud answers `{step: "tools_and_cards", scheme:
"kiff.requests.v1", message: "", key: "critical", planque: {grant, body: {digest, kiff_profile?}}, kiff: {draft,
version}}`. The phone builds the two requests it already knows, with one `signed_at`: the connection server's approve
(`POST …/grants/{grant}/approve`, as the connect step sends it) and KIFF's confirm (as the cards step does), signs
their two hashes once, and sends `with=` on each, as for a key. The report answers `approved` only when the grant is
connected with the tools and the Card is issued; tools in with a Card that didn't answers `failed: <app> is connected;
…`, so the next card asks for the Card alone. It needs the connection server to take the paired approve and to
declare an addition's tools in KIFF while they wait (and withdraw them if the addition ends unapproved); until then
the flag stays off and the two steps work as before. A first connection (nothing connected yet) keeps its steps.

**Until this ships:** an approval card whose connection is already connected never repeats
the connection's text. tafs cloud marks it on Coach's approval component in the conversation
(`connection: {done: true, words: "Qonto is connected (read only)"}`), and the apps draw only
that line and Its Card.

## Lanes

| Lane | What it builds |
|---|---|
| **tafs cloud** | The sheet route and its words in the 8 languages; the `key_and_cards` ask; the connected mark on a later card |
| **Connection server** | The key route takes a `kiff.requests.v1` signature (checked through KIFF, as today) |
| **KIFF** | Signed routes take a `kiff.requests.v1` signature; the device check the connection server calls takes it too |
| **Apps** | The sheet; Paste the key; one fingerprint, two calls; the receipt |
| **Coach** | The Card drafted before the key sheet (an Eval) |

## Tests

- **The first line says read only only when every tool is a read**, and `may_change` for a
  grant with a write tool, or with no flags. The phone refuses a sheet whose `reads_only`
  doesn't match the grant it read.
- **Its Card on the sheet is the draft the phone read from KIFF**, and the signed version is
  that draft's.
- **No placeholder but `{last4}`, `{terms}` and `{privacy}`**, and every line is in the 8
  languages.
- **One fingerprint per key setup with a Card:** the phone signs one `kiff.requests.v1`
  message, and both services accept it once.
- **qa-kiff's negatives:** the signature reused on a third request is refused; a swapped
  `with` hash is refused by both services; a `signed_at` outside 5 minutes is refused; a
  device-key (window) signature in `v3=` is refused.
- **The key never leaves the phone except to the connection server**, and the last 4 aren't
  kept after the sheet closes.
- **A card whose connection is already connected** shows only the done line and Its Card.
