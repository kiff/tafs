# The Wear OS watch

A small companion to the Android app, on Wear OS 3 and later: Samsung Galaxy Watch (Watch4 onwards) and Pixel Watch. It is
the same product, account and words as the phone, drawn smaller, with no sign-in of its own. It is being built in steps;
this page says what is there now and what each step adds.

## What's there now

- **`android/wear`**, the watch app. It has the phone's package (`dev.kiff.tafs`), as the Wearable Data Layer joins a watch
  app only to a phone app with the same package and signing key. It isn't standalone
  (`com.google.android.wearable.standalone` is false): it needs the phone app.
- **Its one screen** (`WatchActivity`): **Needs you**, with tafs cloud's count in its words and each held call as the phone
  words it (its taf, what it wants, KIFF's amount, the reason), each with **Approve on your phone**; then **Your tafs**, each
  taf's name and state. It's read from the phone each time the watch shows it (`WatchModel.refresh`) and forgotten when it's put
  away (`forget`). Without a phone it says so (`watch.phone_not_reachable`, Try again); with the phone signed out,
  `watch.sign_in_on_phone`; with an answer it can't draw, **Open on phone**, never a guess (`WatchModelTest`).
- **Open on phone** opens `tafs://open/needs-you` on the phone (`RemoteActivityHelper`).
- **`DataLayerPhone`** finds the reachable phone that declares `tafs_phone_relay` and sends it a live request, 10 s at most.
- **`android/watchlink`**, the messages the watch and the phone exchange, in plain Kotlin that both apps build on, with its
  tests on the JVM (`WatchLinkTest`).
- **Its words** are generated with the phone's from `app/strings` (`android.py`): the `watch` area and the phone's lines it
  shows as they are (`WEAR_KEYS`), into `wear/src/main/res/values*/strings.xml` and `wear/…/Words.kt`, read through the
  watch's own `Res`. A strings test fails if `WEAR_KEYS` names a line that isn't there.

- **The phone's relay** (`app/…/watch/WearRelayService`, `WatchRelay`). The phone app declares `tafs_phone_relay`
  (`res/values/wear.xml`) and answers requests under `/tafs/watch/v1/` only. Each passes `WatchLink.accept` first; then
  Needs you is read again (`refresh`), at most once every 10 s for the watch (`RefreshFloor`), and answered as the phone shows it: tafs cloud's count and words, and each held call's
  taf, summary, KIFF's amount and reason. Your tafs is each taf's line (`Taf.now`), or Halted. With no KIFF session (signed
  out, or the sample) the watch is told `signed_out`. Coach is answered Open on phone for now, with nothing sent
  (`WatchRelayTest`). The phone answers only a watch connected to it now (`NodeClient.connectedNodes`).
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

## Versions

The watch shares the phone's package, and Play needs every `versionCode` on the listing to be unique across the phone's and
the watch's builds. The phone counts up by one from 1 (docs/android-app.md, Versions). **The watch has its own band: it
counts up by one from 400,000,001** (`android/wear/build.gradle.kts`), so the two never meet. Its `versionName` is its own
(`0.x` until it's on Play). Nothing of the watch is uploaded yet: the release job builds `:app` only, and the Wear OS form
factor on the listing is the owner's.

## How the watch talks to KIFF and tafs cloud: through the phone

The watch holds **no keys, no session and no KIFF client**. It sends the phone a request over the Data Layer, the phone
reads or signs with its own session and keys, and the watch draws the answer in the phone's words. A lost watch can read
and stop things, never approve.

The watch may ask five things (`WatchRequest`), each a path under `/tafs/watch/v1/`:

| Path | What the phone does |
| --- | --- |
| `needs-you` | Reads Needs you: tafs cloud's count and its words, and each held call (its taf, what it wants, KIFF's amount, the reason) |
| `tafs` | Reads each taf's state in one line |
| `refuse` | Refuses one held call, by `hold_id` |
| `halt` | Halts one taf (`taf_id`), or every taf (`all: true`, in so many words: a body without its taf is never every taf) |
| `coach` | Sends Coach a message as text |

**There is no approval request.** Approving a held call, a Card, a payment, a connection or extra room is a signature with
the phone's approval key behind its fingerprint, so on the watch each says **Approve on your phone** and opens it there.

**What the phone refuses before reading or signing anything** (`WatchLink.accept`, `NotAccepted`):

- a path that isn't one of the five (an approval, a newer version): `UNKNOWN_PATH`;
- a body that isn't exactly what its path takes, a field missing, extra (`"answer": "approve_once"` on a refusal) or blank,
  or a Coach message that's empty or over 2,000 characters: `MALFORMED`;
- a request made more than 30 seconds ago: `TOO_OLD`. Requests are live messages (`MessageClient.sendRequest`), never
  `DataItem`s, so nothing queued while the phone was away lands later;
- a request dated more than 5 seconds ahead of the phone's clock: `FROM_THE_FUTURE`.

The phone answers with a `WatchReply`: the lists, the result in its own words once KIFF or tafs cloud has answered
(`done`, `failed`, `already_answered`), `signed_out`, Coach's answer, or `not_accepted` with its reason. A reply of a kind the
watch doesn't know is shown as Open on phone, never guessed at.

## The rules it's built to

- **Refuse and Halt from the wrist** are signed by the phone with its signing key and no prompt of its own, as a
  notification's Refuse on iPhone: they only take authority away. The phone acts only on a message from the paired watch
  running this app, and the watch sends one only while it has a screen lock and is unlocked; otherwise it says to set one
  (`watch.needs_lock`) and offers Open on phone. The approval key is never used on this path.
- **What the watch keeps:** on disk, only Needs you's count and its time, for the tile, cleared on sign-out and when the
  watch comes off the wrist. The lists, Your tafs and Coach's answers stay in memory. No tokens or keys, and no amount,
  merchant or person's name in a log on the watch or in the phone's relay.
- **Voice:** the watch's own speech input becomes text, and only the text goes to Coach, after the AI consent already given
  on the phone. No audio is kept or sent to tafs cloud.
- **A locked watch** shows only "A taf needs you" for an alert the phone bridges to it, as a locked phone does.
- **Words** come from `app/strings` (`watch.*` and the phone's own keys), in all 8 languages.

## Still to come

1. The tile with Needs you's count.
2. Talk to Coach by voice.
3. Screenshots in all 8 languages. A Wear OS build on Play's listing (the internal track first) is a store-listing change,
   so it waits for the owner.
