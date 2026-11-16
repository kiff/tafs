# tafs on Apple Watch

A small companion to the iPhone app, on the watch. It shows what needs you, lets you talk
to Coach by voice, and can stop things. It uses the same account and words as the phone,
drawn smaller, and has no sign-in of its own.

## What it does in v1

| On the watch | v1 | Why |
|---|---|---|
| Read Needs you (the count, and each held call in the phone's words), your tafs, Coach's answers | Yes | Reading |
| Send Coach a message by voice | Yes, after the AI-use consent given on the phone | The same as typing on the phone |
| Refuse a held call, Halt a taf, Halt all | Yes | These only take authority away |
| Approve anything (a Card, a payment, a connection, extra room) | No: "Approve on your phone" | An approval is signed by the phone's approval key, behind Face ID |
| Pair a device, register a passkey, change a Card | No | The same |

## How it talks to tafs cloud and KIFF

- **Through the iPhone.** The phone holds the session and the device key, makes the calls,
  and signs the only answers the watch may ask for: Refuse and Halt.
- **No keys and no tokens on the watch.** A lost watch can read and stop things, never approve.
- **Standalone** (a cellular watch with its phone away) isn't in v1.

## The relay

What the watch asks and the phone answers is `ios/WatchShared/WatchMessage.swift`, which both
apps compile. The phone's end is `ios/Tafs/Services/WatchRelay.swift`, and the watch's is
`ios/TafsWatch/PhoneLink.swift`.

- **Six requests:** Needs you, your tafs, Refuse (naming its held call), Halt (naming its
  taf), Halt all, and Coach (the words dictated on the watch, never audio). Nothing else
  decodes, so an approval can't even be asked for.
- **Refused before anything is read:** an unknown or malformed request, or one missing what
  it names. Nothing on this path signs.
- **Live only:** each request goes with WatchConnectivity's message data and a reply, never
  queued. It carries the time it was made, and the phone drops one more than 30 seconds old,
  either way.
- **Signed out:** the phone answers only that, and the watch shows nothing of the account.
- **With tafs closed:** the phone answers with the session it keeps, as a hold's notification
  is filled in (`HoldNotification`), so the phone can be locked in a pocket.
- **Refuse** is the notification's own refusal (`NotificationRefuse.refuse`): signed with the
  current key, never the approval key, and only if KIFF still holds the call. Unlike the
  notification's, it may be signed while the phone is locked in a pocket: the only presence is
  the watch's own unlock, on the wrist. That's accepted because Refuse only takes authority
  away, as Halt all does. One already
  answered or lapsed says so, with no second call to KIFF.
- **Halt and Halt all** are the app's and Siri's (`HaltAll`): tafs cloud's request with the
  session, no signature and no Face ID. Resuming stays on the phone, behind Face ID.
- **The wrist shows the phone's result:** "Refused" or "Halted" appears only once KIFF or tafs
  cloud has answered. If it didn't go, it says why, in the phone's words.
- **Coach** comes next.

## What the watch keeps

- **In memory only:** the list, the amounts and Coach's answers. They go when the app leaves the
  screen, and are read again when it's back.
- **On disk:** only the Needs you count and when it was read, for the complication. It's cleared
  when the phone says it's signed out. (This comes with the complication.)
- **Never:** a token, a key, or an amount, a merchant or a person's name in a log, on the watch
  or in the relay.

## Before the watch app: what the phone's notifications already do

The iPhone's notifications show on the watch by themselves. On a held call's notification:

- **Refuse** runs on the phone, signed with the current key, and needs no Face ID. It works
  from the wrist.
- **Approve once** only opens tafs on the phone. Nothing can be approved from the wrist.

## Where it lives

- `ios/TafsWatch/`: the watchOS app, with a single target (watchOS 10 and later).
- `ios/TafsWatchTests/`: its unit tests, on a watch simulator.
- `ios/project.yml`: the `TafsWatch` and `TafsWatchTests` targets, and the `TafsWatch` scheme.
  The phone's `Tafs` scheme doesn't build the watch app.
- The words are the phone's (`app/strings`, 8 languages). The watch compiles the generated
  `Words.swift` and its catalog, and its own lines are the `watch.*` keys.
- CI: the iOS workflow's `watch` job builds and tests it on a watch simulator, only when what it's
  built from changes.

## Store

TestFlight first, inside the iPhone app. Its App Store screenshots and listing are store
listing changes, made separately.
