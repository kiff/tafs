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

**Mac TestFlight** (`.github/workflows/mac-testflight.yml`, by hand only) uploads a signed Mac build for the team's testers: no review submission and no external group. It's archived with the newest released Xcode 27 on the runner (the macOS 27 SDK), not the image's default: a runner without one stops before any key or certificate is touched, listing the Xcodes it has.

- **The app and its notification extension** are signed with the same Apple Distribution certificate as the iOS upload (`DIST_P12`). They use the iPhone's and iPad's bundle IDs, so it's universal purchase: the same products, and restore across devices.
- **Their profiles** are Mac Catalyst App Store profiles (`MAC_CATALYST_APP_STORE`), made and installed through the App Store Connect API: `asc-profiles.rb --mac`, "tafs Mac App Store CI" and "tafs notify Mac App Store CI". On a Mac, push is named `com.apple.developer.aps-environment`, and the script checks the profile carries it.
- **The package** is signed with the Mac Installer Distribution certificate (`MAC_INSTALLER_P12`, `MAC_INSTALLER_P12_PASSWORD`). The run stops with a clear error while it isn't stored.
- **Which is which:** the signing secrets (`DIST_P12`, `MAC_INSTALLER_P12`, their passwords, and `ASC_KEY_P8`) are secrets of the **`apple-release`** environment, which deploys only from `main` and `v*` tags and has no required reviewer. iOS's TestFlight job and Mac TestFlight run in it, so an upload never waits on anyone, and a workflow pushed on any other branch can't read them. Only the certificate's own workflow (below) runs in the `mac-signing` environment, so its reviewer approves once per certificate, when it's made and at renewal. It needs `ASC_KEY_P8` too, so that key is also a `mac-signing` secret.

**The installer certificate is made once** (`.github/workflows/mac-installer-cert.yml`). Its private key is made inside that run and leaves it only encrypted to an admin's public key. This repository is public, so its logs and artifacts are too. An admin does each step, on their own machine:

0. **The `mac-signing` environment comes first** (Settings → Environments), with a required reviewer and deployment branches limited to `main`. Without it, GitHub makes the environment unprotected on the first run, and a run from another branch, which runs that branch's copy of the workflow, isn't held to `main`.
1. **Make an RSA key pair** and keep its private half there:

   ```bash
   openssl genrsa -out mac-signing.pem 4096
   openssl rsa -in mac-signing.pem -pubout | base64 | tr -d '\n'
   ```

2. **Run "Mac installer certificate"** from main, with that line as `public_key`. It runs in the `mac-signing` environment, so its reviewer approves it. The run prints the certificate's id and expiry only.
3. **Decrypt the artifact and store the two secrets** in the `apple-release` environment (`gh secret set --env apple-release -R kiff/tafs`):

   ```bash
   gh run download <run id> -R kiff/tafs -n mac-installer-sealed -D sealed
   openssl pkeyutl -decrypt -inkey mac-signing.pem -in sealed/mac-installer.key.enc -out aes.key \
     -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 -pkeyopt rsa_mgf1_md:sha256
   openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in sealed/mac-installer.tar.enc -out bundle.tar -pass file:aes.key
   mkdir out && tar -C out -xf bundle.tar
   base64 < out/installer.p12 | tr -d '\n' | gh secret set MAC_INSTALLER_P12 --env apple-release -R kiff/tafs
   tr -d '\n' < out/password.txt | gh secret set MAC_INSTALLER_P12_PASSWORD --env apple-release -R kiff/tafs
   ```

4. **Delete the run's artifact** (Actions → the run → Artifacts), and the local files: `sealed`, `aes.key`, `bundle.tar`, `out` and, once the secrets are stored, `mac-signing.pem`.

If anything readable ever reaches a log, revoke the certificate in App Store Connect at once and make a new one.

**The app's category.** The Mac App Store refuses a Mac upload whose Info.plist has no `LSApplicationCategoryType` (Mac TestFlight's first run stopped there). `project.yml` sets it to `public.app-category.business` (Business); it should be the App Store listing's primary category, which the owner confirms. iOS ignores the key. The Mac workflow checks it's there.

### The cloud Mac

The two App Store uploads (`ios.yml`'s `testflight` and `mac-testflight.yml`) can run on a Mac of our own in the
cloud instead of GitHub's `macos-26`, while the hosted image lacks a released Xcode 27. Every other macOS job stays
on GitHub's runners.

- **Switched by a repository variable**, which only an admin can change and a pull request can't: `CLOUD_MAC` set to
  `on`, only for the dispatch, and off right after. With it unset, both jobs run on `macos-26` exactly as before.
- **A runner for one dispatch, by a one-time label.** The dispatch names `cloud_mac_label`: `release-` and 16 to 64
  random hex characters, made for that dispatch alone. `runs-on` is that label, and a first job (`cloud-mac-label`)
  stops the run at once if it's missing or malformed. The runner is registered with that label, for one job, only once
  the upload job is queued, so it's online for seconds; afterwards, which job it ran is checked against the run. This
  is a repository runner: until an organization runner group pinned to these two workflows at `main` exists, nothing
  else limits which workflow can name the label, and the signing secrets still reach only `main` and `v*` tags (the
  `apple-release` environment). That group replaces the label once it's set up.
- **The job also stops itself on a self-hosted runner unless it runs from `main`**, before any secret is read. So a
  tag's upload runs on GitHub's runners, or stops while `CLOUD_MAC` is on.
- **Fork pull requests' workflows need an approval** for every outside contributor while this is used.
- **The job's user has no sudo.** Before each job, the Mac's admin selects the Xcode (a released 27.x, signed by
  Apple) and installs a pinned xcodegen that the job's user can only read. The job then picks that Xcode with
  `DEVELOPER_DIR` instead of `sudo xcode-select`, and uses the installed xcodegen instead of `brew install`. It
  stops if either is missing.
- **Between the iPhone and the Mac builds**, the job's user and everything it left (its home, its temporary files)
  are deleted, and the system's launch items are checked against their first listing, so nothing of one build's
  signing keys reaches the next.

## The download for macOS 13 and later

Beside the Mac App Store listing, which stays, the same app is offered as a download from
kiff.dev/tafs/download: signed with Developer ID, notarized by Apple and stapled, so it opens with
no warning. macOS 12 gets tafs-macos's own download.

**Mac Developer ID** (`.github/workflows/mac-developer-id.yml`) makes it. It runs by hand, from main
only, in the **`mac-signing`** environment: main only, with a required reviewer. That environment's
secrets can't be read by a pull request or by a workflow on any other branch, and the run prints no
key, certificate, password or profile. It does this, in order:

0. **The project:** generated in a job of its own, with no environment and no secret, by an
   XcodeGen release pinned by its SHA-256. Only what generating made or changed (the project, its
   Info.plists and entitlements) goes to the signing job, never a script. That job installs nothing
   and runs only its own checkout's scripts, so nothing a package brings can reach the keys.
   Before any key is written, the signing job checks the generated project with its own
   `ios/scripts/build_phases.py`. It reads the project as Apple's `plutil` reads it, and also scans its
   text, so the way it's written doesn't matter. The run stops on anything in the project or its
   schemes that makes Xcode run code while it builds and that `ios/project.yml` doesn't declare:
   - a script build phase (allowed only by the SHA-256 of its script), a build rule, or an external
     build or aggregate target;
   - a build setting that replaces a tool Xcode runs (`CC`, `CPLUSPLUS`, `LD`, `LDPLUSPLUS`,
     `LIBTOOL` or any `*_EXEC`) or loads a compiler plugin, in the project or an `.xcconfig`;
   - a Swift package other than `swift-secp256k1` at exactly 0.24.0, or any local package;
   - a scheme pre- or post-action.

   Today `ios/project.yml` declares none of these, so even a bad XcodeGen release can't add one.
1. **The profiles:** Mac Catalyst Developer ID profiles (`MAC_CATALYST_APP_DIRECT`), made through the
   API with the Developer ID Application certificate (`asc-profiles.rb --developer-id`): "tafs
   Developer ID CI" and "tafs notify Developer ID CI". They're made with the same capabilities as the
   App Store's, Associated Domains among them, and the script stops if a profile lacks one.
2. **The archive:** the app and its notification extension, with the hardened runtime, exported for
   Developer ID.
3. **The signed app is checked before anything else:**
   - its own entitlements (`codesign -d --entitlements -`) must name `webcredentials:kiff.dev`, or
     passkey sign-in would break and the run stops;
   - it must be signed by our team;
   - its hardened runtime must be on.
4. **The app is notarized and stapled,** then checked with `codesign --verify --deep --strict` and
   `spctl -a -vv`. Either failing stops the run.
5. **The image:** `tafs-mac-<version>.dmg` holds the stapled app and a link to Applications. It's
   signed, notarized, stapled and checked as Gatekeeper judges a download.
6. **The release:** the image goes on a **draft** release `mac-v<version>`, with its SHA-256 in the
   notes. These are the names kiff.dev/tafs/download reads, and a draft is only seen by people with
   push access. A draft of the same version is replaced; a published one stops the run, so
   `MARKETING_VERSION` in `ios/project.yml` goes up first. The build number is the run's number.
   GitHub refuses the run's token a release at a commit that changes `.github/`, so the first job
   stops on such a commit. Run it from a later main commit that doesn't change `.github/`.

**Publishing** is never this workflow's. Only the owner publishes it, once their test of the draft
passed. The download page shows it within 10 minutes.

**Before the first run, once:**
- **The `mac-signing` environment's secrets:** `DEVELOPER_ID_P12` and `DEVELOPER_ID_P12_PASSWORD`,
  this repository's own Developer ID Application certificate. It's not tafs-macos's: each repository
  has its own, so neither repository's key is copied to the other. Also `ASC_KEY_ID`, `ASC_ISSUER_ID`,
  `ASC_KEY_P8` and `APPLE_TEAM_ID`. The run names any that are missing.
- **The environment's protection, as it is today:** a required reviewer and deployments from `main`
  only. Admin bypass is on, by the owner's choice. Turning it off and moving to a reviewer account
  only the owner uses both come before any download is published. A run's approval is the owner's
  own, in GitHub, and never a session's.

**Updates:** the App Store doesn't update this copy. An updater that reads a signed feed comes in the
next version: it tells the person a new version is ready, checks the download against the signed
feed and our team, and opens it for them to install.

## Signing and the sandbox

The Mac build has its own entitlements, `Tafs/TafsMac.entitlements` and `Notify/TafsNotifyMac.entitlements`, set for the Mac SDK only in `ios/project.yml` (`CODE_SIGN_ENTITLEMENTS[sdk=macosx*]`). The iPhone and iPad's stay generated from `project.yml`, unchanged. The Mac's files hold every key the iPhone and iPad's do, and:

| Key | Why |
|---|---|
| `com.apple.developer.aps-environment` | push, by its macOS name, in place of iOS's `aps-environment` |
| `com.apple.security.app-sandbox` | the Mac App Store and TestFlight require it, for the app and its extension |
| `com.apple.security.network.client` | KIFF, tafs cloud and the vendors' sign-ins |
| `com.apple.security.device.camera` | the pairing code and a photo for a message |
| `com.apple.security.device.audio-input` | dictation |
| `com.apple.security.files.user-selected.read-only` | a file the person picks to attach |

Keychain, `webcredentials:kiff.dev` (passkeys) and time-sensitive notifications are the iPhone's keys, unchanged. The Mac workflow checks that each Mac file holds every key of its iPhone file and has the sandbox and the network on, so a key added for the iPhone and missed for the Mac fails CI.

The Mac workflow builds the app unsigned, so the sandbox is only ever on in a signed build: Mac TestFlight's ("Signing and TestFlight", above). Its first build is where the sandbox is first tried.

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

- **The View menu's group is given last first** (`MacMenu.viewInPlacementOrder`): placed before the sidebar's item, macOS shows a group's items reversed, as TestFlight build 2 showed on a Mac.
- **File has no document items** (Duplicate, Move, Rename, Export As…): tafs has no documents, so `PushDelegate.buildMenu` removes UIKit's document menu. Close ⌘W stays.
- **The Mac workflow lists every menu's items** in its log and in `mac-shots/menus.txt`, since a screenshot shows the bar closed.
- **The app's name in the menu bar is "tafs"** (`CFBundleName`), as in "About tafs" and "Quit tafs"; it was the target's, "Tafs".
- **The items sit in the system's own menus**, so no menu has a name of its own to translate. Each item's title is the shortcut's title, from the app's words.
- **An item does what its shortcut does** (`AppModel.run`): a tab's item asks for the tab, and New taf and Coach mode open Coach mode.
- **An item is off when its shortcut is** (`AppModel.allows`): signed out, while Coach drives the app, and for Coach's two items where Coach isn't offered.
- **Send (⌘↩), Close (Esc) and Stop (⌘.) aren't in the menu bar.** They belong to the screen that shows their button, and work there as on the iPad.

A new global shortcut is added to `KeyCommand.global` and to one of `MacMenu`'s lists. `MacMenuTests` fails until it's in both.

## A Mac signed in through the browser is view-only

Once the person has a device that approves, KIFF enrols **no** approval key for a computer signed in through the browser, and its session is `approval_mode: view`. A Mac approves only once it's **paired from a phone**: signed out, then signed in with "from another phone" and its QR code. TestFlight build 2 showed it: Touch ID worked on the Mac, and Setup refused the answer ("didn't sign that answer").

The app reads `approval_mode` when it signs in (`KIFFClient.DeviceToken.viewOnly`), and again from the account (`GET /v1/me`, `Account.viewOnly`). A view-only session is view-only from the start: it registers no approval key and shows the view-only words, and Coach's Approve stops before Touch ID, with those words on the card. Held calls already did. On the Mac the words are the Mac's own (`approval.view_only_mac`): "This Mac can only view. To approve here, add it from your phone: Account › Devices › Add a device."

## Where the Mac draws a control differently

- **The calendar's date** (`CalendarView.datePicker`): the Mac draws the iPad's compact date picker as numbers in a field ("11/15/2026"). On the Mac it's the iPad's look instead, the date in words ("Nov 15, 2026"), which opens the month to choose a day from.

## The pointer

On the Mac, buttons answer the pointer, each a little less than a press, so a press still shows (`Hover`, `Design/Components.swift`):

- **the main button's** fill lightens;
- **the outlined button** takes a light wash of ink;
- **a plain button**, which is what a row or a link is (`DrivablePlainButtonStyle`), fades a little, as it fades more when pressed.

The iPad keeps its own pointer effects, so this shows on the Mac build only. The sidebar is the system's list: its screens and tafs show the selection as the system draws it, and its plain buttons fade as above. The sidebar's Coach button lightens under the pointer as the main button does.

## The window

The window never goes smaller than 820 × 600 points (`MacWindow.minimumSize`): the width of an iPad held upright, so the sidebar and a screen beside it always fit. It has no default size of its own: macOS chooses the first one and remembers the person's. The window shows no title, as the iPad shows none; the sidebar says "tafs" under its icon.

## Approvals with Touch ID

A Mac with Touch ID approves as an iPad does: the same approval key, made in the Secure Enclave under the fingerprints enrolled now (`ApprovalSigner`), and the same Touch ID prompt. Nothing about the key is Mac-specific.

What differs is how the Mac reports Touch ID:

- **A Mac has no Touch ID switch for one app.** On an iPhone or iPad, "not available" with Face ID or Touch ID present means it's turned off for tafs, and Open Settings turns it on. On a Mac it means Touch ID is out of reach: the lid is closed on another display, or the Touch ID keyboard isn't connected. So the Mac says Touch ID can't be used right now, with no Open Settings (`ApprovalReadiness.from`, `mac:`).
- **A Mac with no Touch ID can't approve** with this app. It approves on another device, as an iPad without Face ID or Touch ID does. The macOS 12 app is the one made for those Macs: it approves with a USB security key or on the phone ([computer-passkeys.md](computer-passkeys.md)).

Where Touch ID is set up, the Mac says its own path, "System Settings → Touch ID & Password", in the person's language (`approval.settings_path.mac_touch_id`), never the iPhone's "Settings → Touch ID & Passcode".

The approval key needs the app signed. The CI build is unsigned, so the Mac's tests use a stub signer, and the first real approval with Touch ID on a Mac is **owed**, on a signed build: Mac TestFlight's, installed on a Mac with Touch ID. That check also tells two cases apart that look the same from the code: a build without its signing or entitlements reports Touch ID as not available too, so it would read "can't be used right now" though it never will be.

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

## Screenshots

After the unit tests, the Mac workflow runs the app in sample mode and takes the iPad's reference set (`docs/screenshots/ipad/`, `ios.yml`'s `ref` list): the same 18 screens with the same launch arguments, light and dark, so each Mac screen can be laid beside its iPad one. Then the Overview in French and Portuguese, and the whole screen once, for the menu bar. They're the run's `mac-screenshots` artifact.

- **The window alone**, found by the app's process in the window server's list, not the whole screen. A runner has no Apple Account, so the App Store's sign-in (its own process, `storeuid`) shows over the app, as it would on a Mac signed out of the App Store. A person's Mac is signed in. The menu bar shot is the whole screen, sign-in included.
- **Dark** is the runner's own appearance, switched for the dark half, as a person's Mac switches it.
- **A shot is taken** once two frames a second apart are within 0.5% of each other's size, or after 30 s with a warning. A launch with no window stops the step at once, with the windows on screen in the log.

## The owner's check on a signed Mac

What CI can't check, in one place: each step needs Mac TestFlight's signed build, installed on a Mac with Touch ID, by someone signed in with a real account. Each says what it proves and what a failure means. Until a step is done, its row in "Not done yet" stays **owed**, and parity.md keeps the screen partial.

| # | Do | It proves | If it fails |
|-:|:-|:-|:-|
| 1 | Open the app for the first time. | The sandbox lets it run, reach KIFF and tafs cloud, and keep its Keychain items: the first signed build is the sandbox's first run. | A blank or stuck first screen, or a sign-in that doesn't stick, is an entitlement missing from `TafsMac.entitlements` ("Signing and the sandbox", above). |
| 2 | Sign out. On the phone, Account › Devices › Add a device. On the Mac, sign in "from another phone": scan its QR code, then match the check code. | Adding a Mac from a phone (PairSignInView), shown on the phone as a computer (`macos`, no `device_kind`). Only a Mac added this way approves ("A Mac signed in through the browser is view-only"). | A Mac that stays view-only after it was paired: KIFF enrolled no approval key for it. |
| 3 | With the lid open and Touch ID set up, approve a Card with Touch ID: a held call in Needs you, or Coach's Approve. | The approval key, made in the Secure Enclave, signs on a Mac, and KIFF and Setup accept it (ApprovalSetupView, "Approvals with Touch ID"). | "Touch ID can't be used right now" with the lid open: the build's signing or entitlements are wrong. "Didn't sign that answer": the Mac is view-only, so go back to step 2. |
| 4 | Close the lid on an external display, and try to approve. | The Mac says Touch ID can't be used right now, with no Open Settings, and approving moves to another device (`ApprovalReadiness.from`, `mac:`). | An Open Settings button, or the iPhone's "Settings → Touch ID & Passcode". |
| 5 | Open each menu: tafs, File, View. Try ⌘N, ⌘, and ⌘1 to ⌘3, signed in and signed out. | The menus as "The menu bar" lists them, each item off when its shortcut is. | An item twice, a document item in File, or one that works while signed out. |
| 6 | Make the window as small as it goes. | It stops at 820 × 600 (`MacWindow.minimumSize`), with the sidebar and the screen beside it. | It goes smaller, or the sidebar covers the screen. |
| 7 | Move the pointer over the main button, an outlined button and a row. | Each lightens, washes or fades a little, less than a press ("The pointer"). | No change under the pointer. |

Steps 2 and 3 make the two partial Mac rows in parity.md done: PairSignInView and ApprovalSetupView. The rest close rows in "Not done yet".

## Not done yet

| What | State |
|---|---|
| Pointer hover | buttons and rows ("The pointer"); **owed:** seen by a person, since a screenshot can't show it |
| **The menu bar and the window, seen by a person** | the menu bar is in CI's screenshots (`menu-bar.png`); **owed:** its menus opened, and the smallest window size, on a Mac. |
| Approvals with Touch ID on a Mac | the iPad's key and prompt; a Mac never says Touch ID is off for tafs, and says where in System Settings it's set up |
| Adding a Mac from a phone (the QR code and the check code) | built: the iPad's "sign in from another phone" (`AppModel.startPairingSignIn`, `PairingOffer`), with the Mac shown as a computer (`macos`, no `device_kind`). Not yet tried on a real Mac |
| Notarizing and a disk image, outside the Mac App Store | at release |
| **A check on a real Mac with Touch ID** | **owed.** The Mac build has only run in CI, where there is no Touch ID. A person must approve a Card with Touch ID on a real, signed Mac build before the first release. If it says Touch ID "can't be used right now" with the lid open and Touch ID set up, the build's signing or entitlements are wrong. |
