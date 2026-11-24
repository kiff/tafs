# Parity: every feature on every device

One row per feature, one column per device: **done**, **partial** (what's missing is named), **not started**, or **not for it** (and why); a watch row reads **built** while its code has merged but the owner hasn't seen it work ([Watches](#watches)). The iPad's view files are the feature list for the apps with screens; the phones, the watches and the Chrome extension have their own tables below. A PR that moves a row updates this page, and the desktop columns follow each repo's `docs/port-checklist.md`.

**Read from:** kiff/tafs main `ab36368` (iOS, Android, Wear OS, Apple Watch, the extension, docs); kiff/tafs-macos `a246e06`, kiff/tafs-windows `5c0f23d` and kiff/tafs-linux `e018877`, each `docs/port-checklist.md` (judged against the iPad at the pin `9843655`). Phone gaps were checked in the code, not only in the docs; a doc note that the code contradicts is listed under [Docs that say otherwise](#docs-that-say-otherwise).

- **Mac 13+** is the iPad app built with Mac Catalyst ([mac-app.md](mac-app.md)): it follows the iPad unless that page says it differs.
- **macOS 12, Windows, Linux** are native ports ([kiff/tafs-macos](https://github.com/kiff/tafs-macos), [kiff/tafs-windows](https://github.com/kiff/tafs-windows), [kiff/tafs-linux](https://github.com/kiff/tafs-linux)).
- **Wear OS and Apple Watch** are companions to the phone: a glance, Needs you, Refuse, Halt and asking by voice. They never approve and have no sign-in of their own ([watch-app.md](watch-app.md), [watch-wear.md](watch-wear.md)). The iPad's screens are **not for them**; their own table is [Watches](#watches).
- **The Chrome extension** is a view-only KIFF device and a second browser driver ([chrome.md](chrome.md)). It never approves, so the iPad's screens are **not for it**; its own table is [Chrome extension](#chrome-extension).

## Totals

| Device | Totals | Counted over |
| :- | :- | :- |
| iPhone/iPad | done 58 | the 58 iPad view files |
| Mac 13+ | done 56, partial 2 | the 58 iPad view files |
| macOS 12 | done 46, partial 4, not for it 8 | the 58 iPad view files |
| Windows | done 50, not for it 8 | the 58 iPad view files |
| Linux | done 53, not for it 5 | the 58 iPad view files |
| Android | done 55, not for it 3 | the 58 iPad view files |
| Wear OS | built 25, partial 3, not for it 4 | the [Watches](#watches) table |
| Apple Watch | built 21, partial 4, not started 2, not for it 5 | the [Watches](#watches) table |
| Chrome extension | done 9, partial 3, not for it 2 | the [Chrome extension](#chrome-extension) table |

**The three biggest gaps**
1. **macOS 12** is furthest behind: 4 screens partial and 8 not for it, after its recount (tafs-macos#225) counted each step a service takes from the phone alone as done, with that service named. The 4 are alerts: three wait on the signed build (push), and the sample's own alerts, which the Mac's sample leaves out by design.
2. **Linux** is level with the iPad: 53 screens done, and 5 not for it, each with its reason. "Allow this from now on" landed last (tafs-linux#120).
3. **The watches wait on the owner's own checks**: every merged row reads built until the owner has seen it work. **Apple Watch is behind Wear OS** ([Watches](#watches)): a complication, and three switches built but off until the owner's yes or the privacy page. (iPhone/iPad and Android are level: the loop card landed last, tafs#1861.)

## The iPad's screens

Each cell is the device's state; "Missing" lines below the table name what a partial lacks and why a device has no such screen.

| iPad view file | What | iPhone/iPad | Mac 13+ | macOS 12 | Windows | Linux | Android |
| :- | :- | :- | :- | :- | :- | :- | :- |
| AIConsentView | The AI-data consent screen, and Account's row for it | done | done | done | done | done | done |
| APIDoorViews | A door "From another application": its count and cap, last request, keys, New key, Revoke, the instructions | done | done | done | done | done | done |
| AccountPreferencesView | Account's theme and time zone pickers | done | done | done | done | done | done |
| AllowSheetView | "Allow this from now on" | done | done | done | done | done | done |
| AppEventApprovalSheet | An app event's filters before signing a subscription | done | done | done | done | done | done |
| ApprovalSetupView | This device's approval readiness, and the gate in place of an approval's button | done | partial | done | done | done | done |
| AttachViews | Attaching photos and files in a chat | done | done | done | done | done | done |
| BriefView | "Your brief": decisions first with Coach's recommendation, the next move, what got done | done | done | partial | done | done | done |
| CalendarView | Calendar: Day, Week, Month | done | done | done | done | done | done |
| ChromeShotView | Screenshots of a run's acts in the owner's Chrome | done | done | done | done | done | done |
| ChromeViews | Account › Chrome: paired Chromes, Pair a Chrome, Unpair, once planque offers it | done | done | done | done | done | done |
| CoachMode | Coach mode over the app: the panel, its toolbar, the phone's sheet | done | done | done | done | done | done |
| CoachToolsStep | Coach's tools still to give, and what's given as Start's screen draws it | done | done | done | done | done | done |
| ConnectAppsView | Connect apps, a connected app's detail, connecting's approval | done | done | done | done | done | done |
| CreditsViews | The credits the account's tafs used, and one taf's runs | done | done | done | done | done | done |
| DeleteAccountView | Deleting the account | done | done | not for it | done | done | done |
| DevicesView | Account › Devices | done | done | done | done | done | done |
| DiscardSettingUp | Discard a taf being set up | done | done | done | done | done | done |
| DoorsView | A taf's Doors tab | done | done | done | done | done | done |
| DriveViews | The phone's: Coach drives the phone's own screen (docs/drive.md is the iOS and Android contract)… | done | done | not for it | not for it | done | done |
| ExportChatsView | Export my chats | done | done | done | done | done | done |
| FilesViews | Files: the account's or a taf's, newest first, the list beside the open file | done | done | done | done | done | done |
| HaltScreen | A halt of one taf or every taf | done | done | done | done | done | done |
| HomeRecipes | Home with no taf: the recipes | done | done | done | done | done | done |
| HomeSplitView | The sidebar for the whole app and what it opens | done | done | done | done | done | done |
| HomeView | The phone's Home. A wide window uses HomeSplitView. Its first Home (lines 137–152: Ask Coach and How tafs work… | done | done | not for it | not for it | not for it | done |
| MicButton | The iPad's promise is that recognition runs on the device only… | done | done | done | not for it | not for it | done |
| NeedsYouView | Needs you: held calls, "Did this happen?", steps, Coach's waits, knocks, alerts | done | done | partial | done | done | done |
| NotificationSettingsView | Account › Alerts | done | done | partial | done | done | done |
| OnboardingCards | The three cards on how tafs works, after sign-in and from How tafs works | done | done | done | done | done | done |
| OnboardingView | The welcome: what tafs is, the tick for both Terms with the privacy links and the age line… | done | done | partial | done | done | done |
| PairComputerView | The phone's side of pairing: the camera reads a computer's code. | done | done | not for it | not for it | not for it | done |
| PairSignInView | Signing in from another phone | done | partial | done | done | done | done |
| PeopleViews | People & groups, naming a group, adding someone | done | done | done | done | done | done |
| PlanViews | Buying credits and Solo, asking an admin, declining a request | done | done | done | done | done | done |
| QuestionsCard | Several questions Coach asks at once: each question's choices, Something else…, Send answers… | done | done | done | done | done | done |
| RecipeGallery | The jobs' gallery | done | done | done | done | done | done |
| RecordSearchViews | The Record's search, its results and a case | done | done | done | done | done | done |
| RecordView | The Record, its rows, filters and receipts | done | done | done | done | done | done |
| ReportAnswerSheet | Report an answer | done | done | done | done | done | done |
| ReportPaymentSheet | "Report this payment" from a receipt: KIFF marks it and the taf is halted | done | done | done | done | done | done |
| SetupChatView | Coach's conversation | done | done | done | done | done | done |
| SetupShapePanel | What Coach has made so far | done | done | done | done | done | done |
| ShareViews | Sharing a taf (`ShareLine`, `ShareTafSheet`), and the Overview (`OverviewView`) | done | done | done | done | done | done |
| SignInView | `SignInView`: Sign in with KIFF. Also `SettingsView`, `PlanRow`, `AccountView`, `AccountLines` (Account) | done | done | done | done | done | done |
| TafChatView | A taf's chat | done | done | done | done | done | done |
| TafDetailView | A taf's page: head, tabs, Card bar, Activity, Card, Setup, panels | done | done | done | done | done | done |
| TafDialPanel | Not reachable at the pin: the iPad opens `DoorsView` only as `.only(door.id)` from Setup › Starts when… | done | done | not for it | not for it | done | not for it |
| TafKnobRemote | Not reachable at the pin, as `TafDialPanel`. Reopen it if the iPad turns it on. | done | done | not for it | not for it | not for it | not for it |
| TafKnobView | Not reachable at the pin, as `TafDialPanel` (only `TafKnobView.color` is reused, by Coach's chat)… | done | done | not for it | not for it | done | not for it |
| TafPresentationViews | A taf's Activity list and its rows, from tafs cloud; Setup's ring and memory | done | done | done | done | done | done |
| TafSetupViews | A taf's Setup: Starts when, Results go to, the pending dial | done | done | done | done | done | done |
| TafsBar | The phone's bar along the foot. A wide window has the sidebar. | done | done | not for it | not for it | not for it | done |
| TrialViews | Trials of a new version | done | done | done | done | done | done |
| UsageView | Plan & usage: the balance, buying, who bills, members' requests | done | done | done | done | done | done |
| WatchDaysViews | "From your watch" in Coach's Previous conversations | done | done | done | done | done | done |
| WorkLog | What a taf or Coach does while it answers, and what it did | done | done | done | done | done | done |
| YourStepCard | A step a taf hands the person | done | done | done | done | done | done |

### Missing, and why not

Per file, each device that isn't done. The desktop lines are short forms of the checklist's own; the checklist has the whole line.

- **ApprovalSetupView**
  - **Mac 13+** (partial): Touch ID approval not yet checked on a real signed Mac (CI only); a Mac signed in through the browser is view-only. The owner's check, steps 2 to 4 ([mac-app.md](mac-app.md#the-owners-check-on-a-signed-mac))
- **BriefView**
  - **macOS 12** (partial): Alert routing by `brief_kind`, which comes with tafs cloud's push: it waits on the signed Mac build. (The iPad's brief has no held call's page of its own, an answer opens Needs you as here…
- **DeleteAccountView**
  - **macOS 12** (not for it): KIFF takes a deletion only when its intent (`tafs.delete-account.v2`) is signed by the session's approval key (`acceptDeletion`), with no security-key path…
- **DriveViews**
  - **macOS 12** (not for it): The phone's: Coach drives the phone's own screen (tafs docs/drive.md is the iOS and Android contract). As Windows (tafs-windows#242).
  - **Windows** (not for it): The phone's: Coach drives the phone's own screen (docs/drive.md is the iOS and Android contract), so there's nothing on Windows for it to drive.
- **HaltScreen**
  - **Mac 13+** (done): no Siri tip (`SiriTipView` isn't on Mac Catalyst)
- **HomeView**
  - **macOS 12** (not for it): The phone's compact home (`TafsTab` draws it only below regular width, HomeSplitView.swift:10-14). This Mac always has the sidebar: its Overview is `ShareViews`' `OverviewView`…
  - **Windows** (not for it): The phone's Home. A wide window uses HomeSplitView. Its first Home (lines 137–152: Ask Coach and How tafs works over an empty Home) is every desktop's, and is on Windows (`OverviewPage`…
  - **Linux** (not for it): The phone's Home. A wide screen uses HomeSplitView. Its first Home (Ask Coach and How tafs works with no taf, lines 137–152) is on Linux's Overview, as on every desktop: `overview.rs` › `first_home`.
- **MicButton**
  - **Windows** (not for it): The iPad's promise is that recognition runs on the device only, so no audio leaves it (`Dictation.swift`: "no audio leaves the phone"; `voice.voice_becomes_text_device_not`)…
  - **Linux** (not for it): The iPad's dictation is on the device only (`requiresOnDeviceRecognition`), and a Linux desktop has no on-device speech recognition: no mic, as the iPad draws none where no on-device locale exists…
- **NeedsYouView**
  - **macOS 12** (partial): Not for macOS 12: `AlertsCard`, which turns on alerts that reach a closed app through push…
- **NotificationSettingsView**
  - **macOS 12** (partial): Alerts with tafs closed, which need a signed build; with them `AlertingTafsView` (which tafs alert the account's phones), which the iPad reads with the device's push token…
- **OnboardingView**
  - **macOS 12** (partial): The sample's local alerts for its held calls: the Mac's sample announces nothing, by design
- **PairComputerView**
  - **macOS 12** (not for it): The phone's side of pairing: its camera reads a computer's code. This Mac is the computer being added.
  - **Windows** (not for it): The phone's side of pairing: the camera reads a computer's code.
  - **Linux** (not for it): The phone's side of pairing: the camera reads a computer's code. The computer's side is tafs-macos's, ported in tafs-linux's `docs/pairing.md` (`pairing.rs`, `tafs-core/pairing.rs`).
- **PairSignInView**
  - **Mac 13+** (partial): built (the iPad's "from another phone"); not yet tried on a real Mac. The owner's check, step 2 ([mac-app.md](mac-app.md#the-owners-check-on-a-signed-mac))
- **TafDialPanel**
  - **Android** (not for it): not reachable on the iPad at the pin (it opens `DoorsView` only as `.only(door.id)`)
  - **macOS 12** (not for it): Not reachable on the iPad at the pin: it opens `DoorsView` only as `.only(door.id)`, so the remote never shows. To reopen if the iPad turns it on. As Windows (tafs-windows#242).
  - **Windows** (not for it): Not reachable at the pin: the iPad opens `DoorsView` only as `.only(door.id)` from Setup › Starts when, so the remote's panel never shows. Reopen it if the iPad turns it on.
- **TafKnobRemote**
  - **Android** (not for it): not reachable on the iPad at the pin (it opens `DoorsView` only as `.only(door.id)`)
  - **macOS 12** (not for it): Keeps the remote above the phone's tab bar. The Mac has no tab bar.
  - **Windows** (not for it): Not reachable at the pin, as `TafDialPanel`. Reopen it if the iPad turns it on.
  - **Linux** (not for it): Keeps the remote above the phone's tab bar.
- **TafKnobView**
  - **Android** (not for it): not reachable on the iPad at the pin (it opens `DoorsView` only as `.only(door.id)`)
  - **macOS 12** (not for it): The same as `TafDialPanel` (only `TafKnobView.color` is reused, by Coach's chat). As Windows (tafs-windows#242).
  - **Windows** (not for it): Not reachable at the pin, as `TafDialPanel` (only `TafKnobView.color` is reused, by Coach's chat). Reopen it if the iPad turns it on.
- **TafsBar**
  - **macOS 12** (not for it): The phone's bar along the foot. The Mac has the sidebar (`HomeSplitView`).
  - **Windows** (not for it): The phone's bar along the foot. A wide window has the sidebar.
  - **Linux** (not for it): The phone's bar along the foot. A wide screen has the sidebar.

## Phones: iPhone and Android, feature by feature

What one phone has and the other doesn't, beyond the screens above. Each was checked in the code on main, not only in the docs.

| # | Feature | iPhone/iPad | Android | Where it's said |
| -: | :- | :- | :- | :- |
| 1 | KIFF's words for a connection, v2 (`POST /v1/profiles/words?v=2`) | done (tafs#1762) | done (1.27.7) | coach.md, "KIFF's words for a connection" |
| 2 | A failed run's refused line, with Retry | done (a taf's chat: tafs#1766; Coach's: tafs#1867) | done (`chatFailed`, `ChatEnd.Failed`) | android-app.md:24-30, ios-app.md |
| 3 | An answered connect card: its Card line opens that Card as signed | done (tafs#1771) | done | coach.md:55-60 |
| 4 | View-only read from `approval_mode` in `GET /v1/me` | done (tafs#1772) | done | approvals.md:158-162 |
| 5 | A removed taf's connections ended by tafs cloud, not the phone | done (`AppModel.removeTaf`) | done (1.17.1) | connect-apps.md:128-133 |
| 6 | planque's `vendor_refused_client` in planque's own words | done (tafs#1765) | done (`ErrorWords.PLANQUE_WORDED`) | android-app.md:708, ios-app.md |
| 7 | The approval window (one Face ID or fingerprint, then taps) | done (`KIFFClient.window`) | done | approvals.md:182-190 |
| 8 | A taf's loop card ("Still reading": Read on, Stop here) | done (tafs#1861) | done | chat.md:587 |
| 9 | Following a run to its end (long follow, a stale stream dropped) | done (tafs#1860) | done (1.22) | chat.md:515-525 |
| 10 | Coach's answered card pinned | done | done (tafs#1832) | chat.md:254 |
| 11 | How tafs works: Chrome and the emailed code | done (tafs#1758) | done (tafs#1759, with #1758) | onboarding.md |
| 12 | Coach's Start screen: its tools and Cards signed in one go | done (`start.words`, `/v1/connections/batch`) | done (1.20) | coach.md:140-159 |
| 13 | An API door's address only ever on tafs cloud's own host, with only a plain path, and its example quoted for a shell | done (host: tafs#1768; the plain path and quotes: tafs#1875) | done (host: tafs#1770; the plain path and quotes: tafs#1874) | doors.md, `vectors/api-door-endpoint.json` |
| 14 | A re-pairing reads the Chromes and connections again, and signs only what it showed | done (tafs#1859) | done (tafs#1761) | android-app.md, ios-app.md, "Pairing a Chrome" |
| 15 | A re-pairing after a reinstall starts on the one Chrome alike | done (tafs#1780) | done (tafs#1781) | chrome.md, "Your Chrome" 1 |
| 16 | The release card: Allow a saved sign-in into the owner's Chrome (`tafs-chrome/release/v1`) | held by the owner's decision: no passwords go through the extension (built on a branch, not opened) | built and off, and stays off by the same decision (tafs#1830, #1837, tafs#1882) | chrome.md, "The phone's release card" |

## Your Chrome

The design's six items (chrome.md, "Your Chrome"). The desktops' Account › Chrome is the ChromeViews row above.

| # | Item | iPhone/iPad | Android | Chrome extension |
| -: | :- | :- | :- | :- |
| 1 | Re-pairing replaces, never adds; only what this phone signed moves | done (tafs#1755) | done (tafs#1756) | done |
| 2 | Use another Chrome for a connection (planque's `use_another_chrome`, signed for the new Chrome) | done (tafs#1776) | done (tafs#1783) | not for it: planque tells it |
| 3 | Unpairing ends its connections, and says which | done (tafs#1751) | done (tafs#1756) | not for it |
| 4 | Each Chrome says what it's used for; a connection names its Chrome | done (tafs#1751, #1776) | done (tafs#1756, #1777) | not for it |
| 5 | One fingerprint for pairing and the extension's KIFF device | done (tafs#1865, `pairChromeOneTouch`) | done (`confirmChromeOneTouch`) | done |
| 6 | Isolation: each connection's sites signed per Chrome | done | done | done (`checkSites`, `checkAllowances`) |

## Chrome extension

| Feature | State | Why, or what's missing |
| :- | :- | :- |
| Pairing: the code typed in the side panel, confirmed on the phone | done | |
| Its own view-only KIFF device | done | KIFF refuses it every approval route |
| Connecting to planque (hello, welcome, keepalive) | done | |
| Steps and runs in the owner's Chrome (its own tabs, the guard, signed sites) | done | |
| Masking what a page read shows | done | |
| Coach in the side panel | done | reading and chatting; a card that needs approving sends the owner to the phone |
| A step handed to the person ("Your turn": the taf asks, the person does it) | done (tafs#1806) | the extension types, clicks and fills nothing; planque's `browse_hand` tools are still to land |
| A run in the owner's window, its tabs kept as "· done" until that taf's next run | done (tafs#1782, #1813, #1815) | a tab the person moved or is using is never closed |
| Re-pairing replaces (Your Chrome 1), one fingerprint (5), isolation (6) | done | |
| Sealed values (Saved sign-ins) | partial | the extension checks the release; the phone's card that signs `tafs-chrome/release/v1` is designed ([chrome.md](chrome.md), "The phone's release card") and isn't built |
| Screenshots of a run's acts | partial | built; off (`config.js`) until planque keeps them and the privacy words are live |
| In the Chrome Web Store | partial | CI builds the package (`chrome-package.yml`); the upload waits on the owner's yes |
| Approving a held step in Chrome | not for it | the extension is view-only; the card is the phone's (iOS `BrowserStepBlock`, Android `NeedsYouView`) |
| Everything else the phone has (Card, alerts, devices, money) | not for it | a second browser driver and a view-only Coach device, never an approval device |

## Watches

A watch row is **done** only once the owner has seen it work: on their Galaxy Watch 5 for Wear OS, and for the Apple Watch, which they don't own, in Xcode's simulator. Merged code isn't enough. Until the owner's checks of the current builds come back (the Apple Watch app in the iPhone's build 3974, Wear's internal-track build), a row whose code has merged reads **built**. Each row then moves to done, or to "broken on the owner's check" with the build and the fix, from the owner's result; a row whose code isn't in the build they checked stays built.

| # | Feature | Apple Watch | Wear OS | Why, or what's missing |
| -: | :- | :- | :- | :- |
| 1 | Needs you on the wrist: the count and each held call | built | built | |
| 2 | Refuse a held call | built | built | takes power away, so the watch may; needs the watch's lock |
| 3 | Halt a taf, or every taf | built | built | |
| 4 | Approve anything | not for it | not for it | the approval key lives on the phone, behind Face ID or the fingerprint |
| 5 | Resume a halted taf | not for it | not for it | it gives power back: phone only |
| 6 | Ask by voice or text | built | built | |
| 7 | Check before sending (Re-record, Delete, Type, Quick reply) | built | built | |
| 8 | Keep for later | built | built | Apple Watch: on (`CoachModel.keepForLaterOn`), with the privacy page's line and the owner's yes |
| 9 | Send, then "Coach is answering" | built | built | |
| 10 | The answer: Hear it, and Reply | built (Hear: tafs#1868, `WatchHear`, a voice of the app's own language only) | built | |
| 11 | An answer you can't miss, with the app put away | built | built | Apple Watch: tafs cloud's push for a watch request wakes the iPhone, which posts the answer as W3 does (`WatchAskPush`), for a request it sent |
| 12 | Coach as one conversation | built | built | |
| 13 | Chat with one taf | built | built | tafs cloud's switch still decides per account |
| 14 | A taf's page and its settings | built | built | |
| 15 | Your tafs, with their state | built | built | |
| 16 | Today's calendar | built | built | |
| 17 | The Account page | built | built | |
| 18 | Sign out on this watch alone | built | built | |
| 19 | Sign in, pair a device, add a passkey, change a Card | not for it | not for it | no sign-in of its own; the keys stay on the phone |
| 20 | The inbox kept on the watch | built | built | |
| 21 | The inbox's read and dismissed, in step with the phone | built (`InboxModel.sync`, `inbox_marks` / `inbox_mark`) | built (`WatchModel.syncInbox`, `inbox-marks` / `inbox-mark`) | tafs cloud keeps them (`/v1/watch/inbox`), by id alone |
| 22 | Your brief | partial | partial | built; off (`briefOnWatch`, `BRIEF_ON_WATCH`) until the owner's yes |
| 23 | "From your watch" on the phone | built | built | |
| 24 | A complication or tile (count, Ask tafs) | not started | built | Apple Watch: planned only |
| 25 | The phone makes the text when the watch's recognizer gives up | partial | built | Apple Watch: built, off (`WatchVoice.on`) until the privacy page says so and the owner says yes |
| 26 | Play and Pause the recording before sending | not started | partial | Apple Watch: W12 held for the owner's yes. Wear: test builds only (`KEEP_AUDIO`) |
| 27 | Opening at once from what the watch kept | partial | built | Apple Watch: built, off (`WatchCache.on`) |
| 28 | Open on phone | not for it | built | watchOS can't open the iPhone app |
| 29 | The all-clear picture | built | built | |
| 30 | Saying why the phone can't be reached, with Try again | built | built | |
| 31 | Working without the phone | not for it | not for it | the phone holds the session and the keys |
| 32 | In the store | partial | partial | Apple Watch: only the TestFlight build carries it. Wear: the internal track; production is the owner's |

## Today's cross-device work

What landed or is in review on 23 Nov, device by device (read again at 22:50 Paris). "—" means the feature isn't that device's.

| Feature | iPhone/iPad | Android | macOS 12 | Windows | Linux | Chrome extension |
| :- | :- | :- | :- | :- | :- | :- |
| Trials of a new version on the security key | done | done | done (tafs-macos#182) | done | done (tafs-linux#96) | — |
| Sign in again and Replace the key on a connection | done | done | not for it: made on the phone (planque takes no security key) | done (tafs-windows#268) | partial: drawn up to planque's signature, signed on the phone | — |
| Sign in again shows the connection's approval sheet first, and signs only the request it showed | done (tafs#1878) | done (tafs#1826) | as the row above | as the row above | as the row above | — |
| A sealed sign-in fill's release card | held: no passwords go through the extension (the owner's decision) | merged and off, stays off (tafs#1882) | — | — | — | — |
| "Allow this from now on" checks its own `matches`: a revision Card's `current.id`, when sent, must equal `revises` (now); a Card key the check doesn't compare is refused (once KIFF's draft schema is pinned in `contracts`) | first half done (tafs#1891) | first half done (tafs#1893) | first half done (tafs-macos#222) | first half done (tafs-windows#330) | first half in review: tafs-linux#121 | — |
| A connection's own page from planque's row, with its actions | done | done | partial | done (tafs-windows#269) | partial | — |
| An approval that lands after its run ended says so (tafs cloud's `run_ended`, tafs#1812) | done (tafs#1814: the chat's line, with Retry) | done (tafs#1816: the chat's line, with Retry) | not started | done (tafs-windows#271: said on Needs you, not in the chat) | done (tafs-linux#99) | — |
| An API door's example safe to paste into a shell (a plain path only, quoted; `vectors/api-door-endpoint.json`, tafs#1877) | done (tafs#1875) | done (tafs#1874) | done (tafs-macos#205) | done (tafs-windows#323) | done (tafs-linux#111) | — |
| The side panel is the taf in this Chrome (tafs#1799), no client named (tafs#1810), the read after an act waits for its navigation (tafs#1811) | — | — | — | — | — | done |

## Counting checks

The desktops count a row as ported when the only things missing are this device's real limits, each with why (no API, no
hardware, the act signed only by the phone's key); "not built yet" is never one. Read against the checklists on 23 Nov:
- **Linux, AllowSheetView** was counted not started, not "not for Linux", while Linux answered held calls in KIFF. It's
  done now (tafs-linux#120): the sheet, KIFF's draft checked as the iPad's `HoldAllow.matches`, one security-key signature.
- **macOS 12, DeleteAccountView** is "not for macOS 12", as its checklist says. KIFF takes a deletion only signed by
  the session's approval key, with no security-key path, so the Mac draws Account's Delete row saying to approve on
  another device and sends nothing. Linux draws the whole sheet (the warning, what goes) with that line and counts it
  ported. Earlier versions of this page counted the Mac's as done too, but the Mac has no sheet.
- **Linux's five rows moved to Ported by tafs-linux#112** (APIDoorViews, HomeSplitView, AppEventApprovalSheet, DriveViews,
  CoachToolsStep): each item left out is a real limit. A key is shown once and Linux can't hide a window from screen
  recording; Discard and Approve are signed, and Linux signs only with a security key; no drive runs on a computer; access
  is never granted from a computer.
- Every other "not for it" (the phone's Home and bar, pairing from the phone's camera, Coach driving the phone, the
  unreachable dial, on-device dictation on Linux and Windows) is a real limit of the device.
- **DevicesView, on every computer:** Add a device and Add a security key are the phone's. KIFF pairs a device or registers a
  passkey only when a phone's approval key signs it (`approval_key_required`): each grants approval authority, and that starts
  on the phone. A service refusal, so DevicesView counts ported on macOS 12, Windows and Linux alike.

## Docs that say otherwise

What a doc says that the code on main doesn't. Each gets its fix in the PR that touches it. (drive.md, connect-apps.md, coach.md and chrome.md's planque line were fixed with this page's second PR.)

- **macOS 12 and Linux checklists** have no ChromeShotView row yet (new on the iPad since their pin); it's counted here as not started.
