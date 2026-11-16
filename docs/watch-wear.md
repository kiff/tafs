# The Wear OS watch

A small companion to the Android app, on Wear OS 3 or later. It is
the same product, account and words as the phone, drawn smaller, with no sign-in of its own. It is being built in steps;
this page says what is there now and what each step adds.

## What's there now

- **`android/wear`**, the watch app. It has the phone's package (`dev.kiff.tafs`), as the Wearable Data Layer joins a watch
  app only to a phone app with the same package and signing key. It isn't standalone
  (`com.google.android.wearable.standalone` is false): it needs the phone app.
- **Its screen** (`WatchActivity`): **the microphone first**, "Ask tafs" (below), with one small block under it: each ask at
  work or ready, by its taf and tafs cloud's word for it ("Calendar · Working"; past two, how many), and Needs you's count in
  amber. Scrolling down: **Needs you**, with tafs cloud's count in its words and each held call as the phone words it (its
  taf, what it wants, KIFF's amount, the reason), each with **Approve on your phone**; then **Your tafs**, each taf's name and
  state. It's read from the phone each time the watch shows it (`WatchModel.refresh`, `readAsks`) and forgotten when it's put
  away (`forget`). Without a phone it says so (`watch.phone_not_reachable`, Try again); with the phone signed out,
  `watch.sign_in_on_phone`; with an answer it can't draw, **Open on phone**, never a guess (`WatchModelTest`).
- **Open on phone** opens `tafs://open/needs-you` on the phone (`RemoteActivityHelper`).
- **The all-clear** (the owner's illustration board 12): when nothing waits, no count and no held call
  (`NeedsYouRead.allClear`), Needs you shows a green disc with a white check above its own words. It's drawn as shapes in
  `Illustrations.kt` (`AllClearIllustration`), never an image file, and has no text in it. The words under it are the
  screen's, in the watch's language. The disc is 0.38 of the screen's width, in the board's green (`#2E9E5B`), and the check's
  points and stroke are shares of the disc, measured from the board. It's hidden from TalkBack, which reads the words.
- **`DataLayerPhone`** finds the reachable phone that declares `tafs_phone_relay` and sends it a live request, 10 s at most.
- **`android/watchlink`**, the messages the watch and the phone exchange, in plain Kotlin that both apps build on, with its
  tests on the JVM (`WatchLinkTest`).
- **Its words** are generated with the phone's from `app/strings` (`android.py`): the `watch` area and the phone's lines it
  shows as they are (`WEAR_KEYS`), into `wear/src/main/res/values*/strings.xml` and `wear/…/Words.kt`, read through the
  watch's own `Res`. A strings test fails if `WEAR_KEYS` names a line that isn't there.

- **The phone's relay** (`app/…/watch/WearRelayService`, `WatchRelay`). The phone app declares `tafs_phone_relay`
  (`res/values/wear.xml`, kept through the release build's resource shrinking by `res/raw/keep.xml`: Google Play
  services reads it and no code does, so 111 went out without it and the watch read the phone as out of range; CI's
  `play/check_watch_link.sh` fails a release build without it; `WatchCapabilityTest` ties the declaration, the keep and
  the check to the watch's own `WatchLink.PHONE_CAPABILITY`, so the name can't change on one side only) and answers requests under `/tafs/watch/v1/` only. Each passes `WatchLink.accept` first; then
  Needs you is read again (`refresh`), at most once every 10 s for the watch (`RefreshFloor`), and answered as the phone shows it: tafs cloud's count and words, and each held call's
  taf, summary, KIFF's amount and reason. Your tafs is each taf's line (`Taf.now`), or Halted. With no KIFF session (signed
  out, or the sample) the watch is told `signed_out` (`WatchRelayTest`). The phone answers only a watch connected to it now (`NodeClient.connectedNodes`).
- **Refuse and Halt from the wrist.** A held call KIFF holds that the phone can answer shows **Refuse** (one tafs cloud holds alone is signed behind the phone's own prompt, so it's the phone's to answer); each taf not halted shows
  **Halt now**, and the list ends with **Halt all now**. The watch sends one only while a screen lock is set and it's unlocked
  (`KeyguardManager.isDeviceSecure` and not `isDeviceLocked`); otherwise it sends nothing and says `watch.needs_lock`, with
  Open on phone. The phone then:
  - **Refuse:** reads Needs you again; a call answered or gone meanwhile is `already_answered`, and nothing goes to KIFF. Else
    it answers it as its own Refuse does (`AppModel.answer`, `confirmed`), signed with the **signing key** and no prompt of its
    own, the watch's unlock being the confirmation. The approval key is never asked on this path.
  - **Halt** (`AppModel.halt`): one taf, or every taf. **Halt all** is asked once more on the watch first (`halt_all.title`,
    with Halt all now and Cancel), and nothing is sent until it's confirmed. The phone reads where the halt stands first: one
    halted or halting already (a Halt sent twice, or from the phone meanwhile) is answered as it stands, and tafs cloud isn't
    asked again.
  - **Whether a call may be refused is the phone's call,** on its own fresh read (`canAnswer`), never the watch's word: a
    request carries nothing but its hold, and a call the phone can't answer here is Open on phone. A Refuse sent twice finds
    the call already answered.
  - The watch shows the result only once the phone has KIFF's or tafs cloud's answer: "Refused", "Halted", "All your tafs
    halted", tafs cloud's halting line, or what went wrong with Open on phone (`WatchRefuseHaltTest`, `WatchModelTest`).
    Until then a Halt shows **"Halting Refunds…"** (`watch.halting`, or `watch.halting_all`), never "halted" before tafs cloud
    says so (`Outcome.Halting`).
- **One model for the process.** The relay reads the app's own `AppModel`, which `TafsApplication` now owns
  (`TafsApplication.model`) and `MainActivity` draws. It isn't an activity's ViewModel any more, so closing the screen doesn't
  end it while the watch asks, and the session's hooks on the KIFF and tafs cloud clients are never two models'. Where the
  owner left off is restored once per process (`restoredModel`, `RestoreOnce`), by the screen or by the relay with the app
  closed. Restoring from the relay asks the person nothing: what needs them (the approval key's registration for a session
  from before it, behind the fingerprint) and Play's purchases are left for the screen, whose first ask afterwards does them
  once (`finishRestoreOnScreen`), so a watch read never uses them up. A request the service can't finish, even one cancelled
  before it began, answers Open on phone. **Visible on the phone:** what the screen holds in memory now lasts while the process does, so backing out of the
  app and opening it again comes back to it, where it used to start afresh (`SharedModelSourceTest`, and `SharedModelTest`
  on an emulator).
- **`tafs://open/needs-you`** opens Needs you on the phone (`AlertPayload.openedFromWatch`); any other `tafs://open/` link
  opens nothing.

```bash
cd android && ./gradlew :watchlink:test :wear:testDebugUnitTest :wear:assembleDebug
```

- **The tile** (`NeedsYouTileService`): Needs you's count, as the watch app last read it, under "Needs you"; tapping it opens
  the watch app, which reads the phone again. Under it, **Ask tafs** opens the app listening (`EXTRA_LISTEN`), one tap from
  the watch face; the app asks for the watch's lock first, as any ask does. It shows the count only while the watch has a screen lock and is unlocked,
  decided at each draw the system asks for, not the instant the lock changes. Found locked (Wear OS locks a watch taken off
  the wrist) or with no lock at a draw, it shows tafs's name, and the count is forgotten. The
  count and its time are **the only things the watch writes to disk** (`TileCount`, preferences `tafs.tile`); the phone saying
  it's signed out clears them too (`TileCountTest`).

- **Ask tafs** (`WatchModel.voice`, `WatchRelay.ask`, tafs cloud's side in [watch-ask.md](watch-ask.md)). The watch never says
  "Coach": it asks tafs, and tafs cloud picks the one taf that can do it.
  - **One tap, then speak.** The microphone opens the watch's own speech recognizer in the app (`Listener`,
    `SpeechRecognizer`, with partial results): the words show as they're heard, and **when the person stops, the text goes at
    once, with nothing to confirm** (`heard`). Cancel is the only button. Where the watch has no recognizer for apps, the
    system's speech input is used, and its text goes the same way. The first ask asks for the microphone (and, on Wear OS 4
    and later, notifications); without it the watch says `watch.mic_needed`. Only the text leaves the recognizer: the app keeps
    no audio, and the home says "tafs gets your words as text, never your voice."
  - **Only from a watch with a screen lock, unlocked**, as Refuse and Halt: an ask is an instruction to a taf. Otherwise nothing
    is sent and the watch says `watch.needs_lock`.
  - **The watch's own id for each ask** (`client_id`, `w_` and 24 random hex digits), the same on every retry, so tafs cloud
    never starts it twice. The phone signs the ask with its **device key, no prompt**, as the wrist's Refuse, and sends it to
    tafs cloud (`POST /v1/watch/asks`, `AppModel.watchAsk`), only with the AI consent given on the phone (else
    `watch.ask_needs_consent`). It sends the words and the id, and a reply's ask: never a taf, never a mark that it's the
    watch's, which tafs cloud's own route decides. The approval key is never used on this path.
  - **Sending…, then Sent** with the taf it went to and its state once tafs cloud names one ("Calendar · Working"); the
    microphone comes back after 2.5 s, so the wrist can drop.
  - **No receipt** (the phone out of reach, tafs cloud not answering, a 5xx: `unsure`): **"Not sure it arrived"**, with
    **Retry**, which sends the same words with the same id. A definite refusal says why in the phone's or tafs cloud's words
    (`watch.ask_not_on` while tafs cloud has it off; `watch.update_phone` from a phone with an older tafs).
  - **The result.** tafs cloud's push tells the phone an ask changed (`watch_ask`, with its id); the phone reads it and sends it
    to the watch (`WatchResults`, `/tafs/watch/v1/ask-result`, to the watches declaring `tafs_watch_app`), and shows nothing
    itself. On the watch (`AskResultService`), with the app open, it's drawn at once; otherwise, once ended, it's the watch's own
    notification: the taf and its line, and, locked, only "A result is ready". Tapping opens the **result card**: the taf, tafs
    cloud's checked line (at most 140 characters; with none, its word for the state), **Hear** (the watch's own text-to-speech,
    in its language) and **Reply** (the microphone again, continuing that ask: `reply_to`). An older state never replaces a
    newer one.
  - **States:** `accepted`, `working`, `done`, `no_taf` (no one taf could: none, or more than one, which its line says) and
    `failed`. One the watch doesn't know is drawn by tafs cloud's words.
  - **Kept in memory only,** and forgotten once the app is put away; which results were opened is kept for the app's process
    only, so "ready" means not opened yet. When the app opens, it reads the asks of the last day again (`asks`).
  - **Timing, on internal builds only:** a result's card shows how long listening took to start, sending took to get tafs
    cloud's receipt and the result took after it (`watch.timing`, in ms). It's on in debug builds and in a release build made
    with `TAFS_WATCH_TIMING=1` (or `-PwatchTiming`), and off in any other (`BuildConfig.TIMING`). On Play, it's the Android
    workflow run by hand with **form** `wear` and **watch_timing** ticked: an internal build for a test on a real watch. A
    build a version raise starts never has it, and neither does one for production.
  - The phone still answers the old `coach` request, for a watch from before this one (`WatchCoachTest`); this watch never
    sends it.
  - Tests: `WatchModelTest` (sent on stopping, retry with the same id, the result card, Reply, the timing, Halt pending),
    `WatchAskTest` (the device key and no prompt, the body, consent, `unsure`, the push), `WatchLinkTest`.

## Your brief (behind a switch)

Where the phone shows Your brief in Needs you's place, the watch can show it too, in tafs cloud's words. It's built and **off**:
`BRIEF_ON_WATCH` (`wear/…/WatchModel.kt`) is `false`, and turns on with the owner's yes, as that one line. Off, the watch asks and
draws exactly what it did before (the same requests, the same screens, the same store screenshots, `WatchModelTest`).

With it on (`WatchModel(briefOn = true)` in the tests):
- **The read.** After Needs you, the watch asks `brief`; then Your tafs. The phone answers from the brief it already holds
  (`AppState.brief`, read with Needs you by `AppModel.refresh`, behind the same 10 s `RefreshFloor`): no read of its own, and
  nothing new goes to tafs cloud or KIFF. The phone answers `brief` whatever the watch's switch, as answering a read is harmless.
  No brief on the account is `no_brief`, and a phone that doesn't know the path refuses it: either way the watch shows Needs you.
- **What's sent** (`WatchReply.Brief`, `WatchRelay.brief`): the title and its `when` line ("Morning brief · 08:00 · next at
  18:00"); the decisions still to answer on the phone (one set aside there isn't), in tafs cloud's order, which puts the urgent
  first, each with its question, its taf, its deadline in words, why it can't wait, and what it's about (`ref` kind and id); the
  line for more decisions; tafs cloud's word for urgent; Done's heading and its first two cases by name; the rest line; and the
  phone's own line for what Needs you holds beyond the brief, so nothing waiting is out of reach.
- **What's drawn:** the brief in Needs you's place, then Your tafs with Halt, unchanged. Each decision is a card with **Open on
  phone** (`tafs://open/needs-you`, the one destination the phone takes from the watch: Needs you's list, opened over the brief),
  and no answer buttons: decisions are answered on the phone. The rest is one card that opens Needs you on the phone.
- **Refuse, as Needs you has it.** A decision about a held call (`ref` kind `hold`) carries the call's id as Needs you lists it
  (`hold_id`, the phone matching KIFF's id in `ref`); the watch offers **Refuse** only when its own Needs you read lists that
  same id as refusable (`Shown.Read.refusable`). It's the same request, sent only with a screen lock set and the watch unlocked.
- **Unchanged:** the tile (Needs you's count, only while unlocked); a locked watch shows only "A taf needs you"; the brief is
  kept in memory and forgotten when the watch is put away. No new words: the brief's are tafs cloud's, and Open on phone and
  Refuse are the watch's own (`WatchBriefScreenTest`, `WatchBriefTest`).
- **Unlocked only, if it ever leaves the app.** A decision's question and Done's cases can name a person or an amount. They're
  drawn only inside the app's screen today, so only on an unlocked watch; a tile or complication for the brief would follow the
  tile's rule: nothing but a count, and only while the watch is unlocked.

## Versions

The watch shares the phone's package, and Play needs every `versionCode` on the listing to be unique across the phone's and
the watch's builds. The phone counts up by one from 1 (docs/android-app.md, Versions). **The watch has its own band: it
counts up by one from 400,000,001** (`android/wear/build.gradle.kts`), so the two never meet. Its `versionName` is its own
(`0.x` until it's on Play).

**400,000,002 (0.2.0)** carries the all-clear. Its notes are the watch's first in production, since Play shows only the latest
release's, so they describe the whole watch app, with Wear OS's own word for a tile in each language (Ansicht, carte,
tarjeta, scheda, mosaico, tegel, karta).

**Releases to Play** (`android.yml`'s `wear-release` job), as the phone's, on their own:
- **When:** by hand (Run workflow, form `wear`), or on a merge to main that raises the watch's `versionCode` (the `version`
  job compares the phone's and the watch's each on their own), once `test` passed, which runs the watch's tests.
- **The bundle:** `:wear:bundleRelease`, signed with the phone's upload key from Secrets Manager, in the `android-release`
  environment. The Data Layer joins the two apps only when Play signs both with one key, and Play's app signing re-signs
  every upload with the listing's key. It isn't kept as a run artifact: the repository is public, so Play holds the only copy.
- **To Play:** once the repository variable `WEAR_UPLOAD` is `on`, `play_internal.py --wear --upload` sends it to the Wear
  OS internal track, `wear:internal`, and no other: one edit, the bundle, the track's release with its notes, committed.
  The sign-in is the phone's (workload identity federation). A bundle Play reads as another `versionCode` commits nothing.
- **Release notes:** `android/play/release-notes/<versionCode>/<locale>.txt`, in the same 8 languages, the watch's codes
  being in their own band. `test` checks the watch's current ones (`play_internal.py --wear --check`).
- **Before `WEAR_UPLOAD` goes on:** Play takes a watch bundle only once the listing has the Wear OS form factor (Play
  Console › Test and release › Advanced settings › Form factors), which is a store-listing change and the owner's.

**The store screenshots** (`WearStoreScreenshots`): the watch's real screen, drawn on the JVM (Robolectric and Roborazzi,
test-only) in each of the 8 languages, round, 384 × 384 px, the smallest size Play takes for Wear OS. They're filled with the
phone app's own sample tafs and words (`app/strings/<lang>/sample.json`, `taf.json`) and the watch's own strings, with
nothing drawn over them, and written as opaque RGB PNGs, since Play takes no alpha (`opaqueForPlay`). The amount is written as the phone sends it (`Money.formatted`: a whole amount has no cents). Four per language, into `android/play/wear-screenshots/<locale>/`:
- `1-ask-tafs`: the microphone, with an ask at work, one ready and Needs you's count under it;
- `2-result`: a result, with Hear and Reply (its line is one of the phone's sample lines);
- `3-needs-you`: a held refund, with Refuse and Approve on your phone;
- `4-all-clear`: nothing needs you, with the all-clear above the words.

They're redrawn only when asked:

```bash
cd android && ./gradlew :wear:testDebugUnitTest --tests 'bot.tafs.wear.WearStoreScreenshots' -PwearScreenshots
```

Otherwise the same tests still draw every screen, without writing a file, so a screen that no longer draws fails CI.

## How the watch talks to KIFF and tafs cloud: through the phone

The watch holds **no keys, no session and no KIFF client**. It sends the phone a request over the Data Layer, the phone
reads or signs with its own session and keys, and the watch draws the answer in the phone's words. A lost watch can read
and stop things, never approve.

The watch may ask eight things (`WatchRequest`), each a path under `/tafs/watch/v1/`:

| Path | What the phone does |
| --- | --- |
| `needs-you` | Reads Needs you: tafs cloud's count and its words, and each held call (its taf, what it wants, KIFF's amount, the reason) |
| `tafs` | Reads each taf's state in one line |
| `refuse` | Refuses one held call, by `hold_id` |
| `halt` | Halts one taf (`taf_id`), or every taf (`all: true`, in so many words: a body without its taf is never every taf) |
| `coach` | Sends Coach a message as text (a watch from before Ask tafs; this one never sends it) |
| `ask` | Ask tafs: `client_id`, `text`, `reply_to`; signed with the device key and sent to tafs cloud, which answers its receipt |
| `asks` | Reads the watch's asks as tafs cloud has them, updated after `since` (or in the last day) |
| `brief` | Reads Your brief as the phone shows it, or says there's none (`no_brief`); asked only with the watch's switch on (below) |

And the phone sends the watch one message, never a request it answers: **`ask-result`**, an ask's new state, when tafs
cloud's push says it changed.

**There is no approval request.** Approving a held call, a Card, a payment, a connection or extra room is a signature with
the phone's approval key behind its fingerprint, so on the watch each says **Approve on your phone** and opens it there.

**What the phone refuses before reading or signing anything** (`WatchLink.accept`, `NotAccepted`):

- a path that isn't one of the eight (an approval, a newer version, `ask-result`): `UNKNOWN_PATH`;
- a body that isn't exactly what its path takes, a field missing, extra (`"answer": "approve_once"` on a refusal) or blank,
  or a Coach message or an ask that's empty or over 2,000 characters, or an ask whose id isn't 8 to 64 letters, digits, `_`
  or `-`: `MALFORMED`. An ask can't name its taf or mark itself: a field it doesn't take is refused;
- a request made more than 30 seconds ago: `TOO_OLD`. Requests are live messages (`MessageClient.sendRequest`), never
  `DataItem`s, so nothing queued while the phone was away lands later;
- a request dated more than 5 seconds ahead of the phone's clock: `FROM_THE_FUTURE`.

The phone answers with a `WatchReply`: the lists, the result in its own words once KIFF or tafs cloud has answered
(`done`, `failed`, `already_answered`), `signed_out`, Coach's answer, an ask's receipt (`asked`), the asks (`asks`), `unsure`
(sent, with no answer from tafs cloud), or `not_accepted` with its reason. A reply of a kind the
watch doesn't know is shown as Open on phone, never guessed at.

## The rules it's built to

- **Refuse and Halt from the wrist** are signed by the phone with its signing key and no prompt of its own, as a
  notification's Refuse on iPhone: they only take authority away. The phone acts only on a message from the paired watch
  running this app, and the watch sends one only while it has a screen lock and is unlocked; otherwise it says to set one
  (`watch.needs_lock`) and offers Open on phone. The approval key is never used on this path.
- **What the watch keeps:** on disk, only Needs you's count and its time, for the tile, cleared on sign-out and when the
  watch comes off the wrist. The lists, Your tafs and the asks and their results stay in memory. No tokens or keys, and no amount,
  merchant or person's name in a log on the watch or in the phone's relay.
- **Voice:** the watch's own speech recognizer makes text, and only the text goes to the phone and tafs cloud, after the AI
  consent already given on the phone. No audio is kept or sent to tafs cloud. Speaking is an instruction to a taf, never an
  approval: the taf acts under its own Cards.
- **A locked watch** shows only "A taf needs you" for an alert the phone bridges to it, as a locked phone does.
- **Words** come from `app/strings` (`watch.*` and the phone's own keys), in all 8 languages.

## Still to come

1. The listing's Wear OS screenshots are drawn (below); putting them on the listing, and the Wear OS opt-in and review
   policy for production, are the owner's, in Play Console.
2. On a real watch, with the always-on display on, before the internal track: neither the screen nor the tile shows a held
   call's details while the watch is locked or dimmed, and the app forgets them on `onStop` (from kiff/tafs#1161's review).
3. A watch-face complication for Ask tafs: the tile's Ask tafs is the one-tap entry for now.
