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

The phone doesn't answer yet: its relay is the next step, so on a real watch this screen says it can't reach the phone.

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

1. The phone's relay for Needs you and Your tafs (answering `tafs_phone_relay`), the phone's `tafs://open/` link, and the
   tile with Needs you's count.
2. Refuse and Halt (one taf, every taf) from the wrist.
3. Talk to Coach by voice.
4. Screenshots in all 8 languages. A Wear OS build on Play's listing (the internal track first) is a store-listing change,
   so it waits for the owner.
