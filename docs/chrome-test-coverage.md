# The Chrome extension's tests: what they prove, and what they don't

This page lists everything a taf can make the tafs extension for Chrome do, every rule the extension holds while it does
it, and which test proves each one. Where nothing proves a behaviour, it says so. The **Gaps** section is the to-do list
for an end-to-end harness: the extension loaded in a real Chromium, driven by signed steps, with no model and no real
planque or KIFF.

It is written from the code on `main` at Chrome 0.2.13 (`chrome/manifest.json` `version`), which includes the joined
tab (0.2.12) and the guard that gives the person's tab back when a step of another agent run reaches it (0.2.13,
`Runner.giveBackJoined`). The contract it checks against is `docs/chrome.md` in this repository. The extension's code is
in `chrome/src/`, `chrome/sidepanel/` and `chrome/manifest.json`; its tests are in `chrome/test/`.

## 1. How the extension is tested today

`cd chrome && npm test` runs `node --test test/*.test.js`: 269 tests in 18 files. CI runs the same command
(`.github/workflows/chrome.yml`, on every change to `chrome/**`, `docs/chrome.md` or the workflow), on Linux with Node 22,
after installing `playwright@1.63.0` and its Chromium. A second workflow, `.github/workflows/chrome-package.yml`, runs the
Python checks in `chrome/store/` (`test_manifest.py`, `test_package.py`, `test_icons.py`) and builds the store zip twice to
check it is the same bytes.

The tests work at four levels. Each proves something different.

| Level | What it is | Files | What it proves | What it can't prove |
|---|---|---|---|---|
| **unit** | Pure functions called with plain values: signatures, the step check, site rules, masking, the guard, pairing proofs, vectors shared with the phones | `core`, `protocol` (most), `vectors`, `hand`, `runstate`, `push`, `attach`, `chatmd`, `coach`, `coachchat`, `kiff`, `pageconsent` (most), `panels` (`joinRefusal`), `shot` (`canCapture`, `encode`) | The rule as written: what is refused, with which code, under which label | That anything calls the rule, or calls it at the right moment |
| **simulated** | The real `Runner`, `Relay`, `Panels` and `Shots` driven against a fake Chrome API (`fakeChrome` in `runner.test.js`) or a fake WebSocket (`FakeSocket` in `protocol.test.js`), which record every call and answer what the test says | `runner`, `protocol` (the relay), `panels`, `shot` (`Shots.capture`, `send`), `core` (`readForCoach`) | The extension's own order of calls: fenced before loaded, the guard before the page is touched, which tab ids are touched, what is answered and signed | What Chrome does with those calls: whether a rule blocks, a group forms, an event fires, a port closes, a worker restarts. The fake page answers whatever the test sets; `page.js` never runs here |
| **real Chromium page** | `mask.js`, `page.js`, `bar.js`, `aside.js` and `dialogs.js` added to a page in a real Chromium by Playwright (`page.test.js`, 32 tests). Pages are served by `ctx.route` at `https://shop.example/` | `page` | What a read sees and masks in a real DOM, what an act does to real elements, the screenshot covers, the bar's shadow root, trusted versus untrusted events | The extension is not loaded. The scripts run in the page's own world (`addScriptTag`), not Chrome's isolated world. No service worker, no runner, no fence, no tab group, no relay |
| **source text** | A test reads a source file as text and matches a pattern in it | `pageconsent`, `panels`, `panel`, `store`, `protocol` (the runner's ops), `vectors` (the runner's ops) | That a line is still written as it was (`sw.js` wiring, the side panel's markup) | That the line runs, or does what it says |

- **Locally, the page tests skip** unless Playwright is installed (`npm install --no-save playwright@1.63.0 && npx playwright
  install chromium` in `chrome/`). In CI a missing Playwright fails the run, never skips it.
- **On a checkout with CRLF line endings** (Git's `core.autocrlf=true` on Windows), three source-text tests fail because
  their patterns match `\n`: `pageconsent.test.js` › "the worker reads the tab for Coach only through the page consent,
  and keeps the allow with the pairing", and two in `panel.test.js`. CI checks out with LF and passes them.
- **Shared with other parts of the repository:** `vectors/chrome-*.json` are checked by `vectors.test.js` and by the iOS
  and Android tests, `runtime/test/fixtures/one-time-codes.json` by `core.test.js` and tafs cloud, and
  `runtime/test/page-sites.test.ts` holds `chrome/src/sites.js`'s sensitive and mail lists equal to tafs cloud's.

**What does not exist:** no test loads the extension. Nothing in `chrome/`, `scripts/` or `.github/` launches Chromium
with `--load-extension`, uses `launchPersistentContext`, or opens a `chrome-extension://` page. `chrome/store/screenshots.cjs`
renders the side panel with a stub `chrome` object for the store's pictures; it is not a test. So no test runs `sw.js`
(it imports `chrome` globals and has no exports), and no test follows one signed step from the relay through the
runner into a real tab and back as a signed result.

## 2. Everything a taf can make the extension do

A taf acts only through planque. planque sends the extension signed steps over the relay; the extension answers each
with a signed result. The person acts through the side panel and the floating bar.

### 2.1 Steps (5 ops)

`protocol.js` `OPS` and `runner.js` `dispatch` take exactly these:

| Op | What it does | Answers |
|---|---|---|
| `open` | Opens `url` in the run's tab (the first `open` makes the tab, or joins the person's tab), waits for it to load, reads it | `page`, or an error |
| `read` | Reads the run's tab again; `offset` asks for a later window of the text | `page`, or an error |
| `act` | One act (2.2) on the read `read_id` names, then reads the page back | `page`, or an error |
| `hand` | Hands one thing to the person (`hand: {words, ref?}`); answered at once | `{handed: true}`, or an error. The person's Done or Not now is its own message, `hand_result` |
| `close` | Ends the run; its tabs stay | `page: null` |

### 2.2 Acts (7 kinds)

`guard.js` `KINDS`, then `page.js` `act`:

| Kind | Variants | Notes |
|---|---|---|
| `click` | a control, or a link | A link with `target` is followed in the same tab |
| `fill` | plain (value set once); `keys: true` (one character at a time, with key events) | Never submits. Only an `input` or `textarea` that isn't a checkbox or radio |
| `select` | an option by its words or its value | `option_unknown` when none matches |
| `check` | ticks; `value: "unchecked"` unticks | Only a checkbox or radio |
| `press` | `Enter`, `Escape`, `ArrowDown`, `ArrowUp` on a ref | `Enter` on a field in a form submits it (`requestSubmit`) unless the page cancelled the key; `Enter` on a control or link is held to the click rules |
| `scroll` | a ref into view; the page `down` (default) or `up` | |
| `back` | no ref | The tab goes back; the page there is read |
| any kind with `sealed` | | Always `secret_field`: the extension keeps no sign-in |

### 2.3 What the extension answers (22 step codes)

Every code a step can be answered with, from `protocol.js`, `relay.js`, `runner.js`, `guard.js`, `sites.js` and `page.js`:

| Code | Where | When |
|---|---|---|
| `bad_step` | relay, runner, guard, page | not planque's, not for this Chrome, expired or too far ahead, no sites, replayed, an unknown op; sites not the phone's or grown; a bad `offset`; a bad hand-over; a malformed act |
| `bad_url` | `refuseURL`, `joinedRefusal` | not https, user info, not a URL, a non-ASCII host; a joined tab no longer on https |
| `address_private` | `refuseURL` | an IP address in any form, or a local name |
| `site_not_listed` | `refuseURL`, navigation listeners | off the run's sites |
| `site_blocked` | `refuseURL`, `joinedRefusal` | Chrome's own pages, the Web Store, Google's account pages; a sensitive site without the phone's allowance; for a joined tab, a private tab, a mail or sensitive site, or one switched off |
| `no_page` | runner | a read, act or hand-over before any open; a joined tab that is gone |
| `page_changed` | runner, page | the act's read isn't the tab's current read |
| `ref_unknown` | guard, page | no such ref, or its element changed |
| `not_visible` | page | hidden, off screen, or under another element |
| `disabled` | page | the element is disabled |
| `option_unknown` | page | a select has no such option |
| `dialog_open` | page | the page raised a dialog since the read |
| `secret_field` | guard, page, runner | a password or one-time-code field; a key into one; any `sealed` act |
| `payment_step` | guard, page | a card field; a click or `Enter` that pays; any click on a checkout or payment page |
| `stopped` | runner | the run is stopped |
| `paused_by_owner` | runner | the person paused it, or touched its tab |
| `paused_failures` | runner | three failed acts in a row |
| `paused_no_progress` | runner | the same act, page and answer twice |
| `page_unresponsive` | runner | the page didn't answer in 20 s |
| `browser_unavailable` | runner | Chrome failed (a fence or group refused, a tab call threw) |
| `page_too_large` | relay | the answer is over 120 KB even cut |
| `handed` (`ok: true`) | runner | a hand-over taken |

There is no `needs_site_permission`: every https site is granted at install, so nothing in a run asks Chrome for one.
`docs/chrome.md`'s refusal table doesn't list `disabled`, `option_unknown` or `no_page` (see Gaps).

Answers to the person's own buttons (not steps): `no_run` (Go to its tab or a bar press for a run this Chrome doesn't
hold), `not_here` (Resume from another tab's bar), `bad_act` (an unknown bar press), `no_hand` (Done, Not now or Show
me with no hand-over open), and Show me's `shown: "field" | "tab"` with `why` (`page_changed`, `ref_unknown`,
`page_unresponsive`, `no_ref`).

### 2.4 What planque can send (14 messages)

`challenge`, `welcome` (with `connections` and `now`), `step`, `stop` (unsigned), `close` (with `code`), `ping`, `pong`,
`connections`, `push_kept`, `push_refused`, `hand_kept`, `hand_refused`, `run_state_kept`, `run_state_refused`. Anything
else before the welcome closes the connection. The extension sends `open`, `hello`, `result`, `hand_result`, `run_state`,
`push`, `ping` and `pong`.

### 2.5 What the person can do (25 actions)

| Where | Action | What happens |
|---|---|---|
| Side panel, a run's card | Pause, Resume, Stop | `runner.pause`, `resume`, `stop(…, "person")` |
| Floating bar | Go to its tab, Pause, Resume (own tab only), Stop | `Runner.barPress` |
| Panel head | A taf at work | `Runner.goTo` |
| The run's tab | Typing, clicking, scrolling | `owner_input`: pauses the run, unless a hand-over is open |
| The run's tab | Closing its last tab | The person's Stop |
| Hand-over card | Show me, Done, Not now | `showHand`, `answerHand("done" \| "declined")` |
| Coach's chat | Send (with the page), Send without the page, New conversation, Previous conversations, Archive, Put back | `coach_send`, `coach_thread`, `coach_chats`, `coach_archive` |
| A taf's chat | Send, pick a taf | `taf_send`, `taf_thread`, `taf_list` |
| Composer | Attach files (+), remove a file (×) | `attach`, then the panel uploads |
| Page consent screen | Allow, Not now | `page_allow`; Not now closes the screen |
| Settings or Coach's banner | Stop reading pages | `page_withdraw` |
| Coach's line | Not on this site, Read this site | `aside_site` |
| Pairing | Type the code | `pair`, then `poll` |
| Settings | Unpair this Chrome | `unpair` (signed `tafs-chrome/unpair/v1`) |
| (the panel, reading a taf's chat) | Turn over | `turn_over` → `Runner.endTurn` |
| (the service worker's minute alarm) | Idle sweep | `Runner.sweep` |
| (a send from the panel) | The joined tab | `Panels.sent` marks the run; its first step may join the tab in front |
| (the phone) | Unpair | planque closes with `4410` |

## 3. Rules and guarantees

The rules the extension holds, whatever planque sends. Each has rows in the coverage map.

1. **Steps:** signed by planque's pinned key (`config.js` `planqueKey`) under `tafs-chrome/step/v1`, for this `chrome_id`,
   `exp` not past and at most 30 s ahead with 60 s slack (5 s with planque's clock from the welcome), a nonce taken once and
   remembered 10 minutes in `chrome.storage.session`. Any field changed or added after signing, `agent_run` included,
   breaks the signature.
2. **Sites:** a run's first step carries the phone's `sites_signed` (`tafs-chrome/sites/v1` over `{chrome_id, grant,
   sites}`), or its grant is one a re-pairing carried. A run's sites never grow. A sensitive site needs the phone's
   `tafs-chrome/sensitive/v1` allowance. No grant names a public suffix.
3. **Gates:** `refuseURL` on every open, read, hand-over, navigation, commit and pop-up: https only, no address or local
   name, never `ALWAYS_BLOCKED`, only the run's sites, never a sensitive site unless allowed.
4. **The network fence:** three `declarativeNetRequest` session rules per run tab, set before it loads: block every page and
   frame request, allow the run's sites and allowed sensitive sites, block plain http. Rule ids from a counter; the first
   fence clears rules an earlier worker left. Removed when the run ends.
5. **Navigation:** a main-frame navigation off the sites goes to `about:blank` (`onBeforeNavigate`), so does a committed
   page (back-forward cache, `onCommitted`); a new tab off the sites is closed (`onCreatedNavigationTarget`); `about:blank`
   is never refused; a `target` is forced to `_self`; `window.open` opens nothing.
6. **Secrets never typed or read:** a password, card or code field is never filled, pressed into or read; a hidden input is
   never read; text is masked (cards by Luhn, keys by shape, labelled secrets, bearer tokens, one-time codes in four
   languages); link credentials and the page URL's query and fragment go.
7. **Payments:** a card field, a control or link that pays (words or semantics), `Enter` on a pay form, and any click on a
   checkout or payment page are `payment_step`.
8. **Dialogs:** in the page's world, alert, confirm, prompt and `window.open` stop blocking and are reported; confirm answers
   no; `beforeunload` handlers are dropped; an act after a dialog is `dialog_open`.
9. **Screenshots:** off in the build (`screenshots: false`). When on: one before each act, never a read; never on a sensitive
   site, a checkout, a payment frame, plain http, or a tab not in front; secret fields, secret text and every frame covered,
   or no picture; signed `tafs-chrome/screenshot/v1`.
10. **One step at a time** per run; Pause and Stop don't wait.
11. **Pauses:** three failed acts; the same act, page and answer twice; the person's input; the person's Pause. Resume
    resets the counts.
12. **Ends:** the idle sweep (150 s; 15 minutes paused; never with a step running or a hand-over open), the close step,
    planque's `stop`, the person's Stop, the last tab closed, the turn over (10 s quiet, not paused, nothing handed). Tabs
    stay; a taf keeps only its latest finished run's tabs; unpairing closes them all.
13. **The group and the bar:** "tafs · Rae is working" in orange, "· done" in grey 15 s after the end (at once for an end
    someone made); one group per taf per window; the bar in a closed shadow root, only trusted presses, Resume only on the
    run's own tab.
14. **Page consent:** Coach reads the tab in front only with the allow kept here for this person and tafs cloud's consent;
    nothing is read when the person sends alone or sends only files; only https, never the store, incognito, sensitive, mail or switched-off sites; checked again after the
    read.
15. **The joined tab (0.2.12, 0.2.13):** a mark per send (window, front tab, tafs cloud's `run`), taken once, 2 minutes,
    gone with the panel; planque's signed `agent_run` must match; the page consent first; only the active tab of the
    panel's window, not pinned, not in a group of the person's, held by no other run, on the run's sites and passing
    Coach's gate; checked again every step; Stop's five guarantees (ungrouped, unfenced, forgotten, bar gone, still open);
    never closed by the extension; a step of another agent run gives it back first.
16. **The worker's senders:** `owner_input` only from a tab; `bar` only from the extension's own content script; every
    other message only from the extension's own pages, never a tab; the panel port only from the extension's own page. The
    manifest has no `externally_connectable`, so no web page can message the worker.
17. **Hand-over:** words 1 to 280 characters, no control or direction character; the ref in the read; the read known; the
    page on the run's sites; one open per run; 30 minutes at most (`HAND_MAX_MS`); the person's typing pauses nothing;
    Done reads back without field values or editable text; signed `tafs-chrome/hand/v1`, kept until `hand_kept`.
18. **Run state to planque:** each Pause, Resume and Stop signed `tafs-chrome/run_state/v1`, newest per run, kept until
    answered; `stopped` is final.
19. **The relay:** speak first; hello and welcome proofs; nothing before the welcome; close codes `4401`, `4403`, `4410`
    (wipes once); reconnect backoff during a run; idle ping until `push_kept`; results over 120 KB cut, never an act answered
    as failed for its size.
20. **Pairing and what's kept:** the code's secret never sent; proofs bind both keys; the phone's signature checked (one or
    two requests); carried connections only as signed; KIFF's session kept only view-only; old saved sign-ins deleted on
    every start; unpairing wipes everything and closes every run's tabs.

## 4. Coverage map

Levels: **unit**, **simulated** (fake Chrome API or fake WebSocket), **real Chromium page** (Playwright, CI only),
**source text**. A test is written `file › "its exact name"`. A row with no test says **Not covered**.

### 4.1 Steps and their signature

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| A step runs only when planque signed it, for this Chrome, unexpired, new; a forged, relabelled or changed step is refused | `protocol.test.js` › "a step runs only when planque signed it for this Chrome, unexpired and new" | unit | Covers every `checkStep` reason |
| `agent_run` changed or added after signing is refused | `protocol.test.js` › "a step's agent_run is planque's signed word: taken through the check, and refused when added or changed after signing" | unit | |
| Clock skew: 60 s slack, 5 s with the welcome's `now`; the offset only within 2 minutes | `protocol.test.js` › "a clock a little off still runs a step, by planque's time when the welcome carries it and by a fixed slack when not"; "planque's time in the welcome moves the clock only a little, and only when it is a number"; "the clock slack's values are pinned: a wider one is a decision, not a drift" | unit | |
| A nonce is still refused after the worker restarts | `protocol.test.js` › "a step's nonce is still refused after the service worker restarts" | unit | With a fake store, not `chrome.storage.session` |
| The runner's ops and `checkStep`'s are the same set | `protocol.test.js` › "every op the runner runs is an op a signed step may carry"; `vectors.test.js` › "every step planque signs is taken by checkStep, and its ops are exactly the runner's" | source text, unit | |
| A hand-over step is checked like any step | `protocol.test.js` › "a hand-over step signed by planque is taken like any other step" | unit | |
| A refused step with an `id` is answered `bad_step`, signed, and never reaches the runner | `protocol.test.js` › "the relay takes steps only after both ends proved themselves" | simulated | A forged signature with an `id` gets a signed `bad_step` (see Gaps) |
| A result signs `{id, nonce, chrome_id, ok, page, error}`; it can't be filed for another step or Chrome | `protocol.test.js` › "each answer, hello and welcome is signed for its purpose"; `vectors.test.js` › "the extension's answers sign exactly the vector's bodies: a page, a refusal, handed, and the person's Done and Not now" | unit | |
| The pinned step key is a P-256 SPKI key | `core.test.js` › "the pinned planque step key is a P-256 public key in SPKI form, 91 bytes, and parses" | unit | |
| Signatures hold only for their label and body; DER read as r‖s | `core.test.js` › "a signature holds only for its label and its body"; "a phone's DER signature is read as raw r‖s"; "the signed bytes are the label, a zero byte, then JCS"; "canonical JSON sorts keys, drops undefined, and writes numbers as ECMAScript does"; `vectors.test.js` › "each relay message's signed bytes are the extension's own, and its signature verifies under the key named" | unit | |

### 4.2 Sites, gates and the fence

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| A run's sites are the phone's signed list; unsigned, planque-signed, another grant's, or a missing site is `bad_step`; sites never grow | `runner.test.js` › "a run's sites are the phone's signed list for the grant, never planque's word, and never grow" | simulated | |
| Carried connections: a step within them runs with no signature, never beyond; another grant's carried sites aren't this one's | `protocol.test.js` › "a step for a carried connection runs on the sites the pairing carried, and never beyond them" | unit | |
| A grant's sites and sensitive allowances sign the vectors' bytes | `vectors.test.js` › "a grant's sites and a sensitive allowance sign the vectors' bytes"; "the sensitive list is the vectors' list, and reads each sample host the same" | unit | |
| `refuseURL`: https only, no user info, no address or local name, subdomains yes, look-alikes no, no sites reach nothing | `core.test.js` › "a run opens only https pages on its sites, never an address, a local name or a credential URL" | unit | |
| Chrome's pages, the Web Store and Google's account pages never; a sensitive site only when allowed by name | `core.test.js` › "Chrome's own pages are never opened, and sensitive sites only when allowed by name" | unit | |
| A grant never names a public suffix | `core.test.js` › "a grant's site is never a public suffix, which would reach every site under it" | unit | |
| A sensitive site opens only on the phone's own allowance; planque's word or a bad signature is dropped | `protocol.test.js` › "a sensitive site opens only on the paired phone's own allowance for that grant"; `runner.test.js` › "an open off the run's sites is refused before any window opens" | unit, simulated | |
| An open off the sites is refused before any window opens | `runner.test.js` › "an open off the run's sites is refused before any window opens" | simulated | |
| The fence: three session rules per tab (block frames, allow the sites, block http), set before the page loads, removed at the end | `runner.test.js` › "a run's tab is fenced by Chrome's own rules before it loads: only its sites, never plain http" | simulated | Only the rules' shape. That Chrome blocks with them is **Not covered** |
| Rule ids fit an int32 and never repeat; stale rules cleared first; two runs don't remove each other's | `runner.test.js` › "a fenced tab's rule ids are small whatever the tab id is, a rule's id never repeats across tabs, and a run's rules go when it ends"; "two runs opening together in a fresh worker: the stale-rule clear finishes before either fence is added, so neither is removed" | simulated | |
| A fence Chrome refuses closes the window and answers `browser_unavailable` | `runner.test.js` › "a window that can't be set up (the fence refuses) is closed, not left open and blank, and the step says why" | simulated | |
| A navigation off the sites goes to a blank page; a pop-up off them is closed, one on them joins | `runner.test.js` › "a navigation in a run's tab that leaves its sites is stopped, and a pop-up off them is closed" | simulated | Events are fired by the test |
| A page restored from the back-forward cache off the sites is sent to a blank page | `runner.test.js` › "a page Chrome restores from its cache off the run's sites is sent to a blank page and recorded" | simulated | |
| `about:blank` is never refused (no loop) | `runner.test.js` › "the run's own blank page is never refused: no loop, and the page the step opens loads" | simulated | |
| A link with `target` opens in the same tab | `page.test.js` › "a read names fields, controls and links by ref, and masks every secret" | real Chromium page | |
| **In real Chromium, a navigation, redirect, form post, meta refresh or script move off the sites is stopped** | — | **Not covered** | Gap 1 |
| **In real Chromium, a pop-up (`target=_blank`, a frame's `window.open`) off the sites is closed or never opens** | — | **Not covered** | Gap 1 |

### 4.3 Reading and masking

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| A read names fields, controls and links by ref; no password, code or hidden input value; card cut to four digits; keys and labelled secrets masked; link credentials masked; URL without query or fragment; kinds in planque's set; `places_order` by a card form or a provider's button; acting links as controls | `page.test.js` › "a read names fields, controls and links by ref, and masks every secret" | real Chromium page | `places_order` from schema.org order data: **Not covered** |
| Masking rules: cards, keys, labelled secrets; prose and order numbers stay | `core.test.js` › "card numbers, keys and labelled secrets are masked; prose and order numbers aren't" | unit | |
| One-time codes in four languages; postal, order and promo numbers stay; the same cases as tafs cloud | `core.test.js` › "one-time codes lose their digits, in English, French, Spanish and German; postal, order and promo numbers stay" | unit | Not in a real page read |
| A link keeps its address, not its credentials | `core.test.js` › "a link keeps its address but not its credentials" | unit | |
| A URL as recorded has no query or fragment | `core.test.js` › "a URL as recorded has no query or fragment" | unit | |
| Text windows: `more`, `next_offset`, secrets never split; never inside an emoji; Japanese kept to 20,000 bytes | `page.test.js` › "a long page's text comes in windows: each says where the next starts, the last says no more, and secrets never split"; "a text window never ends inside an emoji: it stops one short and the next starts with the whole character"; "a window of Japanese text keeps to its bytes, and the whole text is still reachable" | real Chromium page | |
| A read at an offset asks for that window; a bad offset is `bad_step` and never reaches the page | `runner.test.js` › "a read at an offset asks the page for that window, and a bad offset is a bad step" | simulated | |
| Refs are stable from read to read; a gone or changed element's ref is never reused | `page.test.js` › "a ref names the same element from read to read; one that goes or changes is never given to another" | real Chromium page | |
| Page text that imitates a fence marker or an instruction goes up as written, never as a control | `page.test.js` › "page text that looks like a fence marker or an instruction reaches planque as the page wrote it" | real Chromium page | |
| A checkout's lines, total and currency; European and English marks; two totals or two currencies read none; lines masked | `page.test.js` › "a checkout is read as its lines, its total and its currency, the same way planque's own browser reads one"; "a checkout reads European and English number marks the same, and a page with two different totals reads none"; "a checkout's lines are masked like the page's text, and a total in two currencies reads none" | real Chromium page | |
| `payment_frame` from a payment provider's frame | — | **Not covered** | The guard's use of it is covered (4.4); its detection in `page.js` isn't |
| **What is inside a frame never reaches a read** (the content script runs in the top frame only) | — | **Not covered** | Gap 3 |
| A result over 120 KB is cut in order and says so; an act that ran is never answered as failed; one that fits no way is `page_too_large`, signed | `protocol.test.js` › "an answer over the relay's limit is cut to fit and says what was cut; an act that ran is never answered as failed"; "a page that fits no way is page_too_large, signed"; "a page is cut in order: text to 4,000, options past 10, links past 50, then the rest of the text and the links" | simulated, unit | |

### 4.4 Acts and the guard

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| The guard refuses what planque's does: secret and card fields, paying controls and links, any click on a payment or checkout page, unknown refs, unknown kinds, a fill with no value, any `sealed` act | `core.test.js` › "the extension's guard refuses what planque's does" | unit | |
| `press`, `scroll`, `back` and `fill` with `keys` under the same rules | `core.test.js` › "press, scroll and back (gap 7) are held to the same rules: Enter submits nothing on a pay page, a key never goes into a secret" | unit | |
| The guard runs before the page is touched; an act needs its read; a `sealed` act is `secret_field` | `runner.test.js` › "an act needs the read it was planned from, and the guard holds before the page is touched" | simulated | |
| `click`: a control under another element isn't clicked (`not_visible`) | `page.test.js` › "a control under another element isn't clicked" | real Chromium page | |
| `fill`, `select`, `check`; `secret_field` on a password; a stale read is `page_changed`; an unknown ref is `ref_unknown` | `page.test.js` › "an act fills, selects and ticks; it refuses secret fields, stale reads and unknown refs" | real Chromium page | |
| A fill never submits; a navigation makes the read stale | `page.test.js` › "a fill never submits, and a navigation in the page makes the read stale" | real Chromium page | |
| `fill` with `keys: true` types one character at a time and submits nothing | `page.test.js` › "a fill with keys types one character at a time, with key events, and submits nothing" | real Chromium page | |
| `press Enter` submits a field's form; on a card form it is `payment_step`; `Tab` is `bad_step` | `page.test.js` › "Enter submits a field's form as the browser would; on a form that takes a card it's refused and nothing is sent" | real Chromium page | |
| A page that handles Enter itself is left to it; ArrowDown and Escape arrive as keys | `page.test.js` › "a page that handles Enter itself is left to it; ArrowDown and Escape reach the field as keys" | real Chromium page | |
| `scroll` of the page and of a ref | `page.test.js` › "scroll moves the page by most of a screen, or brings a ref into view" | real Chromium page | |
| `back` goes back and reads; nothing runs in the page; `back` with a ref is `bad_step` | `runner.test.js` › "back goes where the tab was and reads the page there; nothing runs in the page before it" | simulated | Chrome's `tabs.goBack` itself: **Not covered** |
| An element that changed since the read is `ref_unknown` (words, target, row) | `page.test.js` › "an act refuses a ref whose element has become something else since the read"; "an act on a ref in a re-rendered list refuses instead of acting on another row" | real Chromium page | |
| On a checkout every click is `payment_step` and nothing runs in the page | `runner.test.js` › "on a checkout page a taf clicks nothing, and a dialog the page raised stops the next act with what the page said" | simulated | |
| The page script has no way to type a saved value | `page.test.js` › "the page script has no way to type a saved value: the extension keeps none"; `store.test.js` › "nothing in the extension saves, lists or types a sign-in any more" | real Chromium page, source text | |
| A click that navigates is read after the new page; one that navigates nothing waits 1 s at most | `runner.test.js` › "a click that navigates is read after the new page loads, not from the old page still marked complete"; "a click that navigates nothing waits one second at most for a navigation and then reads" | simulated | |
| An act is outlined first, after its picture; back and a page scroll outline nothing | `runner.test.js` › "an act on a ref is outlined first, after its picture, for a moment; back and a scroll of the page outline nothing"; `page.test.js` › "the outline that shows where the taf will act goes as the act starts" | simulated, real Chromium page | |
| A Pause, the person's input or a Stop during the outline holds the act | `runner.test.js` › "a Pause, the person's input or a Stop while the outline shows holds the act: the page is never touched" | simulated | |
| `disabled` on a disabled element | — | **Not covered** | |
| `option_unknown` on a select with no such option | — | **Not covered** | |
| `check` with `value: "unchecked"`, and a radio | — | **Not covered** | |
| `page_unresponsive` when a read or an act doesn't answer in 20 s | — | **Not covered** | Only Show me's path is tested (4.8) |
| `no_page` for an act or a hand-over with no tab | — | **Not covered** | A read's `no_page` is covered in 4.9 and 4.10 |
| Every act kind has its words in the panel's step list | `core.test.js` › "every kind of act has its words in the side panel's step list" | unit | |

### 4.5 Dialogs

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| Confirm answers no, prompt answers nothing, `window.open` opens nothing; the next read reports the dialog; the next act is `dialog_open` | `page.test.js` › "the page's dialogs don't block: a confirm is never accepted, and the next read reports it" | real Chromium page | The guard is installed by the test, after the page's scripts ran |
| A dialog's code goes up and counts as a failure | `runner.test.js` › "on a checkout page a taf clicks nothing, and a dialog the page raised stops the next act with what the page said" | simulated | |
| The guard goes into the page's world (`MAIN`) of a run's tab | `runner.test.js` › "a run opens in a background window of its own, grouped and named, and reads the page" | simulated | Only that the call is made |
| `alert` stops blocking and is reported | — | **Not covered** | Gap 5 |
| `beforeunload` handlers and `onbeforeunload` are dropped | — | **Not covered** | Gap 5 |
| The guard is in place before the page's own scripts run (`injectImmediately` at commit) | — | **Not covered** | Gap 5 |

### 4.6 Screenshots

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| Only an https, non-sensitive page that isn't a checkout or payment frame is pictured | `shot.test.js` › "only an https site that isn't sensitive, a checkout or a payment frame is pictured" | unit | |
| An act takes its picture first, a read never; a failed picture never holds the act | `runner.test.js` › "an act takes its picture first, a read never does, and a picture that fails never holds the act" | simulated | |
| Covered first, covers off after, also on failure or a hung capture | `shot.test.js` › "a picture is taken with the secret fields covered, and the covers come off after it"; "the covers come off even when the browser can't take the picture, and a hung one is given up on" | simulated | |
| No picture when a cover can't be confirmed, on a sensitive site, or when the tab isn't in front | `shot.test.js` › "no picture when a secret field can't be confirmed covered, on a sensitive site, or when the tab isn't in front" | simulated | |
| Covers sit over password, card and secret-valued fields; text that looks like a card or key; every frame; open shadow roots; no picture past the caps | `page.test.js` › "the picture's covers sit over the password, card and saved-fill fields, and come off again"; "text that looks like a card number or a key is covered in the picture, and ordinary text isn't"; "a frame is covered whole in the picture (its inside is another page's to hide), and a field in an open shadow root is covered as any is"; "a page too long or too deep for the covers to be sure of gives no picture, however it ends"; `core.test.js` › "secretSpans names what a picture must cover: cards that pass Luhn, keys by shape, labelled values, long hex and base64 runs" | real Chromium page, unit | |
| WebP at most 1280 × 8000, quality 0.6 then 0.4, at most 120 KB | `shot.test.js` › "a picture is scaled to at most 1280 px wide and encoded as WebP at quality 0.6"; "a very tall picture is scaled to at most 8000 px high"; "a small picture isn't scaled up"; "one that comes out too big is encoded once more at 0.4, and dropped if it is still too big"; "anything that isn't WebP is dropped" | unit | With stand-ins for the canvas |
| Posted signed over `{chrome_id, step, sha256}`; a failed upload is only a word | `shot.test.js` › "the picture is posted for its step as JSON with the WebP in base64url, signed by the extension's key over the step and its hash"; "a refused upload, or one that fails, is only a word: nothing throws" | simulated | |
| A page with a one-time-code field gets no picture | — | **Not covered** | `maskForShot`'s `why: "code"` |
| The build ships with `screenshots: false` | — | **Not covered** | |
| **In real Chromium, `captureVisibleTab` of a run's tab: the picture is taken before the act, shows only covered secrets, and none on a refused or sensitive site** | — | **Not covered** | Gap 4 |

### 4.7 Runs: tabs, groups and ends

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| A run opens a background tab in the owner's window, grouped, never focused; with no window or a private one, a window of its own | `runner.test.js` › "a run opens in a background window of its own, grouped and named, and reads the page"; "a run opens a background tab in the owner's window, and Stop leaves it open, in a group that says done"; "with no window to join, or a private one, a run has a window of its own, which Stop leaves and unpairing closes" | simulated | |
| The runner never lists the owner's tabs and touches only tab ids it made | `runner.test.js` › "the runner never lists the owner's tabs"; "a run in the owner's window touches only tab ids it created (unpairing closes them)" | simulated | |
| A Stop while the tab is made, fenced or grouped leaves nothing open and loads nothing | `runner.test.js` › "a Stop that lands while the tab is being made leaves nothing open, in the owner's window or its own"; "a Stop that lands while the tab is being fenced and grouped closes it, and nothing is navigated" | simulated | |
| A broken-up group keeps its pop-up; a closed window makes a window of its own; a tab that can't be grouped closes alone | `runner.test.js` › "a tab group the owner has broken up doesn't lose the run its pop-up, and a window that closed makes a window of its own"; "when the tab can't be grouped in the owner's window, only the run's tab closes" | simulated | |
| A run's steps never overlap | `runner.test.js` › "a run's steps never overlap: the next waits for the one running" | simulated | |
| The group says "is working", then "· done" in grey; a grace of 15 s; at once for an end someone made; after a restart a kept group greys | `runner.test.js` › "the run's group says its taf is working, in the extension's words, until the run ends"; "a group whose run is over says so in the extension's words, as it said the taf was working"; "a finished run's group turns grey only after a grace, and the taf's next run in that time keeps it from ever turning grey"; "after the worker restarts, a finished run's group that never turned grey says done"; "the person's Stop greys the run's group at once; planque's stop and a run's own end keep the grace" | simulated | |
| One group per taf per window | `runner.test.js` › "one group per taf per window: the taf's done group there works again, and its old tabs aren't the new run's" | simulated | |
| The close step keeps the tabs; a run that never loaded a page closes them | `runner.test.js` › "a run that ends by planque's close step or stop keeps its tabs; one that never loaded a page, and unpairing, close them" | simulated | |
| A taf keeps only its latest finished run's tabs; moved or used tabs stay; done groups don't pile up | `runner.test.js` › "a taf keeps only its latest finished run's tabs: its next run closes the earlier ones, another taf's stay, unpairing closes all"; "a taf's next run leaves a kept tab the person has moved or is using, and closes one still where the run left it"; "a person watching each run of a taf doesn't collect one done group per run: each earlier run's tab leaves its group" | simulated | |
| Idle sweep: 150 s; a paused run 15 minutes; a step running isn't swept; recorded `by: "idle"` | `runner.test.js` › "a run nobody sends a step to for 150 s ends, as Stop does (its tabs stay); a paused one waits longer, and one with a step running isn't swept"; "a paused run a taf still holds is kept; one nothing will resume ends after 15 minutes, and the bar goes" | simulated | The alarm that calls `sweep` is **Not covered** |
| The turn over ends a quiet run here: grey at once, forgotten, planque not told; never paused, handed or running | `runner.test.js` › "the taf's turn over ends its quiet run here: grey at once, forgotten, planque never told it stopped, and a later call goes on in a new run"; "the turn over never ends a run that's paused, waits on the person, or has a step running"; `coachchat.test.js` › "a taf's turn is over, in tafs cloud's word, only for its own live runs, and never while it works or waits on the phone" | simulated, unit | The panel sending `turn_over` is **Not covered** |
| planque's unsigned `stop` over the relay ends the run | — | **Not covered** | `runner.stop` is tested directly; the relay's routing of `{type: "stop"}` isn't |
| **What a run becomes when MV3 stops the worker mid-run** | — | **Not covered** | Gap 6 |

### 4.8 The person's controls

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| The person's input pauses the run (`paused_by_owner`) | `runner.test.js` › "the owner's input in a run's tab pauses it, and Stop leaves its tabs and window open" | simulated | `ownerInput` is called by the test |
| A trusted click in the page sends `owner_input`; a press on the bar doesn't | `page.test.js` › "only a real press counts, naming its run; it's no owner input; the page can't keep the bar away, and it goes when told"; "an element the page names tafs-working-bar is the page's: a click inside it still pauses the run, and laid over a button it still blocks an act" | real Chromium page | A click only |
| Typing and scrolling (keydown, wheel) send `owner_input` | — | **Not covered** | Gap 7 |
| `owner_input` reaches the runner and pauses the run, end to end | — | **Not covered** | Gap 7 |
| Three failed acts pause; Resume goes on | `runner.test.js` › "three failed acts in a row pause the run; Resume goes on" | simulated | |
| No progress pauses; a changed page is progress; Resume resets | `runner.test.js` › "the same act on the same page that answers with the same page twice pauses the run, and Resume starts the count again"; "an act that changes the page is progress, however often it repeats" | simulated | |
| Pause, a long wait, Resume: the held act runs once, planque hears paused then working | `runner.test.js` › "Pause, a long wait, then Resume: the held act never touches the page, planque hears one paused and one working, the bar works again, and the act runs once" | simulated | The resend is planque's |
| Go to its tab: the run's tab to the front, nothing else | `runner.test.js` › "the panel's head goes to a live run's tab and focuses its window; nothing else, and no run it doesn't hold" | simulated | |
| The bar on the tab in front of each window, for its run or the first; gone at the end | `runner.test.js` › "while a run lives, the tab in front of each window shows the bar, for its own run or the first; the run's end takes them all away" | simulated | |
| The bar's words and buttons; nothing of it in a read; an act under it still lands | `page.test.js` › "the floating bar says who works; Go to its tab shows only on another tab; Pause turns to Resume; nothing of it is in a read"; "an act on an element under the floating bar still lands: the check for what's on top looks through it" | real Chromium page | |
| A bar press acts only on a run this Chrome holds; Resume only from its own tab | `runner.test.js` › "a press on the bar acts only on a run this Chrome holds, as the panel's buttons do" | simulated | |
| Stop on the bar ends that run at once; a hand-over open is told Not now | `runner.test.js` › "Stop on the bar ends the run at once, for that tab's run only; a hand-over open is told Not now" | simulated | |
| Closing a run's last tab is the person's Stop; one of two isn't | `runner.test.js` › "the person closing a run's last tab ends the run as their Stop; closing one of two, or a tab of no run, doesn't" | simulated | |
| A late step on a stopped run is `stopped` | `runner.test.js` › "the owner's input in a run's tab pauses it, and Stop leaves its tabs and window open"; "the person closing a run's last tab ends the run as their Stop; closing one of two, or a tab of no run, doesn't" | simulated | |
| The toolbar badge and title | `coachchat.test.js` › "the toolbar says a taf is at work: a mark while one works, ! when one waits on you, and who it is in the tooltip" | unit | The worker setting them is **Not covered** |
| The panel's buttons send `pause`, `resume`, `stop`, `hand_show`, `hand_answer` | — | **Not covered** | |

### 4.9 Hand-over

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| Answered at once as handed; waits for the person | `runner.test.js` › "a step handed to the person is answered at once as handed, and waits for them" | simulated | |
| Words checked (`validWords`), ref in the read, read known | `hand.test.js` › "the taf's words are plain text of 1 to 280 characters, with no control or direction character"; `runner.test.js` › "the extension checks a hand-over itself: its words, the field it names, the read, and the page's site" | unit, simulated | |
| A hand-over on a page off the sites is refused | `runner.test.js` › "a hand-over on a page off the run's sites is refused, as any step is" | simulated | |
| The person's typing pauses nothing while handed; does after | `runner.test.js` › "the person typing in the page doesn't pause the run while a step is handed to them, and does after" | simulated | |
| Not swept while open; over at 30 minutes (`HAND_MAX_MS`) | `runner.test.js` › "a run with a step handed to the person isn't swept as idle, until the hand-over's 30 minutes are up" | simulated | |
| Done reads back without field values; Not now says no; each once | `runner.test.js` › "Done reads the page back without what is in its fields; Not now says no; either answers once"; "Done is recorded as done, Not now as declined, so the step list says each once"; `hand.test.js` › "a page read after the person typed has none of what is in its fields" | simulated, unit | |
| Done's read leaves out editable regions' text, and puts them back | `page.test.js` › "the read after a hand-over leaves out the text of an editable region, and puts the page back as it was" | real Chromium page | |
| A new hand-over ends the old as declined; Stop ends it | `runner.test.js` › "a new hand-over ends the one still open as declined, and Stop ends it too" | simulated | |
| Show me: the tab in front, the field outlined, nothing clicked; says why when it can't | `runner.test.js` › "Show me brings the run's tab to the front and outlines the field the taf named, and clicks nothing"; "Show me says when it couldn't point to the field: the page changed, the field isn't on screen, the page didn't answer, or none was named"; `page.test.js` › "Show me outlines one field and nothing else: no click, no focus, every pointer through, and it goes" | simulated, real Chromium page | |
| The answer is signed `tafs-chrome/hand/v1`; the result says handed | `hand.test.js` › "the answer is signed by this Chrome's key over {hand, outcome, page?}, under its own label"; "a step's result says handed, and a result with no handed says nothing of it" | unit | |
| Kept until `hand_kept`; `too_large` once more without the page; `not_kept` waits for the next welcome | `hand.test.js` › "an answer is kept until planque has it, resent as long as it hasn't, and stopped when planque never will" | unit | The worker's `sendHands` on each welcome, and its over-cap page drop, are **Not covered** |
| A hand step over the relay is answered handed, signed | `protocol.test.js` › "a signed hand-over step reaches the runner over the relay and is answered handed, signed" | simulated | |

### 4.10 The joined tab (0.2.12, 0.2.13)

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| Only the extension's own panel page opens a port; open while the port is | `panels.test.js` › "only the extension's own panel page says a panel is open, and only while its port is" | unit | |
| A send marks tafs cloud's `run` with the window and front tab; a failed send marks nothing | `panels.test.js` › "a send marks the run tafs cloud's answer names, with the panel's window and the tab in front; a failed send, or one naming no run, marks nothing" | unit | |
| A closed panel's marks go | `panels.test.js` › "a closed panel's marks go with it" | unit | |
| A first step before the send's answer waits 5 s at most | `panels.test.js` › "a run's first step that comes before the send's answer waits for it, a few seconds at most" | unit | |
| The page consent is asked only for a marked run, before the tab is looked at | `panels.test.js` › "the worker asks the page consent only for a marked run, before anything of the tab is looked at, and nothing joins without it"; `runner.test.js` › "without the page consent, the worker never lets the tab in front join, and never looks at it: the run opens its own tab as today" | unit, simulated | |
| Which tab may join (`joinRefusal`) | `panels.test.js` › "which tab may join: the active tab of the panel's window, on the run's sites, never a refused one" | unit | |
| The worker wires joining only through the mark and the consent | `panels.test.js` › "the worker joins a tab only through the panels' mark and the page consent, and marks a run only from the panel's own sends" | source text | |
| A panel run joins the front tab, fenced first, read through `page.js`, followed | `runner.test.js` › "a run the person started from the side panel works in the tab in front: it joins the group, fenced first, read through page.js, and Chrome follows it" | simulated | |
| A tab already at the address is read in place | `runner.test.js` › "the tab in front already at the asked address is read in place, not loaded again" | simulated | |
| A scheduled run, a step naming no run or another, gets its own tab | `runner.test.js` › "a scheduled run with the panel open and an allowed tab in front gets its own tab; so does a step naming no run, or another" | simulated | |
| A mark is taken once; expired (2 minutes) or a closed panel's joins nothing | `runner.test.js` › "a mark is taken by the first run that names it, once; an expired one, or a closed panel's, joins nothing" | simulated | |
| A mark from window A never joins a tab in window B | `runner.test.js` › "a mark from window A never lets a run join a tab in window B" | simulated | |
| Never off the sites, a refused site, private, pinned, in the person's group, or not https | `runner.test.js` › "the tab in front never joins off the run's sites, on a refused site, private, pinned, in a group of the person's, or not https" | simulated | |
| Navigation and pop-ups in a joined tab are fenced | `runner.test.js` › "a navigation in a joined tab that leaves its sites is stopped, and a pop-up off them is closed" | simulated | |
| Checked again every step; an open goes on in a tab of the run's own | `runner.test.js` › "every step checks the joined tab again: a page it may no longer show is refused, and an open goes on in a tab of the run's own" | simulated | |
| The person's input in a joined tab pauses the run | `runner.test.js` › "the person typing, clicking or scrolling in the joined tab pauses the run" | simulated | |
| Stop's five guarantees | `runner.test.js` › "Stop hands the tab back: out of the group, unfenced, forgotten by the run, its bar gone, still open" | simulated | |
| Never closed by any end, the next run or unpairing | `runner.test.js` › "a joined tab is never closed by the extension: not at unpairing, an idle end, the turn over, the close step, planque's stop, nor the taf's next run" | simulated | |
| Chrome follows the run only while the panel is open | `runner.test.js` › "Chrome follows the run only while the panel is open in its window" | simulated | |
| A step of another agent run gives the tab back first (0.2.13, `giveBackJoined`) | `runner.test.js` › "a step of another agent run never works in the person's joined tab: it goes back to them first, and the run works in a tab of its own" | simulated | Merged in #2025, on `main` |
| **All of the above in real Chromium: a real side panel's port, real tabs, groups and fence rules** | — | **Not covered** | Gap 2 |

### 4.11 The worker's messages and senders

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| The panel port only from the extension's own page, never a content script | `panels.test.js` › "only the extension's own panel page says a panel is open, and only while its port is" | unit | |
| `onMessage`: `owner_input` only from a tab; `bar` only from the extension's own content script; every other type only from the extension's own pages, never a tab | — | **Not covered** | Gap 8 |
| No `externally_connectable` in the manifest, so no web page can message the worker | — | **Not covered** | `test_manifest.py` pins `permissions` and `host_permissions`, not this key. Gap 8 |
| The manifest's permissions and hosts are exactly the chosen ones | `chrome/store/test_manifest.py` › `test_asks_for_every_https_site_at_install_and_no_other_host`, `test_asks_for_nothing_it_does_not_need` | unit (Python) | |

### 4.12 Run state to planque

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| Each Pause, Resume and Stop, whoever made it, goes out; nothing after a stop | `runner.test.js` › "each Pause, Resume and Stop, whoever made it, is said to planque as the run's state, and a stopped run says nothing more" | simulated | |
| Signed `tafs-chrome/run_state/v1`; newest kept; answers matched in order; `not_kept` waits; `stopped` final | `runstate.test.js` › "a run's state is signed by this Chrome's key over {run, state, at}, under its own label"; "only each run's newest state waits; planque's answer drops the state it answers, never a newer one"; "a store failure keeps the state for the next welcome; any other refusal drops it"; "stopped is final: a run stopped or swept never says working again, even once planque has the stop" | unit | |
| planque's answers go to the run states, never taken for a step | `protocol.test.js` › "planque's answer to a run's state goes to the run states, and is never taken for a step" | simulated | |
| The worker sends kept states on each welcome | — | **Not covered** | `sw.js` `sendRunStates` |

### 4.13 The relay

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| The extension speaks first | `protocol.test.js` › "the extension speaks first: open, then planque's challenge" | simulated | |
| Steps only after both proofs | `protocol.test.js` › "the relay takes steps only after both ends proved themselves" | simulated | |
| Ready only after the welcome; push goes only to planque | `protocol.test.js` › "the relay says it is ready only once the welcome proved planque, so a push subscription goes only to planque" | simulated | |
| A step before the welcome, or a forged welcome, closes | `protocol.test.js` › "a step before the welcome, or a forged welcome, ends the connection" | simulated | |
| Close codes: `4410` wipes once, `4401` and `4403` close | `protocol.test.js` › "planque's close message counts as its close code: 4410 wipes once, 4401 and 4403 only close"; "planque closing with 4410 wipes the extension" | simulated | |
| Reconnect at once then backing off during a run; idle waits for the alarm | `protocol.test.js` › "a connection dropped during a run is opened again at once; idle, it waits for the alarm" | simulated | |
| Idle ping until `push_kept`; `push_refused` keeps it | `protocol.test.js` › "an idle relay pings every 20 s to keep the worker up, until planque can wake it by push; a run pings either way"; "an idle worker is let sleep only when planque says it keeps the push subscription, and a refusal keeps the ping going" | simulated | |
| `connections` kept, display only, malformed left out | `protocol.test.js` › "the relay keeps this Chrome's live connections from the welcome and each change, display only, malformed left out" | simulated | |
| The relay address is `wss` with no path | `core.test.js` › "the pinned relay is the WebSocket API's own domain, over wss, with no path" | unit | |
| planque's `ping` is answered `pong` | — | **Not covered** | |

### 4.14 Pairing, unpairing, what's kept, push

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| A code is a 4-character lookup and a 17-character secret | `protocol.test.js` › "a pairing code is four characters of lookup and a 17-character secret, about 83 bits"; `core.test.js` › "the side panel's code field holds a whole pasted code, dashes and spaces included, and the extension reads it" | unit | |
| Pairing binds both keys under the secret; a substituted key, renamed pairing, other secret or other extension fails | `protocol.test.js` › "pairing binds the phone's key and ours under the code's secret, which planque never sees"; `vectors.test.js` › "the extension's proof, the phone's proof and the signed bytes are the vectors'" | unit | |
| One fingerprint for the pairing and KIFF's request | `protocol.test.js` › "one fingerprint confirms the pairing and KIFF's request: the extension checks the one signature over both"; "the offer says the extension checks one fingerprint, under a proof only the code's holder can make"; "what the phone signs once for two requests is the same lines KIFF and planque build, the hashes sorted" | unit | |
| An offer refused for an unknown field goes again without the newer fields | `protocol.test.js` › "an offer planque refuses for a field it doesn't know goes again without the newer fields, and only then" | unit | |
| A re-pairing's carried connections only as the phone signed them (bound to this Chrome's key) | `protocol.test.js` › "a re-pairing's carried connections are kept only as the phone signed them" | unit | |
| `profile_id` kept and offered | `protocol.test.js` › "the extension keeps an id for its Chrome profile, and offers it to pair under" | unit | |
| The manifest hash is 64 lower-case hex | `core.test.js` › "a build's manifest hash is 64 lower-case hex characters, the form planque's allowlist keeps" | unit | |
| KIFF's offer is view-only; the claim's proof; a session that could approve is ended | `kiff.test.js` › "the KIFF offer is a view-only browser extension, as KIFF's QR text"; "the claim's proof is a DER signature over the claim message, as KIFF reads it"; "a claim waits for the phone, and a session that could approve is ended, never kept" | unit | |
| Old saved sign-ins deleted on every start, before anything is read | `store.test.js` › "an update deletes the sign-ins an earlier version saved, and keeps the pairing"; "the service worker deletes them on every start, before it reads anything kept" | unit, source text | |
| Unpairing closes every run's tabs and own windows | `runner.test.js` › "a run in the owner's window touches only tab ids it created (unpairing closes them)"; "a run's own window the owner has emptied (its tab dragged out) still has its tab closed at unpairing"; "unpairing closes a kept tab the person has moved too" | simulated | `runner.stopAll` only |
| Unpairing wipes everything: KIFF logout, every run ended, IndexedDB and `storage.session` cleared, push unsubscribed | — | **Not covered** | `sw.js` `wipe`. Gap 9 |
| The panel's Unpair posts a signed `tafs-chrome/unpair/v1` | — | **Not covered** | Gap 9 |
| The worker's pairing: `pair`, `poll`, the secret only in `storage.session`, keys kept only after `checkConfirmation` | — | **Not covered** | `sw.js` `pairWith`, `pollPairing` |
| Push: the key's bytes, the subscription's shape, silent subscribe, signed message | `push.test.js` › "planque's VAPID key is read as the bytes the push service wants"; "a subscription goes to planque only as an https endpoint with its two keys"; "the subscription is the one the extension has, else a new silent one, and never without planque's key"; "the message to planque is signed by this Chrome's key over the subscription" | unit | |
| A push wakes the worker and it connects; the subscription goes after each welcome | — | **Not covered** | `sw.js` `push` listener, `announcePush` |
| The minute alarm sweeps and connects | — | **Not covered** | |

### 4.15 The side panel: Coach, page consent, files, chats

| Behaviour | Test(s) | Level | Notes |
|---|---|---|---|
| Without the consent, or sending alone, the tab is never touched | `pageconsent.test.js` › "without the page consent, or sending alone, the tab is never touched: no script, no read, the words go alone" | unit | |
| The allow holds only for this pairing's person and the current words | `pageconsent.test.js` › "the allow kept here holds only for this pairing's person and the current words" | unit | |
| The worker reads only through the consent; the allow is kept with the pairing | `pageconsent.test.js` › "the worker reads the tab for Coach only through the page consent, and keeps the allow with the pairing" | source text | |
| Consent read, given and withdrawn at tafs cloud; the version is tafs cloud's | `pageconsent.test.js` › "the page consent is read, given and withdrawn at tafs cloud, as the session's own"; "the extension's page consent words' version is tafs cloud's" | unit, source text | |
| The consent screen: focus on its heading; a modal over the panel; withdrawing one tap away | `panel.test.js` › "the consent screen opens with the focus on its heading, never on Allow, so no stray key gives consent"; "the consent is a screen over the whole panel, a modal dialog, never a line inside Coach's chat"; "withdrawing is one tap from the chat, beside what a send reads, and in Settings" | source text | |
| Coach reads only the front tab of the panel's window, https, never sensitive, mail, switched off, store or incognito | `core.test.js` › "Coach reads only the tab in front of the panel's window, on https, never a sensitive, switched-off, store or incognito page"; "Coach never reads a mail site: Gmail, Outlook, any webmail host, by name or by its label"; "the mail rule reaches every language's word and Outlook's new address, and a trailing dot gets past nothing"; `runtime/test/page-sites.test.ts` | unit | |
| Checked again on the address it was read on | `core.test.js` › "the page is checked again on the address it was read on: a tab that moved onto a refused page sends nothing" | simulated | |
| What Coach gets: masked text, no field value, no typed text, a bare address, 20,000 bytes | `page.test.js` › "what Coach gets of a page: its masked text, never a field's value, typed text or a secret, and a bare address" | real Chromium page | |
| Coach's routes, chats, archive, one Coach per session, the page as data, a refused page resent alone | `coach.test.js` (all 9 tests) | unit | |
| Fresh conversations, polling, pending messages, drafts, tafs offered, tabs followed | `coachchat.test.js` (all 17 tests) | unit | |
| Files: types, limits, redraw, upload, tray, ids with the words | `attach.test.js` (all 8 tests); `panel.test.js` › "files are picked with Chrome's own file picker only, shown as chips until Send, and Send waits while one goes up" | unit, source text | |
| Markdown drawn from elements only; links never open; control characters shown as U+FFFD | `chatmd.test.js` (all 7 tests) | unit | |
| The composers, heads and pending messages in the panel's markup | `panel.test.js` › "the composers are the app's: Send inside the box on its right, named by its words, and Enter sends"; "the heads stay at the top, the composer at the bottom, and Coach's page line goes with the composer"; "a message shows at once with the working line, stays until the chat has it, and its words come back on a failure"; "the composer sits at the bottom even with a short chat: a full-height column, the composer pushed to its end" | source text | |
| Not on this site and Read this site are kept and checked before any read | — | **Not covered** | `sw.js` `asideSwitch`; `asideRefusal`'s `off` is covered |
| Locales carry English's keys and placeholders | `core.test.js` › "every shipped language has an extension locale with English's keys and placeholders, and no English left in it" | unit | |

## 5. Gaps

In order: what could let a taf reach or reveal something it shouldn't come first. Most close only with the end-to-end harness
(section 6): **E2E** below means the extension loaded in Chromium, a fake planque relay and fixture sites.

1. **The network fence and navigation guards are never run in Chrome.** `declarativeNetRequest` rules are checked only for
   their shape, and `webNavigation` events are fired by the test. Nothing shows Chrome blocks a request off the run's sites,
   matches `requestDomains` to subdomains as expected, blocks plain http in a frame, or keeps a listed site's refused or
   sensitive subdomain off the screen. Why it matters: it is the line that keeps a run on the sites the phone signed.
   E2E: for each of a link, a 302 redirect, a form post, a meta refresh, `location.assign`, a frame, a `target=_blank`
   link and a frame's `window.open`, the fixture server for the refused host logs no request and the tab ends on
   `about:blank` or closed; the same for a back-forward cache restore.
2. **The joined tab (0.2.12, 0.2.13) is proven only against fakes.** The port's life with a real side panel, the mark per
   window, the 2-minute expiry, the consent first, the front tab's checks (pinned, grouped, private), the re-check each
   step, the five Stop guarantees, never closed, and the give-back on another `agent_run` all run against `fakeChrome`.
   Why: it is the one place a run works in a tab of the person's. E2E: one scenario per row of 4.10.
3. **Masking in real pages is proven for the top document only.** No test puts a field, a code or a card number inside a
   same-origin or cross-origin frame and checks the read and Coach's read leave it out (the content script is injected into
   the top frame only). One-time codes written in a page's text are masked in a unit test only, never in a real page's
   read. E2E: a fixture page with frames holding secrets, and a page whose text carries a one-time code in each of the
   four languages.
4. **Screenshots are never taken in Chrome.** The covers are tested in a real page and the capture against a fake
   `captureVisibleTab`. Nothing shows a picture is taken before the act, with the covers in it, and none on a sensitive site,
   a refused site, a checkout or a page with a one-time-code field. Screenshots are off in the build, so this matters before
   they are switched on. E2E, in a test build with `screenshots: true`: a fake `/v1/extension/steps/<step>/screenshot`
   receives one picture per act and none for the refused cases; and a unit test pins `screenshots: false` in `config.js`.
5. **The dialog guard is tested only after the page's scripts ran.** `alert`, `beforeunload` and `onbeforeunload` are not
   tested, and nothing shows the guard is in place before a page's own script captures `confirm` or adds a leave handler.
   E2E: fixture pages that call `alert` and `confirm` at load and set `onbeforeunload`, and a step that navigates away.
6. **A worker restart mid-run is never exercised.** The runner holds `runs` and `tabRun` in memory; only nonces, `doneTabs`,
   hand answers and run states are kept in `chrome.storage.session`, and those are tested with fake stores. After a
   restart a step for the same run is a first step again (it needs `sites_signed`), the old tab's fence rules are cleared by
   the next fence, and a replayed step must still be refused. E2E: stop the worker through the DevTools protocol mid-run,
   then send a replay, a step without `sites_signed`, and a fresh first step.
7. **The person's input is proven in two halves.** A trusted click sends `owner_input` (real page), and `ownerInput` pauses a
   run (fake). Typing and scrolling are not tested, nor the worker passing the message to the runner. E2E: type, click and
   scroll in a run's tab; the next step is `paused_by_owner` and planque receives `run_state` `paused`.
8. **The worker's sender checks have no test.** `sw.js` takes `owner_input` from any tab, `bar` only from its own content
   script, and everything else only from its own pages; the manifest has no `externally_connectable`. None of this is
   tested, and nothing pins the manifest key's absence. Unit: add `externally_connectable` to `test_manifest.py`'s
   forbidden keys. E2E: a message sent from a content-script context with each panel type changes nothing.
9. **Unpairing end to end.** `wipe` (KIFF logout, every run ended, IndexedDB and `storage.session` cleared, push
   unsubscribed) and the panel's signed `unpair` have no test; only `runner.stopAll` and the relay's call to `onUnpaired`
   do. E2E: `4410` mid-run, and Unpair from the panel; the fake planque gets a valid `tafs-chrome/unpair/v1`, every run tab
   closes (joined tabs only leave their group), and a later step gets no answer.
10. **No step goes the whole way.** Each link is tested alone: the relay against a fake runner, the runner against a fake
    page (which answers by matching the function's source text), the page script without the runner. E2E: open, read, each
    act kind of 2.2 and close on a fixture site, each result's signature checked with the extension's key and its page
    compared with the fixture.
11. **Codes with no test:** `disabled`, `option_unknown`, `page_unresponsive` for a read or act (20 s), `no_page` for an act
    or hand-over with no tab, and `check` with `"unchecked"` or a radio. Real Chromium page tests for the first two and the
    last; simulated for the timeout and `no_page`.
12. **The hand-over's and run state's chains through the worker.** `sendHands` (resend on each welcome, a page over 120 KB
    sent as Done with no page) and `sendRunStates` are untested. E2E: Done while the relay is down, then a welcome; a page
    over the cap.
13. **planque's unsigned `stop` and `ping` over the relay.** Their routing isn't tested. Simulated, in `protocol.test.js`.
14. **Real tab groups and windows.** Grouping, titles and colours, the 15 s grey, a group Chrome ends once empty, one group
    per taf per window, `getLastFocused` with a private or pop-up window, an unfocused window of its own. E2E.
15. **`payment_frame` and `places_order` by schema.org order data are not detected in any test.** Real Chromium page tests
    with a provider's frame and an `itemtype` order form.
16. **Not on this site, the alarm, the push wake and the badge's wiring** (`asideSwitch`, the minute alarm, the `push`
    listener, `announcePush`, `publish`) have no test. E2E, lower priority.
17. **The contract has three slips.** `docs/chrome.md`'s refusal table lacks `disabled`, `option_unknown` and `no_page`; and it
    says the extension "gives an unsigned message no signed answer", while `relay.js` answers any refused step that carries a
    string `id` with a signed `bad_step`, a bad signature included (`protocol.test.js` › "the relay takes steps only after
    both ends proved themselves" asserts it). Fix the page, or the code, in the harness's pull request.
18. **Isolated world.** The page tests run `mask.js` and `page.js` in the page's own world, so nothing shows the page can't
    see `TafsPage` or `TafsMask`, replace them, or call `act`. E2E: a fixture page that tries each.

## 6. The end-to-end harness this calls for

A design, not code. It runs in CI with no model, no real planque and no real KIFF, and gives the same result every time.

- **A test build.** `config.js` pins production's step key, relay and API, so the harness builds a copy of `chrome/` with a
  `test` environment: a step key whose private half the fake planque holds, the fake relay's and API's addresses, no push
  key, and `screenshots: true` for the screenshot scenarios. The store's package check keeps it out of any release.
- **Chromium with the extension.** Playwright's `chromium.launchPersistentContext` with
  `--disable-extensions-except=<build>` and `--load-extension=<build>`, headless where Chromium allows extensions. The
  service worker comes from `context.serviceWorkers()`; its id names `chrome-extension://<id>/`. A DevTools protocol session
  stops the worker for the restart scenarios.
- **A fake planque.** A local WebSocket server that speaks the relay's protocol: `challenge`, a signed `welcome` with `now`,
  steps signed with the test key (`tafs-chrome/step/v1`), `stop`, `close` codes, and the `*_kept` and `*_refused` answers. It
  verifies every message from the extension with the key the extension paired with. Its HTTP side answers pairing
  (`/v1/extension/pairings/…`), `unpair` and screenshots. A fake phone, with its own test key, signs the pairing
  confirmation, `sites_signed` and sensitive allowances, so the real pairing flow runs first and nothing is seeded.
- **Fixture sites over https.** A local server with a self-signed certificate, and
  `--host-resolver-rules="MAP * 127.0.0.1, EXCLUDE localhost"` so every name (`shop.example`, `elsewhere.example`, a
  sensitive name, a mail name) reaches it and nothing reaches the internet (the extension refuses IP addresses and local
  names, so the fixtures must have names). Pages: a plain shop; a sign-in with password and one-time-code fields; a
  checkout; a provider's payment frame; secrets in text, fields, frames and shadow roots; dialogs at load and on click; a
  `beforeunload` page; pop-ups and `target=_blank`; redirects, form posts and meta refreshes off-site; a slow page and one
  that never answers; a page over 120 KB. The server logs every request by host, which is how a scenario proves a request
  was never sent.
- **The side panel.** Chrome opens a side panel only on a user gesture. How the harness opens the real panel (so its port
  and messages come from the panel, not a tab, which the worker refuses) is the first thing to settle; a test-build page
  that calls `chrome.sidePanel.open` from a click is one way.
- **Scenarios.** One per row of section 4 marked simulated or **Not covered**, each a list of steps the fake planque sends
  and person actions Playwright takes (clicks, keys, closing tabs, the bar's buttons by position), with the expected
  signed results, `run_state` and `hand_result` messages, tab, group and window states (read in the worker with
  `chrome.tabs` and `chrome.tabGroups`), fixture-server logs, and `chrome.storage.session` contents. Time comes from the
  fake planque's clock and `now`, never from waiting on real timeouts where a constant can be lowered in the test build.
