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
| `chrome/manifest.json` | MV3. Permissions at install: `storage`, `alarms`, `scripting`, `sidePanel`, `tabs`, `tabGroups`, `webNavigation`. **No host permissions at install**: each site is asked for, once, when a run needs it (`optional_host_permissions`). No `debugger`, `downloads`, `cookies`, `history`, `webRequest` or `nativeMessaging`. |
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
| `chrome/_locales/en/` | The words, in English for now. They move into `app/strings` with the other languages before the switch goes on. |

## Crypto

- **Every key is ECDSA P-256 with SHA-256:** the extension's (WebCrypto, not extractable, kept in IndexedDB), the
  phones' (their device keys, as for every approval), and planque's step key.
- **A signature is over a label, a zero byte, then the body's canonical JSON** (RFC 8785):
  `sig = ECDSA(label ‖ 0x00 ‖ JCS(body))`, sent as base64url of the raw 64-byte `r‖s`. A DER signature from a phone's
  keystore is read too.
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

- **HMACs** are HMAC-SHA256 under the pairing code's secret, over `label ‖ 0x00 ‖ data`, with `tafs-chrome/ext/v1` and
  `tafs-chrome/phone/v1`.
- **planque's step key is pinned in the build** (`chrome/src/config.js`), one per environment. A new key ships as a new
  build. A build with no key pinned pairs with nothing.

## Pairing: it starts on the phone

The owner pairs from the phone, and only types a code into Chrome. A code shown by someone else's extension can never get
the owner to pair a stranger's Chrome, and planque never learns the part of the code that proves both keys.

1. **The phone opens a pairing.** `POST /v1/accounts/{t}/chromes/pairings` is signed by the phone as every planque call
   is, and answers `{pairing: "pr_…", lookup: "K7QF"}`. The phone makes a 6-character **secret** of its own that it never
   sends, and shows `K7QF-29XMDP` (the lookup, then the secret) for 10 minutes, with "Pair only a Chrome on a computer
   you're at". The characters exclude `0 1 I L O U`.
2. **The owner types the code into the side panel.** The extension makes its key pair and calls
   `POST /v1/extension/pairings/{lookup}` (unauthenticated, rate-limited per lookup) with:
   ```
   {key: <SPKI, base64url>, version, manifest_hash, browser, os,
    proof: HMAC(secret, "tafs-chrome/ext/v1" ‖ key)}
   ```
3. **The phone reads the pairing** (`GET …/pairings/{pairing}`) and checks `proof` with its secret. A wrong proof
   means another extension used the lookup: it stops there. It shows the browser and OS, the owner names it
   ("Work Mac"), and Face ID or the fingerprint signs `tafs-chrome/pair/v1` over:
   ```
   {pairing, chrome_key_sha256, name, phone_key, phone_proof}
   phone_proof = HMAC(secret, "tafs-chrome/phone/v1" ‖ phone_key ‖ sha256(chrome key))
   ```
   It sends that with `POST …/pairings/{pairing}/confirm`, and planque binds `chrome_id` (`chr_…`) to the account.
4. **The extension polls** `GET /v1/extension/pairings/{lookup}?key=…`. It gets `202` while the phone hasn't confirmed,
   then `{pairing, chrome_id, name, phone_key, phone_proof, phone_sig}`. It keeps the pairing only if `phone_proof`
   holds under the secret it typed and `phone_sig` holds under `phone_key` (`pairing.checkConfirmation`).
   - The secret then leaves memory.
   - A new phone (a new device key) pairs again; planque never carries a phone key over.
- **Several Chromes:** each paired Chrome has its name, and a grant names the one it uses.
- **Only the owner, on the phone, turns the `extension` driver on for a grant.** Nothing in a taf, Coach or a page can.

## Connect: both ends prove themselves

`wss://planque.kiff.dev/v1/extension?chrome=chr_…`, opened by the extension:

1. **planque sends** `{type: "challenge", nonce_p}`.
2. **The extension answers** `{type: "hello", chrome_id, nonce_p, nonce_e, version, manifest_hash, sig}`, signed with its
   paired key. planque checks the key, and checks `manifest_hash` against its allowlist of released builds; anything else
   is closed with `4403`.
3. **planque answers** `{type: "welcome", sig}`, its step key's signature over `{chrome_id, nonce_p, nonce_e}`. The
   extension checks it with the pinned key, or closes.

**Before the welcome, anything else closes the connection.**
- **Close codes:** `4401` (unknown Chrome), `4403` (build not allowed), `4410` (unpaired: the extension wipes itself).
- **Keepalive:** `{type: "ping"}` / `{type: "pong"}` every 20 s while a run is on. MV3 keeps the service worker alive
  while messages flow.
- **While no run is on,** the extension wakes on an alarm every minute and connects, so planque can hand it a step. The
  connection drops after a minute with no run.
- **`manifest_hash`** is the SHA-256 of the installed `manifest.json`, as the Chrome Web Store serves it. The release
  workflow writes each released build's hash into planque's allowlist.

## Steps

**planque sends** `{type: "step", step, sig}`, where `sig` is its step key's signature over `step`:

```
step = {id: "op_…", nonce, exp: <unix ms, at most 30 s ahead>, chrome_id, run, taf, grant,
        sites: ["shop.example", …], sensitive_allowed?: [...],
        op: "open" | "read" | "act" | "close",
        url?,                          // open: a start URL or a link from the current read, already checked by planque
        read_id?,                      // act: the read it was planned from
        act?: {kind: "click" | "fill" | "select" | "check", ref, value?, sealed?},
        release?}                      // only with act.sealed: the phone's release
```

**The extension runs nothing that isn't planque's, for this Chrome, unexpired and new** (`protocol.checkStep`). A nonce
is remembered for 10 minutes. It answers a refused step's `id` with `bad_step`, and gives an unsigned message no
signed answer.

**The extension answers** `{type: "result", id, ok, page, error, sig}`, signed with its key over `{id, ok, page, error}`:

```
page = {read_id, url (no query or fragment), title, text (≤ 20 000 characters, masked),
        fields: [{ref, label, kind, value?, options?, masked?, disabled?}],
        controls: [{ref, label, submits}], links: [{ref, label, url}],
        payment_frame?, dialog?: {kind, text}}
error = {code, detail?}      // detail never carries page text
```

- **This is planque's `browser.Page`,** plus `read_id`.
- **Refs restart with each read** (`f1`, `c1`, `l1`), and an act names the read it was planned from.
- **Every act answers with the page read back:** a click is never proof of its effect (content-script events aren't
  trusted input, and a site can tell).
- **`stop`:** planque may send `{type: "stop", run}` unsigned, since stopping only takes power away. The run's window
  closes.

### What the extension refuses, whatever planque sent

| Code | When |
|---|---|
| `bad_step` | not planque's, not for this Chrome, expired, replayed, or malformed |
| `bad_url` | not `https`, user info in the URL, or not a URL |
| `address_private` | an IP address (in any form a browser reads), or a local name (`.local`, `.internal`, …) |
| `site_not_listed` | a host that isn't one of the run's sites or their subdomains, including a navigation the page starts |
| `site_blocked` | Chrome's own pages, the Web Store and Google's account pages, always. Also a sensitive site (a bank, wallet, password manager, payment, health or government account, by list and by name), unless the owner allowed it by name (`sensitive_allowed`) |
| `needs_site_permission` | Chrome hasn't let the extension into that site yet: the side panel offers "Allow shop.example in Chrome" |
| `page_changed` | the act's read isn't the tab's current read: a navigation, a new document, or an act since |
| `ref_unknown` | the read has no such ref, or its element is gone |
| `not_visible` | the element is hidden, off screen, or under another element at its centre |
| `secret_field` | a fill of a password or one-time-code field |
| `payment_step` | a fill of a card field, a click whose words or semantics pay or place an order, or any click on a checkout or payment page |
| `dialog_open` | the page raised a dialog since the read |
| `paused_by_owner`, `paused_failures`, `stopped` | the run is paused or stopped |
| `page_unresponsive`, `browser_unavailable` | the page didn't answer in 20 s, or Chrome failed |

## Runs

- **A run gets a window of its own,** opened without focus, with one tab group, "tafs · Rae", in orange. It never comes
  to the front, never takes focus from the owner, and closes when the run ends.
- **The runner touches only tabs it opened.** It never lists the owner's tabs, and it ignores navigation events from
  any other tab. A tab a run's page opens joins the run only on the run's sites; anything else is closed.
- **A navigation that leaves the run's sites is stopped in the tab,** whatever started it.
- **The owner's own keystroke, click or scroll in a run's tab pauses the run** (`isTrusted`). The side panel says
  "Paused: you're using this tab", with Resume.
- **Three failed acts in a row pause the run,** with what it read so far.
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

## What's kept

- **In IndexedDB:** the key pair, whose private key can't be exported, and the pairing (`chrome_id`, its name, the phone's
  key). No page, step or value read is ever written to disk.
- **A code being typed** waits in `chrome.storage.session`, which Chrome keeps in memory only.
- **Unpairing wipes it all** and closes every run's window. It happens from the phone (planque closes with `4410`, or
  answers `4410` at the next connect), from the side panel (`POST /v1/extension/unpair`, signed with
  `tafs-chrome/unpair/v1`), or by uninstalling.

## Sealed values (not in this build)

A later slice. The taf sees only a handle (`act.sealed`), and the value is kept in the extension. It opens for one fill
only on the paired phone's own release, which planque relays and can't make:
- `release` is the phone's `tafs-chrome/release/v1` signature over
  `{chrome_id, op: step.id, sealed, origin, field_ref, nonce, exp}`.
- The extension checks it with the phone key it learned at pairing (`protocol.checkRelease`).
- The page must be on that exact origin, in the top frame, and the field must be a password, username or one-time-code
  field.

**Until then, a sealed act is refused as `secret_field`.**

## Tests

- `cd chrome && node --test test/*.test.js` runs every test. CI's `chrome.yml` runs them on every change to `chrome/`
  or this page, with Chromium for the page tests.
- **`core.test.js`:** canonical JSON, signatures and their labels, DER signatures, the site rules, masking, and the
  guard (mirroring planque's).
- **`protocol.test.js`:** a step's signature, Chrome, expiry and nonce; answers, hello and welcome; a phone release that
  planque can't forge; the whole pairing, including a substituted key, a renamed pairing, another secret and another
  extension; the relay taking nothing before both proofs; and `4410` wiping the extension.
- **`runner.test.js`:** against a fake Chrome API. A run's window is never focused. The owner's tabs are never listed.
  Off-site opens, navigations and pop-ups are stopped. The guard runs before the page is touched. There are the pauses,
  Stop, and the site permission.
- **`page.test.js`:** the content script in a real Chromium page. A read's refs and masking (no password, code, hidden
  input, card number or key), fill, select and check, a fill that never submits, a stale read after a navigation, a
  covered button that isn't clicked, and dialogs.

## Not done yet

- **planque's `extension` driver,** the relay and the release-build allowlist (kiff/planque).
- **The phones' pairing screens and the approval card** for browser steps (iPhone and iPad, then Android).
- **Sealed values,** with their own security review.
- **The words in every language,** and the release workflow (two approvals) that publishes to the Chrome Web Store.
