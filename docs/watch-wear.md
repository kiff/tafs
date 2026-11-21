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

- **Ask tafs on the watch face** (`AskComplicationService`, kiff/work#14): a complication the person adds to their face, a
  microphone (with "tafs" beside it where the face shows text) that opens the app listening, as the tile's Ask tafs does
  (`WatchActivity.EXTRA_LISTEN`; the watch's lock is asked first, as for any ask). Short text, monochromatic image or small
  image, whichever the face's slot takes; TalkBack reads "Ask tafs". It shows nothing else, no count, no taf and nothing a held
  call says, so a locked or dimmed face shows only the microphone, and it's never updated (`AskComplicationTest`).
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
    tafs cloud (`POST /v1/watch/asks`, `AppModel.watchAsk`). tafs cloud checks the AI consent given on the phone, and its
    refusal (`ai_consent_required`) reaches the watch as `watch.ask_needs_consent`. It sends the words and the id, and a reply's ask: never a taf, never a mark that it's the
    watch's, which tafs cloud's own route decides. The approval key is never used on this path.
  - **Sending…, then Sent** with the taf it went to and its state once tafs cloud names one ("Calendar · Working"); the
    microphone comes back after 2.5 s, so the wrist can drop.
  - **No receipt** (the phone out of reach, tafs cloud not answering, a 5xx: `unsure`): **"Not sure it arrived"**, with
    **Retry**, which sends the same words with the same id. A definite refusal says why in the phone's or tafs cloud's words
    (`watch.ask_not_on` while tafs cloud has it off; `watch.update_phone` from a phone with an older tafs).
  - **The result.** tafs cloud's push tells the phone an ask changed (`watch_ask`, with its id); the phone reads that one ask (`GET /v1/watch/asks/{id}`) and sends it
    to the watch (`WatchResults`, `/tafs/watch/v1/ask-result`, to the watches declaring `tafs_watch_app`), and shows nothing
    itself. On the watch (`AskResultService`), with the app open, it's drawn at once; otherwise, once ended, it's the watch's own
    notification: the taf and its line, and, locked, only "A result is ready". Tapping opens the **result card**: the taf, tafs
    cloud's checked line (at most 140 characters; with none, its word for the state), **Hear** (the watch's own text-to-speech,
    in its language) and **Reply** (the microphone again, continuing that ask: `reply_to`). An older state never replaces a
    newer one.
  - **The answer you can't miss** (v2, W3; the owner's Watch 5 test: it flashed past, and then they didn't know where it
    was). With the app open, the answer to an ask sent from this watch opens by itself when it ends, wherever the app is,
    unless a new message is being recorded or another answer is open: then it waits, unread, in Coach's home and the Inbox
    (`WatchModel.took`, `awaiting`, in memory only). The result card scrolls to its end and closes only with **Close** or the
    system's back, never on a tap on its words. With the app put away, its notification is on a **high-importance**
    channel (`replies`; the old default `results` channel is deleted, since a channel's importance can't be raised), so it
    peeks and buzzes and stays until opened or swiped away. Its words keep no amount (`AskResults.alertText`, watchlink's `Amounts.strip`, as
    the Inbox and the Apple Watch's alert): when one was taken out, "The rest is on your phone." follows; opened, the answer
    shows whole. The answer card is a `ScalingLazyColumn`, scrolled by the crown or bezel as by a swipe.
  - **States:** `accepted`, `working`, `done` (a taf's result, or Coach's own answer), `no_taf` (Coach's turn ended with
    nothing to show) and `failed`; their words are tafs cloud's ("Sent · Coach has it", "Coach is answering", docs/watch-ask.md). One the watch doesn't know is drawn by tafs cloud's words.
  - **Kept in memory only,** and forgotten once the app is put away; which results were opened is kept for the app's process
    only, so "ready" means not opened yet. When the app opens, it reads the asks of the last day again (`asks`).
  - **Timing, on internal builds only:** a result's card shows how long listening took to start, sending took to get tafs
    cloud's receipt and the result took after it (`watch.timing`, in ms). It's on in debug builds and in a release build made
    with a `-test` version name, `TAFS_WATCH_TIMING=1` or `-PwatchTiming`, and off in any other (`BuildConfig.TIMING`). On Play, it's the Android
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

## Version 2 (behind a switch, being built)

Wear v2 follows the owner-approved mockup v2.1 (kiff/work `watch-v2/`). It's built behind `WATCH_V2`
(`wear/…/WatchPages.kt`, from `BuildConfig.WATCH_V2`): **on in every release build from 0.4.0** (400,000,005), the build the
owner tried on a Galaxy Watch 5 and sends to Play's review, and off in debug builds and the unit tests, which pass it where they
need it. What waits on a server piece stays behind its own switch (step vii). Steps: (i) the phone's three new reads (`taf`, `calendar`,
`account`, above); (ii) the pages and Coach's home; (iii) record, review and send, with Type and Quick reply; (iv) the inbox
kept on the watch; (v) Tafs, a taf's page, its settings and choosing which to halt; (vi) the held call, the calendar day and
Account; (vii) the switches for what needs a new server piece (a watch ask as a Coach chat, the chats read, the inbox's read and
dismissed state, an ask to one taf, alerts per taf), flipped as each lands.

**Step (ii), what's built** (`WatchPages`, `WatchPagesTest`):
- **The pages,** side by side, moved between with a swipe, with the page dots at the foot: Coach (home), Inbox, Tafs, Brief and
  Account. Brief is there only with Your brief on (`BRIEF_ON_WATCH`). The microphone's screens, a result's card and Halting take
  the whole face, as in v1.
- **Coach's home:** the microphone, named "Ask Coach", with the asks at work under it; how many wait in the Inbox ("2 unread in
  Inbox"), which goes there; the line that Coach gets words, never a voice; and **Menu** at the foot.
- **Ask Coach as one conversation** (W4, the owner-approved Watch Coach mockup; `CoachThread.kt`, `WatchModel.thread`): above the
  microphone, today's turns from this watch, oldest first, so the page opens on the microphone with the newest turn just above
  it. Each turn is the person's words on the right, with when they were sent beside them, and Coach's answer on the left: its line once the
  ask has ended (a tap opens the answer card, with Hear and Reply), or tafs cloud's state for it while it's at work. Once it ended, the answer
  has its time beside it too: beside, not under, so a turn is no taller and the newest stays in sight over the microphone; and a
  header goes where a day starts (row 44, C; "When each message was sent" in docs/chat.md). TalkBack
  hears an answer as "Coach: …"; a turn at work is heard as its state line alone, which already names Coach. **Memory
  only:** what was said is held with the asks and forgotten with them, when the app is put away and on sign-out, so the watch
  keeps nothing new ("What the watch keeps"). Until tafs cloud keeps the watch's conversation (for the phone's "From your
  watch", W11), it's what this watch said since the app was opened.
- **The menu:** every page by name, and **Halt** at the very bottom, apart from the rest, offered only while a taf runs. It
  opens Choose which to halt (step v, below).
- **The Inbox, Tafs and Account** were stand-ins here, as v1 reads them; steps (iv), (v) and (vi) below replace them. Before the
  phone answers, or when it can't, each page says so in v1's words.
- **Words:** `watch.page.*`, `watch.inbox_unread`, `watch.inbox_empty`, `watch.menu` and `watch.trust` are new, in all 8
  languages (legal-i18n's list); the rest are the phone's own (`coach.ask`, `coach.title`, `home.title`, `settings.title`,
  `taf.halt.title`). Nothing new is asked of the phone, and nothing is stored on the watch.

**Step (iii), first half: record, check, send** (`ReviewScreens.kt`, `WatchModel(reviewFirst = true)`, `WatchModelTest`,
`WatchPagesTest`). Nothing leaves the watch before Send:
- **Not cut off by a pause** (`Dictation`, `Listener`, row 44, A; `DictationTest`): the system's recognizer ends a stretch on its
  own silence rule, or answers "no match"; the watch listens on across those, joining what each stretch heard into one text on
  the screen, and ends only on ✓ (Stop, or the 30 s limit) or after about 8 s with no new words. Nothing said in those 8 s is
  "Didn't catch that". A recognizer that fails outright (no network, busy) ends it with what was heard so far. Only with the
  watch's own microphone; a test build's app audio (`KEEP_AUDIO`) stays one stretch. A word said in the instant between two
  stretches may be missed, which only a real watch shows. Handing the recording to the phone when the recognizer gives up is
  not built.
- **The microphone opens its screen already listening** (`WatchModel.talk`, row 44, the owner's Galaxy Watch review: one tap,
  not two): Ask Coach, a taf's microphone, Reply, the tile's Ask tafs and the watch-face complication all go straight to
  listening, with the words shown as they come. A watch without a screen lock, or locked, gets its line instead, and never
  listens. The ready screen (`Voice.Ready`: **Start**, a round button in the brand's colours with the microphone,
  "Ready · 30 s", and what to do) is shown only while the microphone's permission is asked, the first time: listening, and its
  30 s, start once it's allowed, never behind the system's dialog (`WatchActivity.talk`). Listening, the screen shows "● Recording · 30 s" in red, the timer against
  0:30, a ring that fills to the limit, the words as they're heard (two lines, 68% of the face's width up to 150 dp, so on the 40 mm Watch 5 they stay clear of
  the curve, `theHeardWordsStayInsideTheFaceAt40mm`; the review has them all), and **Stop**: another
  control, not Start relabelled, a solid red button with a white square and a halo, as mockup v2.1 draws it (`RECORD_START`,
  `RECORD_STOP`). The top line is kept short for the top of a round face, and TalkBack reads it in full ("Ready · 30 s at
  most", `watch.limit`); it sits 12 % of the face down (`TOP_INSET_SHARE`), where the face is wide enough for it (the Galaxy
  Watch 5 test, W8). At 30 s (`LISTEN_LIMIT_MS`) listening stops by itself (`Listener.stop`). Reply opens it the same way, for
  the same ask.
- **Stopped, or the recognizer heard the end:** the words go to **Check before sending** (`Voice.Review`), never to the phone.
  It shows them in full, scrolled with the crown or a swipe, starting below the face's top curve, and fading out at the curve
  and above the foot rather than being cut. Its three actions are pinned at the foot, always in reach without scrolling (W9):
  round icon buttons in a row that follows the face's bottom curve, **Delete** (✕, left: "Deleted. Nothing was sent.", which
  closes by itself after 2 s), **Send** (→, in the middle, the biggest, in orange) and **Re-record** (↻, right: the words
  dropped, ready again for the same ask). Each is named for TalkBack in the shared words (`chat.delete`, `common.send`,
  `watch.rerecord`). The last line of a long message rests above them (`REVIEW_FOOT`), so it's readable to its end.
- **Type** opens the system's text input (`RemoteInput`: keyboard, handwriting or voice typing) and **Quick reply** four
  ready-made lines; each goes to the same review. On Coach's home each is its icon over its word (a keyboard, a speech bubble
  with a bolt; `IconOverWord`), the word on two lines at most and never broken inside: when a word is wider than its button
  (German's "Schnellantwort" on the 40 mm face) its size steps down from 12 sp, to 9 sp at least (`WholeWords`). `IconsTest`
  holds all nine languages to it on the 40 mm face. Everywhere else on the watch a keyboard types, a microphone talks and ↻
  retries, icons only, each named for TalkBack in its word (row 44, B). Blank is nothing; the words are cut at 2,000 characters, as an ask is.
- **Send** sends what v1 sends (`ask`, a new id), checked against the watch's lock again, since it may have locked while the
  words were read; from there Sending, Not sure it arrived (Retry, the same id, a round ↻) and Not sent are v1's. **Sent stays until the answer** (W2, the owner's Watch 5 test: a Sent that closed after 2.5 s left nothing showing Coach had it): tafs cloud's state for the ask in its own words ("Support replies · Working"), a ring turning round the check while it's at work, and the answer in its place the moment the ask ends; a tap goes back to Coach's home, and the answer then comes as the result's alert (`SentScreenV2`). v1's Sent still closes after 2.5 s.
- **Keep for later** (`WatchModel.keepForLater`, `PreferencesDraftStore`), on "Not sure it arrived", where the phone may not
  have got it. One message, **text only**: its words, its ask id and the ask it continues, in the watch's private preferences
  (`tafs.draft`). Coach's home shows it with "Kept on this watch. It's sent when your phone is in reach again." and **Delete**.
  It's sent at the next read that reaches the phone, from an unlocked watch only, under its **own id**, so tafs cloud starts it at
  most once; once taken it's gone from the watch and shows as an ask at work, and anything else leaves it for the next read. It's
  deleted on Delete and when the phone says it's signed out. Putting the app away keeps it, and so does the watch locking, since
  the person asked for it to be sent. v1 offers none (`v1OffersNoKeepForLater`).
- **Play and Pause** (`VoiceAudio.kt`, `Listener(keepAudio = true)`), **only in a test version** (`BuildConfig.KEEP_AUDIO`: a
  `-test` `versionName`, or `TAFS_WATCH_AUDIO=1`). On the owner's Galaxy Watch 5 the app's own recording wasn't the path the
  watch took, so the probe never ran (#1716, W1), and a production build keeps no recording: its review is the words alone.
  In a test version the app opens the microphone itself (`AudioTap`,
  16 kHz mono PCM) and hands the same sound to the watch's recognizer through a pipe (`RecognizerIntent.EXTRA_AUDIO_SOURCE`, Wear
  OS 4 and later), so the recording and the words are one capture. **Stop** (or the 30 s limit) ends that sound first
  (`AudioTap.endFeed`): the microphone stops and the pipe closes, so the recognizer reaches the end of the audio and returns the
  words, and the recording stays for Play. Before, Stop only asked the recognizer to stop while the app went on filling the
  pipe, and the owner's Galaxy Watch 5 (0.4.0-test) answered "Didn't catch that" (`AudioTapTest`). The recording is a WAV in the app's private files
  (`files/voice/`), kept beside the review only, under its title and above the words: **Play** (▶) and **Pause** (‖), a round
  icon button named for TalkBack (`watch.play`, `taf.pause`), go on from where it was, with "0:03 / 0:12". It never
  leaves the watch, only the words do. It's erased the moment the review is left, by whatever leaves it (`WatchModel.voiceNow`):
  Send, Delete, Re-record, Cancel, a lock at Send, put away, and signed out; v1 keeps none, and Keep for later is text only. Any
  recording no screen holds is swept when the app starts and when it's put away (`VoiceFiles.sweep`).
- **The probe** (internal builds only, a small line on the review): what the recognizer did with the app's audio, as "audio:
  kept 7200 ms · peak 9100", "audio: recognizer refused it (error 5)" or "audio: microphone not opened". A recognizer that
  refuses it before it's ready to listen is noted for the app's life, and the next Start listens with the watch's own microphone
  as v1 does, with no recording and no Play. Whether the Galaxy Watch 5's recognizer takes it is checked on the owner's watch; if
  it doesn't, the choice is the owner's (PM 19 Nov 01:19).
- **v1 is unchanged** with the switch off: what's heard is sent the moment the person stops (`whatsHeardGoesAtOnceWithNothingToConfirm`).
- **Words:** `watch.type`, `watch.quick_reply`, `watch.qr.*`, `watch.type_hint`, `watch.review`, `watch.rerecord`,
  `watch.deleted_nothing_sent`, `watch.limit`, `watch.recording`, `watch.ready`, `watch.tap_start` and `watch.listening`, in
  all 8 languages; Start, Stop and Delete are the phone's (`coach.start`, `chat.stop`, `chat.delete`).

**Step (iv): the Inbox kept on the watch** (`Inbox.kt`, `WatchModel.inbox`, `PreferencesInboxStore`). Each held call Needs you
lists and each ask that ended (Coach's or a taf's answer) is one line of the Inbox, newest first, kept on the watch until it's
dismissed: its taf, its words, and a blue dot while it's unread. An answer opens its result card (from the line alone if the app
was put away since), which reads it; a held call shows what it wants, and its amount, while Needs you still holds it, and a tap reads it and opens
it whole (step vi), where Refuse and Approve on your phone are; else it says "Already answered, or it ended.". Each line has **Dismiss**, and
**Clear read** takes every read line away; either stays taken away while the phone still lists the line (its id is kept, and
dropped once the phone no longer lists it), so a read never brings it back; a lock clears the lines but keeps those bare ids, so a dismissed line doesn't come back
unread after it. **When the Inbox can't be kept** (locked, or no screen lock), nothing is stored and the page shows what the
phone answered now, Needs you's calls and the results ready, with Coach's home counting those (`WatchModel.inboxKept`): a call
that needs the person is never hidden behind "Nothing new". Coach's home counts only the unread lines. It's kept in the watch's private preferences
(`tafs.inbox`, one JSON list), and like the tile's count its lines are cleared, and nothing is added to it, whenever the watch is locked or
has no screen lock (Wear OS locks a watch taken off the wrist): checked when the app is opened and when it's put away. It's
also cleared, with the ids taken away, when the phone says it's signed out, and with everything else when the person signs out on this watch (step vi). Read and dismissed stay
on this watch until tafs cloud keeps them in step with the phone (server piece c).

**Step (v): Tafs, a taf's page, its settings, and choosing which to halt** (`TafScreens.kt`, `WatchModel.openTaf` and
`haltPick`, `TafScreensTest`, `TafModelTest`). Halt is never on the main screens:
- **Tafs:** each taf with its state, in the phone's words; a tap opens **its page**, read from the phone (`taf`, by its id),
  in memory only, as the rest. Before the phone answers it shows the taf's name from Your tafs and "Asking your phone…"; signed
  out, no phone (with Try again), the phone's own words with Open on phone, or "Update tafs on your phone" for a phone that
  doesn't know the read: v1's words. A late answer for a page since closed, or another taf's page, is never drawn.
- **A taf's page:** its name and state (Halted in red), **What it does** and its job, **Its Card** with each Card's title, its
  face's figure and line, and each rule on a line, as the phone's Card tab draws them; **Waiting for you** with how many of its
  calls wait ("2 need you"), which goes to the Inbox, where they are with Refuse and Approve on your phone, or "Nothing waits for
  you." Halted, it says "Halted. Resuming starts only new work." and **Resume on your phone**: resuming asks for a fingerprint or
  face, so it's the phone's. Then **Its settings ›**, "Swipe left for its settings", and Close.
- **Its settings,** one swipe left (a second page beside the taf's), or Its settings: "{taf} · settings", **Open on phone**, and,
  apart at the end, **Halt this taf**, in red. It's asked once more ("Halting stops it until you resume. A call already sent may
  still arrive.", Halt now, Cancel); Halt now sends v1's Halt for that taf, only from an unlocked watch with a screen lock, shows
  "Halting {taf}…" until the phone has tafs cloud's word, then the page read again, with the phone's word for the result. A
  halted taf's settings offer Resume on your phone instead.
- **Choose which to halt,** from the menu's Halt: **All your tafs**, then each taf not halted, each a switch (a taf halted
  already isn't offered), and **Halt now · N**, off with none picked. It's asked once more: every taf offered picked is "Halt all
  your tafs?" and sends one Halt all (`all: true`); one or a few are named ("Late parcels, Invoices"), with the line for one or
  for several, and send one Halt per taf picked, one after the other, each to the end, so one that fails never keeps the next
  from halting. "Halting …" shows until the last answers, then what came of each, by name, in the phone's words, with Open on
  phone if any didn't go through (`Outcome.Several`). Cancel goes back to the choice, then to the menu; nothing is sent before
  Halt now, and nothing from a locked watch or one with no screen lock.
- **Left out until their server piece** (step vii): the mockup's **Tell {taf}** (an ask addressed to one taf: today tafs cloud
  picks the taf) and **Alerts from this taf** (alerts per taf on the watch). Open on phone and Resume on your phone open Needs you
  on the phone, the one place a watch's link opens there today (`Alerts.openedFromWatch`); opening the taf itself is the phone's
  to add.
- **Words:** `watch.what_it_does`, `watch.nothing_waits`, `watch.its_settings`, `watch.swipe_settings`, `watch.settings_of`,
  `watch.halt_this`, `watch.resume_on_phone`, `watch.halt_pick_title`, `watch.all_your_tafs`, `watch.halt_now_count` (a count)
  and `watch.halt_all_q`, in all 8 languages (legal-i18n's list); the rest are the phone's (`card.its_card`,
  `chat.waiting_for_you`, `halt.explain.halted`, `halt.explain.running_one`, `halt.explain.running_all`, `halt.now`,
  `taf.halt.title`). Nothing new is asked of the phone.

**Step (vi): a held call, a calendar day, Account and Sign out on this watch** (`AccountScreens.kt`, `WatchModel.readCalendar`,
`readAccount`, `signOutHere`, `AccountModelTest`, `AccountScreensTest`). Each opens over the pages (`Over`); back or Close returns
to them:
- **A held call:** the Inbox lists each held call in a line (its taf, what it wants, the amount); a tap opens it whole, as the
  phone's Needs you words it: its taf, what it wants, KIFF's amount and reason, and "Nothing is sent until you approve." It's
  drawn from Needs you's read as it stands, so a call answered meanwhile closes. **Refuse** only where Needs you's read says the
  wrist may (`refusable`), through v1's path: from an unlocked watch with a screen lock, signed by the phone. **Approve on your
  phone** opens Needs you there (`tafs://open/needs-you`) and shows "Check your phone" with OK, which goes back to the Inbox and
  reads it again. Approving is never on the watch. The phone opens Needs you's list, not the one call (`AlertPayload.openedFromWatch`
  knows only `needs-you`), so the line says "Needs you is open there. Approve the call there." and not "on this call".
- **A calendar day:** "Today's calendar", at the foot of the Tafs page (and of Brief, where the brief is on), reads the
  `calendar` read for today in the account's zone: tafs cloud's line for the day (in red when the account is halted), then each
  event in tafs cloud's order with its time, its taf, its words and "×3" when it happens more than once. **Previous day** and
  **Next day** read the day before or after the one shown; the title is Today only for the day the phone answered as today, else
  the date in the watch's language. It's in memory only and forgotten on Close.
- **Account**, read from the phone (`account`) each time the page is shown: Signed in (the email and the account's name, the
  name only when it isn't the email itself, as a personal account's is: the phone's `WatchRelay.account` leaves it out), Plan
  (the plan's line as the phone's Account shows it), the watch's language, its version, **Open on phone**, **Sign out** and the
  trust line. With the phone out of reach it says so, with Try again, and Sign out still works: it's the watch's own.
- **Sign out,** asked once more ("Sign out on this watch?", "Your phone stays signed in. This watch forgets your tafs, chats and
  inbox."), is on this watch alone: nothing is sent to the phone. The watch forgets everything it holds: what it showed, the asks
  and the results opened, a review and its recording (every recording is swept), the message kept for later, the tile's count,
  the calendar and Account, and its own result notifications. It keeps one flag in its private preferences (`tafs.sign_in`,
  `PreferencesWatchSignIn`) and shows only "Signed out on this watch. Your phone stays signed in." with **Sign in with your
  phone**, across restarts, until the person taps it. Meanwhile it reads and sends nothing (no read, ask, Refuse or Halt, the
  tile's Ask tafs included) and shows no result notification. Sign in clears the flag and reads the phone again.
- **Left out:** "Alerts on this watch". The watch's own notifications are only an ask's result; Needs you's alerts are the
  phone's, which Wear OS bridges, and the watch app doesn't switch them today. A switch with that name would leave those on, so
  it waits until it can switch both.
- **Words:** `watch.check_phone_title`, `watch.check_phone_sub` (legal-i18n's line without "on this call"),
  `watch.nothing_sent_until`, `watch.calendar_today`, `watch.signed_in`, `watch.sign_out_q`, `watch.sign_out_sub` and
  `watch.signed_out_here` (not in legal-i18n's list) are new, in all 8 languages; the rest are the phone's (`common.ok`,
  `calendar.today`, `calendar.previous_day`, `calendar.next_day`, `calendar.reading`, `calendar.times`, `settings.plan`,
  `settings.version`, `settings.language_title`, `settings.sign_out`, `sign_in.with_phone`).

**Back** (`BackHandler`, `WatchBackTest`): the system's back gesture, a swipe from the left edge, closes what's drawn over the
pages, through the same action as its own Cancel or Close, and never the app. The menu and Quick reply close (nothing picked);
Choose which to halt goes from the question to the choice, then to the menu (`cancelPick`); a taf's page goes from Halt this
taf's question to its settings, from its settings to its page, then to the pages; a held call, the hand-off, the calendar
(`closeCalendar`) and Sign out's question close, nothing answered or signed out; a result's card closes; on the microphone's
screens (Ready, recording, the review, and v1's after them) back is Cancel (`closeVoice`), so the review's recording is erased as
it's left. While the words are being sent, or a Halt is, back is taken and does nothing: the phone's answer is shown when it
comes. On the pages themselves back is the system's own, and leaves the app.

**Colour** (W7, the owner's Galaxy Watch 5 test: "every button is purple"): colour by meaning, from the watch's one palette,
`Look` (`Look.kt`), and only where it means something. **Send and the person's own words** are orange (`Look.send`): the
microphone, the words as they're heard and on the review, a message kept for later, Send, Retry and Reply; in Coach's
conversation, the person's bubble is on `Look.mineGround`. **Coach** is blue (`Look.coach`; its bubble on `Look.coachGround`): an ask at work and its turning ring on Sent, an answer's taf and Hear, an answer in the Inbox and its unread dot,
the answer's words in `Look.onCoachGround`. **Held, waits for you** is amber (`Look.held`): Needs you, a held call and its
reason, Approve on your phone, "1 needs you" on a taf's page, the mark on Not sure it arrived. **Done and sent** is green
(`Look.done`): the check on Sent, and the dot of an ask ready (v1). **Delete, halt, refuse and sign out** are red
(`Look.danger`): a halted taf's state, the switches of Choose which to halt, and Halt now, Halt this taf and Sign out asked again
as red buttons; the review's ✕, the menu's Halt, Refuse, Delete on a kept message and Account's Sign out as grey ones with red
words or a red mark. Every other button is grey (`Look.neutral`, words in `Look.onNeutral`; `Look.raised` on a card). Words on orange, amber, green and red are dark,
for contrast. The theme (`WatchTheme`, `WatchColors`) is Wear Material3's with primary `Look.send`, its containers
`Look.neutral` and error `Look.danger`, so nothing is Material's default purple (`WatchColoursTest`). Recording keeps mockup
v2.1's red (`Look.recording`) and Start the brand's colours.

**Screenshots** (`WearV2Screenshots`): v2's screens for review, not for Play, drawn through `WatchPages` (the switch stays off) in
the watch's theme, in English with the sample tafs (Late parcels, Support replies, Lead follow-up), at the Galaxy Watch 5's two
sizes, 40 mm (396 × 396 px) and 44 mm (450 × 450 px), as xhdpi round faces of 198 and 225 dp: Coach, the Inbox with an unread
line, Tafs, a taf's page, its settings, Choose which to halt, a held call, the calendar day, Account, the review with a long
transcript (its actions pinned at the foot), Recording, Sent while Coach works, an answer's card, Halt asked once more, a taf's
chat with its send bar, Not sure it arrived with ↻, and Type and Quick reply under their icons in English and German. 36
images, into `android/play/wear-screenshots-v2/<40mm|44mm>/`, only when asked, so the store screenshots stay as
they are; otherwise each screen is still drawn:

```bash
cd android && ./gradlew :wear:testDebugUnitTest --tests 'bot.tafs.wear.WearV2Screenshots' -PwearScreenshots
```

## Versions

The watch shares the phone's package, and Play needs every `versionCode` on the listing to be unique across the phone's and
the watch's builds. The phone counts up by one from 1 (docs/android-app.md, Versions). **The watch has its own band: it
counts up by one from 400,000,001** (`android/wear/build.gradle.kts`), so the two never meet. Its `versionName` is its own
(`0.x` until it's on Play).

**400,000,006 (0.4.1)** is the owner's Watch Coach build (the mockup approved 19 Nov): W2 (Sent stays until the answer), W3
(the answer you can't miss), W4 (Ask Coach as one conversation), W7 (colour by meaning), W8 and W9 (round screens, Send at the
foot), the crown on the answer, no amount in the alert, TalkBack's "Coach: …" and the page back on the microphone after a
send. No recording kept (`KEEP_AUDIO` off), as 0.4.0. It goes to `wear:internal` only, for the owner's test, and is promoted
to production only **after 0.4.0's review passes**, so the review isn't restarted (PM, 19 Nov 13:04).

**400,000,009 (0.4.4-timing)** is the measuring build for row 44 (D0): its `-timing` name draws the link's line (lookup,
reply, bytes, or why it failed; "Measuring the link", above), with no recording kept (`KEEP_AUDIO` off). For the owner's 10
minutes on the Galaxy Watch, each screen opened twice near the phone. To `wear:internal` only; never promoted to production,
whose next raise has a plain name.

**400,000,008 (0.4.3)** adds Ask tafs on the watch face (the complication, #1570) and Korean (#1572), and no hidden
direction character from a server reaches the watch (#1549). To `wear:internal` on merge, with its notes in the 8
languages and Korean (ko-KR, the listing's language since 20 Nov).

**400,000,007 (0.4.2)** turns chatting with a taf on (`WATCH_TAF_CHAT`, the owner's picks 1A, 2A and Read more), with
a taf's message never kept for later (#1515), for the owner's tap-to-text timing on the Galaxy Watch. tafs cloud's switch
stays per account. To `wear:internal` on merge, with its notes in 8 languages.

**400,000,005 (0.4.0)** is v2 for Play's review, the owner's direction after the Galaxy Watch 5 test: v2 on, with no timing
or probe line and no recording (`KEEP_AUDIO` off), from the same code as the test build. It needs the phone at Android 123 or
later. It goes to `wear:internal` on merge, as every raise does; the owner sends it to review from Play's console, after the
privacy text that describes v2 is live.

**400,000,004 (0.4.0-test)** is v2's test build for the owner's Galaxy Watch 5: its `-test` name turns on v2 (`WATCH_V2`) and
the timing readout, with the Play/Pause probe line on the review. It needs the phone at Android 123 or later, the first with the
relay for v2's reads (`taf`, `calendar`, `account`). Never promoted to production.

**400,000,003 (0.3.0-test)** is Ask tafs's test build for a real watch: its `-test` name turns on the timing readout on a
result's card, so the build a merge raising it starts has it, and it's never promoted to production. Production gets its own
raise with a plain name (0.3.0), which never shows the readout.

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
- **Release notes:** `android/play/release-notes/<versionCode>/<locale>.txt`, in the same languages as the phone's (Korean since 0.4.2), the watch's codes
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

## Measuring the link (internal builds only)

A **timing build** (a `versionName` ending in `-timing`, or `-test`, `TAFS_WATCH_TIMING=1`, `-PwatchTiming`) draws one faint line above
the page dots: the last ask to the phone as the watch measured it (`LinkTrace`, recorded by `DataLayerPhone`). For example,
"tafs 0.4s + 1.9s · 3.2 kB" is the time to find the phone, then from sending to the reply, then the reply's size. A failure
reads "tafs ✕ no phone (0.1s)", "✕ timeout" or "✕ error: …", so "your phone isn't reachable" can be told apart: no phone
found by the capability lookup, the 10 s passing, or the Data Layer's own error. A failure keeps the split: "✕ timeout
(0.2s + 9.8s)" found the phone and waited on its reply, "✕ timeout (10.0s)" never found it. An ask cut short because its
screen closed reads "✕ cancelled", not a timeout. It holds no words of a reply, only its size,
and a `-timing` build keeps no recording (only `-test` does). Production never draws it.

**What the phone sends, by page** (`WatchPayloadAuditTest`, on the full sample, 20 Nov): Needs you 405 B (count, words, each
call's taf, what it wants, its reason, refusable, its hold id); Tafs 709 B (each taf's id, name, state, halted); about 1.1 kB
for every page at once. Each reply has a budget the test holds. So the slowness the owner saw isn't the bytes: it's the round
trips and the phone's own reads, which the line above measures.

**Reaching the phone** (`PhoneRoute`, `PhoneRouteTest`), so a phone that's there isn't called unreachable: the phone the
watch reached last is asked directly, with no capability lookup first; no phone found (common in the first seconds after the
app starts) is looked for once more after half a second; and a read whose send failed goes once more to a phone found afresh.
A write (Refuse, Halt, an ask, a Coach message) is never sent twice, since a failed send may still have arrived. All of it
stays inside the same 10 s. A timing build's line marks a second try with ↻ ("tafs 0.0s + 1.2s · 709 B ↻", "✕ no phone ↻").

**Each failure says which** (`LinkFailure`, as the Apple Watch's `PhoneFailure`, tafs#1632, in the same lines), never "not in
reach" for all of them. The four lines are:
- "Your phone isn't in reach. Bring it closer, then try again." when no phone is found, or the Data Layer says it isn't
  connected (`TARGET_NODE_NOT_CONNECTED`).
- "Your phone didn't answer in time. Try again." when the 10 s ran out.
- "tafs isn't on your phone. Install it there, then try again." only when a phone is paired and connected and none ever
  offered tafs's capability (`FILTER_ALL`, `noPhone`). The first seconds after start, before the capability shows, read as not
  in reach, never as tafs missing.
- "Couldn't talk to your phone. Try again." for any other failure of the link (`failureOf`).

Every screen that says the phone gave no answer shows its own reason (`Asked.Unreachable.why`), with Try again
(`WatchModelTest`, `AccountScreensTest`).

## How the watch talks to KIFF and tafs cloud: through the phone

The watch holds **no keys, no session and no KIFF client**. It sends the phone a request over the Data Layer, the phone
reads or signs with its own session and keys, and the watch draws the answer in the phone's words. A lost watch can read
and stop things, never approve.

The watch may ask twelve things (`WatchRequest`), each a path under `/tafs/watch/v1/`:

| Path | What the phone does |
| --- | --- |
| `needs-you` | Reads Needs you: tafs cloud's count and its words, and each held call (its taf, what it wants, KIFF's amount, the reason) |
| `tafs` | Reads each taf's state in one line, with the account's time zone (`zone`), which the watch's chat times and days are in |
| `refuse` | Refuses one held call, by `hold_id` |
| `halt` | Halts one taf (`taf_id`), or every taf (`all: true`, in so many words: a body without its taf is never every taf) |
| `coach` | Sends Coach a message as text (a watch from before Ask tafs; this one never sends it) |
| `ask` | Ask tafs: `client_id`, `text`, `reply_to`, and `taf_id` to chat with a taf; signed with the device key and sent to tafs cloud, which answers its receipt |
| `asks` | Reads the watch's asks as tafs cloud has them, updated after `since` (or in the last day) |
| `brief` | Reads Your brief as the phone shows it, or says there's none (`no_brief`); asked only with the watch's switch on (below) |
| `taf` | One taf's page (Wear v2), by `taf_id`: its state and job, each Card's title, face (figure and line) and rules as the phone's Card tab draws them, and how many of its calls wait on the person (`taf`); read again within the same floor as Needs you |
| `calendar` | A calendar day (Wear v2), `day` as `YYYY-MM-DD` or today in the account's zone: tafs cloud's `GET /v1/calendar` for that day alone, its line and that day's events in time order with each taf's name (`calendar`); the phone's own calendar screen keeps its read |
| `account` | Who is signed in, the account's name and the plan's line as Account shows it (`account`) |
| `transcribe` | Turns the recording the watch just sent over its channel (`voice/{id}`) into text with the phone's **on-device** recognizer, in the app's language, and deletes it: `transcribed` with the text, or `no_offline_speech` (below, "When the watch's recognizer gives up") |

**Chatting with a taf, the phone's half** (behind tafs cloud's `TAFS_WATCH_TAF_CHAT`, off; the wrist's screens follow the
owner's picks; docs/watch-ask.md). An `ask` with `taf_id` names the phone's taf: the phone sends that taf's tafs cloud id as
`taf_id`, inside the bytes its device key signs (`TafsCloud.watchAsk`), so it can't be changed after. The words go only into
that taf's own chat. A taf the phone doesn't list sends nothing, and tafs cloud's refusals that say the phone has it (its switch
off, `watch_taf_chat_off` or `watch_off`; `no_taf`; `coach_taf`) are Open on phone (`WatchRelay.refusedTafMessage`): never
Coach's chat. The rest are an ask's (a view-only phone's line, the AI consent, a 5xx's "may have arrived"). The receipt carries
tafs cloud's `held` (the taf waits on the person, on the phone; the watch never approves) and `more` (the rest is on the phone),
which an older watch ignores, and `kind: "taf"`, so the watch keeps none of a taf's chat (no Inbox line, no place in Coach's
conversation); a phone from before refuses an `ask` with `taf_id` (an unknown field), so the words never reach
Coach. Nothing of a taf's chat is kept on the watch (`WatchAskTest`, `WatchLinkTest.onlyAMessageToATafCarriesItsTaf`).

**Chatting with a taf, the wrist** (`WATCH_TAF_CHAT`, **on since 0.4.2**, once the privacy paragraph was live (kiff/cloud#19);
tafs cloud's `TAFS_WATCH_TAF_CHAT` still decides per account, and where it's off a message gets Open on phone, never Coach;
the owner's picks of 20 Nov; `TafScreens.kt` `tafChat`, `WatchModel` `TafChat`; tested in `TafChatModelTest`,
`TafChatScreenTest`). **1A:** a taf opens its own chat, then its page, then its settings, in its pager (Wear's pager is
sideways, so "one swipe below" on Apple Watch is one swipe on here). The chat shows its state, its last turns as the phone shows
them (`taf-chat`, read only, the last 6, each cut as an answer is, with "The rest is on your phone"), this visit's turns (each
message with its time under it, and a header where a day starts: Today, Yesterday, then the date, as on the phone,
`WatchChatTime`, `eachDayHasItsHeaderAndEachMessageItsTime`; a line tafs cloud sent no time for has none; in the account's
zone, which the phone sends with Your tafs and a taf's chat, so the phone, the Apple Watch and this watch agree, the watch's
own only from an older phone, and forgotten at sign-out, `theWatchsChatTimesAreInTheZoneThePhoneNames`), then
the send bar, as in a phone's chat: a round keyboard (**Type**) and a round microphone (named **Message {taf}** for TalkBack),
then a quick reply in its own words. Each start names its taf (`readyToRecord(tafId)`, `review(tafId)`); Coach's
starts name none, so words never go to a taf by another start. The review is titled "Message {taf}". Sent, the turn shows in
the chat: "Sent", then "{taf} is answering", then its answer; held, **Approve on your phone** (the watch never approves).
**2A:** a paused taf still takes the message, with "Paused: its schedule is off. It still answers what you send." Halted,
nothing is sent: Resume on your phone. **Read more:** a long answer shows its first part; one tap reads the whole from tafs
cloud (`ask-full`, `?full=1`'s top-level `text`), shown in place; without it, the rest is on the phone. **Nothing of a taf's
chat is kept:** its turns live in the open taf only, gone when it closes; a taf chat's request (`kind: "taf"`, or sent from a
chat this session) never reaches the Inbox, Coach's conversation or a result card; and a taf message whose Send isn't sure is
never offered Keep for later (which keeps the words on the watch and sends them as Coach's): Retry or Cancel only
(`aTafsMessageIsNeverKeptForLater`). The phone answers `taf-chat` from tafs cloud's conversation for a taf it lists, and
`ask-full` from `GET /v1/watch/asks/{id}?full=1`; Your tafs carries `paused`.

And the phone sends the watch one message, never a request it answers: **`ask-result`**, an ask's new state, when tafs
cloud's push says it changed.

**There is no approval request.** Approving a held call, a Card, a payment, a connection or extra room is a signature with
the phone's approval key behind its fingerprint, so on the watch each says **Approve on your phone** and opens it there.

**What the phone refuses before reading or signing anything** (`WatchLink.accept`, `NotAccepted`):

- a path that isn't one of the twelve (an approval, a newer version, `ask-result`): `UNKNOWN_PATH`;
- a body that isn't exactly what its path takes, a field missing, extra (`"answer": "approve_once"` on a refusal) or blank,
  or a Coach message or an ask that's empty or over 2,000 characters, or an ask whose id isn't 8 to 64 letters, digits, `_`
  or `-`, a taf's page with no `taf_id`, or a calendar day that isn't `YYYY-MM-DD`: `MALFORMED`. An ask can't name its taf or mark itself: a field it doesn't take is refused;
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
- **What the watch keeps:** in v1, on disk, only Needs you's count and its time, for the tile (`tafs.tile`), cleared on
  sign-out and when the watch comes off the wrist; the lists, Your tafs and the asks and their results stay in memory. v2 (behind
  `WATCH_V2`) keeps more, all in the app's private storage:
  - `tafs.draft`: one message kept for later, its text, ask id and the ask it continues; gone on Delete, once it's sent and
    taken, and on either sign-out; not on a lock or put-away, since the person asked for it to be sent.
  - `tafs.inbox`: the Inbox's lines (`ask:`/`held:` id, kind, the taf's name, the result's line or what a held call wants, the
    time first seen, read) and the bare ids taken away. No amount and no reason: the phone takes any amount out of what a call wants before it goes, even where KIFF's title writes one in (`WatchRelay.withoutAmount`): first the call's own amount however it's written, then any figure with a currency beside it (a sign, a code or a word, such as €, zł, kr, PLN or euros); a number with no currency, such as an order's, stays. An answer's line, or its state in tafs cloud's words (`AskLine.words`, e.g. "No taf took it") where there's no line, is kept the same way (`Amounts.strip`, in `watchlink`, the one rule both share); opened while the app holds the answer, it shows whole. An answer that was only an amount is kept blank, and its line reads "The rest is on your phone." (`watch.more_on_phone`), as on the Apple Watch. The lines go when the app is opened or put away
    while locked or with no screen lock (a lock while the app is closed leaves them until the next open), and everything goes on
    either sign-out.
  - `tafs.sign_in`: one flag, signed out on this watch.
  - `watch-cache.bin` (`WatchCache`, row 44, D), so the watch opens at once: Needs you's count, and each taf's
    name and whether it's halted or paused. Not Needs you's line, never a held call's details, never a taf's state line (it can name what a call wants),
    never a taf's chat. Encrypted with AES-GCM under a key in the watch's Android Keystore (`KeystoreCacheStore`). Cleared on
    either sign-out, whenever the app opens or refreshes while the watch is locked or has no screen lock (Wear OS locks a watch
    taken off the wrist), and after 24 hours. On start, the pages draw it at once with a small dot at the top ("Loading" for
    TalkBack) while the phone is asked; the phone's read replaces it. If the phone doesn't answer, the last read stays, with the
    dot, for 30 s (`UNREACHABLE_AFTER_MS`), and only then is the phone called unreachable (`WatchCacheTest`).
  - `files/voice/`, in a test version only (`KEEP_AUDIO`): the review's recording, erased when the review is left and swept at
    start, put-away and sign-out here. A production build keeps no recording.
  - The result notifications (a taf's name and its line), cancelled on Sign out on this watch.

  In memory only, forgotten when the app is put away: the reads (apart from the cache above), the asks, an open result, a taf's page, the halt pick, the
  calendar day, and Account's read (email, account name, plan). No tokens or keys, and no amount, merchant or person's name in a
  log on the watch or in the phone's relay.
- **Voice:** the watch's own speech recognizer makes text, and only text goes to tafs cloud, after the AI consent already given
  on the phone. A test version keeps the review's recording on the watch, until it's sent or deleted (Play and Pause, step iii).
- **When the watch's recognizer gives up** (row 44, A; `Listener.recordForPhone`, `WatchModel.transcribeOnPhone`,
  `WearRelayService.onChannelOpened`, `VoiceInbox`, `OnDeviceTranscriber`): on an error that isn't the person's own quiet (no
  network, the server, the language unavailable, the recognizer busy; `Dictation.fallsBackToPhone`), the watch records the
  rest itself, with its own microphone, until ✓ or the 30 s limit, while the screen goes on listening. On ✓ the recording goes
  to the paired phone over its own Data Layer channel (`voice/{id}`, at most 31 s), and the watch asks `transcribe`, showing
  "Turning it into text on your phone…" with Cancel. The phone turns it into text with its **on-device** recognizer only
  (`createOnDeviceSpeechRecognizer`, Android 13 and later, fed the file); with none for the language, it answers
  `no_offline_speech` and the watch says so (`watch.no_offline_speech`): nothing goes to a server. Only the text comes back,
  joined to what was heard first, to the same review as words heard, so nothing is sent before Send. **Deleted:** on the phone
  as soon as it's read, whatever the outcome, and otherwise after 2 minutes on its own timer, even if no request ever comes
  (and any a killed process left, when the relay service next starts); on the watch once the text
  is back, on Cancel, and after 2 minutes at most (`VOICE_KEEP_MS`; `TranscribeOnPhoneTest`, `VoiceTranscribeTest`). A phone
  from before refuses `transcribe` as an unknown path, and what was heard first stands. Production builds only (`WATCH_V2`). Speaking is an instruction to a taf, never an
  approval: the taf acts under its own Cards.
- **A locked watch** shows only "A taf needs you" for an alert the phone bridges to it, as a locked phone does.
- **Words** come from `app/strings` (`watch.*` and the phone's own keys), in all 8 languages.

## Still to come

1. The listing's Wear OS screenshots are drawn (below); putting them on the listing, and the Wear OS opt-in and review
   policy for production, are the owner's, in Play Console.
2. On a real watch, with the always-on display on, before the internal track: neither the screen nor the tile shows a held
   call's details while the watch is locked or dimmed, and the app forgets them on `onStop` (from kiff/tafs#1161's review).
