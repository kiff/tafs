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
  them takes about half a minute.

## The harness

| File | What it is |
|---|---|
| `e2e/build.mjs` | The test build: a copy of `chrome/` in a temporary folder (without `test/`, `e2e/` and `store/`). Only the copy's `config.js` changes: it gains a `test` environment (the fake planque's `api` and `relay` on 127.0.0.1, its test step key, no push key, no screenshots) and `ENV = "test"`. The copy's manifest also lists `http://127.0.0.1/*`, so the service worker can reach the fake planque. |
| `e2e/planque.mjs` | The fake planque: the pairing routes and the relay on one port of 127.0.0.1, and the paired phone. |
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
- Every other route answers 404, and `POST /v1/extension/unpair` 204. The extension's claim of its view-only KIFF device goes
  to `api.kiff.dev`, which the test Chromium can't resolve, so it fails at once and does nothing.
- The relay is a small WebSocket server of its own (text messages, ping, pong and close), so the harness needs no package
  beyond Playwright.

**The fixture sites** are `news.test` (an article, and a long page whose text comes in windows), `shop.test` (a form with
a text field, a select, a checkbox and a password field holding a known value; a page that asks `confirm()`; a page with
a link to `other.test`) and `other.test`, which no grant lists. Chromium reaches them through
`--host-resolver-rules=MAP *.test 127.0.0.1, MAP * ~NOTFOUND, EXCLUDE 127.0.0.1`, with `--ignore-certificate-errors`
and `--no-proxy-server`. Their URLs carry the server's port (`https://shop.test:43127/form`): the extension's site rules
and Chrome's fence read the host alone. The grant's sites are `shop.test` and `news.test`.

**Pairing is done as the owner does it.** The harness opens the side panel with a click (`chrome.sidePanel.open` needs one),
types the fake phone's code into the panel's form and submits it; the panel sends `pair`, then `poll`, and the extension
connects. The service worker takes these messages only from the extension's own pages outside a tab, and Playwright lists
no side panel among its pages, so the harness reaches the panel through Chromium's DevTools protocol. The same way, the
tests press the panel's Pause, Resume and a hand-over's Done (`pause`, `resume`, `hand_answer`).

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
| A step signed by another key, changed after signing, for another Chrome, expired, too far ahead, or sent twice (also under a new id) is `bad_step`, signed, and nothing opens or loads | `refusals.e2e.test.js` |
| A message with no id and no signature gets no answer | `refusals.e2e.test.js` |
| A run's sites are the phone's: sites the phone didn't sign, or signed by planque in its place, are `bad_step` before anything opens | `refusals.e2e.test.js` |
| An open off the run's sites is `site_not_listed`; plain http is `bad_url`; an address or a local name is `address_private`; the site is never reached | `refusals.e2e.test.js` |
| A click on a link off the run's sites is fenced in the tab: the other site never gets the request | `refusals.e2e.test.js` |
| planque's `stop` ends the run: planque hears `stopped`, signed; the next step is `stopped`; the tab stays | `runs.e2e.test.js` |
| `close` ends the run and leaves its tab; the same taf's next run closes it | `runs.e2e.test.js` |
| The panel's Pause holds the run's steps (`paused_by_owner`) until Resume, and planque hears both, signed | `runs.e2e.test.js` |
| The person's own click in a run's tab pauses it, and the taf's next act doesn't reach the page | `runs.e2e.test.js` |
| A hand-over is answered at once as `handed`; the panel shows the taf's words; the person's Done reaches planque signed, its page without any field's value | `runs.e2e.test.js` |

## Not covered yet

- **Sensitive sites** and the phone's signed allowance (`sensitive_allowed`): the fixture sites would need a host the
  sensitive rules match.
- **A step's own refusals of a payment or a checkout** (`payment_step`), and `press`, `scroll` and `back`: covered by the
  unit and page tests only.
- **The relay's limits:** a dropped connection opened again, a result cut to fit 120 KB, `page_too_large`, and a step
  replayed after Chrome stops the service worker.
- **A run that works in the person's tab** (a run started from the side panel, "A taf works where you are"), and the
  floating bar.
- **Push:** the test build has no push key, so the extension never subscribes.
- **Coach and the KIFF device** in the side panel: they need tafs cloud and KIFF.
- **Chrome as people install it:** the tests load the unpacked copy; the Web Store's build and its manifest hash are checked
  by the release, not here.
