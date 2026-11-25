# tafs in your Chrome: the end-to-end tests

The extension's unit tests (`chrome/test/`) check each part against fakes. The end-to-end tests (`chrome/e2e/`) check the
parts together: **the real extension**, loaded in Chromium, paired and connected to **a fake planque** that signs its
steps with a test key, working on **local websites**. Nothing in them is an LLM or a service on the internet: a test
decides every step, and every name Chromium looks up is either a fixture site on this computer or fails to resolve.

The rules they check are [chrome.md](chrome.md)'s. This page says how the harness works, how to run it and what it covers.

## Running them

```bash
cd chrome
npm install --no-save playwright@1.63.0   # once; CI does the same
npx playwright install --with-deps chromium
npm run e2e                               # node --test e2e/*.e2e.test.js
```

- They need Playwright, its full Chromium (the tests launch it with `channel: "chromium"`, in its new headless mode: the
  headless shell loads no extension) and the `openssl` command, which makes the fixture sites' certificate.
- Without Playwright they skip, as `page.test.js` does. In CI (`CI` set) a missing Playwright fails them instead.
- CI's `chrome.yml` runs them as their own step, after the unit tests, on every change under `chrome/`.
- Each test file starts its own Chromium, profile, fake planque and sites, and throws them away after. A run of all of
  them takes about 40 seconds (the slow-tab test waits out the extension's 30 s load limit).

## The harness

| File | What it is |
|---|---|
| `e2e/build.mjs` | The test build: a copy of `chrome/` in a temporary folder (without `test/`, `e2e/` and `store/`). Only the copy's `config.js` changes: it gains a `test` environment (the fake planque's `api` and `relay` on 127.0.0.1, its test step key, no push key, no screenshots) and `ENV = "test"`. The copy's manifest also lists `http://127.0.0.1/*`, so the service worker can reach the fake planque. |
| `e2e/planque.mjs` | The fake planque: the pairing routes and the relay on one port of 127.0.0.1, and the paired phone. Also the fake tafs cloud and KIFF (`FakeCloud`), for the tests of a run started from the side panel. |
| `e2e/sites.mjs` | The fixture sites: one HTTPS server, with a certificate made at the start, that answers by host name. |
| `e2e/harness.mjs` | Starts all of it, opens the side panel, pairs, and gives the tests their helpers. |
| `e2e/*.e2e.test.js` | The tests, run with `node --test`. |

**The fake planque** is built on the extension's own `src/crypto.js` and `src/pairing.js`, so what it signs is checked by
the extension exactly as planque's would be. Its keys are made for each test file and never kept.
- **As the phone** it makes a pairing code (a 4-character lookup and a 17-character secret from `pairing.js`'s alphabet).
  When the extension posts its offer to `POST /v1/extension/pairings/{lookup}`, it checks the offer's proof of the
  extension's key under the code's secret. It answers the extension's poll with the phone's confirmation: its own key, its
  `phone_proof` (the HMAC of its key and the extension's key's hash under the secret) and its `phone_sig` over the pairing.
  It also signs the grant's sites (`tafs-chrome/sites/v1`) for every step it sends.
- **As planque** it answers the relay's `open` with a challenge, checks the extension's `hello` with the key from the
  offer, and answers with a `welcome` signed by its step key, with the one connection and its own time. It answers
  `ping`, keeps the signed `run_state` and `hand_result` messages (`run_state_kept`, `hand_kept`), and checks every
  result's signature with the extension's key.
- **Steps:** `sendStep({op, ...})` builds a step (id, nonce, expiry 20 s ahead, this Chrome's id, run, taf, grant,
  sites, the phone's `sites_signed`), signs it with the step key, sends it and resolves with the extension's signed result.
  Any field can be overridden, and the step can be signed by another key, or its sites by planque's key in the phone's
  place: that is how the refused steps are made.
- The welcome lists the one connection with the phone's signature over its sites (`sites_signed`), as planque relays it.
- Every other route answers 404, and `POST /v1/extension/unpair` 204. The extension's claim of its view-only KIFF device goes
  to `api.kiff.dev`, which the test Chromium can't resolve, so it fails at once and does nothing, except in the tests that
  start the fake tafs cloud (below).

**The fake tafs cloud and KIFF** (`FakeCloud`, `start({cloud: true})`) are only what a send from the side panel goes through.
The harness maps `api.kiff.dev` and `cloud.tafs.kiff.dev` to the fixture sites' HTTPS server (`MAP <host> 127.0.0.1:<port>`),
which answers them with `FakeCloud`. As KIFF it answers the extension's claim of its view-only device at once, as if the owner
had approved it on the phone, and its logout. As tafs cloud it keeps the page consent (`/v1/me/page-consent`), lists the one
taf (`GET /v1/tafs`, matched to the connection by its KIFF agent), and answers a message to it (`POST /v1/tafs/{id}/messages`)
with the agent run it starts (`run`), which the test's steps then name as `agent_run`. Every call must carry the claimed
session's bearer.
- The relay is a small WebSocket server of its own (text messages, ping, pong and close), so the harness needs no package
  beyond Playwright.

**The fixture sites** are `news.test` (an article, a long page whose text comes in windows, and a page with a link on the
run's sites and a form to `other.test`), `shop.test` (a form with a text field, a select, a checkbox and a password field
holding a known value; a page that asks `confirm()`; a page with a link to `other.test`; one page for each other way off the
run's sites: a 302 redirect, a form that posts there, a meta refresh, a button whose script calls `location.assign()`, a
`target=_blank` link and a frame; and a page with a password in a frame of its own site and another in a frame of `news.test`)
and `other.test`, which no grant lists. The server logs every request (`hits`, and `posts`), so a test can show `other.test`
was never asked for anything. Chromium reaches them through
`--host-resolver-rules=MAP *.test 127.0.0.1, MAP * ~NOTFOUND, EXCLUDE 127.0.0.1`, with `--ignore-certificate-errors`
and `--no-proxy-server`. Their URLs carry the server's port (`https://shop.test:43127/form`): the extension's site rules
and Chrome's fence read the host alone. The grant's sites are `shop.test` and `news.test`.

**Pairing is done as the owner does it.** The harness opens the side panel with a click (`chrome.sidePanel.open` needs one),
types the fake phone's code into the panel's form and submits it; the panel sends `pair`, then `poll`, and the extension
connects. The service worker takes these messages only from the extension's own pages outside a tab, and Playwright lists
no side panel among its pages, so the harness reaches the panel through Chromium's DevTools protocol. The same way, the
tests press the panel's Pause, Resume, Stop and a hand-over's Done (`pause`, `resume`, `stop`, `hand_answer`), and send the
taf a message as the panel's composer does (`taf_send`, with the panel's window and the tab in front). What only the service
worker can read (Chrome's `declarativeNetRequest` session rules, a tab's address and group) is read there with Playwright's
`serviceWorker.evaluate` (`inWorker`, `sessionRules`, `rulesOf`, `tabInfo`).

**Nothing waits on a clock.** A test waits for a result, a message or a state, each with a time limit. Any dialog that
reaches Chromium is accepted by the harness and recorded, so a `confirm()` the extension failed to answer would read as
yes, and fail the test.

## What each test covers

| Rule ([chrome.md](chrome.md)) | Test |
|---|---|
| The extension that ships is production's: its environment, its pinned key, its manifest | `config.e2e.test.js` |
| The test build changes only its copy, and fails if `config.js` changed shape | `config.e2e.test.js` |
| The offer proves the extension's key under the code's secret, and the secret never goes to planque | `pairing.e2e.test.js` |
| The hello is signed by the paired key for planque's challenge; the welcome is checked with the pinned key, and only then does the extension say it's connected, with planque's list of connections | `pairing.e2e.test.js` |
| planque's `close` with 4410 wipes the extension, which asks for a code again | `pairing.e2e.test.js` |
| A confirmation that isn't the phone's (planque swapped the phone's key) pairs nothing, and the relay never opens | `pairing.e2e.test.js` |
| Paired again, the extension connects under its new Chrome id and runs steps | `pairing.e2e.test.js` |
| `open` on the grant's sites opens a background tab of the run's own; the result is signed, for the step's id and nonce | `steps.e2e.test.js` |
| `read` gives the text, fields with refs, options and controls; a password field is `masked`, and its value is nowhere in a result | `steps.e2e.test.js` |
| A long page's text comes in windows (`more`, `next_offset`, `offset`) | `steps.e2e.test.js` |
| `act` fills, selects and ticks in the real page, and reads it back; a fill of the password is `secret_field` and leaves it as it was; an act on another read is `page_changed` | `steps.e2e.test.js` |
| A page's `confirm()` doesn't block the run, is answered no, never reaches Chromium, and the read says the page asked | `steps.e2e.test.js` |
| A step signed by another key, changed after signing, for another Chrome, expired, too far ahead, or sent twice (also under a new id) is dropped with no answer at all, not even a signed refusal, and nothing opens or loads; a good step after it is answered | `refusals.e2e.test.js` |
| A message with no id and no signature gets no answer | `refusals.e2e.test.js` |
| A run's sites are the phone's: sites the phone didn't sign, or signed by planque in its place, are `bad_step` before anything opens | `refusals.e2e.test.js` |
| An open off the run's sites is `site_not_listed`; plain http is `bad_url`; an address or a local name is `address_private`; the site is never reached | `refusals.e2e.test.js` |
| A click on a link off the run's sites is fenced in the tab: the other site never gets the request | `refusals.e2e.test.js` |
| The fence on every other way off the run's sites, with nothing reaching `other.test`: a 302 redirect (the open is `site_not_listed`; Chrome's error page stays, and a read of it is refused), a form that posts there, a script's `location.assign()` and a `target=_blank` link (the taf's click is `bad_url`, the move is recorded as `navigate`/`site_not_listed` and the tab is on the run's blank page; no new tab opens), a meta refresh (the same record and blank page), and a frame (blocked; the page reads as usual) | `fence.e2e.test.js` |
| A run's tab has its three session rules (block every page and frame, allow the run's sites, block `http`) while the run lives; planque's stop and the taf's close take them away, and another run's stay; unpairing (4410) leaves none at all | `fence.e2e.test.js` |
| A password in a frame of the same site or of another of the run's sites, and the frames' words, are in no read: only the top document is read | `masking.e2e.test.js` |
| The page's own script sees nothing of the isolated world: no `TafsPage`, `TafsMask` or `TafsBar`, no extension API, only the dialog guard's own mark, and a read leaves the page's DOM as it was | `masking.e2e.test.js` |
| A run started from the side panel joins the person's tab only when its first open asks for exactly the address it shows: then it's read in place (loaded once, by the person), grouped and fenced; another address opens a tab of the run's own and leaves theirs alone; the person's Stop gives it back out of the group, unfenced, still open | `joined.e2e.test.js` |
| Once joined, an open of another address goes to a tab of the run's own in the same group, and the person's tab keeps its address; an open of their address reads it in place again | `joined.e2e.test.js` |
| The taf's acts in the joined tab stay inside the run's sites: a link on them is followed, a form to another site is fenced (the tab goes to a blank page, nothing reached) | `joined.e2e.test.js` |
| A step of another agent run gives the joined tab back unread (`no_page`): out of the group, its rules gone, still at its address | `joined.e2e.test.js` |
| planque's `stop` ends the run: planque hears `stopped`, signed; the next step is `stopped`; the tab stays | `runs.e2e.test.js` |
| `close` ends the run and leaves its tab; the same taf's next run closes it | `runs.e2e.test.js` |
| The panel's Pause holds the run's steps (`paused_by_owner`) until Resume, and planque hears both, signed | `runs.e2e.test.js` |
| The person's own click in a run's tab pauses it, and the taf's next act doesn't reach the page | `runs.e2e.test.js` |
| A hand-over is answered at once as `handed`; the panel shows the taf's words; the person's Done reaches planque signed, its page without any field's value | `runs.e2e.test.js` |

## Not covered yet

- **Sensitive sites** and the phone's signed allowance (`sensitive_allowed`): the fixture sites would need a host the
  sensitive rules match.
- **A step's own refusals of a payment or a checkout** (`payment_step`), and `press` and `scroll`: covered by the unit and
  page tests only. `back` is covered only as a known bug (below).
- **The relay's limits:** a dropped connection opened again, a result cut to fit 120 KB, `page_too_large`, and a step
  replayed after Chrome stops the service worker.
- **The floating bar**, and a joined tab followed while the panel is open or after it closes; **Coach's own send**
  (`coach_send`) as the start of a joined run (the tests start one from a taf's chat, `taf_send`).
- **A frame's own reads:** the extension reads no frame, so there is nothing more to mask in one; Coach's page read
  (`aside.js`) in a frame isn't tested either.
- **Push:** the test build has no push key, so the extension never subscribes.
- **Coach and the KIFF device** in the side panel: they need tafs cloud and KIFF.
- **Chrome as people install it:** the tests load the unpacked copy; the Web Store's build and its manifest hash are checked
  by the release, not here.

## Known bugs, as `todo` tests

These tests say what the extension should do and fail today; `node --test` reports them as `todo`, not as failures.

- **A run's first open can fail under load** (`races.e2e.test.js`). `Runner.open` navigates the run's new tab before its
  `about:blank` has committed; when the tab's renderer is slow, that blank page commits after the navigation began and aborts
  it (`net::ERR_ABORTED`), the tab is left "unloaded" on `about:blank`, `loaded()` waits its whole 30 s, and the open answers
  `bad_url`. The test makes the renderer slow on purpose (one renderer process, a page busy for two seconds). This is what made
  a test's first open fail now and then in a loaded run of the whole suite, among them "the person's own click in the run's tab
  pauses the run": that test itself has no race (the content script's listener is in the page before `open` answers).
- **The person closing a run's last tab leaves its three rules behind** (`fence.e2e.test.js`): the run ends, but the closed
  tab's session rules stay until the service worker starts again; unpairing doesn't clear them either.
- **`back` after the taf's own click skips the page it clicked on** (`steps.e2e.test.js`, `joined.e2e.test.js`): in a run's own
  tab it does nothing and answers with the same page; in a joined tab it goes to the person's page before.
- **After a step of another agent run gives the joined tab back, the run's next open fails** (`joined.e2e.test.js`): the run's
  group ended with the tab leaving it, and the open answers `browser_unavailable` and closes the run.

