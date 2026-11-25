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
  them takes about a minute and a half.

## The harness

| File | What it is |
|---|---|
| `e2e/build.mjs` | The test build: a copy of `chrome/` in a temporary folder (without `test/`, `e2e/` and `store/`). Only the copy's `config.js` changes: it gains a `test` environment (the fake planque's `api` and `relay` on 127.0.0.1, its test step key, no push key, no screenshots) and `ENV = "test"`. The copy's manifest also lists `http://127.0.0.1/*`, so the service worker can reach the fake planque. |
| `e2e/planque.mjs` | The fake planque: the pairing routes and the relay on one port of 127.0.0.1, and the paired phone. Also the fake tafs cloud and KIFF (`FakeCloud`), for the tests of a run started from the side panel. |
| `e2e/sites.mjs` | The fixture sites: one HTTPS server, with a certificate made at the start, that answers by host name. |
| `e2e/harness.mjs` | Starts all of it, opens the side panel, pairs, and gives the tests their helpers. `start({lang})` starts Chromium in another language (`--lang` and the environment's `LANGUAGE`), so the extension's words can be checked in it; English by default. |
| `e2e/joining.mjs` | What the tests of a run that joins the person's tab share: the person's tab, a send to the taf from the side panel with it in front, the person's Stop, and a record of every move the extension makes of a tab (`watchMoves`). |
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
`target=_blank` link and a frame; a page with a password in a frame of its own site and another in a frame of `news.test`; and
the person's own page, with words and a field's value only it holds and a form that posts to its own site, open in their tab
before a run joins it) and `other.test`, which no grant lists (it has the person's own page too). The server logs every request (`hits`, and `posts`), so a test can show `other.test`
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
- **What the extension does to a tab** is recorded the same way, from the test: `watchMoves` wraps the worker's own
  `chrome.tabs.update`, `goBack` and `goForward`, so a test can show the person's tab was never sent anywhere. The extension's code
  is as it ships; a worker that starts again starts without the wrapper.
- **Chrome's own limits and events** are reached the same way: `fillSessionRules` fills Chrome's limit on session rules with rules
  no tab names, so the next fence is refused as it would be at that limit; and a test stops the service worker as Chrome stops it,
  through the DevTools protocol (`ServiceWorker.stopWorker`, with its version id), never `chrome.runtime.reload`, which would
  reload the whole extension.
- **The back/forward cache:** Playwright starts Chromium with it off (`--disable-back-forward-cache`); `start({bfcache: true})`
  leaves it on, as people have it, and a page asked for with `?bfcache=1` is served so Chrome may keep it there.

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
| A run's first open succeeds even when its new tab is slow to show its blank page (`blankReady`) | `races.e2e.test.js` |
| `read` gives the text, fields with refs, options and controls; a password field is `masked`, and its value is nowhere in a result | `steps.e2e.test.js` |
| A long page's text comes in windows (`more`, `next_offset`, `offset`) | `steps.e2e.test.js` |
| `act` fills, selects and ticks in the real page, and reads it back; a fill of the password is `secret_field` and leaves it as it was; an act on another read is `page_changed` | `steps.e2e.test.js` |
| A page's `confirm()` doesn't block the run, is answered no, never reaches Chromium, and the read says the page asked | `steps.e2e.test.js` |
| `back` in a run's own tab, after the taf's click on a link, returns to the page it clicked on; with nothing before the run's first page it's `no_back` and nothing moves | `steps.e2e.test.js` |
| A step signed by another key, changed after signing, for another Chrome, expired, too far ahead, or sent twice (also under a new id) is dropped with no answer at all, not even a signed refusal, and nothing opens or loads; a good step after it is answered | `refusals.e2e.test.js` |
| A message with no id and no signature gets no answer | `refusals.e2e.test.js` |
| A run's sites are the phone's: sites the phone didn't sign, or signed by planque in its place, are `bad_step` before anything opens | `refusals.e2e.test.js` |
| An open off the run's sites is `site_not_listed`; plain http is `bad_url`; an address or a local name is `address_private`; the site is never reached | `refusals.e2e.test.js` |
| A click on a link off the run's sites is fenced in the tab: the other site never gets the request | `refusals.e2e.test.js` |
| The fence on every other way off the run's sites, with nothing reaching `other.test`: a 302 redirect (the open is `site_not_listed`; Chrome's error page stays, and a read of it is refused), a form that posts there, a script's `location.assign()` and a `target=_blank` link (the taf's click is `bad_url`, the move is recorded as `navigate`/`site_not_listed` and the run's own tab is on its blank page; no new tab opens), a meta refresh (the same record and blank page), and a frame (blocked; the page reads as usual) | `fence.e2e.test.js` |
| The fence covers navigations only, after a taf's fill, in a tab of the run's own and in the person's joined tab. A page's own `fetch`, XHR, `sendBeacon`, `<img>` and WebSocket reach `other.test`: they're the site's own. A form submitted into a frame is fenced, and nothing reaches `other.test`. A `postMessage` to an off-site frame reaches nobody in a run's own tab (the frame was fenced before it loaded). In the person's tab, the frame loaded before the run joined, and it sends the value on with a fetch of its own. With no run, the same pages send the value by all seven ways, so the fenced cases aren't empty | `requests.e2e.test.js` |
| A sensitive site of the grant (`bank.test`: its "bank" label makes it one) opens only on the paired phone's own signed allowance (`sensitive_allowed`). With none, or with one signed by planque's key instead, the open is `site_blocked`, and the bank is never asked for anything. The person's "Not on this site", switched on mid-run, makes the run's next read of their joined tab `site_blocked`, with nothing read, and their next send from the panel goes without the page. A step replayed after Chrome stopped the service worker gets no answer, and the page isn't opened again. A click whose words say only "Continue" is `payment_step`, and nothing is ordered, on each of: a submit in a schema.org Order, a payment provider's button, the submit of a form with a card field, and a button on a page with a provider's payment frame | `gaps.e2e.test.js` |
| Chrome's own fence says what `refuseURL` says (0.2.19). Four kinds of host are blocked by Chrome before anything is asked of them: a bank label under a granted site (`bank.shop.test`), `accounts.google.com` under a `google.com` grant, a site under a listed `github.io`, and an address (`127.0.0.1`). Each is tried by an open, by the taf's click on a link, and by a 302 from a granted page, in a tab of the run's own and in the person's joined tab. Chrome already holds a connection to each target, since the person visited it first and that visit is checked to have reached it. The server logs no request for any of them, and no result carries the target's page. An open answers `refuseURL`'s code: `site_blocked`, `site_not_listed` or `address_private` | `fence219.e2e.test.js` |
| What leaves for planque when a page is too large, or the relay is down. A read over the relay's 120 KB is cut to fit, says so (`truncated`), and no cut carries the password field's value, a card number in the page's text, or what the person typed. A hand-over's Done pressed while the relay is down reaches planque once the relay is back: once, signed, without what the person typed. A Done whose page is over the cap goes with no page | `leaving.e2e.test.js` |
| A wrong action, and a stale display: what the rest of the ranked gaps covers. A disabled button's click is `disabled`, an option that isn't there is `option_unknown`, and `check` on a radio chooses it. planque's unsigned `stop` mid-act, while the outline is up, makes the act answer `stopped` with nothing reaching the page, and planque hears `stopped`, signed. A stop with a hand-over open answers it `declined`, and a stop for a run this Chrome doesn't hold changes nothing. A "leave this page?" handler doesn't hold the taf's next open, and no dialog reaches Chromium. A content script's message of a panel's kind changes nothing: `stop`, `pause`, `aside_site`, `page_withdraw`, `unpair`, `taf_send`. Nor does `owner_input` from a tab of no run. After the person takes their joined tab back, the floating bar there no longer says it's the run's tab (`here: false`, read where `TafsBar.show` is given it), and the badge counts the run until it ends, then clears | `gaps2.e2e.test.js` |
| A run's tab has its three session rules (block every page and frame, allow the run's sites, block `http`) while the run lives; planque's stop and the taf's close take them away, and another run's stay; unpairing (4410) leaves none at all | `fence.e2e.test.js` |
| The person closing a run's last tab ends the run, and the closed tab's rules go with it | `fence.e2e.test.js` |
| A fence Chrome refuses (its limit on session rules reached) loads nothing: a run's first tab, or a further tab of a run in the person's tab, closes before its page is asked for, and the step is `browser_unavailable` | `fence.e2e.test.js`, `joined.e2e.test.js` |
| No fence outlives the service worker: stopped as Chrome stops it and woken by the panel, the worker clears every session rule as its Runner is made, and both the person's joined tab and a run's own tab load `other.test` | `worker.e2e.test.js` |
| A password in a frame of the same site or of another of the run's sites, and the frames' words, are in no read: only the top document is read | `masking.e2e.test.js` |
| The page's own script sees nothing of the isolated world: no `TafsPage`, `TafsMask` or `TafsBar`, no extension API, only the dialog guard's own mark, and a read leaves the page's DOM as it was | `masking.e2e.test.js` |
| A run started from the side panel joins the person's tab only when its first open asks for exactly the address it shows: then it's read in place (loaded once, by the person), grouped and fenced; another address opens a tab of the run's own and leaves theirs alone; the person's Stop gives it back out of the group, unfenced, still open, and the panel lists none of the run's tabs at once | `joined.e2e.test.js` |
| Once joined, an open of another address goes to a tab of the run's own in the same group, and the person's tab keeps its address; an open of their address reads it in place again | `joined.e2e.test.js` |
| The taf's acts in the joined tab stay inside the run's sites: a link on them is followed; a form to another site is fenced, with nothing reached, the move recorded, and the person's tab never blanked or moved by the extension (no `tabs.update`, `goBack` or `goForward`) | `joined.e2e.test.js` |
| In a joined tab, the taf's `back` never goes before the address it joined at: with the run not having moved it, `no_back` and nothing moves; after the taf's click, its answer is never the person's earlier page; a read and an act there carry none of that page's words or field values, and post nothing (the person's earlier page on the run's sites, and off them) | `joined.e2e.test.js` |
| A step of another agent run gives the joined tab back unread (`no_page`, and an act too): out of the group, its rules gone, still at its address, nothing moved | `joined.e2e.test.js` |
| After the joined tab is given back (Chrome ends the run's emptied group), the run's next open goes on in a tab of its own, in a group made again, fenced | `joined.e2e.test.js` |
| planque's `stop` ends the run: planque hears `stopped`, signed; the next step is `stopped`; the tab stays | `runs.e2e.test.js` |
| `close` ends the run and leaves its tab; the same taf's next run closes it | `runs.e2e.test.js` |
| The panel's Stop: the state the panel reads lists none of the stopped run's tabs as soon as the Stop is answered | `runs.e2e.test.js` |
| The panel's Pause holds the run's steps (`paused_by_owner`) until Resume, and planque hears both, signed | `runs.e2e.test.js` |
| The person's own click in a run's tab pauses it, and the taf's next act doesn't reach the page | `runs.e2e.test.js` |
| A hand-over is answered at once as `handed`; the panel shows the taf's words; the person's Done reaches planque signed, its page without any field's value | `runs.e2e.test.js` |
| In a run's own tab, the person's click on a link pauses the run, and its next read and act are `paused_by_owner`, with nothing of the page | `owntabs.e2e.test.js` |
| The person drags the run's own tab out of its group: it stays the run's, fenced, its own page read, an open off the run's sites still `site_not_listed` | `owntabs.e2e.test.js` |
| The person drags a tab of theirs into the run's group: it never becomes the run's (not in its tabs, not fenced), an open of its exact address goes to the run's own tab and nothing they typed in theirs is in a result; the run's Stop and the taf's next run leave it open | `owntabs.e2e.test.js` |
| Two runs of two tafs (two grants) at once: a tab and a group each; a read of one is `page_changed` in the other; each fill lands in its own tab only; one's close and its taf's next run leave the other's tab, group and fence as they were | `owntabs.e2e.test.js` |
| The side panel after a refusal of a page that isn't the run's: `given_back` (the person moved the tab: one of theirs the run joined, or one of the run's own), `page_changed` (an act on an old read) and `no_back` (the taf's `back`), in a joined tab and in a tab of the run's own. Each is one new line in the run's card, "<step> · not done (<code>)", drawn as refused, with no Your turn left; after `given_back` the tab is in none of the run's tabs and the panel's head no longer marks the taf as the one whose tab is in front; the card's site stays the run's, and the person's own move on the run's sites draws no line. The run's next open after `given_back` is one plain line, in a tab of its own | `panel.e2e.test.js` |
| The side panel says why a step wasn't done in plain words (0.2.18), never by its code. All seven codes with a line of their own (`given_back`, `page_changed`, `no_back`, `site_not_listed`, `paused_by_owner`, `no_page`, `browser_unavailable`) are made to happen in one real run. Each shows in the run's card as "<the step> · <its line>", word for word from the locale, with Chromium started in English and again in French (`start({lang})`). No line of the card names a code, and no locale's line names its code. `paused_by_owner` is the person's Pause while the taf's outline shows: a step that arrives already paused is refused before it's recorded, so the card says "Paused" and draws no line for it | `panelwords.e2e.test.js` |
| Chrome's back/forward cache is on when a test asks for it (the person's Back case below runs with it on and off) | `bfcache.e2e.test.js` |

## Not covered yet

- **The minute alarm's idle sweep** (a run nobody sends a step to for 150 s ends): it needs that much real time per test, so
  only the runner's unit tests cover it.
- **A redirect or a joined tab's move to a sensitive site of the grant** the phone didn't allow: only the taf's click in a
  tab of the run's own is tested (see the known bugs).
- **A checkout's own refusals by its words and totals** (`checkout`), and `press` and `scroll` as acts of their own: covered by
  the unit and page tests only. The refusals by a page's semantics (`places_order`, `payment_frame`) are in `gaps.e2e.test.js`.
- **The relay's limits:** `page_too_large` (a page no cut makes fit). A dropped connection opened again, and a result cut to
  fit 120 KB, are covered (`leaving.e2e.test.js`). A step replayed
  after Chrome stops the service worker is covered (`gaps.e2e.test.js`). The worker's restart is covered for its fences and that
  replay, woken by the panel; its
  wake by the relay's alarm isn't.
- **A pop-up whose fence Chrome refuses** (a run's page opening a tab on its sites at the session rules' limit): the fixture sites
  have no such link; the run's first tab and a further tab are covered.
- **A page off the run's sites restored from the back/forward cache in a joined tab**: Chrome flushes that cache as the fence's
  rules are added, so the person's Back off the run's sites is always a new request (see the known bugs); the give-back on such a
  commit can't be reached from a test.
- **The floating bar**, and a joined tab followed while the panel is open or after it closes; **Coach's own send**
  (`coach_send`) as the start of a joined run (the tests start one from a taf's chat, `taf_send`). That the bar is hidden when
  the person's tab is given back is not checked.
- **A frame's own reads:** the extension reads no frame, so there is nothing more to mask in one; Coach's page read
  (`aside.js`) in a frame isn't tested either.
- **A real drag of a tab:** headless Chromium has no tab strip, so the person's drag in or out of a run's group is made with
  Chrome's own `tabs.group` and `tabs.ungroup`, called from the service worker. It changes Chrome's tabs as a drag does, but no
  drag event is seen. A drag of a run's tab to another window isn't tested.
- **Push:** the test build has no push key, so the extension never subscribes.
- **Coach and the KIFF device** in the side panel: they need tafs cloud and KIFF.
- **Chrome as people install it:** the tests load the unpacked copy; the Web Store's build and its manifest hash are checked
  by the release, not here.

## Bugs these tests found that are fixed

- **A run's first open failed now and then under load** (`races.e2e.test.js`). `Runner.open` navigated the run's new tab
  before its `about:blank` had committed. When the tab's renderer was slow, that blank page committed after the navigation
  began and aborted it (`net::ERR_ABORTED`), and the open waited 30 s, then answered `bad_url`. The test makes the renderer
  slow on purpose (one renderer process, a page busy for two seconds). The fix is `Runner.blankReady`: the new tab shows its
  blank page before the step's page goes into it. This was also what made "the person's own click in the run's tab pauses
  the run" fail now and then; that test itself has no race.
- **The person closing a run's last tab left its three rules behind** (`fence.e2e.test.js`): the run ended, but the closed tab's
  session rules stayed until the service worker started again. The tab is unfenced as it closes now.
- **`back` after the taf's own click did nothing in a run's own tab** (`steps.e2e.test.js`): Chrome skips, on back, a history
  entry left without a person's gesture. `back` now goes to the page before in the run's own record of the tab, and loads it
  when Chrome's back lands elsewhere.
- **After a step of another agent run gave the joined tab back, the run's next open failed** (`joined.e2e.test.js`): the run's
  group ended as the tab left it, and the open answered `browser_unavailable` and closed the run. A group Chrome ended is made
  again now.

- **The person's own Back to a page they had before the run, on the run's sites, was read by the run** (`joined.e2e.test.js`).
  The run joined their tab at page B; their Back to page A committed on the run's sites and was taken as the run's own work: the
  run's next read answered with A, its words and its fields' values, and an act on that read posted A's form. Now only the
  run's own act, while it waits for its page, moves a joined tab; any other commit there is the person's, and the tab is given
  back at once: the run's next read and act answer `given_back`, and nothing of A is read.
- **The person's own Back off the run's sites wasn't given back** (`joined.e2e.test.js`, `bfcache.e2e.test.js`). The joined
  tab's fence blocked their Back (Chrome flushes its back/forward cache as the fence's rules are added, so the Back is always a
  new request): Chrome's error page at their address, the tab still the run's, fenced. A failed navigation in a joined tab
  (`webNavigation.onErrorOccurred`) now gives it back, on Chrome's block page; their next try (a reload) loads, as the fence has
  gone. The run's next read and act answer `given_back`.
- **An off-site click or submit in the joined tab replaced the person's page with Chrome's error page**
  (`joined.e2e.test.js`): the fence stopped the request, but Chrome committed its "blocked" page at the other site's address,
  with a new history entry. An act whose link or form leaves the run's sites is now refused in the page before it fires
  (`site_not_listed`), so the person's page stays as it was.
- **The taf's `back` in a joined tab, after its own click, loaded the person's earlier page** (`joined.e2e.test.js`): Chrome
  skipped the joined page on back and asked for the page the person had before the run. `back` in a joined tab is now always
  `no_back`, and nothing moves.
- **An address the person typed in a run's own tab, on the run's sites, was read by the run** (`owntabs.e2e.test.js`):
  `onCommitted` took every commit on the run's sites in a tab of the run's own as the run's. Since 0.2.16 only the run's own
  work moves its tab; the person's move gives the tab to them, and the run's next read and act answer `given_back`.
- **The person's Back or Forward in a run's own tab: the run read the page they landed on** (`owntabs.e2e.test.js`): the same
  cause; the transition now decides, never the trail.
- **An address the person typed in a run's own tab, off the run's sites, sent their tab to a blank page** (`owntabs.e2e.test.js`):
  the runner blanked a tab of its own on any off-site navigation. It now does that only for the run's own work; the fence's stop
  gives the tab to them, on Chrome's block page, and their next try loads.
- **The extension asked for a blocked address again itself** (`owntabs.e2e.test.js`, `joined.e2e.test.js`, two fixture pages
  `/leave-later` and `/leave-slowrefresh`): after giving a tab back on a fence block, it reloaded the blocked address or loaded it
  again. A navigation a page starts by itself off the run's sites after a step returned looks the same, so what the taf typed could
  leave, sent by the extension. Since 0.2.17 it never asks for a blocked address again: other.test is asked for nothing, the value
  is in no request, and the tab is given back on Chrome's block page, in a tab of the run's own and in a joined one.
- **A second taf of the same name, under another grant, closed the first taf's kept tab** (`owntabs.e2e.test.js`): a taf's
  finished run is now kept by its grant (`doneKey`), not its name.

- **The person's own move off the run's sites showed in the run's card as the taf's, and the card said the taf was there**
  (`panel.e2e.test.js`): an address the person typed off the run's sites was recorded as a step of the run's (twice in a joined
  tab), and `record()` took the run's site from it. Since 0.2.17 a move the person made is never one of the run's steps, and the
  run's site comes only from its own pages.

**The fence's sensitive-site patterns are ones this Chromium holds** (`fence.e2e.test.js`, 0.2.19): one pattern with every word
was refused (`isRegexSupported`: `memoryLimitExceeded`), which would fail the fence of every run, so the words are split over a few,
and each is checked in real Chromium. A fenced tab holds `RULES_PER_TAB` rules here (harness.mjs: runner.js `FENCE_RULES`, less the
allowance's, as no grant here allows a sensitive site).

**A link with user info to the bank** (`gaps.e2e.test.js`, `/to-bank`'s "Sign in at your bank", 0.2.19): `https://x@bank.test/`
slipped past the label patterns; the fence's user-info rule stops it, with a warm connection to the bank, which logs nothing.

**Chromium runs in English** (`--lang=en-US`, harness.mjs), whatever the computer's language: the panel's lines are checked word for word.

## Known bugs, as `todo` tests

The "still open" checks after a Stop and after the taf's next run wait for the runner to have let the run's tabs go (its state, and
Chrome no longer having the last run's own tab), bounded at 5 s, not for a fixed time: a close that comes late fails them.

A test that says what the extension should do and fails today is marked `todo`; `node --test` reports it as `todo`, not as a
failure.

None.

Fixed in 0.2.20 (`gaps2.e2e.test.js`): **a page's `alert` or `confirm` as it loads reached Chromium as a real dialog**: a run's
own tabs now load under a sandbox with no `allow-modals`, a response header Chrome adds for that tab alone, so Blink drops the
call from the page's first byte (the at-load confirm test now asserts the page's own answer is no and nothing reached Chromium;
the read can't say the page asked). **A field in a disabled `<fieldset>`, a read-only field and a disabled `<option>` took the
taf's act**, and **`unchecked` on a checked radio answered ok**: refused now (`disabled`, `bad_step`). Added: on one site in one
run, the person's own tab still gets its real confirm at load; a frame that sets `top.location` (as a 3-D Secure frame does)
still works; and two findings, logged rather than asserted: the person's own typed address in a run's own tab may still load
sandboxed (the sandbox comes off as their navigation starts, but Chrome doesn't wait), and a page the run loaded stays sandboxed
after the run lets its tab go.

Fixed in 0.2.19: **a link to an address, clicked in the person's joined tab, answered `site_not_listed`**
(`fence219.e2e.test.js`): `page.js` `offFence()` had no branch for an address, a local name, user info or a name not in ASCII.
It now follows `refuseURL`'s order and gives its very code (`address_private` here), which a page test checks address by
address.

Fixed in 0.2.19: **the taf's click on a link to a sensitive site of the grant that the phone didn't allow** asked that
site for its page, because the fence allowed every site of the grant and only `onBeforeNavigate` raced the request. The fence's
rules now say what `refuseURL` says, so Chrome stops the request before it's sent, and the act answers `site_blocked`
(`gaps.e2e.test.js`).
