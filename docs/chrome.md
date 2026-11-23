# tafs in your Chrome

A taf, running in tafs cloud and often unattended, uses the owner's own Chrome through the tafs extension. planque
decides every step and records it, KIFF counts it on the taf's Card, and anything risky waits for the owner's approval
on the phone. It is a second driver for planque's browser connection (`kind: "browser"`, the `browse_*` tools and their
guard), in the owner's Chrome instead of planque's hosted browser.

**It ships behind tafs cloud's switch, and is off until it's done and proved.** Nothing here runs for anyone until
planque offers the `extension` driver, which only the owner turns on, from the phone, per grant.

The extension's code is in `chrome/` (Manifest V3, plain ES modules, no bundler). planque's side is in kiff/planque.
This page is also the contract between the extension, planque and the phones: each side builds to it, and a change to
it changes all three.

## What the extension is

| File | What it does |
|---|---|
| `chrome/manifest.json` | MV3. Permissions at install: `storage`, `alarms`, `scripting`, `sidePanel`, `tabs`, `tabGroups`, `webNavigation`, `declarativeNetRequest` (to fence a run's tabs; it widens no access). **No host permissions at install**: each site is asked for, once, when a run needs it (`optional_host_permissions`). No `debugger`, `downloads`, `cookies`, `history`, `webRequest` or `nativeMessaging`. |
| `chrome/src/sw.js` | The service worker: keeps the pairing, connects to planque when paired, runs what planque sends, answers the side panel. |
| `chrome/src/relay.js` | The one connection to planque, always opened by the extension. Nothing connects in. |
| `chrome/src/protocol.js` | Checks each step as planque's and signs each answer. |
| `chrome/src/pairing.js` | The pairing code, the extension's proof, and the check of the phone's answer. |
| `chrome/src/crypto.js`, `jcs.js` | Keys, signatures, HMAC, canonical JSON. |
| `chrome/src/runner.js` | Runs checked steps in the run's own tabs. |
| `chrome/src/sites.js` | Where a run may go, enforced in the extension. |
| `chrome/src/guard.js` | The act rules, mirroring planque's `browser.Refuse`. |
| `chrome/src/page.js`, `mask.js` | The content script: reads a page as the taf sees it, masks, does one act. |
| `chrome/src/dialogs.js` | In a run's tabs only: the page's dialogs stop blocking, and a confirm is never accepted. |
| `chrome/src/store.js` | What's kept: the key pair and the pairing, nothing else. |
| `chrome/sidepanel/` | Pairing, and while a taf works: what it's doing, Pause, Resume, Stop, and the sites to allow. |
| `chrome/_locales/` | The words: English, and de, es, fr, it, nl, pl, pt_PT, pt_BR, ko and ja, in the app's own terms. `core.test.js` checks every locale has English's keys and placeholders, and a description of at most 132 characters, Chrome's limit. |

## Crypto

- **Every key is ECDSA P-256 with SHA-256:** the extension's (WebCrypto, not extractable, kept in IndexedDB), the
  phones' (their device keys, as for every approval), and planque's step key.
- **A signature is over a label, a zero byte, then the body's canonical JSON** (RFC 8785):
  `sig = ECDSA(label ‖ 0x00 ‖ JCS(body))`, sent as the raw 64-byte `r‖s` (IEEE P1363), base64url without padding.
  planque converts its keystore's DER to `r‖s`, and the phones convert theirs. The extension also reads a DER signature,
  but sends only `r‖s`.
- **Keys are SPKI DER, base64url without padding.** `chrome_key_sha256` is the SHA-256 of the extension's SPKI DER, in
  lowercase hex.
- **Each direction and purpose has its own label,** so a signature made for one message is never valid as another:

| Label | Signed by | Over |
|---|---|---|
| `tafs-chrome/pair/v1` | the phone | a pairing's confirmation |
| `tafs-chrome/hello/v1` | the extension | its proof on connect |
| `tafs-chrome/welcome/v1` | planque | its proof on connect |
| `tafs-chrome/step/v1` | planque | a step |
| `tafs-chrome/result/v1` | the extension | a step's answer |
| `tafs-chrome/release/v1` | the phone | one sealed value for one fill |
| `tafs-chrome/unpair/v1` | the extension | its own unpairing |
| `tafs-chrome/sensitive/v1` | the phone | one sensitive site allowed for one grant: `{chrome_id, grant, site}` |
| `tafs-chrome/sites/v1` | the phone | a grant's sites, when the owner approves the grant: `{chrome_id, grant, sites}` |

- **HMACs** are HMAC-SHA256 under the pairing code's secret, over `label ‖ 0x00 ‖ data`, with `tafs-chrome/ext/v1` and
  `tafs-chrome/phone/v1`.
- **planque's step key is pinned in the build** (`chrome/src/config.js`), one per environment. A new key ships as a new
  build. A build with no key pinned pairs with nothing.

## Pairing: it starts on the phone

The owner pairs from the phone, and only types a code into Chrome. A code shown by someone else's extension can never get
the owner to pair a stranger's Chrome, and planque never learns the part of the code that proves both keys.

1. **The phone opens a pairing.** `POST /v1/accounts/{t}/chromes/pairings` is signed by the phone as every planque call
   is, and answers `{pairing: "pr_…", lookup: "K7QF"}`. The phone makes a **17-character secret** of its own (about 83
   bits, so a proof planque stores can't be searched offline) that it never sends. It shows `K7QF-29XM-DPQR-STVW-XYZ2-3`
   (the lookup, then the secret in groups of four) for 10 minutes, with "Pair only a Chrome on a computer you're at". The
   characters exclude `0 1 I L O U`, and the extension takes the code with or without dashes and in any case.
2. **The owner types the code into the side panel.** The extension makes its key pair and calls
   `POST /v1/extension/pairings/{lookup}` (unauthenticated, rate-limited per lookup) with:
   ```
   {key: <SPKI, base64url>, version, manifest_hash (64 lower-case hex), browser, os, kiff_offer,
    proof: HMAC(secret, "tafs-chrome/ext/v1" ‖ key ‖ kiff_offer),
    profile_id, one_touch: true,
    one_touch_proof: HMAC(secret, "tafs-chrome/ext-one-touch/v1" ‖ key ‖ kiff_offer)}
   ```
   `profile_id`, `one_touch` and `one_touch_proof` are the newer fields (below). A planque that predates them refuses the
   body (`400`, "unknown field"), so the extension sends the offer once more without them: it then pairs the old way.
3. **The phone reads the pairing** (`GET …/pairings/{pairing}`: `status` is `waiting_for_chrome`, then
   `waiting_for_phone` with the extension's `key`, `key_sha256`, `proof`, `version`, `browser` and `os`, then
   `confirmed`; `contested` when a second Chrome posted on the code, which planque then spends; an expired pairing
   is 404) and checks `proof` with its secret. A wrong proof
   means another extension used the lookup: it stops there. It shows the browser and OS, the owner names it
   ("Work Mac"), and Face ID or the fingerprint signs `tafs-chrome/pair/v1` over:
   ```
   {pairing, chrome_key_sha256, name, phone_key, phone_proof}
   phone_proof = HMAC(secret, "tafs-chrome/phone/v1" ‖ phone_key ‖ sha256(chrome key))
   ```
   (`sha256(chrome key)` is the raw 32 bytes here, and lowercase hex in the signed body.) It sends that with
   `POST …/pairings/{pairing}/confirm`, and planque binds `chrome_id` (`chr_…`) to the account. The account's Chromes
   are `GET …/chromes` (`{chromes: [{chrome_id, name, browser, os, version, paired_at}]}`, 404 while planque doesn't
   offer them) and `DELETE …/chromes/{chrome_id}`.
4. **The extension polls** `GET /v1/extension/pairings/{lookup}?key=…`. It gets `202` while the phone hasn't confirmed,
   then, once, `{pairing, chrome_id, name, phone_key, phone_proof, phone_sig}`; 404 for a key that isn't the pairing's.
   Its `POST` answers 403 for a build that isn't released, and 404 for an unknown or expired code and for one another
   Chrome used (planque spends it, and the phone reads `contested`), so a sweep can't tell them apart. It keeps the pairing only if `phone_proof`
   holds under the secret it typed and `phone_sig` holds under `phone_key` (`pairing.checkConfirmation`).
   - **With one fingerprint** (`kiff.requests.v1`) the answer carries `tenant`, `signed_at` and `kiff_request_sha256` beside
     `phone_sig`, all three or none, and `phone_sig` is then the approval key's signature over `kiff.requests.v1`, the
     tenant, `signed_at` and the sorted SHA-256 (lower-case hex) of this pairing's message (`tafs-chrome/pair/v1`, a zero
     byte and the body's canonical JSON) and of KIFF's request for the extension's view-only device. The extension
     rebuilds its own message and takes KIFF's hash as given; a request paired with itself, a newline in a field or a part
     of the three is refused. Without them `phone_sig` is checked over the pairing alone, as before.
   - **The offer carries `profile_id`**, an id the extension keeps for its Chrome profile (see "What's kept"). The phone
     uses it only to pre-select which Chrome a re-pairing replaces; it isn't covered by the proof and authenticates nothing.
   - **`one_touch: true` says this extension checks the combined signature**, and `one_touch_proof` is what makes the flag
     the extension's: the key's proof under its own label, so it is made only with the code's secret, which planque never
     sees. The phone sends the combined signature only when `one_touch_proof` verifies, else the two signatures of today,
     so a phone that doesn't know the flag, and an extension that predates it, pair as before. The key's `proof` is
     unchanged, as the phones verify it today.
   - The secret then leaves memory.
   - A new phone (a new device key) pairs again; planque never carries a phone key over.
- **Several Chromes:** each paired Chrome has its name, and a grant names the one it uses.
- **Only the owner, on the phone, turns the `extension` driver on for a grant.** Nothing in a taf, Coach or a page can.

### The extension's KIFF device (view-only)

The extension is also a **view-only KIFF device** of the account, paired in the same step, so the side panel can talk
to Coach (§ "Coach in the side panel") through tafs cloud's own routes. tafs never takes a back door into KIFF.

- **Its own second key** (P-256, WebCrypto, not extractable), separate from its planque key, and KIFF's own
  `kiff.pair.v1` offer for it (`chrome/src/kiff.js`): `{v, pub, name: "tafs for Chrome", platform: "chrome",
  kind: "extension", key_store: "software", nonce, exp}`, with no `approval_pub` and no `credential`. KIFF pairs it
  view-only and refuses `chrome`/`extension` otherwise.
- **It rides the Chrome offer:** `kiff_offer` (its text) goes with the planque offer, and the extension's proof covers it:
  `proof = HMAC(secret, "tafs-chrome/ext/v1" ‖ key ‖ kiff_offer)`. planque keeps it as given, and the phone checks it
  with the proof. So planque can swap neither key.
- **One Face ID on the phone** signs the Chrome confirmation, then KIFF's `POST /v1/me/pairings` for that offer, as Add a
  device does, with the approval key and no second prompt (`AppModel.confirmChrome`). KIFF's pairing goes first, so a
  Chrome is never confirmed without its device. iOS reads the offer with `PairingRequest.read`, which takes `chrome` and
  `extension` only together and only with `key_store: "software"`. Android reads it with `PairQr.readChromeExtension`, to
  the same rule, and asks a second fingerprint for KIFF's pairing: its approval key unlocks for one signature.
- **The extension claims its session** as an iPad does: `POST /v1/pair/claim` with the nonce, its KIFF key and the
  `kiff.pair.claim.v1` proof (a DER signature, standard base64), every 3 s while the offer lives. A session that isn't
  `approval_mode: "view"` is ended at once with `POST /v1/auth/device/logout` and never kept.
- **The session reads and chats only.** KIFF refuses it every approval route (`403 view_only`). It's kept in IndexedDB
  with the pairing, and unpairing ends it with its own bearer (`/v1/auth/device/logout`).

## Your Chrome (design, for the owner's read; not built yet)

What the owner lives with: a browser connection is on one of their Chromes, they can see which, and pairing again, unpairing
or switching never leaves a connection pointing at a Chrome that's gone. Nothing moves without the owner's approval.

1. **Re-pairing replaces, never adds.** The pairing confirm names the Chrome the new one replaces (`replaces: "chr_…"`,
   planque's field, on the confirm's own signature) and the connections that move (`carried`). The phone **pre-selects**
   what it replaces: the Chrome with the same `profile_id` (an id the extension keeps for its Chrome profile, kept across
   key changes and updates); else, when the extension was reinstalled and its id is new, the one Chrome on the same
   browser and system that hasn't connected since the new one arrived. The owner can pick none ("a new Chrome") or
   another. With nothing chosen, nothing moves.
   - **What the fingerprint binds is `carried`.** Each connection's sites are signed for one Chrome
     (`tafs-chrome/sites/v1` over `{chrome_id, grant, sites}`), so the pairing body the phone signs for the extension
     (`tafs-chrome/pair`, inside the one `phone_sig`) carries `carried: [{grant, sites}]`, one entry per connection that
     moves. The phone reads each from the old Chrome's verified signed copy, never from what planque says, and shows them
     on the replace's approval ("moves Wiki lookup: wikipedia.org"). planque moves exactly the grants it sent, each only
     if it's on the Chrome replaced with exactly those sites; if planque relayed another list, the extension's check of
     `phone_sig` fails and nothing is paired. Ending the old Chrome's other connections is taking power away, as unpair is.
   - **Carried again:** a carried connection has no `sites_sig` of its own, so the new Chrome keeps the pairing as the phone
     signed it (planque's `paired_with` on the Chromes list: the pairing body and `phone_sig`). A later re-pairing carries it
     only when the phone verifies that `phone_sig` with its own key, for that Chrome's key and exactly the connection's sites,
     as planque checks too; else it ends with the old Chrome.
   - **The extension's rule for `carried`:** read only from the confirmed pairing body; at most 50 connections and 100
     sites each, no grant twice, each site passing the same check as a grant's sites, in the order sent. A step for a
     carried grant is accepted when its sites are within the carried sites for that grant (the same "within" as a fresh
     `sites_sig`), else by a fresh `sites_sig` for this Chrome as today; planque marks such a step `carried: true` with no
     `sig`. The entries live with the pairing and go with unpairing. Like any `sites_sig` today they don't expire, so a
     later narrowing of a grant's sites relies on the fresh signature being sent.
   - **What doesn't move:** the owner's yes for a sensitive site (`tafs-chrome/sensitive/v1` is over the old Chrome's id;
     they allow it again, and the replace's approval never promises it), the saved sign-ins (sealed in the old Chrome,
     wiped with it; the owner saves them again), and the pictures kept of the old Chrome's acts (they go with it).
2. **"Your Chrome" is a choice per connection.** Each browser connection names one Chrome. A new connection takes the most
   recently approved Chrome by default; Coach says which and the owner can pick another. Switching an existing
   connection to another Chrome is one signed planque call (`PUT /v1/accounts/{t}/grants/{grant}/chrome {chrome_id}`,
   routine: a tap inside an open approval window, else the fingerprint). Its sites and Card stay; it goes to the new
   Chrome's key.
3. **Unpairing a Chrome ends its connections.** The unpair confirm lists them ("ends Wiki lookup's wikipedia.org"); once
   unpaired, planque revokes each, so nothing dangles. They come back only by connecting again, or by a replace (1).
4. **Every browser connection names its Chrome**, its name and browser ("Work Mac · Chrome 131"), wherever a
   connection is listed: the taf's page, Coach, Account › Chrome (each Chrome with the connections on it). Coach can
   propose removing a connection whose Chrome is gone; removing is a safe route (the device key, no fingerprint).
5. **One fingerprint per act.** Pairing is one prompt on every phone. The two approval-key signatures it needs today, the
   extension's `phone_sig` over the pairing (`tafs-chrome/pair`) and KIFF's view-only device for the extension
   (`POST /v1/me/pairings`), become one `kiff.requests.v1` signature: `kiff.requests.v1`, the tenant, `signed_at`, then the
   SHA-256 (hex, sorted) of the pairing message the extension checks and of KIFF's `kiff.request.v1` message. The phone
   sends planque's confirm `tenant`, `signed_at` and `kiff_request_sha256` (all three or none), which the extension
   collects beside `phone_sig`; the extension rebuilds its own pairing
   message, hashes it, and checks the one signature over both, as KIFF checks its half. planque's confirm stays on the
   device key, and the replace (1) is in it.
6. **Isolation is unchanged.** Each Chrome keeps its own key; each connection keeps its own signed sites and its own tab
   group; a step still goes only to the Chrome its connection names. A replace or a switch changes the Chrome a
   connection uses, never its sites or Card.

Who builds what, once the owner agrees: planque (`replaces` on the confirm, the switch route, revoke on unpair, the Chrome
on each connection's read), the extension (`profile_id` in the pairing post), Coach (the default and the choice, removing
a dead connection), and the apps (the replace choice at pairing, the Chrome on each connection, Account › Chrome).

## Connect: both ends prove themselves

`wss://relay.planque.kiff.dev?chrome=chr_…` (the WebSocket API's own domain, mapped onto its stage, so no path), opened by the extension:

1. **The extension sends** `{type: "open"}` once the connection is up. planque's relay can't post to a connection until
   its connect has returned, so the extension speaks first.
2. **planque answers** `{type: "challenge", nonce_p}`. The challenge is what makes a replayed hello useless, so it stays.
3. **The extension answers** `{type: "hello", chrome_id, nonce_p, nonce_e, version, manifest_hash, sig}`, signed with its
   paired key. planque checks the key, and checks `manifest_hash` against its allowlist of released builds; anything else
   is closed with `4403`.
4. **planque answers** `{type: "welcome", sig}`, its step key's signature over `{chrome_id, nonce_p, nonce_e}`. The
   extension checks it with the pinned key, or closes.

**Before the welcome, anything else closes the connection.**
- **Close codes:** `4401` (unknown Chrome), `4403` (build not allowed), `4410` (unpaired: the extension wipes itself).
  - planque's relay can't choose a close code. So planque sends `{type: "close", code, reason}` and then drops the
    connection, and the extension treats the message's `code` exactly as a close code, at any point, before the
    welcome too.
  - It needs no signature, as with `stop`: closing only takes power away. A forged `4410` could only wipe the
    extension, and only planque can post to the connection.
  - A real close code counts too, and a `4410` wipes once.
- **The relay's limits:**
  - A connection is dropped after 10 minutes idle and after 2 hours in all. One dropped during a run is opened again at
    once, then after 2, 4, 8, 16 and 30 seconds. Idle, the next alarm opens it.
  - A message carries at most 128 KB. A result over 120 KB is answered `page_too_large` instead, signed as any result.
- **Keepalive:** `{type: "ping"}` / `{type: "pong"}` every 20 s while a run is on. MV3 keeps the service worker alive
  while messages flow.
- **The allowlist of released builds** is planque's `deploy/chrome-builds.txt`, one released build per line
  (`<manifest sha256> <version>`). The release workflow opens the planque PR that adds a line, and two people merge it.
  Removing a line revokes that build at the next deploy.
- **While no run is on,** the extension wakes on an alarm every minute and connects, so planque can hand it a step. The
  connection drops after a minute with no run.
- **`manifest_hash`** is the SHA-256 of the installed `manifest.json`, as the Chrome Web Store serves it, written as **64
  lower-case hex characters** in the pairing and in the relay's `hello` alike (not base64url, which every other value in this
  protocol uses). The release workflow writes each released build's hash into planque's allowlist in the same form
  (`chrome-builds.txt`), and planque looks a build up by the text it is sent. `manifestHashOf` in `chrome/src/crypto.js` is
  the one place it is made.

## Steps

**planque sends** `{type: "step", step, sig}`, where `sig` is its step key's signature over `step`:

```
step = {id: "op_…", nonce, exp: <unix ms, at most 30 s ahead>, chrome_id, run, taf, grant,
        sites: ["shop.example", …], sites_signed: {sites, sig},        // sig: the phone's, tafs-chrome/sites/v1
        sensitive_allowed?: [{site, sig}],                            // sig: the phone's, tafs-chrome/sensitive/v1
        op: "open" | "read" | "act" | "close",
        url?,                          // open: a start URL or a link from the current read, already checked by planque
        offset?,                       // read: a later window of the page's text (a whole number, 0 or more; default 0)
        read_id?,                      // act: the read it was planned from
        act?: {kind: "click" | "fill" | "select" | "check", ref, value?, sealed?},
        release?}                      // only with act.sealed: the phone's release
```

**A clock that is a little off.** The extension checks `exp` against its computer's clock, which drifts (one that doesn't sync can be a minute or more out), and a step checked against the wrong now would be refused whole. So the check has slack: `SKEW_MS` (60 s) either way, and when planque's welcome carries its own time (`now`, unix ms, within 2 minutes of this clock), the clock is read by that offset and the slack is `SKEW_KNOWN_MS` (5 s) (`protocol.js`, `clockOffset`). The welcome's proof covers the connection's nonces, not the time, so the offset only ever shifts the reading a little. A step is still signed, bound to this Chrome, and takes once: the slack widens how long its expiry reads as valid, never what it may do.

**The extension runs nothing that isn't planque's, for this Chrome, unexpired and new** (`protocol.checkStep`). A nonce
is remembered for 10 minutes, in `chrome.storage.session`, so a step replayed after MV3 stops the service worker is still
refused.
- **A run's sites are the phone's, never planque's word alone.** When the owner approves a grant, the phone signs its
  sites (`tafs-chrome/sites/v1` over `{chrome_id, grant, sites}`). A run's first step carries that as `sites_signed`, and
  its `sites` must lie within the signed list (`protocol.checkSites`, with the paired `phone_key`). Later steps can never
  widen them. Chrome's per-site permission stays the second gate.
- **A grant's `sites` never name a public suffix** (`com`, `co.uk`, `github.io`, …: `sites.siteOK`). Such an entry
  reaches nothing.
- **A sensitive site opens only on the paired phone's own allowance** (`sensitive_allowed[].sig`, the phone's
  `tafs-chrome/sensitive/v1` over `{chrome_id, grant, site}`, checked with the paired `phone_key`, `protocol.checkAllowances`).
  planque relays it, and an entry without a valid one is dropped. The phones sign it when the owner approves a grant that
  names a sensitive site. It answers a refused step's `id` with `bad_step`, and gives an unsigned message no
signed answer.

**The extension answers** `{type: "result", id, nonce, chrome_id, ok, page, error, sig}`, signed with its key over
`{id, nonce, chrome_id, ok, page, error}`. The step's own `nonce` and this Chrome's `chrome_id` are inside what's signed, so
planque files a result only against the pending step it sent to this Chrome, and one can't be replayed for another step:

```
page = {read_id, url (no query or fragment), title, text (a window of ≤ 20 000 characters of the masked text),
        offset?, more?, next_offset?,        // see "A page's text comes in windows"
        fields: [{ref, label, kind, value?, options?, masked?, disabled?}],
        controls: [{ref, label, submits, places_order?}], links: [{ref, label, url}],
        payment_frame?, dialog?: {kind, text}, checkout?: {lines: [{item, quantity, price}], total, currency}}
error = {code, detail?}      // detail never carries page text
```

- **This is planque's `browser.Page`,** plus `read_id`.
  - `fields[].kind` is planque's set: `text, email, tel, number, search, select, checkbox, textarea, password, card, code,
    other`. Card and code come from `autocomplete` (`cc-*`, `one-time-code`) and the field's name.
  - `controls[].places_order` comes from the page's semantics, in no language: a payment provider's button, the submit of
    a form with a card field, or a submit inside an order's structured data. The words are planque's fallback.
  - `payment_frame` is a payment provider's frame on the page.
  - With these facts, planque refuses a payment step before it reaches the extension, whose own check stays as the second
    guard.
- **`checkout` is a checkout page as it shows itself,** read the way planque's own browser reads one, so the two agree on
  what the owner approves and what planque pays:
  - The total is one amount next to a "total" word (in the languages planque's own browser reads: English, German, French, Spanish, Italian, Dutch and Portuguese) in
    one currency (`EUR USD GBP CHF SEK NOK DKK PLN CZK HUF JPY CAD AUD BRL MXN`, or `€ $ £`). A row that names several
    amounts gives its last, and "grand total", "to pay" and their translations win over a plain "total". Two different
    candidates, or none, is no total, and then `checkout` is left out.
  - The lines are the rows (table rows, list items) with an amount in that currency that aren't a total, a subtotal,
    shipping, tax or a discount: the item's words, the quantity from "× 2", "x 2" or "qty 2" (else 1), and the price. A
    page that shows them otherwise gives a total with no lines.
  - Amounts are decimal strings, whichever marks the page uses (`1.234,50 €` and `$1,234.50` are `1234.50`). Words are masked
    like the page's text, and at most 50 lines go up.
  - **With a checkout, any click on the page is `payment_step`** (the guard's second look), whatever the control says.
- **`read_id` is the extension's own binding to the tab.** planque keeps its own fingerprint of the page it sent the taf
  (the URL, the title, and every ref with its kind and words, no values) and which `read_id` goes with it. Both are
  checked: planque refuses `page_changed` before sending, and the extension refuses it again in the tab.
- **`open.url` is never one the taf wrote:** the taf names a link ref or a start URL, and planque resolves and checks it.
  The tab keeps the full URL. Only the query-less URL goes up.
- **A page's text comes in windows.** A read answers the first 20,000 characters of the page's masked text. When the page
  goes on, `more` is `true` and `next_offset` is where the next window starts; a read with `offset: next_offset` answers
  that window, with `offset` set to where it starts. The text is masked before it is cut, so a secret is never split
  across a window. Fields, controls and links are the page's whole in every window, and a read at an offset is a read like
  any other: it has its own `read_id` and fresh refs, so an act names the latest read. An open and an act read the page
  back at the start. An `offset` that isn't a whole number, 0 or more, is `bad_step` and never reaches the page.
- **Refs restart with each read** (`f1`, `c1`, `l1`), and an act names the read it was planned from.
- **A link that acts is a control,** never a link to follow: one with a script handler (`onclick`), a button's role, or a
  URL that is this page plus a fragment. A link whose words pay is refused as a control would be, and on a checkout or
  payment page no link is followed at all: neither a URL nor its words can prove a link places no order. planque's
  guard does the same.
- **Every act answers with the page read back:** a click is never proof of its effect (content-script events aren't
  trusted input, and a site can tell).
- **`stop`:** planque may send `{type: "stop", run}` unsigned, since stopping only takes power away. The run's tabs close (and its window, when it has its own).

### What the extension refuses, whatever planque sent

| Code | When |
|---|---|
| `bad_step` | not planque's, not for this Chrome, expired, replayed, or malformed |
| `bad_url` | not `https`, user info in the URL, or not a URL |
| `address_private` | an IP address (in any form a browser reads), or a local name (`.local`, `.internal`, …) |
| `site_not_listed` | a host that isn't one of the run's sites or their subdomains, including a navigation the page starts |
| `site_blocked` | Chrome's own pages, the Web Store and Google's account pages, always. Also a sensitive site (a bank, wallet, password manager, payment, health or government account, by list and by name), unless the paired phone allowed it for this grant (`sensitive_allowed`, signed) |
| `needs_site_permission` | Chrome hasn't let the extension into that site yet: the side panel offers "Allow shop.example in Chrome" |
| `page_changed` | the act's read isn't the tab's current read: a navigation, a new document, or an act since |
| `ref_unknown` | the read has no such ref, or its element is gone, or it has become something else since the read: the element's tag, type, name, label, words and link target, and the words of the row it sits in, must be what the read saw |
| `not_visible` | the element is hidden, off screen, or under another element at its centre |
| `secret_field` | a fill of a password or one-time-code field with a plain value, or a sealed value into a field its part doesn't fit |
| `payment_step` | a fill of a card field, a click whose words or semantics pay or place an order, or any click on a checkout or payment page |
| `dialog_open` | the page raised a dialog since the read |
| `paused_by_owner`, `paused_failures`, `paused_no_progress`, `stopped` | the run is paused or stopped |
| `page_unresponsive`, `browser_unavailable` | the page didn't answer in 20 s, or Chrome failed |
| `page_too_large` | the answer is over 120 KB, the relay's limit with room to spare |

planque uses the same codes. It adds `chrome_offline` for a step that found no connection within 60 s, and then nothing
was done.

## Runs

- **A run opens in the window the owner is using,** as one background tab in a tab group, "tafs · Rae", in orange,
  beside their tabs. Their active tab stays in front and the run never takes focus. When the run ends, only the tabs the
  run opened close, wherever they are by then (a tab the owner dragged elsewhere included); the window and the owner's
  tabs stay. When the window last used is private or a pop-up, or there is no normal window (the run never opens in a
  private window), the run gets a window of its own, opened without focus, and that window closes with its tabs. A Stop
  that lands while the tab is still being opened closes it too.
- **The runner touches only tabs it opened.** It never lists the owner's tabs, and it ignores navigation events from
  any other tab. A tab a run's page opens joins the run only on the run's sites; anything else is closed.
- **A request off the run's sites never leaves.** As a run's tab opens, before it loads anything, the runner gives
  Chrome `declarativeNetRequest` session rules scoped to that tab: block every page and frame request, allow the run's
  sites (and the sensitive sites the phone allowed), and block plain `http`. So a link, a redirect or a form submit to
  another host is refused by Chrome before the request is sent. A tab the run's page opens gets the same rules. The
  rules go when the run ends. Their ids come from a counter of the runner's own (a rule id must fit an int32, and a tab id can be
  larger than that over four), and the first fence clears any rule an earlier service worker left. A window that can't be set up
  (the fence or the tab group refused) is closed and the step answers `browser_unavailable` with the reason. `webNavigation`'s record of a refused move stays as the log.
- **No new window escapes the fence before it's in place:** in a run's tab, `window.open` opens nothing (it's reported as a
  dialog), and a link or form that would open a new tab is followed in the same tab.
- **The owner's own keystroke, click or scroll in a run's tab pauses the run** (`isTrusted`). The side panel says
  "Paused: you're using this tab", with Resume.
- **Three failed acts in a row pause the run,** with what it read so far.
- **The same act on the same page that answers with the same page twice in a row pauses the run** (`paused_no_progress`), with
  what it read: a click that did nothing, done again, would do nothing a third time. A different act, a different page or
  a different answer starts the count again, and so does Resume. The read's own id and the sealed handles don't count as a
  change. A run paused this way is not swept, like one the owner paused: its tabs stay open until the owner acts.
- **A run nobody sends a step to for five minutes ends** as Stop does (its tabs close, and its window when it has its own), recorded as `stop` by `idle`: planque's
  `close` is only sent after a call, and takes power away, so a call that timed out or a lost `close` must not leave a window
  open. The service worker's minute alarm does the sweeping. A run the owner paused isn't idle, and neither is one with a step
  running.
- **One step at a time in a run.** A step waits for the one before it in the same run, so two acts never overlap and a
  read never lands in the middle of an act. Pause and Stop don't wait.
- **A page Chrome restores from its back-forward cache commits with no request,** so the network fence never sees it. A
  committed main-frame page off the run's sites is sent to a blank page and recorded, as a navigation is. The blank page
  itself (`about:blank`, where a run's tab opens and where a refused move goes) is never refused: refusing it sent the
  tab to `about:blank` again and again, which also cancelled the page the step was opening, so the step timed out. A step
  line whose address can't be read draws with no site, never an error.
- **A run's tabs get two scripts:**
  - In the page's own world, as each document starts: `dialogs.js`. Alert, confirm and prompt stop blocking and are
    reported, a confirm answers no, and a "leave this page?" handler is dropped.
  - In the isolated world: `mask.js` and `page.js`.
- **The toolbar badge shows "ON" while a run is on.** The side panel shows the taf, the site, the last steps, and Pause,
  Resume and Stop.

## Masking

What a read sends never carries:
- a password, card or one-time-code field's value;
- a hidden input;
- a card number in the text (cut to its last four digits, by the Luhn check);
- a secret by its shape: private keys, JWTs, Stripe, model-provider, GitHub, Slack, AWS, Google and GitLab keys;
- a labelled secret (`password: …`, `api_key=…`, `Bearer …`);
- a link's user info or credential parameters (`token`, `code`, `signature`, …);
- the page URL's query and fragment.

planque masks again whatever the extension sends.

## The approval card

A step that waits for the owner is a hold like any other, and the phone reads it from the same held-call view. When the
held call is a browser tool's, the view also carries a `browser` block, in planque's words, from the read the step was
built on, masked. The apps only draw it:

```
browser: {
  site: "shop.example",                     // the registrable domain, drawn large
  origin: "https://checkout.shop.example",  // drawn small, punycode as sent
  action_words: "Click “Place order”",      // the verb and the element's own visible label
  context: {title, text},                   // the masked page's title and an excerpt around the element (≤ 600)
  checkout: {lines: [{item, quantity, price}]},  // only on a checkout, as read: price as the page shows it
  chrome: {id, name}                        // which paired Chrome
}
```

- **The card's summary stays KIFF's.** `action_words` holds the element's own label, which the page wrote, so it's
  drawn under "On the page" with the page's title and text, never as what the card asks. The total is the hold's own
  amount, the taf's reason is its own `taf_reason`, and the rule and answers stay as they are for any held call.
- **The words approved are the words shown.** The hold's signed answer covers the block's JCS SHA-256, so the phone signs
  over what it drew. Until that's in the answer's signed message, the phone signs as for any hold.
- **Without the block,** a held browser step shows the hold's summary, amount and reason, as any hold does.
- **The page's words come from the site.** Each is drawn as one plain line, verbatim: no markup, no link, no control or
  direction character, cut to its cap. The app never acts on them.
- **A block that can't be read is left out, never the hold.** Without a `site` or `action_words`, the hold reads as
  any other held call. A part with the wrong shape loses that part only.
- **There's no screenshot.** It would need its own privacy words, so the card shows text only.
- **On iOS:** `KIFFMapping.browserStep` and `BrowserStepBlock` (`ios/Tafs/Views/NeedsYouView.swift`), tested in
  `ios/TafsTests/BrowserStepCardTests.swift`.

## What's kept

- **In IndexedDB:** the key pair, whose private key can't be exported, the pairing (`chrome_id`, its name, the phone's
  key, and the connections a re-pairing carried, `carried`, as the phone signed them), and the sealed sign-ins the owner saved, with their key. No page, step or value read is ever written to disk.
- **A code being typed** waits in `chrome.storage.session`, which Chrome keeps in memory only.
- **`profile_id`** is 16 random bytes in `chrome.storage.local`, made once: a re-pairing under a new key keeps it, so the
  phone can tell the same Chrome pairing again. Unpairing doesn't wipe it, and it is no secret. It isn't in
  `chrome.storage.sync`, which would copy it to every computer on the Google account; a reinstall makes a new one.
- **Unpairing wipes it all** and closes every run's tabs (and its window, when it has its own). It happens from the phone (planque closes with `4410`, or
  answers `4410` at the next connect), from the side panel (`POST /v1/extension/unpair`, signed with
  `tafs-chrome/unpair/v1`), or by uninstalling.

## Sealed values

A sign-in the owner saves in the side panel ("Saved sign-ins": site, an optional username or email, password), kept for
one exact origin (`chrome/src/vault.js`).

- **Sealed on the computer.** Each value is encrypted with AES-GCM under a key that can't be exported, kept in
  IndexedDB, with the origin, the entry and the part (username or password) bound in as additional data. So an entry
  moved to another origin can't be opened, and neither can an entry's username and password swapped in storage. The
  side panel lists only each site and whether a username is saved, never a value. One sign-in is kept per site: saving
  another for the same site replaces it, and the panel says so. Unpairing wipes the vault with everything else.
- **The taf sees handles only.** A read of a page on that exact origin carries `page.sealed:
  ["sec_…:username", "sec_…:password"]`. A taf names one in `act.sealed` to ask for a fill.
- **Only the paired phone's own release opens one, for one fill.** `step.release` is the phone's `tafs-chrome/release/v1`
  signature, with the `phone_key` the extension learned at pairing, over
  `{chrome_id, op: step.id, sealed, origin, field_ref, read_id, nonce, exp}`, with `exp` at most 2 minutes ahead. Refs are
  renumbered on every read, so `read_id` makes the release name the exact field the owner saw. planque relays it and can't make one, and a
  compromised planque can't either. The extension checks it (`protocol.checkRelease`), takes its nonce once, then
  opens the value.
- **It goes only where it fits:** a `:password` handle only into a password field, and a `:username` handle only into a
  text, email or username field. It goes on the tab's exact origin, in the top frame, on the read the step was planned
  from. The value goes from the vault into that field and nowhere else. The page is read back, the field masked as
  always.
- **A one-time code is never sealed.** The owner types it in the run's tab, which pauses the run until Resume.

| Code | When |
|---|---|
| `release_missing` | no release, one not signed by the paired phone, for another step, field or origin, expired, or already used |
| `sealed_unknown` | no sign-in is saved under that handle for this origin |

### The phone's release card (design, not built)

The extension already refuses a sealed fill without the phone's `tafs-chrome/release/v1` (`protocol.checkRelease`). This is the
card on the phone that makes one. It is a hold like any other (see "The approval card"), so a release is **never** made by a
rule, a limit, a watch or the extension. **Its lock is the approval's own:** Allow signs with the approval key behind a
fresh Face ID or fingerprint every time, never from a notification's action or a reused unlock, and never inside an
approval window (whose tap signs with the device key, which can't make this signature). Allow carries the confirm mark, and
its `chrome.release_*` words go on Android's confirm-mark list, as `tafs-chrome/sites/v1`'s do.

**When it comes.** A taf's `act.sealed` step makes planque hold the step. A sealed fill is always held: no standing rule
or daily limit approves it, since it moves a stored sign-in into a page. planque's hold carries the `browser` block (site,
origin, the element's label, which Chrome) and the release's own fields below.

**What the owner sees,** in this order:

1. "Fill a saved sign-in?", the taf's name and its own reason (KIFF's `taf_reason`, as on any hold).
2. **The site large** (its registrable domain) and **the full origin small** (an internationalised domain stays in its
   `xn--` form), and which Chrome ("In Work Mac").
3. **Which part: "Password" or "Username or email."** The phone accepts a handle only if it matches
   `^sec_[A-Za-z0-9_-]+:(password|username)$`, exactly two segments, and reads the part from it, never from planque's words.
   Anything else (a third segment, another part, an empty id) is no release: the card is drawn as an unreadable hold (the
   hold's summary only) and nothing is signed. The extension reads the part as the segment after the first colon (`runner.js`
   `fillSealed`, `vault.js`), so a three-segment handle would show one part and fill the other; it refuses such a handle too
   (a follow-up PR of mine, with its test), and the vector's refused cases include one.
4. Under "On the page", the field's label and the page's title as planque read them, as every page word is drawn: one plain
   line, verbatim, never as what the card asks.
5. One fixed line: "It never leaves your Chrome. tafs doesn't see it." (the phone never has the value, and says so rather than
   showing a stand-in; the line is the same for either part), and how long the card is good for.
6. **Allow this fill** and **Refuse.** Allow asks for Face ID or the fingerprint.

**What is signed.** One touch, two signatures (as the pairing's): the hold's answer, and the release. **Precondition:** the
hold's answer covers the `browser` block's JCS SHA-256, so the phone signs over what it drew; that is the approval card's
own "until that's in the answer" step and isn't built, so until it is, the card can't be shipped as an approval of what it
drew, and the release is the only signature over the handle, origin and field. The release is the phone's approval key over
`tafs-chrome/release/v1` and

```
{chrome_id, op, sealed, origin, field_ref, read_id, nonce, exp}
```

- `chrome_id`, `op` (the step's id), `sealed` (the handle), `origin`, `field_ref` and `read_id` are the held step's own,
  copied byte for byte. `chrome_id` is the paired Chrome the card names, never another.
- `nonce` is the phone's own: at least 16 random bytes, unpadded base64url, made when the owner allows and never reused.
  `exp` is 60 s after that moment, in unix ms. The extension compares it with its own clock as it is (`checkRelease` takes no
  clock offset) and takes at most 2 minutes ahead, so a phone clock ahead by more than about 60 s, or behind by more than
  60 s, has every release refused: the owner sees "Not filled. Check that this phone's clock is right", from planque's
  `release_missing` answer.
- The answer carries `release: {chrome_id, op, origin, nonce, exp, sig}`, and planque puts it on the step as `step.release`.

**Where the phone refuses to sign,** whatever planque says (it shows the hold as unreadable, and says why):
- the origin isn't `https`, or its registrable domain isn't within the sites of the step's `sites_signed`, which the phone
  verifies against its own key (`tafs-chrome/sites/v1`): the phone never signs a release for a site its owner didn't connect;
- the origin is a sensitive site and no `sensitive_allowed` entry of the step verifies against its own key
  (`tafs-chrome/sensitive/v1`) for this grant and site;
- the Chrome isn't one it is paired with, or the hold has no `read_id`, `field_ref` or a handle of the shape above;
- the hold has expired (below): the owner's late yes signs nothing, and planque asks again with a new step.

**Refuse, ignore or late.** Refuse signs no release and answers the hold's refusal, as any hold: it takes power away, so it needs
no lock. The taf is told the owner said no, and nothing is filled.

**How long a card lives.** The extension ends a run that gets no step for 5 minutes (`RUN_IDLE_MS`, swept as Stop by `idle`),
and a held fill sends none while it waits. So a sealed fill's hold lives **3 minutes**, not longer, and the card shows the
time left; an unanswered card ends at that expiry and the taf is told nothing was filled. An Allow after the expiry signs
nothing. If the run ended or the page changed (`page_changed`) before the release arrived, planque tells the owner "Not
filled", never "filled", and the owner's yes buys nothing further. (Keeping a run with a held fill from counting as idle, as a
paused one doesn't, would let a card live longer: a later change, in the extension and planque both.)

**One card per fill.** A sign-in is two fills (username, then password), so two cards. Folding the two into one card, one touch, two
releases, is a later change, once one card is built and used.

**What the card can't promise,** and says nothing about:
- The phone never reads the page or the saved entry. The field's label and the page's words are planque's relay of what
  the extension read; they are not signed. What the signature does hold is the handle, the exact origin, the field's ref on
  the read the owner's taf planned from, and the 60 s.
- The extension enforces the rest: a password handle goes only into a password field, a username handle only into a text,
  email or username field; the tab's exact origin, the top frame, and the read the step was planned from; the nonce is taken once.
  A compromised planque could therefore choose which fitting field on that origin gets the value (a username into another
  text field, say). It can never send a value anywhere else, make a release, or fill a field of the wrong kind.

**Open for planque** (its docs, not this one): the held call's kind for a sealed fill, how the answer's `release` reaches
the step, and the vector for `tafs-chrome/release/v1` (`vectors/chrome-release.json`, made from `checkRelease` and checked by
the extension's and each app's tests) that both apps need before they sign.

**For the apps,** the card is built once on iPhone and iPad (`BrowserStepBlock`'s sibling in `NeedsYouView`) and once on
Android (`NeedsYouView`), each drawn from `chrome.release_*` words in every language and each tested against the vector: a
release made from it is accepted by `checkRelease`, and one with any field changed, another Chrome's, an expired one, or
a reused nonce is refused.

## Coach in the side panel

The side panel talks to Coach: the same chat the apps use, through tafs cloud's own routes (`GET /v1/setup`,
`GET /v1/tafs/{coach}/conversation`, `POST /v1/tafs/{coach}/messages`), as the extension's view-only KIFF device
(`chrome/src/coach.js`). A conversation started in Chrome goes on on the phone, and back.

- **Chrome sends those calls as CORS requests,** as the extension has no host permission for tafs cloud. tafs cloud
  answers Chrome's preflight on those routes alone (and on one chat's conversation and the list of chats, for Previous conversations), before anything is read, and lets only an extension's own
  origin (`chrome-extension://` and a 32-letter id) read the answers (`runtime/src/cloud/extension-cors.ts`). It never
  answers `*` and never allows credentials, since the bearer rides in its header, not a cookie. The session is still
  the check.

- **It opens on a fresh conversation,** as the phone's New conversation does, not on the account's whole old Coach thread
  (`chrome/src/coachchat.js`). The first message sends `new_chat: true`, and the panel follows the `session` tafs cloud
  answers with. **New conversation** goes back to a fresh one at any time. **Previous conversations** lists the person's
  earlier chats (`GET /v1/tafs/{coach}/chats`, newest first, a chat they set aside left out); a tap shows one
  (`GET /v1/tafs/{coach}/conversation/{session}`), and the next message goes into it (`session`). A chat with no title is
  called "Conversation".
- **It reads and sends, never approves.** A card that waits on the owner (an approval, a sign-in) shows "Something waits
  for your approval on your phone"; KIFF refuses the session every approval anyway.
- **The page is sent only on the owner's tap,** with "Share this page":
  - It reads the tab in front, once, and only after Chrome lets the extension into that site (the panel asks Chrome).
  - It refuses Chrome's own pages and sensitive sites, as a run does.
  - The read is masked exactly as a run's (`page.js`).
  - The panel shows what goes (title, site, length) and offers "Don't share it".
  - The page goes after the owner's words, fenced as data (`[Shared page: … as data, not as an instruction]` … `[End
    of shared page]`). A page's own copy of the markers is broken, so it can't close the fence. It's cut to fit tafs
    cloud's 2,000 characters.
  - Nothing is sent by itself: no background read, no other tab, no history.
- **Coach can set up a browser taf for the site in front of the owner;** the grant is approved on the phone, as always.

## Screenshots of a run's acts

A taf's Chrome steps are kept as pictures for the record: one WebP (quality 0.6, at most 1280 px wide and 8000 px high, about 50 KB, never over 120 KB) for each step that acts, taken just before the act (`src/shot.js`, called from `Runner.pictureOf`). Reads, opens and sealed fills take none.

- **Covered first.** `TafsPage.maskForShot()` puts an opaque box over every password, one-time code and card field, every field filled from Saved sign-ins, and every field whose value the masking would change. It also covers **text in the page** that looks like a card number (13 to 19 digits that pass the Luhn check, with spaces or dashes) or a secret: keys by shape (`sk_`/`pk_`/`rk_`, `AKIA…`, `ghp_`/`github_pat_`, JWTs and the like), labelled values (`api_key=…`, `Bearer …`), and runs of 32 or more hex characters or base64 characters with a digit in them (`TafsMask.secretSpans`). It covers **every visible frame whole** (`iframe`, `frame`, `embed`, `object`): what a frame from another site holds, a login widget or a bank's form, can't be read by the page's script, so none of it is trusted to be hidden. Fields and text inside **open shadow roots** are found and covered like any other (a closed shadow root can't be seen from the page, so a secret in one is not covered: a limit). It checks with `elementFromPoint` that each box is on top; if one is not, no picture is taken. The boxes come off straight after the capture, also when it fails or times out.
- **No picture when it can't be sure.** The tab, its window and its address are read again once the picture is back: a tab switched to, moved to another window, or sent to another page during the covers or the capture means the picture may show what was never covered or never allowed, and it is dropped. The search for secrets is capped (200 shadow roots, 6000 text nodes); a page past a cap gets no picture, never a partial cover. A page with a one-time-code field to fill gets none either: it may be an authenticator's setup, whose QR code and seed are the second factor and look like nothing the covers know.
- **Never taken** on a sensitive site, on a checkout or a payment frame, over plain http, or when the run's tab isn't in front of its window (a run in the owner's window is a background tab, so it has no pictures there while it stays in the background; one in a window of its own is in front, so it does): the browser takes the picture of a window, not of a tab, so a tab the person dragged into another window is not pictured (it would be the old window's front tab, which was never covered). A picture that can't be taken never holds the act.
- **Sent over HTTPS, not the relay** (the relay's message limits are too small for a picture): `POST /v1/extension/steps/<step>/screenshot` with the JSON `{chrome_id, image, sig}`, `image` being the WebP bytes in unpadded base64url. `sig` is the extension's key over `tafs-chrome/screenshot/v1` and `{chrome_id, step, sha256}` (`sha256` the lower-case hex of the image bytes), so planque can tell the picture is this extension's, for this step, unaltered.
- **Off until planque keeps them.** The build ships with `screenshots: false` (`src/config.js`); it is switched on in a release only once planque stores the pictures behind its own flag and the privacy wording is live.

Limits: a step held for the owner and then refused is not pictured; a secret written in a way these rules don't recognise (a card number split across elements, a code in an image, a key in a short form, anything in a closed shadow root) may still show.

## Tests

- `cd chrome && node --test test/*.test.js` runs every test. CI's `chrome.yml` runs them on every change to `chrome/`
  or this page, with Chromium for the page tests.
- **`core.test.js`:** canonical JSON, signatures and their labels, DER signatures, the site rules, masking, and the
  guard (mirroring planque's).
- **`protocol.test.js`:** a step's signature, Chrome, expiry and nonce; answers, hello and welcome; a phone release that
  planque can't forge; the whole pairing, including a substituted key, a renamed pairing, another secret and another
  extension; the relay taking nothing before both proofs; the extension speaking first; planque's `close` message and
  `4410` wiping the extension once; `page_too_large`; and a run's dropped connection opened again.
- **`runner.test.js`:** against a fake Chrome API. A run's tab opens in the background of the owner's window and only the run's own tab ids are touched; a window of its own is never focused. The owner's tabs are never listed.
  Off-site opens, navigations, pop-ups and restored pages are stopped. The guard runs before the page is touched. There
  are the pauses (failures, no progress, the owner), Stop, runs closed when idle, steps that never overlap, windows of a page's text, the site
  permission, a checkout page that refuses every click, and a dialog's answer.
- **`vectors.test.js`** and `ios/TafsTests/ChromePairingTests.swift`: both check the pairing's proofs and signed bytes
  against `vectors/chrome-pairing.json`, so the extension and the phones can't drift. The phone's side of pairing is
  `ios/Tafs/KIFF/ChromePairing.swift`.
- **`coach.test.js`** and **`kiff.test.js`:** the shared page fenced, cut and unclosable; Coach read and written through
  tafs cloud's routes with the session's own bearer; the KIFF offer as a view-only extension; the claim's DER proof
  verified; and a claim that waits, and a session that could approve, ended and never kept.
- **`sealed.test.js`:** the vault (sealed per exact origin, never listed or kept in clear, an entry moved to another
  origin or its parts swapped unreadable), handles on a read, and a sealed fill that runs only on the paired phone's
  release. It refuses no release, planque's, another origin's, another read's, an expired one, and a reused one, and
  checks which part fits which field.
- **`page.test.js`:** the content script in a real Chromium page. A read's refs and masking (no password, code, hidden
  input, card number or key), a long page's windows, a checkout's lines, total and currency (in European and English number marks, masked, none when the total is unclear), page text that imitates a fence or an instruction (it goes up as
  written), an act that refuses an element that has become something else (its words, its target, its row), fill, select and check, a fill that never submits, a stale read after a navigation, a covered button that isn't
  clicked, and dialogs.

## Known limits

- **A window a page's script opens** (from a blank frame, say) is fenced only *after* its first request: Chrome gives the
  extension its tab only once it navigates, so that first request goes out before the fence holds it. A run's tabs never
  open one (`window.open` is off in its tabs, and `target` is forced to `_self`).

## Not done yet

- **The sealed-value release on the phone's card** (`tafs-chrome/release/v1`): the extension already checks it; the card that signs it is designed ("The phone's release card") and still to build.
- **The release workflow** (two approvals) that publishes to the Chrome Web Store.
