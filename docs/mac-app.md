# The Mac app

tafs for the Mac (macOS 13 and later) is the iPhone and iPad app, built with **Mac Catalyst**. It has the iPad's layout, the same account and the same words. This page says how it's built, what differs from the iPad, and what isn't done yet.

For the app's code, see [ios-app.md](ios-app.md). Everything there holds for the Mac unless this page says otherwise.

## Why Mac Catalyst

An iPad app reaches a Mac in two ways. tafs uses Mac Catalyst.

| | Mac Catalyst | "Designed for iPad" |
|---|---|---|
| Macs it runs on | Intel and Apple silicon | Apple silicon only |
| How it ships | the Mac App Store, or a signed and notarized disk image | the Mac App Store only |
| The menu bar and the window | the app's own | what the system gives an iPad app |
| Tested in CI | yes, as a Mac build | no |
| Code changes | a few, listed below | none |

The iOS deployment target, 16.0, is macOS 13 for a Mac Catalyst build. So the Mac app needs macOS 13 or later, with no change to what the iPhone and iPad need.

## How it's built

`ios/project.yml` turns the Mac build on for the whole project:

- **`SUPPORTS_MACCATALYST: YES`** builds each target for the Mac as well.
- **`DERIVE_MACCATALYST_PRODUCT_BUNDLE_IDENTIFIER: NO`** keeps the bundle ids (`bot.tafs.app`), with no `maccatalyst.` prefix.
- **`TafsWidgets` is left out.** It draws the Live Activity for a held call on the lock screen and in the Dynamic Island, and a Mac has neither. The app's dependency on it is filtered to iPhone and iPad (`platformFilter: iOS`).

To build it, on a Mac with Xcode:

```bash
cd ios && xcodegen generate
xcodebuild build -project Tafs.xcodeproj -scheme Tafs \
  -destination "platform=macOS,variant=Mac Catalyst" CODE_SIGNING_ALLOWED=NO
```

The build is unsigned for now. Signing, and the disk image, are set up at release.

## What differs from the iPad

- **The device's name.** `ThisDevice` tells KIFF what the device is. A Mac build says `kind` "Mac", `platform` `macos` and KIFF's kind `other`, which is what KIFF records for a computer. The Mac build reports an iPad's model, so the build decides before the model does (`ThisDevice.kind(model:mac:)`).
- **No Live Activity.** A held call doesn't show on a lock screen. Notifications work as on the iPad.

`ThisDevice.isMac` and `#if targetEnvironment(macCatalyst)` are the only two ways the code asks whether it's the Mac build. The Mac workflow finds Mac-specific files by those names.

## Tests

`.github/workflows/mac.yml` builds the app for the Mac and runs the unit tests (`TafsTests`) on a macOS runner. A macOS runner is scarce, so it runs:

- on a pull request that changes `ios/project.yml`, the workflow, or a file that names the Mac build (`macCatalyst` or `ThisDevice.isMac`);
- every night on main, which catches an iPhone or iPad change that stops the Mac build compiling;
- by hand (Actions → Mac → Run workflow).

A draft pull request runs it too. The Mac build can only be checked on a machine with Xcode, so this workflow is where a change to it is tried.

`ThisDeviceTests` checks the name, platform and kind KIFF is told, for an iPhone, an iPad and a Mac.

## Not done yet

| What | State |
|---|---|
| A menu bar, a minimum window size, pointer hover | next |
| Approvals with Touch ID on a Mac, and a Mac without Touch ID | next; today the Mac build uses the iPad's approval code as it is |
| Adding a Mac from a phone (the QR code and the check code) | after KIFF's pairing routes |
| Signing, notarizing and the disk image | at release |
| **A check on a real Mac with Touch ID** | **owed.** The Mac build has only run in CI, where there is no Touch ID. A person must approve a Card with Touch ID on a real Mac before the first release. |
