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
- **The `TafsMac` scheme** builds the app and its unit tests. The UI tests (`TafsUITests`) stay the iPhone's and iPad's: they press the Home button, which a Mac lacks.
- **`TafsWidgets` is left out.** It draws the Live Activity for a held call on the lock screen and in the Dynamic Island, and a Mac has neither. The app's dependency on it is filtered to iPhone and iPad (`platformFilter: iOS`).

To build it, on a Mac with Xcode:

```bash
cd ios && xcodegen generate
xcodebuild build -project Tafs.xcodeproj -scheme TafsMac \
  -destination "platform=macOS,variant=Mac Catalyst" CODE_SIGNING_ALLOWED=NO
```

The build is unsigned for now. Signing, and the disk image, are set up at release.

## What differs from the iPad

- **The device's name.** `ThisDevice` tells KIFF what the device is. A Mac build says `kind` "Mac", `platform` `macos` and KIFF's kind `other`, which is what KIFF records for a computer. The Mac build reports an iPad's model, so the build decides before the model does (`ThisDevice.kind(model:mac:)`).
- **No Live Activity.** Live Activities are unavailable to a Mac Catalyst build, so `Shared/HeldActivity.swift` and the Live Activity half of `LockScreen` compile for iPhone and iPad only. `LockScreen.endActivities()` does nothing on the Mac. Notifications work as on the iPad.
- **No Siri tip on the Halt screen.** `SiriTipView` is unavailable to a Mac Catalyst build.

## The menu bar

On an iPad, the shortcuts that work on every screen are hidden buttons (`GlobalKeyCommands`, `Design/Keyboard.swift`). On the Mac the same shortcuts are menu items (`MacCommands`, `Design/Mac.swift`), and the hidden buttons aren't used, so no shortcut is there twice.

Both come from one list, `KeyCommand`. `MacMenu` says where each sits:

| Menu | Item | Shortcut |
|---|---|---|
| File | New taf, in place of "New Window" | ⌘N |
| tafs (the app's menu) | Account, in place of "Settings…" | ⌘, |
| View | Tafs | ⌘1 |
| View | Needs you | ⌘2 |
| View | Record | ⌘3 |
| View | Coach mode | ⌘K |

- **The items sit in the system's own menus**, so no menu has a name of its own to translate. Each item's title is the shortcut's title, from the app's words.
- **An item does what its shortcut does** (`AppModel.run`): a tab's item asks for the tab, and New taf and Coach mode open Coach mode.
- **An item is off when its shortcut is** (`AppModel.allows`): signed out, while Coach drives the app, and for Coach's two items where Coach isn't offered.
- **Send (⌘↩), Close (Esc) and Stop (⌘.) aren't in the menu bar.** They belong to the screen that shows their button, and work there as on the iPad.

A new global shortcut is added to `KeyCommand.global` and to one of `MacMenu`'s lists. `MacMenuTests` fails until it's in both.

## The window

The window never goes smaller than 820 × 600 points (`MacWindow.minimumSize`): the width of an iPad held upright, so the sidebar and a screen beside it always fit. It has no default size of its own: macOS chooses the first one and remembers the person's.

## How the code asks whether it's the Mac

`ThisDevice.isMac` and `#if targetEnvironment(macCatalyst)` are the only two ways the code asks whether it's the Mac build. The Mac workflow finds Mac-specific files by those names.

## Tests

`.github/workflows/mac.yml` builds the app for the Mac and runs the unit tests (`TafsTests`) on a macOS runner. A macOS runner is scarce, so it runs:

- on a pull request that changes `ios/project.yml`, the workflow, or a file that names the Mac build (`macCatalyst` or `ThisDevice.isMac`);
- every night on main, which catches an iPhone or iPad change that stops the Mac build compiling;
- by hand (Actions → Mac → Run workflow).

A draft pull request runs it too. The Mac build can only be checked on a machine with Xcode, so this workflow is where a change to it is tried.

`ThisDeviceTests` checks the name, platform and kind KIFF is told, for an iPhone, an iPad and a Mac.

`MacMenuTests` checks that every global shortcut is in the menu bar once, what each item does, when it's off, and the window's smallest size.

**The Mac's tests run on the Mac itself, not on a Simulator.** So a test can't assume the Simulator's answers:

- **Approvals are always ready on the Simulator.** On a Mac they depend on its Touch ID, and a runner has none. A test that needs an approval sets its model ready (`model.readApprovalReadiness = { .ready }`), with a stub signer.
- **`testTheSimulatorIsReady` is skipped** off the Simulator.

## Not done yet

| What | State |
|---|---|
| Pointer hover on rows and buttons | next, with screenshots of the Mac app from CI |
| **The menu bar and the window, seen by a person** | **owed.** The menu bar and the smallest window size are built and unit-tested, but nobody has seen them on a Mac. |
| Approvals with Touch ID on a Mac, and a Mac without Touch ID | next; today the Mac build uses the iPad's approval code as it is |
| Adding a Mac from a phone (the QR code and the check code) | after KIFF's pairing routes |
| Signing, notarizing and the disk image | at release |
| **A check on a real Mac with Touch ID** | **owed.** The Mac build has only run in CI, where there is no Touch ID. A person must approve a Card with Touch ID on a real Mac before the first release. |
