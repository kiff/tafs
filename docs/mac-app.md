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

That build is unsigned. The signed one goes to TestFlight (below).

## Signing and TestFlight

**Mac TestFlight** (`.github/workflows/mac-testflight.yml`, by hand only) uploads a signed Mac build for the team's testers: no review submission and no external group.

- **The app and its notification extension** are signed with the same Apple Distribution certificate as the iOS upload (`DIST_P12`). They use the iPhone's and iPad's bundle IDs, so it's universal purchase: the same products, and restore across devices.
- **Their profiles** are Mac Catalyst App Store profiles (`MAC_CATALYST_APP_STORE`), made and installed through the App Store Connect API: `asc-profiles.rb --mac`, "tafs Mac App Store CI" and "tafs notify Mac App Store CI". On a Mac, push is named `com.apple.developer.aps-environment`, and the script checks the profile carries it.
- **The package** is signed with the Mac Installer Distribution certificate (`MAC_INSTALLER_P12`, `MAC_INSTALLER_P12_PASSWORD`). The run stops with a clear error while it isn't stored.
- **Which is which:** only the certificate's own workflow (below) runs in the `mac-signing` environment, so its reviewer approves once per certificate, when it's made and at renewal. The two certificate secrets are **repository** secrets, not the environment's, so Mac TestFlight reads them with no approval, and an upload never waits on anyone.

**The installer certificate is made once** (`.github/workflows/mac-installer-cert.yml`). Its private key is made inside that run and leaves it only encrypted to an admin's public key. This repository is public, so its logs and artifacts are too. An admin does each step, on their own machine:

0. **The `mac-signing` environment comes first** (Settings → Environments), with a required reviewer and deployment branches limited to `main`. Without it, GitHub makes the environment unprotected on the first run, and a run from another branch, which runs that branch's copy of the workflow, isn't held to `main`.
1. **Make an RSA key pair** and keep its private half there:

   ```bash
   openssl genrsa -out mac-signing.pem 4096
   openssl rsa -in mac-signing.pem -pubout | base64 | tr -d '\n'
   ```

2. **Run "Mac installer certificate"** from main, with that line as `public_key`. It runs in the `mac-signing` environment, so its reviewer approves it. The run prints the certificate's id and expiry only.
3. **Decrypt the artifact and store the two secrets,** as repository secrets (`gh secret set -R kiff/tafs`), not the environment's:

   ```bash
   gh run download <run id> -R kiff/tafs -n mac-installer-sealed -D sealed
   openssl pkeyutl -decrypt -inkey mac-signing.pem -in sealed/mac-installer.key.enc -out aes.key \
     -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 -pkeyopt rsa_mgf1_md:sha256
   openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in sealed/mac-installer.tar.enc -out bundle.tar -pass file:aes.key
   mkdir out && tar -C out -xf bundle.tar
   base64 < out/installer.p12 | tr -d '\n' | gh secret set MAC_INSTALLER_P12 -R kiff/tafs
   tr -d '\n' < out/password.txt | gh secret set MAC_INSTALLER_P12_PASSWORD -R kiff/tafs
   ```

4. **Delete the run's artifact** (Actions → the run → Artifacts), and the local files: `sealed`, `aes.key`, `bundle.tar`, `out` and, once the secrets are stored, `mac-signing.pem`.

If anything readable ever reaches a log, revoke the certificate in App Store Connect at once and make a new one.

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

## Approvals with Touch ID

A Mac with Touch ID approves as an iPad does: the same approval key, made in the Secure Enclave under the fingerprints enrolled now (`ApprovalSigner`), and the same Touch ID prompt. Nothing about the key is Mac-specific.

What differs is how the Mac reports Touch ID:

- **A Mac has no Touch ID switch for one app.** On an iPhone or iPad, "not available" with Face ID or Touch ID present means it's turned off for tafs, and Open Settings turns it on. On a Mac it means Touch ID is out of reach: the lid is closed on another display, or the Touch ID keyboard isn't connected. So the Mac says Touch ID can't be used right now, with no Open Settings (`ApprovalReadiness.from`, `mac:`).
- **A Mac with no Touch ID can't approve** with this app. It approves on another device, as an iPad without Face ID or Touch ID does. The macOS 12 app is the one made for those Macs.

The Mac's own words for where Touch ID is set up ("System Settings → Touch ID & Password") and for unlocking it with a password come with the app's words, not yet.

The approval key needs the app signed. The CI build is unsigned, so the Mac's tests use a stub signer, and the first real approval with Touch ID on a Mac is **owed**, on a signed build (signing is done on another computer, at release).

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
| Approvals with Touch ID on a Mac | the iPad's key and prompt; a Mac never says Touch ID is off for tafs. Its words for System Settings and a password come with the app's words |
| Adding a Mac from a phone (the QR code and the check code) | after KIFF's pairing routes |
| Notarizing and a disk image, outside the Mac App Store | at release |
| **A check on a real Mac with Touch ID** | **owed.** The Mac build has only run in CI, where there is no Touch ID. A person must approve a Card with Touch ID on a real Mac before the first release. |
