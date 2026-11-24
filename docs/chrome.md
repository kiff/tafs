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
| `chrome/manifest.json` | MV3. Permissions at install: `storage`, `alarms`, `scripting`, `sidePanel`, `tabs`, `tabGroups`, `webNavigation`, `declarativeNetRequest` (to fence a run's tabs; it widens no access), `notifications` (Chrome asks it of an extension that receives Web Push; the extension shows none, see "Waking the extension"). **Every https site at install** (`host_permissions: ["https://*/*"]`): one Chrome prompt when the extension is installed, never one per site (the owner's choice, 23 Nov; `chrome/store/test_manifest.py` pins it). No `debugger`, `downloads`, `cookies`, `history`, `webRequest` or `nativeMessaging`. |
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
| `chrome/sidepanel/` | Pairing, and while a taf works: what it's doing, Pause, Resume and Stop. |
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
     they allow it again, and the replace's approval never promises it), and the pictures kept of the old Chrome's acts (they go with it).
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
  - A message carries at most 128 KB. A result over 120 KB has its page cut until it fits (`relay.js` `cutPages`), and the
    page says what was cut (`truncated`: `text`, `options`, `links`). The text goes first, to 4,000 characters, with `more`
    and `next_offset` as a window has, since planque's fingerprint of a read doesn't count text; then a select's options past
    10, links past 50, the rest of the text, and every link. The fingerprint does count options and links, so a page cut
    there is another read than the same page uncut: steady while the page stays over the limit, but a page near it can go
    from one to the other and give `page_changed`, and the taf reads again. Fields and controls are never cut: an act names
    them. **An act
    that ran is never answered as failed for its size** (a taf told its click failed may click Submit again). Only a page
    whose fields and controls alone are over the limit is answered `page_too_large`, signed as any result.
- **Keepalive:** `{type: "ping"}` / `{type: "pong"}` every 20 s while a run is on. MV3 keeps the service worker alive
  while messages flow. **Until planque can wake the worker by push** (no `pushKey` in the build, or planque hasn't said `push_kept`), an idle connection pings too, every 20 s, because traffic under 30 s is what keeps an MV3 worker, and so the socket, alive (an idle one is otherwise down about half of each minute). **Once planque has the subscription (it answers `{type: "push_kept"}`; a `push_refused` keeps the ping going), an idle one doesn't ping** (a ping each 20 s for every idle Chrome would cost a message each, about $2 to 3 thousand a month at 10,000 installs): the push wakes it.
- **The allowlist of released builds** is planque's `deploy/chrome-builds.txt`, one released build per line
  (`<manifest sha256> <version>`). The release workflow opens the planque PR that adds a line, and two people merge it.
  Removing a line revokes that build at the next deploy.
- **While no run is on,** the extension wakes on an alarm every minute and connects, so planque can hand it a step, and it
  **never closes the connection for being idle** (it did, after a minute, and a step that came while it was closed was refused at once: the
  owner's runs reached their Chrome about half the time). Chrome still stops an idle service worker after about 30 s, which
  drops the socket unless there is traffic (the ping above), so the alarm with the ping is the stopgap and the push below is the cure.
- **Waking the extension** (`chrome/src/push.js`): a service worker Chrome has stopped can't be reached, so the extension gives planque a
  **Web Push subscription**, and planque sends **one silent push** (no data) when a taf needs this Chrome and it isn't connected; the worker
  wakes on the `push` event and connects. After each welcome the extension sends `{type: "push", subscription: {endpoint, keys: {p256dh, auth}},
  sig}`, the sig being this Chrome's key over `tafs-chrome/push/v1` and the subscription as sent (an https endpoint and the two keys,
  nothing else; planque checks it over that JSON). planque's **VAPID public key is pinned in the build** (`config.js` `pushKey`, public, one per environment, like the step key), so
  nothing at connect can swap it; a build with none subscribes to nothing and goes on connecting by its alarm. The subscription is silent
  (`userVisibleOnly: false`, from Chrome 121), which is why the manifest asks `notifications`: Chrome asks it of an extension that receives push, and warns at install.
  **The extension never shows a notification**, and the push carries no data. On Chrome before 121 the subscribe is refused and the extension
  falls back to its alarm. Unpairing drops the subscription. At 10,000 users it costs nothing idle: one push per run start.
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
        op: "open" | "read" | "act" | "hand" | "close",
        url?,                          // open: a start URL or a link from the current read, already checked by planque
        offset?,                       // read: a later window of the page's text (a whole number, 0 or more; default 0)
        read_id?,                      // act, hand: the read it was planned from
        hand?: {words, ref?},          // hand: what the person is asked to do in the page (a step handed to the person, below)
        act?: {kind: "click" | "fill" | "select" | "check" | "press" | "scroll" | "back", ref?, value?, keys?, sealed?},
        release?}                      // only with act.sealed: the phone's release
```

**A clock that is a little off.** The extension checks `exp` against its computer's clock, which drifts (one that doesn't sync can be a minute or more out), and a step checked against the wrong now would be refused whole. So the check has slack: `SKEW_MS` (60 s) either way, and when planque's welcome carries its own time (`now`, unix ms, within 2 minutes of this clock), the clock is read by that offset and the slack is `SKEW_KNOWN_MS` (5 s) (`protocol.js`, `clockOffset`). The welcome's proof covers the connection's nonces, not the time, so the offset only ever shifts the reading a little. A step is still signed, bound to this Chrome, and takes once: the slack widens how long its expiry reads as valid, never what it may do.

**The extension runs nothing that isn't planque's, for this Chrome, unexpired and new** (`protocol.checkStep`). A nonce
is remembered for 10 minutes, in `chrome.storage.session`, so a step replayed after MV3 stops the service worker is still
refused.
- **A run's sites are the phone's, never planque's word alone.** When the owner approves a grant, the phone signs its
  sites (`tafs-chrome/sites/v1` over `{chrome_id, grant, sites}`). A run's first step carries that as `sites_signed`, and
  its `sites` must lie within the signed list (`protocol.checkSites`, with the paired `phone_key`). Later steps can never
  widen them. **Chrome's permission is not a gate here:** the extension holds every https site at install, so the phone's signed list, the per-tab network fence and the extension's own checks below are what keep a taf off a site its connection doesn't list.
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
- **A page's text comes in windows.** A read answers the first 20,000 characters of the page's masked text, and at most
  20,000 bytes of it in UTF-8 (20,000 characters of Japanese are three times that). When the page
  goes on, `more` is `true` and `next_offset` is where the next window starts; a read with `offset: next_offset` answers
  that window, with `offset` set to where it starts. The text is masked before it is cut, so a secret is never split
  across a window. Fields, controls and links are the page's whole in every window, and a read at an offset is a read like
  any other: it has its own `read_id`, and an act names the latest read. An open and an act read the page
  back at the start. An `offset` that isn't a whole number, 0 or more, is `bad_step` and never reaches the page.
- **A ref names one element for the life of the document** (`f`, `c`, `l` and a number; `page.js` `bind`). A read never
  renumbers what an earlier read named: an element keeps its ref while it's on the page and still what it was (its print:
  tag, type, name, label, words, target and row). One that goes leaves its number unused, one that changes gets a new number,
  and a node a page draws again in the same place, exactly as it was (a framework re-rendering a list), keeps the old node's
  ref. A new document starts again from 1. An act still names the read it was planned from.
- **The acts** (the guard, `guard.js`, then `page.js` `act`):
  - `click`, `fill`, `select` and `check` of a ref, as before. A `fill` with `keys: true` types one character at a time with
    key events, for a field that opens its list as you type (a city, an airport); a fill never submits.
  - `press` of one key on a ref: `Enter`, `Escape`, `ArrowDown` or `ArrowUp`, and nothing else (no Tab, no shortcuts). The
    keys reach the page as key events; `Enter` on a field in a form then submits the form as the browser would
    (`requestSubmit`), unless the page cancelled the key. `Enter` on a checkout or payment page, in a form that takes a card
    or whose button places an order, and any key on a password, card or code field, are refused, as a click there is.
  - `scroll` of a ref (into view) or of the page (`value` `down`, the default, or `up`, by most of a screen).
  - `back`, with no ref: the tab goes back, and the page there is read and fenced as any page is; nothing is clicked.
  - planque sends these only once its `browse_act` takes them; until then the extension simply has them.
- **A link that acts is a control,** never a link to follow: one with a script handler (`onclick`), a button's role, or a
  URL that is this page plus a fragment. A link whose words pay is refused as a control would be, and on a checkout or
  payment page no link is followed at all: neither a URL nor its words can prove a link places no order. planque's
  guard does the same.
- **Every act answers with the page read back:** a click is never proof of its effect (content-script events aren't
  trusted input, and a site can tell).
  The read comes **after the navigation the act started**: the tab is looked at every 100 ms for up to 1 s, until it is loading or at another
  address, then until it is `complete`, then 600 ms more. A click that navigates nothing costs that second and no more. (Without the look, `complete`
  was still the old page's status and the taf read the page it had just left.)
- **`stop`:** planque may send `{type: "stop", run}` unsigned, since stopping only takes power away. The run ends and **its tabs stay open** (see the next section); that taf's next run, or unpairing, closes them.

### What the extension refuses, whatever planque sent

| Code | When |
|---|---|
| `bad_step` | not planque's, not for this Chrome, expired, replayed, or malformed |
| `bad_url` | not `https`, user info in the URL, or not a URL |
| `address_private` | an IP address (in any form a browser reads), or a local name (`.local`, `.internal`, …) |
| `site_not_listed` | a host that isn't one of the run's sites or their subdomains, including a navigation the page starts |
| `site_blocked` | Chrome's own pages, the Web Store and Google's account pages, always. Also a sensitive site (a bank, wallet, password manager, payment, health or government account, by list and by name), unless the paired phone allowed it for this grant (`sensitive_allowed`, signed) |
| `page_changed` | the act's read isn't the tab's current read: a navigation, a new document, or an act since |
| `ref_unknown` | the read has no such ref, or its element is gone, or it has become something else since the read: the element's tag, type, name, label, words and link target, and the words of the row it sits in, must be what the read saw |
| `not_visible` | the element is hidden, off screen, or under another element at its centre |
| `secret_field` | a fill of a password or one-time-code field with a plain value, or a sealed value into a field its part doesn't fit |
| `payment_step` | a fill of a card field, a click whose words or semantics pay or place an order, or any click on a checkout or payment page |
| `dialog_open` | the page raised a dialog since the read |
| `paused_by_owner`, `paused_failures`, `paused_no_progress`, `stopped` | the run is paused or stopped |
| `page_unresponsive`, `browser_unavailable` | the page didn't answer in 20 s, or Chrome failed |
| `page_too_large` | the answer is over 120 KB even with its page cut (its fields and controls alone are over), the relay's limit with room to spare |

planque uses the same codes. It adds `chrome_offline` for a step that found no connection within 60 s, and then nothing
was done.

## Runs

- **A run opens in the window the owner is using,** as one background tab in a tab group, "tafs · Rae", in orange,
  beside their tabs. Their active tab stays in front and the run never takes focus. **When the run ends, its tabs stay open** (the
  taf's close step, planque's `stop`, the person's Stop, the idle sweep): the person sees the page where the taf stopped and
  nothing opens and closes under them. **Each taf keeps only its latest finished run's tabs:** when that taf's next run opens, it closes the
  previous "· done" tabs (and a window of its own) first, so a taf's calls never pile up. **Only a tab still where the run left it, and not in front of the window the person is using, closes then;** one they moved on or are using stays, is dropped from the list, leaves the run's "· done" group if it's still in it, never a group of the person's own (Chrome ends a group once it's empty, so a person who watches each run doesn't collect one done group per run), and keeps a window of its own open with it. Unpairing closes them all. Which tabs are kept is held in `chrome.storage.session`
  (`doneTabs`), so a stopped worker still finds them; a taf is told apart by the name its steps carry. The group is renamed "tafs · Rae · done" and turns grey **15 s after the run ends** (`DONE_GRACE_MS`), unless the taf's next run starts first: a taf works in a few calls, each its own run, so its group stays amber while it works and waits instead of blinking grey between calls (a worker that stopped in those 15 s greys it when it starts again). Its fence goes, and the tabs are no longer the
  run's: a touch in one pauses nothing and a step can't reach it. **Unpairing closes them all** (the tabs the run opened, wherever they are
  by then, and a window of its own); so does a run that never loaded a page (a Stop while the tab is still being opened, or a window that
  couldn't be set up), since a blank tab is nothing to keep. When the window last used is private or a pop-up, or there is no normal window (the run
  never opens in a private window), the run gets a window of its own, opened without focus, and it stays with its tabs.
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
  change. A run paused this way waits for the owner like one the owner paused (below): its tabs stay open.
- **A run nobody sends a step to for 150 s ends** as Stop does (its tabs stay, as at any end), recorded as `stop` by `idle`.
  planque keeps a taf's run for the turn's next call only within 2 minutes of the last one, and sends no `close` when a turn
  ends, so this is what ends a turn's run: soon after, the bar, the badge and the group stop saying the taf works. A call that
  timed out or a lost `close` doesn't leave a run fenced and counting as active either. The service worker's minute alarm does
  the sweeping. A run with a step running or a step handed to the person isn't idle.
- **A paused run gets 15 minutes** (`PAUSED_IDLE_MS`), counted from its last step, its pause or its Resume, whichever is
  last: a taf holds its step for the owner's Resume for up to 10 minutes, so a held run is never ended under it (a step sent
  meanwhile is answered `paused_by_owner` and keeps the run, like any step). One no step follows (the taf's turn ended on the pause, or
  it paused on failures or no progress) ends then as Stop does, recorded as `stop` by `idle`, so the bar and the group stop
  saying it works or is paused when nothing will resume it. Resume starts the 150 s count again.
- **The person closing a run's last tab is their Stop:** the run ends then, recorded as `stop` by `person` (a hand-over open is
  answered not now), and planque's next step on it is told `stopped`. Closing one of its tabs while another is left changes nothing.
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
- **Every https site is granted at install, so nothing in a run asks Chrome for one.** The owner chose it (23 Nov: "closer to Claude in
  Chrome"), and Chrome asks once, when the extension is installed or updated to a manifest that widens it (an update that does is
  disabled until accepted, so `host_permissions` is only changed on purpose). **Chrome's permission is therefore no second gate.** What stops a taf
  reaching a site its connection doesn't list is, in this order: the phone's signed list (`sites_signed`, checked on a run's first step
  and held for the run), the network fence of each run tab (rules set before the tab loads anything, blocking every other host), `refuseURL` on every
  open, read, commit and navigation, the sensitive-site rule (a bank, wallet, payment, health or government site never opens without the
  phone's own allowance for that grant), and `window.open` off in a run's tabs. A run on a site its connection lists just runs.
- **The toolbar says a taf is at work** (`coachchat.js` `actionBadge`, `actionTitle`): a mark (●) on the badge while a run is on, "!" while one waits on the person, nothing otherwise, the same in every language; its tooltip names who works, in the extension's words ("Rae is working"), and is the extension's name when none does. The side panel shows the taf, the site, the last steps, and Pause,
  Resume and Stop.

## While a taf works on a page

- **Its group says so.** The run's tab group is titled "tafs · Rae is working" (`working`, in every language) and is orange for
  as long as the run lives; its end turns it grey, "tafs · Rae is done" (`group_done`, `Runner.doneTitle`; `Runner.workingTitle` while it works).
- **Where it acts, a moment first.** Before an act on a ref (a click, a fill, a select, a tick, a key, a scroll to it), the
  element is outlined in amber for half a second (`OUTLINE_MS`, Show me's outline), after the act's picture so the outline
  isn't in it; the act takes it away. Back and a scroll of the page name no ref and outline nothing. The outline lets every
  pointer through, so it never stands in the act's way.

- **A floating bar, on the page in front of every window** (`src/bar.js`, `Runner.syncBars`). While a run lives, the tab in
  front of each normal window shows a small bar of the extension's own, floating at the foot of the page: who is working
  ("Rae is working", `working`; "Rae is paused", `bar_paused`), **Go to its tab** (`bar_go`, on any tab but the run's own),
  **Pause** or **Resume**, and **Stop**. On a run's own tab it's that run's; elsewhere, the first live run's. It follows the
  tab in front (a tab or window change, a page that loads again) and goes from every tab when the last run ends. Only https
  tabs, which the extension may reach; Chrome's own pages are left alone. Nothing is read from those tabs: the bar is drawn
  in them, and that's all. No new permission: `scripting` on `https://*/*` was already granted.
- **Its buttons are the panel's.** Go to its tab brings the run's tab to the front in its window (`Runner.goTo`). Pause holds
  the run (the next step is answered `paused_by_owner`), and Resume lets it go on. **Stop ends the run's browser work at
  once**, as the panel's Stop does (recorded `stop` by `person`): a step handed to the person is answered Not now, the run's
  tabs stay, and planque's next step for it is answered `stopped`, which tells the taf the person stopped it.
- **Pause, Resume and Stop reach the taf** (`chrome/src/runstate.js`): each change of a run's state, whoever made it (the bar,
  the panel, the person's touch, the run's own failures or no progress, the idle sweep), is sent on the relay as
  `{type: "run_state", run, state: "paused"|"working"|"stopped", at, sig}`, signed by this Chrome's key over `{run, state, at}`
  under `tafs-chrome/run_state/v1`. planque keeps the newest (planque#406), so a taf whose step was answered
  `paused_by_owner` waits for `working` and sends the step again, instead of ending its turn (kiff/agents). Each run's newest
  state is kept until planque answers `run_state_kept`, or `run_state_refused` with a code; `not_kept` waits for the next
  welcome, as a hand-over's answer does. Answers are matched to what was sent in order, so an answer to a Pause never drops
  the Resume sent after it. **`stopped` is final:** a run stopped or swept never says `working` again.
- **The page can't use it.** The bar lives in a closed shadow root: the page can't read or restyle its words or reach its
  buttons. Only a real press (`isTrusted`) counts; what it sends names its run, the service worker takes it only from the
  extension's own content script in a tab, and the runner acts only on a run it holds and that still lives
  (`Runner.barPress`). A press on it is not the person's input that pauses a run. A read never includes it, and an act's "is
  it on top" check looks through it (by its element, `tafs-working-bar`), so it never blocks the taf's click on what's under
  it. A page that removes the bar's element gets it back while the run works. It can still hide it, as it can any page
  content: the panel, the toolbar's badge and the tab's group still show the run.

## Masking

What a read sends never carries:
- a password, card or one-time-code field's value;
- a hidden input;
- a card number in the text (cut to its last four digits, by the Luhn check);
- a secret by its shape: private keys, JWTs, Stripe, model-provider, GitHub, Slack, AWS, Google and GitLab keys;
- a labelled secret (`password: …`, `api_key=…`, `Bearer …`);
- a one-time code written in the text, named as one in English, French, Spanish or German ("Your verification code is
  482913", "123456 is your login code", "votre code est 4821"): its 4 to 8 digits go, its words stay. A postal, order or
  promo code stays. tafs cloud masks the same in a page Coach reads, and `runtime/test/fixtures/one-time-codes.json` holds
  both to the same cases;
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
  key, and the connections a re-pairing carried, `carried`, as the phone signed them). No page, step or value read is ever written to disk.
- **No sign-in, ever.** The extension has no saved sign-ins and no password field. At a sign-in page, the taf hands the step
  to the person ("A step handed to the person"): they sign in themselves, in their own tab, and press Done. Versions up to
  0.2.3 could save sealed sign-ins; from 0.2.4, every start of the service worker deletes what they kept (`vault` and
  `vault_key`, `store.js` `forgetSavedSignIns`), before it reads anything else.
- **A code being typed** waits in `chrome.storage.session`, which Chrome keeps in memory only.
- **`profile_id`** is 16 random bytes in `chrome.storage.local`, made once: a re-pairing under a new key keeps it, so the
  phone can tell the same Chrome pairing again. Unpairing doesn't wipe it, and it is no secret. It isn't in
  `chrome.storage.sync`, which would copy it to every computer on the Google account; a reinstall makes a new one.
- **Unpairing wipes it all** and closes every run's tabs (and its window, when it has its own). It happens from the phone (planque closes with `4410`, or
  answers `4410` at the next connect), from the side panel (`POST /v1/extension/unpair`, signed with
  `tafs-chrome/unpair/v1`), or by uninstalling.

## Sealed values (none in the extension)

The extension keeps no sign-in and types none (see "What's kept"). A step naming a sealed value (`act.sealed`) is refused
as `secret_field` before the page is touched, and the page script has no way to type one. If a secret is ever kept for a
taf, it lives only in planque, permitted explicitly by the person, and that needs its own design and the owner's yes
first. Sealed fills stay off everywhere.

The release contract below stays as the phones' and planque's shared design (built, off): the extension still checks a
release's signature in its tests (`protocol.checkRelease`, `parseHandle`, `vectors/chrome-release.json`), but has nothing to
open with one.

### The phone's release card (design; Android's card built, off)

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
   hold's summary only) and nothing is signed. `parseHandle` (`protocol.js`) reads a
   handle the same way: exactly `sec_<id>:password` or `sec_<id>:username`, a third segment or any other part refused whole,
   and the vector's refused cases include one.
4. Under "On the page", the page's title as planque read it (`context.title`), as every page word is drawn: one plain line,
   verbatim, never a link or anything to tap, and never as what the card asks.
5. One fixed line: "It never leaves your Chrome. tafs doesn't see it." (the phone never has the value, and says so rather than
   showing a stand-in; the line is the same for either part), and how long the card is good for.
6. **Allow this fill** and **Refuse.** Allow asks for Face ID or the fingerprint.

**What is signed.** Two signatures per Allow, each the approval key's behind that Allow's own biometric: **one prompt on iPhone
and iPad** (the step's own `LAContext`, the Secure Enclave key still checking the biometric for each), **two on Android** (its
approval key unlocks for one signature per prompt, a per-operation `CryptoObject`). On Android the release's prompt comes first
("Release the password for shop.example"), then the answer's ("Allow the fill on shop.example"). **Nothing is sent until both
are signed:** a closed second prompt sends nothing, the release included; never a release without its answer, and never an
allowing answer without its release. One touch everywhere waits for the hold's answer to cover the `browser` block, below, which
can then cover the release's bytes too. **Precondition:** the
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

**Where the phone refuses to sign,** whatever planque says (it shows the hold as unreadable, and says why). Which sites a
fill may go to comes **only from the phone's own signed grants**, never from fields the hold relays:
- the origin isn't `https`, or it isn't within the sites this phone signed (`tafs-chrome/sites/v1`, checked with its own key)
  for one of the account's **live** connections on that same Chrome, as planque lists them (`status` connected): a list
  planque widened, another key's signature, a connection on another Chrome, or one the owner ended (it keeps its signature)
  counts for nothing. The phone never signs a release for a site its owner didn't connect;
- the block KIFF serves isn't the one its `browser_sha256` names (the lower-case hex SHA-256 of the RFC 8785 JCS of the
  served `browser` object): then what the card would draw isn't what the answer covers, so it's refused before any prompt;
- the origin is a sensitive site, unless this phone holds its own `tafs-chrome/sensitive/v1` allowance for it on that
  connection. **Android keeps none yet, so it refuses every sensitive site;**
- the Chrome isn't one it is paired with, or the hold has no `read_id`, `field_ref` or a handle of the shape above;
- the hold has expired (below): the owner's late yes signs nothing, and planque asks again with a new step.

**Refuse, ignore or late.** Refuse signs no release and answers the hold's refusal, as any hold: it takes power away, so it needs
no lock. The taf is told the owner said no, and nothing is filled.

**How long a card lives.** The extension ended a run that got no step for 5 minutes when this was designed (`RUN_IDLE_MS`, 150 s now; see Runs),
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

**The vector** is `vectors/chrome-release.json`: the eight fields, the bytes the phone signs, and the handles a phone takes
with the part each names, made from the extension's own `signingBytes` and `parseHandle`. The extension's test checks it
against `checkRelease` (a release over those fields holds; another origin, Chrome, part, field, read or nonce, or a late
one, doesn't), and Android's builds the same bytes.

**Open for planque** (its docs, not this one): the held call's kind for a sealed fill, and how the answer's `release`
reaches the step. They must match the vector.

**For the apps,** the card is built once on iPhone and iPad (`BrowserStepBlock`'s sibling in `NeedsYouView`) and once on
Android (`NeedsYouView`), each drawn from `chrome.release_*` words in every language and each tested against the vector.
**Android's checks and signing are built, off** (`SealedRelease`, `ENABLED = false`): the handle's part, the refusals above
(a paired Chrome, the hold's time, an `https` origin within the sites this phone signed for one of the account's connections
on that Chrome, never a sensitive site, the op, read and field present), and a release with a new 16-byte nonce and `exp`
60 s on, signed over the vector's bytes. **Android's card is built, off too:**
- **What it reads:** planque's block as it sends it (planque's `sealedBlock`, which KIFF serves unchanged):
  `browser.release_fields`, `{chrome_id, op, sealed, origin, field_ref, read_id}`, copied byte for byte into the release
  (`KiffMapping.sealedFill`), and for what's drawn `site`, `origin`, `chrome.name`, `action_words` and `context.title`. The
  hold's `expires_at` is the fill's end. The sites it checks are the account's live grants as planque lists them, with this
  phone's own `chrome_sites_sig` (`ChromePairing.signedSites`), never anything in the block. **The hold's `browser_sha256`**
  must equal the served block's own hash (`TolerantBrowser.servedSha256`), and **every answer to the hold, Refuse included,
  signs it** as `kiff.answer.v1`'s ninth line (`KiffClient.answerMessage`), as KIFF checks it.
- **What it draws** (`SealedFillBlock`): the design's order above, in `chrome.release_*` words in every shipped language; the
  block's own words (the site, the Chrome's name, the page's title) as plain text only; a fill the phone's checks refuse says
  why (`SealedRelease.refusalWords`) and offers Refuse only.
- **Allow this fill** carries the confirm mark and asks the two prompts above (`AppModel.release`, then the answer); the answer
  goes to KIFF's `approve-once` with `release: {chrome_id, op, origin, nonce, exp, sig}` beside its `signature`. **KIFF's half
  must take it and hand it to planque.**
- **Never allowed without its release:** with the card off, a sealed fill is drawn as any step with Refuse only, and no other way
  of approving (a notification's action, an approval window's tap, an answer signed elsewhere, Extra room) allows it, and "Allow this from now on" is never offered for one (`HoldAllow.allowable`). A sealed fill with no
  `expires_at` reads as expired. **Whether a hold is a fill** (`HeldCall.needsRelease`) is read from its `browser` block as sent
  (`TolerantBrowser`): a `release_fields`, `sealed_fill` or `fill` that isn't null, or a block that isn't an object. So a fill whose block can't be
  drawn (no site, say) still can't be allowed, and one bad block never hides the other held calls. Tests: `SealedFillCardTest`.

## A step handed to the person

A taf sometimes needs the person to do one thing in the page (a card number, a check, a click on Send) and carries on after. It hands the
step over: `op: "hand"`, `hand: {words, ref?}`, `read_id`, signed like every step, on the connection's own sites. The design is
[chrome-panel.md](chrome-panel.md); planque's side (`browse_hand`, `browse_hand_result`) is its own.

- **The extension acts for no one here.** It types, clicks and fills nothing, and no sealed value is offered. It answers the step **at once**
  as handed (`{ok: true, handed: true}`, in the usual signed result), because a person takes longer than a step may wait, and the panel
  shows **Your turn** (`hand.js`, `runner.js` `hand`). The person's answer is its own message, below.
- **It checks the step itself and trusts nothing of planque's checks:** `words` is 1 to 280 characters of plain text with no control or
  direction character (refused, never stripped: `validWords`); `ref`, when given, is a field or control **in the read the step names** (else
  `bad_step`); that read must be one the extension has (else `page_changed`); the tab's page must pass `refuseURL` for the run's sites (a
  sensitive site is refused); and **one hand-over is open per run**: a new one ends the old as declined.
- **The card:** "Your turn", under "The taf says" the taf's words as plain text (never as a KIFF or extension line), a fixed line "Only type
  where you trust this page.", and three buttons. **Show me** brings the run's tab to the front and outlines the named field or button
  (`TafsPage.outline`: an outline that lets every pointer through, never a scroll, a focus or a click, gone after 8 s). **When it can't
  point to one, the card says why** under its buttons: the page changed since the taf read it, the field isn't on screen (a narrow
  window can hide a search box), the page didn't answer, or the taf named no field. A press never goes unanswered. **Done** and **Not
  now** answer. The toolbar badge says "!" while one waits.
- **A hand-over doesn't stop its run.** While one is open the run isn't swept as idle (a person takes longer than 5 minutes), and **the
  person's own typing in the page doesn't pause it** (it is what was asked); both end with the answer or at its 30 minutes (`HAND_MAX_MS`,
  planque's own limit). A touch after the answer pauses as before.
- **Done reads the page back to the taf without what is in its fields.** The read after a hand-over leaves out every field's `value`
  (a text, the checked state, a select's chosen option; `withoutValues`), so the taf learns the field is there and nothing of what was
  typed; planque blanks them again. **The text inside an editable region** (`contenteditable`, `role=textbox`, where a form made of one holds what
  was typed) is left out of that read too (`TafsPage.read(offset, {withoutEditable: true})`). **A residual, said plainly:** a page that echoes what
  was typed into its own, non-editable text (a summary, "your card ends 4242") is still read as text, masked only by pattern, as any read is.
  **Not now** gives no page.
- **The answer is its own message,** `{type: "hand_result", hand, outcome: "done" | "declined", page?, sig}`, `sig` this Chrome's key over
  `tafs-chrome/hand/v1` and `{hand, outcome, page?}` (a new label, so a step's result can never stand for it). planque takes the first and
  answers `{type: "hand_kept", hand}`, or `{type: "hand_refused", hand, code}` with `unknown_hand`, `expired`, `unsigned`, `too_large` or `not_kept`.
  The extension keeps the answer (in `chrome.storage.session`, which survives the worker stopping) and **sends it again on each welcome until
  `hand_kept`**, stops on `unknown_hand`, `expired` or `unsigned`, keeps it and sends it again **on the next welcome only** after `not_kept` (planque's store failed just then; never at once, so a failing store is not answered by a loop), and on `too_large` sends Done once more with no page. A page over the
  relay's 120 KB cap goes as Done with no page, and the taf reads again. A run that ends with a hand-over open answers it declined.

## The taf in the side panel

The side panel is for watching a taf work and talking to it ([chrome-panel.md](chrome-panel.md)). Coach sits behind
**Set up a new taf for this site**, in a view of its own: the taf's chat goes out of sight, and **← Back** returns to it.
**Remove this taf** says it's removed on the phone: tafs cloud takes a removal only signed by the phone's approval key or a
computer's passkey, which this Chrome has neither of.

- **Which tafs:** planque's list of this Chrome's live connections, `connections: [{grant, agent, name, sites}]`, comes in
  the relay's `welcome` and again on each change (`{type: "connections"}`, `readConnections`, at most 50). The panel offers
  each one that is one of the person's tafs (`GET /v1/tafs`, matched by `kiff_agent`, `tafsHere`), once each, with the one
  at work first. With one taf it opens on it, and with several there's a picker.
- **The list is for display only, never authority.** What a step may do still comes only from the signed step and the
  connection's phone-signed sites. The extension never takes a site from this list as allowed. The panel reads the chat of
  a taf on it, and no other (`taf_thread`, `taf_send`). That limit is only as strong as the list, which planque sends and
  nobody signs. tafs cloud's own routes still decide whose chat the session may read.
- **The person's tafs are read once per change** of this Chrome's connections, and again when the panel is shown
  (`keptTafs`). A chat's reads and sends reuse them, so they make no extra request.
- **Its chat** is the taf's own, as on the phone (`GET /v1/tafs/{id}/conversation`, `POST …/messages`, tafs cloud's
  extension routes). It's read on each event: the panel shown, a taf picked, a send, a step seen in a run. Then every 4 s
  while the taf works or for a minute after a send, otherwise backing off from 60 s to 5 minutes, jittered (`idleDelay`),
  and never while the panel is hidden. Coach is read only while it's shown.
- Cards still say to approve on the phone ([chrome-panel.md](chrome-panel.md) has the route that approves in the panel,
  off until its gates are answered).

## Coach in the side panel

The side panel talks to Coach: the same chat the apps use, through tafs cloud's own routes (`GET /v1/setup`,
`GET /v1/tafs/{coach}/conversation`, `POST /v1/tafs/{coach}/messages`), as the extension's view-only KIFF device
(`chrome/src/coach.js`). A conversation started in Chrome goes on on the phone.

- **The side panel's chats are only this Chrome's** (`runtime/src/cloud/chrome-chats.ts`): the chats begun in it since it
  was paired, never the person's other chats, which stay on their phone and other devices, which list them all. tafs cloud
  knows the Chrome from KIFF alone: `/v1/me`'s `device_id` and `device_platform` ("chrome"), from the session KIFF paired,
  never from the request. Each chat a Chrome begins is kept as one item by its id. Its lists (`…/chats`, `…/chats/archived`)
  hold only those. A chat it didn't begin is 404 by its id (its thread, a message into it, archiving it). Its live thread,
  and where a message with no chat goes, are its own newest chat or a new one. A re-pair is a new KIFF device, so it starts
  empty. A view-only device session KIFF names neither a device nor a platform for is taken as a Chrome with no chats (fail closed); once KIFF names the device, only its platform says Chrome.
- **A Chrome calls only the side panel's routes:** the Coach and tafs reads, a taf's thread, chats, messages and archiving,
  and the page consent. Every other tafs cloud route answers it 404 (export included), so a route added later is closed to
  it until it's listed.

- **Chrome treats those calls as the extension's own** (it holds every https site, tafs cloud's included), so they need no CORS
  answer; tafs cloud still answers the preflight as it did, for a build that doesn't hold the permission. tafs cloud
  answers Chrome's preflight on those routes alone (and on one chat's conversation and the list of chats, for Previous conversations), before anything is read, and lets only an extension's own
  origin (`chrome-extension://` and a 32-letter id) read the answers (`runtime/src/cloud/extension-cors.ts`). It never
  answers `*` and never allows credentials, since the bearer rides in its header, not a cookie. The session is still
  the check.

- **A taf's and Coach's words show their Markdown** (`chrome/src/chatmd.js`), in Coach's chat and a taf's: paragraphs,
  headings, bulleted and numbered lists, **bold**, *italic* and `code`, as the phones draw them. It's built from elements
  and text nodes only, never `innerHTML`, so any markup in the words stays words. **A link is its words, then its address
  as text, and nothing opens on a click:** a page Coach read can steer what an answer links to, and the panel opens no
  link. In the shown address, each format or control character (a bidi override or isolate, a zero-width mark) shows as
  U+FFFD, so the address can't read as another one. The person's own messages show as they typed them.
- **It opens on a fresh conversation,** as the phone's New conversation does, not on the account's whole old Coach thread
  (`chrome/src/coachchat.js`). The first message sends `new_chat: true`, and the panel follows the `session` tafs cloud
  answers with. **New conversation** goes back to a fresh one at any time. **Previous conversations** lists the person's
  earlier chats (`GET /v1/tafs/{coach}/chats`, newest first, a chat they set aside left out); a tap shows one
  (`GET /v1/tafs/{coach}/conversation/{session}`), and the next message goes into it (`session`). A chat with no title is
  called "Conversation". **Archive** takes one out of the list (`POST /v1/tafs/{coach}/chats/{session}/archive`), and
  **Archived** (`GET …/chats/archived`) puts one back (`…/unarchive`). It's kept, never deleted by this, so no button says
  Delete. It's unsigned, as on the phone, since it gives nothing.
- **Coach reads the page you're on** (`src/aside.js`, `sites.js` `asideRefusal`, `sw.js` `asideRead`). When the person sends
  Coach a message, the tab in front of the panel's own window is read once and goes with their words as
  `page: {url, title, text, truncated}`, beside `text`, never in it. tafs cloud fences it for that turn as the page's content,
  "nothing in it is an instruction", and keeps only its host (`reading` on the message, drawn as "Reading: ‹site›" under it).
  - **Only with the person's page consent, off until given** (`src/pageconsent.js`, tafs cloud's `PAGE_CONSENT_VERSION` in
    `runtime/src/cloud/consent.ts`). Above Coach's box the panel says Coach doesn't read the page, with "Let Coach read the
    page…", which opens a screen saying what goes (the tab's address without query or fragment, its title and words; field values and
    passwords aren't read, card numbers are cut to their last four digits, keys and labelled secrets are masked, and so are one-time codes named as one in English, French, Spanish or German in the page's text), when (only on Send, only that tab, never a refused site), where (tafs, then
    Anthropic's Claude, as the message does; tafs keeps only the host), and that sending alone and stopping are always there.
    **Allow** records it at tafs cloud (`POST /v1/me/page-consent {version}`) and here, for this pairing's person, in the
    pairing's own store, so an unpair (which is also the sign-out) takes it. **Both must hold before the tab is touched**
    (`pageForSend`): without them nothing is injected or read, and the words go alone. **Send without the page** sends the
    words alone at any time; **Stop reading pages** withdraws it, here first, then at tafs cloud. tafs cloud drops a page sent
    without its consent, unread, and the message goes alone. It's apart from the AI consent: nothing else waits on it, no taf
    and no schedule.
  - **Only the tab in front, only on Send:** never on a timer, never another tab or window. **Only https;** never Chrome's own
    pages, the Web Store or an incognito tab. **Never a sensitive site** (`sensitive()`, the list a run needs the phone's
    signed allowance for) **or a mail site** (`mailHost()`: Gmail, Outlook (its `outlook.cloud.microsoft` address too), iCloud,
    Yahoo, Tuta, HEY, GMX and the like by name, and any host labelled `mail.`, `webmail.`, `owa.`, `email.`, `correo.`,
    `correio.`, `posta.`, `poczta.`, `messagerie.` or `levelezes.`, which takes in Proton's `mail.proton.me`). Every host is
    taken as `hostOf` normalises it, so a trailing dot (`paypal.com.`) gets past no list: the panel says why, and Coach gets
    the words alone. Runs aren't changed by the mail list. tafs cloud refuses such a page too (`page_refused`, from the same
    lists in `runtime/src/cloud/page-sites.ts`, held equal to these by a test), so a change to `SENSITIVE` or `MAIL` here
    is a change there. **Never a site the person switched off** with
    **Not on this site**, kept in this Chrome (`chrome.storage.local`, `aside_off`) and checked before any read; **Read this
    site** turns it back on.
  - **Masked as a run's read is:** the page's text with every editable region (a textarea, `contenteditable`, a
    `role=textbox`) left out for the moment it's read, so nothing typed goes; no field's value; secret shapes (a key, a card
    number, a labelled secret) masked in the text and the title; the address with no query or fragment; at most 20,000
    characters and 20,000 bytes of UTF-8, with `truncated`. Only `mask.js` and `aside.js` go into that tab, for that one read:
    nothing listens or stays.
  - **Said every time:** above Coach's box, "Coach reads this page when you send: ‹site›", or why it doesn't, with the switch.
  - **Checked twice** (`src/asideread.js` `readForCoach`): before the read, and again after it on the address the read was
    actually taken on (`aside.js` reports it, and it never leaves Chrome), with a fresh look that the tab is still the one in
    front. A tab that moved in between (a redirect, a link, a click as the person sends) onto a page the gate refuses sends
    nothing.
  - A page that can't be read is no reason to hold the message: it goes with the person's words alone. So does one tafs cloud
    refuses (`400 page_refused`): the words are sent once more on their own (`Coach.send`). The address sent is at most 2,000
    characters (the origin alone past that), so it never runs past what tafs cloud takes.
- **It reads sparingly** (`coachchat.js` `pollDelay`), so the load doesn't grow with each open panel. A fresh conversation is
  read once (is Coach reachable) and never polled. A chat is read every 4 s while Coach is at work and for a minute after the
  owner's message, and every 30 s otherwise, to see an answer from the phone; a panel that isn't seen reads nothing, and one
  that becomes seen reads once at once. The account's Coach id (`GET /v1/setup`) is read once for the session, not on each read.
  Tafs cloud has no push for Coach's replies yet (the relay carries steps only), so this is the fallback a push would replace.
- **It reads and sends, never approves.** A card that waits on the owner (an approval, a sign-in) shows "Something waits
  for your approval on your phone"; KIFF refuses the session every approval anyway.
- **No page goes with the owner's words.** There is no "Share this page": when Coach or a taf works on a page, its
  first step there is to read it itself, through planque, as any run's read (masked, fenced, on the connection's sites).
- **Coach can set up a browser taf for the site in front of the owner;** the grant is approved on the phone, as always.

## Screenshots of a run's acts

A taf's Chrome steps are kept as pictures for the record: one WebP (quality 0.6, at most 1280 px wide and 8000 px high, about 50 KB, never over 120 KB) for each step that acts, taken just before the act (`src/shot.js`, called from `Runner.pictureOf`). Reads and opens take none.

- **Covered first.** `TafsPage.maskForShot()` puts an opaque box over every password, one-time code and card field, and every field whose value the masking would change. It also covers **text in the page** that looks like a card number (13 to 19 digits that pass the Luhn check, with spaces or dashes) or a secret: keys by shape (`sk_`/`pk_`/`rk_`, `AKIA…`, `ghp_`/`github_pat_`, JWTs and the like), labelled values (`api_key=…`, `Bearer …`), and runs of 32 or more hex characters or base64 characters with a digit in them (`TafsMask.secretSpans`). It covers **every visible frame whole** (`iframe`, `frame`, `embed`, `object`): what a frame from another site holds, a login widget or a bank's form, can't be read by the page's script, so none of it is trusted to be hidden. Fields and text inside **open shadow roots** are found and covered like any other (a closed shadow root can't be seen from the page, so a secret in one is not covered: a limit). It checks with `elementFromPoint` that each box is on top; if one is not, no picture is taken. The boxes come off straight after the capture, also when it fails or times out.
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
  are the pauses (failures, no progress, the owner), Stop, runs closed when idle, steps that never overlap, windows of a page's text, a checkout page that refuses every click, and a dialog's answer.
- **`vectors.test.js`** and `ios/TafsTests/ChromePairingTests.swift`: both check the pairing's proofs and signed bytes
  against `vectors/chrome-pairing.json`, so the extension and the phones can't drift. The phone's side of pairing is
  `ios/Tafs/KIFF/ChromePairing.swift`.
- **`coach.test.js`** and **`kiff.test.js`:** the owner's words sent alone, cut to fit; Coach read and written through
  tafs cloud's routes with the session's own bearer; the KIFF offer as a view-only extension; the claim's DER proof
  verified; and a claim that waits, and a session that could approve, ended and never kept.
- **`store.test.js`:** what 0.2.3 and before saved as sign-ins is deleted on start and the pairing kept; nothing in the
  extension saves, lists or types a sign-in, or shares a page.
- **`page.test.js`:** the content script in a real Chromium page. A read's refs and masking (no password, code, hidden
  input, card number or key), a long page's windows, a checkout's lines, total and currency (in European and English number marks, masked, none when the total is unclear), page text that imitates a fence or an instruction (it goes up as
  written), an act that refuses an element that has become something else (its words, its target, its row), fill, select and check, a fill that never submits, a stale read after a navigation, a covered button that isn't
  clicked, and dialogs.

## Known limits

- **A window a page's script opens** (from a blank frame, say) is fenced only *after* its first request: Chrome gives the
  extension its tab only once it navigates, so that first request goes out before the fence holds it. A run's tabs never
  open one (`window.open` is off in its tabs, and `target` is forced to `_self`).

## Not done yet

- **The sealed-value release on the phone's card** (`tafs-chrome/release/v1`): a design, off. The extension keeps no sealed value; the vector and Android's card are built, off. planque's hold for a sealed fill, KIFF's taking the release with the answer, and iPhone and iPad's card are still to build ("The phone's release card").
- **The release workflow** (two approvals) that publishes to the Chrome Web Store.
