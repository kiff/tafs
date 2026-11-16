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
