# What breaks at 1,000 and 10,000 users

The question the owner asked: *what would definitely backfire at 1,000 or 10,000 users, and how is it solved across the
whole app?* This page answers it for **the apps and the Chrome extension**, from the code on main, ranked by what grows fastest
with users. It started from one finding: the Chrome side panel read tafs cloud every 4 s for as long as it was open (450 requests in
13 minutes, tafs#1787).

**Read this as a first pass.** Every finding says where it is. The load is arithmetic from the code's own intervals, not a
measurement. "At 10,000" means 10,000 *active* users, each doing the thing named; where the share who do it is a guess, the line
says so. The backend's own findings (scheduled work, sweeps, account limits) are tracked privately and not listed here:
this page keeps the pattern, the target and the rule below, which apply to every device.

## The pattern

Every finding below is a client asking "has anything changed?" on a timer, for as long as a screen or the app is open, with no
back-off and no jitter. The load grows with users times open screens, and none of it is needed when a push or the relay can say
"something changed".

## The target, for every device

A screen **learns of a change by push or by the relay**, reads once on open and on pull-to-refresh, and polls only as a
slow fallback that **backs off and jitters**. It is the same on every device (parity). Concretely:

1. **Poll only while something is happening and the app is seen.** A run working, a message just sent, a card waiting: poll;
   otherwise don't. A backgrounded app polls nothing and reads once when it comes back.
2. **Back off.** 2.5 s becomes 5 s, then 10 s, then 30 s while nothing changes, and each wait gets a random ±20%.
3. **Push where the platform has one.** Phones have APNs and FCM; tafs cloud sends a data push on "message written" and
   "run ended", and the phone reads once. Desktops and Chrome have no push for tafs cloud; the relay (planque's
   WebSocket) is the push there, and it must carry tafs cloud's changes too (today it carries only Chrome steps).
4. **One cheap "anything new?" read.** A change cursor (a version number per account) that one cheap read answers, so a
   timer's read is a few bytes, not a thread.
5. **A pull request that polls, loops, schedules, fans out or stores per user says "At 10,000 users:" in its description**, and a
   reviewer refuses it without.

## The client findings

| # | What | Per user | At 1,000 | At 10,000 | Area |
| -: | :- | :- | -: | -: | :- |
| 1 | **Android follows a taf's chat by polling** | one read per 2.5 s for ~6 min, then per 15 s for ~25 min, **even with the app in the background** | 400 req/s | **4,000 req/s** | Android |
| 2 | **Coach's thread and a taf's chat read every 1 to 2 s while they work** (iPhone/iPad, Android, Windows, Linux, macOS) | up to 60 reads a minute per open page | 100 req/s if 10% have it open and working (Windows' chat and Coach: 2 s, no cap) | **1,000 req/s** (Windows: 5,000 if all 10,000 chats work at once) | every app |
| 3 | **The Chrome extension connects to the relay about every minute or two, for ever** | one connect (two signatures, a server call, a row) while Chrome is open | ~10 connects/s | **80 to 170 connects/s** | the extension |
| 4 | **Held calls read on a timer on desktops** (macOS every 30 s with four more reads; Linux and Windows every 60 s) | 2 to 10 reads a minute while the app is open | 17 to 170 req/s | **170 to 1,700 req/s** | desktops |
| 5 | **A card waiting on its grant** (Android): 74 signed reads in 10 minutes | 7 reads a minute while it waits | 12 to 33 req/s | **120 to 330 req/s** if all have a card open | Android |
| 6 | **No timer anywhere has jitter**, and the retries after an outage are the same for everyone | fixed 2, 10, 30, 60, 120 s (phones); 1, 2, 4, 8, 16, 30 s (extension) | a synchronised burst after a blip | the same, ten times | every app |
| 7 | **People, every 20 s while the screen is open** | 3 reads a minute | small | small, but it never backs off | every app |
| 8 | **macOS `followStart`: every 10 s, no cap** | 6 reads a minute while open and Coach isn't started | small | small, unbounded | macOS |
| 9 | **The side panel's reads** (fixed in tafs#1787) | was 30 req/min per open panel | was 500 req/s | was **5,000 req/s** | the extension |

### 1. Android follows a chat by polling, in the background too

- **Where:** `android/app/src/main/kotlin/bot/tafs/app/app/AppModel.kt` `followChat`, line 2850 (`CHAT_POLL_MS` 2,500,
  `CHAT_POLL_BUDGET` 150, then `CHAT_SLOW_POLL_MS` 15,000 × `CHAT_SLOW_POLL_BUDGET` 100), `STREAM_CHECK_MS` 30 s at 7085.
  iPhone's `followChat` (`ios/Tafs/App/AppModel.swift:6341`) is 4 s × 30 per unanswered message, bounded, and reads nothing while no
  answer is owed.
- **What it does:** reads the thread up to every 2.5 s for about 6 minutes, then every 15 s for about 25 more, while a run works. It runs
  in `viewModelScope`, so it **keeps going with the app in the background** while the process lives. A stream takes over when there is one.
- **Load:** 400 req/s at 1,000 users with a run each, **4,000 req/s at 10,000**.
- **Fix:** stop when the app goes to the background and read once on resume; back off 2.5 → 5 → 10 s with jitter; then a data push
  for "run ended / new message" so the poll becomes a rare fallback. iPhone: the same rules in `followChat`.

### 2. Coach's thread and a taf's chat, every 1 to 2 s while they work

- **Where:** iPhone/iPad `followSetup` (`ios/Tafs/App/AppModel.swift:9198`, `setupWorkingPollInterval` 1 s, `setupPollInterval` 3 s,
  `setupPollBudget` 100, skipped while Setup's stream is live); Linux `coach.rs` (`WORKING_POLL`, `POLL`, `POLL_BUDGET`); Windows
  `CoachPanel.Chats.cs:65` and a taf's chat `TafPage.xaml.cs:142`, **2 s, no cap and no back-off**; macOS `CoachView.swift:807`, 4 s × 30.
  Android's `SET_UP_READ_MS` is 3 s.
- **What it does:** up to 60 reads a minute for each open Coach page, up to 100 reads (5 minutes) after the owner last wrote, and
  unbounded while a card waits on its grant (`cardWaitsOnItsGrant`, ten minutes).
- **Load:** one phone in Setup is about 1 request a second; at 1,000 people in Setup at once that is ~1,000 req/s, at 10,000 about 10,000.
  If 10% have Coach open and working it is 100 req/s at 1,000 and **1,000 req/s at 10,000**.
- **Fix:** a stream already carries Coach's words (the read is the fallback when nothing streams): read nothing while a stream is live,
  and start the fallback at 3 s, backing off 3, 6, 10, 15 s (30 s at most) with ±20% jitter, never 1 s; Windows adds the cap Android has.

### 3. The Chrome extension connects every minute or two, for ever

- **Where:** `chrome/src/sw.js:83` (`chrome.alarms.create(WAKE, {periodInMinutes: 1})`) and its handler at 84 to 95: every minute the service
  worker calls `connect()`; the connection is closed after `IDLE_CLOSE_MS` (60 s) with no run (`sw.js:17,89`). docs/chrome.md says so: "While no
  run is on, the extension wakes on an alarm every minute and connects, so planque can hand it a step. The connection drops after a minute
  with no run."
- **What it costs:** each connect is a WebSocket connect, a server call, the extension's `open`, a challenge, **two signatures** checked, and a
  connection row written. A paired Chrome that is open does this about every 1 to 2 minutes, for ever, whether or not a taf will ever use it.
- **Load:** about **10 connects/s at 1,000** Chromes, **80 to 170/s at 10,000**.
- **Why it is that way:** the extension must open the connection (nothing connects in), and a taf that needs the browser waits up to 60 s for
  the next alarm.
- **Fix, in order:** (1) hold one connection open instead of cycling it: ping every 5 minutes (the relay drops at 10 minutes idle and 2 hours in all),
  reconnect on the drop; a step then starts at once and connects fall to one per two hours per Chrome; (2) or keep cycling but wake every 5 to
  10 minutes when no taf uses this Chrome, and every minute only when one does; (3) jitter on the first connect and the retries.

### 4. Held calls on a timer, on every desktop

- **Where:** macOS `App/shell/SignedInView.swift:169` (`refreshEvery`, 30 s: held calls, Home, Your brief, access, and the halt when no taf runs);
  Linux `main.rs` `held_watch` (`hold_alert::PERIOD`, 60 s, all the time tafs runs); Windows `MainWindow.xaml.cs:89` `heldClock`, `ReadHeldAsync` :660
  (held calls **and `/v1/me`** every 60 s, until signed out or minimised more than 10 minutes).
- **What it does:** a computer has no push, so it asks. Macs ask five things every 30 s; Linux and Windows ask one or two every 60 s.
- **Load at 10,000 users with the app open:** Linux and Windows about **170 to 330 req/s**, steady; macOS about **1,700 req/s**.
- **Fix:** the relay as push (planque's WebSocket telling a desktop "something is held"), or a change cursor with a fallback of 5 minutes and jitter. macOS reads
  the five things on different schedules (held calls often, Home and brief rarely). Windows reads `/v1/me` once a session and on F5, adds ±10 s jitter, and
  stops when the window has lost focus for some minutes, not only when minimised.

### 5. A card waiting on its grant

Android `CARD_GRANT_READ_DELAYS`: 3 s for the first minute, then 10 s, for ten minutes: 74 signed reads. **At 10,000** with a card each open: 120 to 330 req/s, each a
device-signature check. **Fix:** the server says "grant listed" over a push; or back off further after the first minute.

### 6. No jitter, and the same retries for everyone

Phones `AppModel.retryWaits` (2, 10, 30, 60, 120 s: `ios/Tafs/App/AppModel.swift:2465`), Windows' foot retry, Linux's and the extension's `RETRY_MS`
(`chrome/src/relay.js:13`), and every fixed poll above. After a deploy or a blip, everyone's next read lands in the same second, and a fixed interval started at
launch lines users up on a shared clock. **Fix:** each wait gets a random ±20% (and the first read after open a random 0 to 2 s).

### 7 and 8. People and `followStart`

People reads every 20 s while open on every app (iPhone `PeopleViews.swift:64`, Android `PeopleViews.kt:91`, Linux `people.rs:442`, macOS
`PeopleView.swift:88`): 3 reads a minute, no back-off. macOS `CoachView.swift:871` (`followStart`) reads Setup every 10 s with **no cap** while Coach isn't
started. Both are small at 10,000 users but have no reason to be unbounded: back off, and stop `followStart` at a cap.

### 9. The side panel (fixed in tafs#1787)

`setInterval(drawCoach, 4000)` read `GET /v1/setup` and `GET /v1/tafs/{coach}/chats` every 4 s for as long as the panel was visible, and the service worker built a new `Coach`
per call, so the Coach id was read each time. Now a fresh conversation isn't polled, a chat is read every 4 s only while Coach works (and a minute after a send) and every 30 s
otherwise, and one `Coach` is kept per session.

## Bounded, and fine as they are

Chrome pairing (3 s, up to 10 minutes), the sign-in device flow (the server's interval), a halt's follow-up reads (2 s, up to 2 minutes), a failed doors read (1 s and 3 s, twice),
the stream check (30 s, one per open chat), the update check (once a day). The watches make **no repeated reads of their own**: they ask the phone, which answers from its state.

## The backend

The scheduled work, the sweeps and the account's limits are the backend's half of this question. They are tracked privately; what is public here is the direction: the
scheduled sweeps and schedule firings are being moved to bounded, fanned-out work.
