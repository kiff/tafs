# Parity: every feature on every device

One row per feature, one column per device: **done**, **partial** (what's missing is named), **not started**, or **not for it** (and why). The iPad's view files are the feature list for the apps with screens; the phones, the watches and the Chrome extension have their own tables below. parity-steward keeps this page; a PR that moves a row updates it, and the desktop columns follow each repo's `docs/port-checklist.md`.

**Read from:** kiff/tafs main `5e1b8ba` (iOS, Android, Wear OS, Apple Watch, the extension, docs); kiff/tafs-macos `7a1c139`, kiff/tafs-windows `92a03f3` and kiff/tafs-linux `b1d4d69`, each `docs/port-checklist.md` (judged against the iPad at the pin `9843655`). Phone gaps were checked in the code, not only in the docs; a doc note that the code contradicts is listed under [Docs that say otherwise](#docs-that-say-otherwise).

- **Mac 13+** is the iPad app built with Mac Catalyst ([mac-app.md](mac-app.md)): it follows the iPad unless that page says it differs.
- **macOS 12, Windows, Linux** are native ports ([kiff/tafs-macos](https://github.com/kiff/tafs-macos), [kiff/tafs-windows](https://github.com/kiff/tafs-windows), [kiff/tafs-linux](https://github.com/kiff/tafs-linux)).
- **Wear OS and Apple Watch** are companions to the phone: a glance, Needs you, Refuse, Halt and asking by voice. They never approve and have no sign-in of their own ([watch-app.md](watch-app.md), [watch-wear.md](watch-wear.md)). The iPad's screens are **not for them**; their own table is [Watches](#watches).
- **The Chrome extension** is a view-only KIFF device and a second browser driver ([chrome.md](chrome.md)). It never approves, so the iPad's screens are **not for it**; its own table is [Chrome extension](#chrome-extension).

## Totals

| Device | Totals | Counted over |
| :- | :- | :- |
| iPhone/iPad | done 55, partial 3 | the 58 iPad view files |
| Mac 13+ | done 53, partial 5 | the 58 iPad view files |
| macOS 12 | done 12, partial 31, not started 8, not for it 7 | the 58 iPad view files |
| Windows | done 40, partial 9, not started 1, not for it 8 | the 58 iPad view files |
| Linux | done 30, partial 21, not started 1, not for it 6 | the 58 iPad view files |
| Android | done 55, not for it 3 | the 58 iPad view files |
| Wear OS | done 24, partial 3, not started 1, not for it 4 | the [Watches](#watches) table |
| Apple Watch | done 17, partial 6, not started 4, not for it 5 | the [Watches](#watches) table |
| Chrome extension | done 9, partial 3, not for it 2 | the [Chrome extension](#chrome-extension) table |

**The three biggest gaps**
1. **macOS 12** is furthest behind: 8 screens not started and 31 partial. Most partials wait on approving on this Mac with a security key, landing PR by PR (trials: tafs-macos#182).
2. **iPhone still behind Android on three phone features** ([Phones](#phones-iphone-and-android-feature-by-feature) 8, 9, 14): the loop card, following a run to its end, and the re-pair re-check. Four more are in review (3, 13, Your Chrome 2 and 4).
3. **Apple Watch behind Wear OS** ([Watches](#watches)): Keep for later, a complication, Hear the answer, and three switches built but off until the owner's yes or the privacy page.

## The iPad's screens

Each cell is the device's state; "Missing" lines below the table name what a partial lacks and why a device has no such screen.

| iPad view file | What | iPhone/iPad | Mac 13+ | macOS 12 | Windows | Linux | Android |
| :- | :- | :- | :- | :- | :- | :- | :- |
| AIConsentView | The AI-data consent screen, and Account's row for it | done | done | done | done | done | done |
| APIDoorViews | A door "From another application": its count and cap, last request, keys, New key, Revoke, the instructions | done | done | partial | done | partial | done |
| AccountPreferencesView | Account's theme and time zone pickers | done | done | done | done | done | done |
| AllowSheetView | "Allow this from now on" | done | done | not started | done | not for it | done |
| AppEventApprovalSheet | An app event's filters before signing a subscription | done | done | partial | done | partial | done |
| ApprovalSetupView | This device's approval readiness, and the gate in place of an approval's button | done | partial | partial | done | done | done |
| AttachViews | Attaching photos and files in a chat | done | done | partial | partial | done | done |
| BriefView | "Your brief": decisions first with Coach's recommendation, the next move, what got done | done | done | partial | done | done | done |
| CalendarView | Calendar: Day, Week, Month | done | done | partial | done | done | done |
| ChromeShotView | Screenshots of a run's acts in the owner's Chrome | done | done | not started | not started | not started | done |
| ChromeViews | Account › Chrome: paired Chromes, Pair a Chrome, Unpair, once planque offers it | partial | partial | partial | done | done | done |
| CoachMode | Coach mode over the app: the panel, its toolbar, the phone's sheet | done | done | partial | done | partial | done |
| CoachToolsStep | Coach's tools still to give, and what's given as Start's screen draws it | done | done | not started | done | partial | done |
| ConnectAppsView | Connect apps, a connected app's detail, connecting's approval | done | done | partial | partial | partial | done |
| CreditsViews | The credits the account's tafs used, and one taf's runs | done | done | partial | done | done | done |
| DeleteAccountView | Deleting the account | done | done | not started | done | done | done |
| DevicesView | Account › Devices | done | done | partial | done | done | done |
| DiscardSettingUp | Discard a taf being set up | done | done | not started | done | done | done |
| DoorsView | A taf's Doors tab | done | done | partial | done | partial | done |
| DriveViews | The phone's: Coach drives the phone's own screen (docs/drive.md is the iOS and Android contract)… | done | done | not for it | not for it | partial | done |
| ExportChatsView | Export my chats | done | done | done | done | done | done |
| FilesViews | Files: the account's or a taf's, newest first, the list beside the open file | done | done | partial | partial | partial | done |
| HaltScreen | A halt of one taf or every taf | done | done | done | done | done | done |
| HomeRecipes | Home with no taf: the recipes | done | done | partial | done | done | done |
| HomeSplitView | The sidebar for the whole app and what it opens | done | done | partial | done | partial | done |
| HomeView | The phone's Home. A wide window uses HomeSplitView. Its first Home (lines 137–152: Ask Coach and How tafs work… | done | done | not for it | not for it | not for it | done |
| MicButton | The iPad's promise is that recognition runs on the device only… | done | done | done | not for it | not for it | done |
| NeedsYouView | Needs you: held calls, "Did this happen?", steps, Coach's waits, knocks, alerts | done | done | partial | partial | done | done |
| NotificationSettingsView | Account › Alerts | done | done | partial | done | partial | done |
| OnboardingCards | The three cards on how tafs works, after sign-in and from How tafs works | done | done | done | done | done | done |
| OnboardingView | The welcome: what tafs is, the tick for both Terms with the privacy links and the age line… | done | done | partial | done | done | done |
| PairComputerView | The phone's side of pairing: the camera reads a computer's code. | done | done | not for it | not for it | not for it | done |
| PairSignInView | Signing in from another phone | done | partial | done | done | done | done |
| PeopleViews | People & groups, naming a group, adding someone | done | done | partial | done | done | done |
| PlanViews | Buying credits and Solo, asking an admin, declining a request | done | done | partial | partial | partial | done |
| QuestionsCard | Several questions Coach asks at once: each question's choices, Something else…, Send answers… | done | done | partial | done | done | done |
| RecipeGallery | The jobs' gallery | done | done | done | done | done | done |
| RecordSearchViews | The Record's search, its results and a case | done | done | done | done | done | done |
| RecordView | The Record, its rows, filters and receipts | done | done | partial | done | done | done |
| ReportAnswerSheet | Report an answer | done | done | done | done | done | done |
| ReportPaymentSheet | "Report this payment" from a receipt: KIFF marks it and the taf is halted | done | done | not started | done | done | done |
| SetupChatView | Coach's conversation | partial | partial | partial | done | partial | done |
| SetupShapePanel | What Coach has made so far | done | done | not started | done | partial | done |
| ShareViews | Sharing a taf (`ShareLine`, `ShareTafSheet`), and the Overview (`OverviewView`) | done | done | partial | done | done | done |
| SignInView | `SignInView`: Sign in with KIFF. Also `SettingsView`, `PlanRow`, `AccountView`, `AccountLines` (Account) | done | done | partial | partial | done | done |
| TafChatView | A taf's chat | partial | partial | partial | done | partial | done |
| TafDetailView | A taf's page: head, tabs, Card bar, Activity, Card, Setup, panels | done | done | partial | partial | partial | done |
| TafDialPanel | Not reachable at the pin: the iPad opens `DoorsView` only as `.only(door.id)` from Setup › Starts when… | done | done | not for it | not for it | partial | not for it |
| TafKnobRemote | Not reachable at the pin, as `TafDialPanel`. Reopen it if the iPad turns it on. | done | done | not for it | not for it | not for it | not for it |
| TafKnobView | Not reachable at the pin, as `TafDialPanel` (only `TafKnobView.color` is reused, by Coach's chat)… | done | done | not for it | not for it | partial | not for it |
| TafPresentationViews | A taf's Activity list and its rows, from tafs cloud; Setup's ring and memory | done | done | partial | partial | partial | done |
| TafSetupViews | A taf's Setup: Starts when, Results go to, the pending dial | done | done | partial | done | partial | done |
| TafsBar | The phone's bar along the foot. A wide window has the sidebar. | done | done | not for it | not for it | not for it | done |
| TrialViews | Trials of a new version | done | done | not started | done | done | done |
| UsageView | Plan & usage: the balance, buying, who bills, members' requests | done | done | partial | partial | partial | done |
| WatchDaysViews | "From your watch" in Coach's Previous conversations | done | done | done | done | done | done |
| WorkLog | What a taf or Coach does while it answers, and what it did | done | done | partial | done | partial | done |
| YourStepCard | A step a taf hands the person | done | done | done | done | done | done |

### Missing, and why not

Per file, each device that isn't done. The desktop lines are short forms of the checklist's own; the checklist has the whole line.

- **APIDoorViews**
  - **macOS 12** (partial): New key (the phone's); the API instructions; Coach's drafted door (`DoorDraftCard`)
  - **Linux** (partial): `NewKeySheet` and `IssuedKeyView` (a key is only made signed, so never here); Coach's `DoorDraftCard` (a draft is approved signed: with Coach's drafts)
- **AllowSheetView**
  - **macOS 12** (not started): HoldAllow
  - **Linux** (not for it): "Allow this from now on" comes with answering a held call on the device, which the iPad does only with the session's key (`canAnswer`). A session with no key, as this computer's, answers in KIFF.
- **AppEventApprovalSheet**
  - **macOS 12** (partial): Approving it: it signs planque's trigger for the grant (`POST grants/{id}/triggers`), a route this Mac never signs
  - **Linux** (partial): `driveTerms` (Coach driving the app isn't on Linux)
- **ApprovalSetupView**
  - **Mac 13+** (partial): Touch ID approval not yet checked on a real signed Mac (CI only); a Mac signed in through the browser is view-only (docs/mac-app.md)
  - **macOS 12** (partial): `ReadinessReader`, `ApprovalGate`, `ApprovalNotice`: no Mac approves yet
- **AttachViews**
  - **macOS 12** (partial): Take photo (this Mac's camera); Quick Look in place (a file opens in the app this Mac opens its kind with)
  - **Windows** (partial): Take a photo (`CameraPicker`)
- **BriefView**
  - **macOS 12** (partial): A held call's own page (Approve isn't on macOS 12 yet); the mission log's Ask Coach; alert routing by `brief_kind`; the sample brief
- **CalendarView**
  - **macOS 12** (partial): Opening a taf from an event
- **ChromeShotView**
  - **macOS 12** (not started): new on the iPad since the checklist's pin; no row yet
  - **Windows** (not started): planque's picture of a step (docs/chrome.md, "Screenshots of a run's acts"); new at pin 3d0a961
  - **Linux** (not started): new on the iPad since the checklist's pin; no row yet
- **ChromeViews**
  - **iPhone/iPad** (partial): "Your Chrome" 2 (use another Chrome): in review, tafs#1776; 5 (one signature for both acts): not started
  - **Mac 13+** (partial): "Your Chrome" 2 (use another Chrome): in review, tafs#1776; 5 (one signature for both acts): not started
  - **macOS 12** (partial): Each Chrome's own connections, and Unpair naming them (waiting on tafs' words)
- **CoachMode**
  - **macOS 12** (partial): The dimmed ground and its tap outside, and closing by a drag past the narrowest or a swipe: on the Mac the screen beside Coach stays usable, and Back or Esc close it. `PhoneCoachPanel` is the phone's
  - **Linux** (partial): The swipe of the conversation that closes it (touch); the slide in and out; `PhoneCoachPanel` (a phone's)
- **CoachToolsStep**
  - **macOS 12** (not started)
  - **Linux** (partial): Giving them (signed, the phone's); `CoachToolsGiven`'s Start screen (`start: true`), which belongs to Coach's first Start (signed); opening the step after a first Start
- **ConnectAppsView**
  - **macOS 12** (partial): Not for macOS 12, each drawn up to its approval and made on the phone: connecting and Sign in again, giving or replacing a key, the receipt's Disconnect…
  - **Windows** (partial): `ConnectionDetailView`, the other tafs on a connection's Cards; the narrow window's rows; a failed read in the app's error line
  - **Linux** (partial): `startConnect` (connecting is done on the phone: a request started here could never be approved here); planque's read of the connections (`readsConnections`: set up with planque, signed)…
- **CreditsViews**
  - **macOS 12** (partial): Opening a taf's run (`KnobRequestDetail`); a taf's line in `CreditsPanel` opening its runs; the period before on a taf's runs
- **DeleteAccountView**
  - **macOS 12** (not started)
- **DevicesView**
  - **macOS 12** (partial): Remove and Add a passkey (each signed); Add a device is the phone's (`PairComputerView`)
- **DiscardSettingUp**
  - **macOS 12** (not started)
- **DoorsView**
  - **macOS 12** (partial): WhatsApp's signup (on a phone); the Slack door's approval and its subscription's removal; `DoorDial` and `DoorRim`, not reachable at the pin (as Windows found)
  - **Linux** (partial): `SlackDoorSheet` (Starts when's); WhatsApp's signup (it comes back to the iPhone's own address: the shared line says where it's added); `DoorRim` on the avatar
- **DriveViews**
  - **macOS 12** (not for it): The phone's: Coach drives the phone's own screen (tafs docs/drive.md is the iOS and Android contract). As Windows (tafs-windows#242).
  - **Windows** (not for it): The phone's: Coach drives the phone's own screen (docs/drive.md is the iOS and Android contract), so there's nothing on Windows for it to drive.
  - **Linux** (partial): Approving a drive (signed, the phone's) and Not now: a drive card is answered on the phone, never here
- **FilesViews**
  - **macOS 12** (partial): Share; the system preview of a file with no text start; the chat's file chips
  - **Windows** (partial): `ChatFileChips` (waits on tafs cloud naming a message's run); a preview of a PDF or other type with no text start (the iPad's QuickLook)
  - **Linux** (partial): Share (Linux has no share sheet: Download is it); a type with no text start drawn from its bytes (no system previewer here: its details and Download only)…
- **HaltScreen**
  - **Mac 13+** (done): no Siri tip (`SiriTipView` isn't on Mac Catalyst)
- **HomeRecipes**
  - **macOS 12** (partial): Handing a recipe to a new Coach conversation; Own words opening a new conversation (`startNewTaf`)
- **HomeSplitView**
  - **macOS 12** (partial): Shared with you; Other agents; tafs being set up and Discard; the recipes strip and Explore; + New's gallery; the Unreachable and Plan lines at the foot
  - **Linux** (partial): Discard on a computer with no security key (the phone's); the sample's Coach (title only); an open trial's row under its taf (`trialTaf`…
- **HomeView**
  - **macOS 12** (not for it): The phone's compact home (`TafsTab` draws it only below regular width, HomeSplitView.swift:10-14). This Mac always has the sidebar: its Overview is `ShareViews`' `OverviewView`…
  - **Windows** (not for it): The phone's Home. A wide window uses HomeSplitView. Its first Home (lines 137–152: Ask Coach and How tafs works over an empty Home) is every desktop's, and is on Windows (`OverviewPage`…
  - **Linux** (not for it): The phone's Home. A wide screen uses HomeSplitView. Its first Home (Ask Coach and How tafs works with no taf, lines 137–152) is on Linux's Overview, as on every desktop…
- **MicButton**
  - **Windows** (not for it): The iPad's promise is that recognition runs on the device only, so no audio leaves it (`Dictation.swift`: "no audio leaves the phone"; `voice.voice_becomes_text_device_not`)…
  - **Linux** (not for it): The iPad's dictation is on the device only (`requiresOnDeviceRecognition`), and a Linux desktop has no on-device speech recognition: no mic, as the iPad draws none where no on-device locale exists…
- **NeedsYouView**
  - **macOS 12** (partial): Allow from now on; Refuse's Touch ID or password confirmation; `CoachWaitingCard`'s swipe (its button is on the Mac); `AlertsCard`; knocks
  - **Windows** (partial): KIFF's held calls (#68, merged), with their notifications (#69, merged); answering here (#78, merged, the answers' signing in Core: approve, extra room, next steps, refuse; Allow from now on, #80…
- **NotificationSettingsView**
  - **macOS 12** (partial): `AlertingTafsView` (which tafs alert); alerts with tafs closed, which need a signed build
  - **Linux** (partial): What alerts you and `AlertingTafsView` (a phone's push choices, read with its push token, which a computer hasn't: the iPad draws none of it there either); Haptics (a phone's)
- **OnboardingView**
  - **macOS 12** (partial): Try it with sample data
- **PairComputerView**
  - **macOS 12** (not for it): The phone's side of pairing: its camera reads a computer's code. This Mac is the computer being added.
  - **Windows** (not for it): The phone's side of pairing: the camera reads a computer's code.
  - **Linux** (not for it): The phone's side of pairing: the camera reads a computer's code. The computer's side is tafs-macos's, ported in tafs-linux's `docs/pairing.md` (`pairing.rs`, `tafs-core/pairing.rs`).
- **PairSignInView**
  - **Mac 13+** (partial): built (the iPad's "from another phone"); not yet tried on a real Mac (docs/mac-app.md)
- **PeopleViews**
  - **macOS 12** (partial): Making, renaming or deleting a group and changing its people (each signed)
- **PlanViews**
  - **macOS 12** (partial): Nothing to port: `BuyCreditsView` and `SoloView` are the App Store's, not for macOS 12
  - **Windows** (partial): Buying a pack or Solo: the App Store's purchase is the iPad's (StoreKit); on Windows it's KIFF's web billing, whose address isn't settled yet
  - **Linux** (partial): `BuyCreditsView`, `SoloView` and their offers (held for the owner's money question)
- **QuestionsCard**
  - **macOS 12** (partial): An answer sent the old way, as a message, read back in words (`QuestionAnswers.words`); a refusal for want of the AI-data consent keeping the card quietly (it says tafs cloud's why here)…
- **RecordView**
  - **macOS 12** (partial): a receipt's Chrome shots (`chrome.shot_open`, `ChromeShotView`, with ChromeViews.swift); Report this payment (the desktop-macos lane's)
- **ReportPaymentSheet**
  - **macOS 12** (not started)
- **SetupChatView**
  - **iPhone/iPad** (partial): the answered connect card reopening the signed Card: in review, tafs#1771 (see Phones 3)
  - **Mac 13+** (partial): the answered connect card reopening the signed Card: in review, tafs#1771 (see Phones 3)
  - **macOS 12** (partial): Approving a connection or a Card; attachments; `SetupShapePanel`, `CoachToolsStep`, `DriveCard`; an approval a newer one for the same connection replaced, folded as history (`replaced`)…
  - **Linux** (partial): Approve, Decline, the sign-in's button and Not now (`ApprovalGate`'s line instead); planque's grant on an approval (the card's own title and words stand for it…
- **SetupShapePanel**
  - **macOS 12** (not started): SetupShape
  - **Linux** (partial): The tools it connects, from planque's grants (this computer reads none); shown without `planqueReady`, which this computer never is
- **ShareViews**
  - **macOS 12** (partial): `ShareTafSheet`: sharing is signed with Face ID on the iPad, so it waits for this Mac to approve; the Overview reading its list again by itself
- **SignInView**
  - **macOS 12** (partial): Delete account itself: signed by the device key and erasing tafs cloud's, planque's and KIFF's sides (`deleteAccount`); its click says this Mac can't approve, and sends nothing
  - **Windows** (partial): `SignInView`'s Copy. The KIFF account's rows from `GET /v1/me` (KIFF account, Signed in as, `AccountLines`); People & groups under People & apps (#73, merged); the Plan section (`PlanRow`)…
- **TafChatView**
  - **iPhone/iPad** (partial): the loop card (Read on, Stop here); following a run to its end; a failed run's line in Coach's chat (a taf's chat has it, #1766) (see Phones 2, 8, 9)
  - **Mac 13+** (partial): the loop card (Read on, Stop here); following a run to its end; a failed run's line in Coach's chat (a taf's chat has it, #1766) (see Phones 2, 8, 9)
  - **macOS 12** (partial): What Coach's conversation also misses (attachments, questions); `SteadyMarkdown`
  - **Linux** (partial): attachments and the mic; the sample's chat
- **TafDetailView**
  - **macOS 12** (partial): Connect again; a request's Your step card in `WorkPanel` (with Needs you)
  - **Windows** (partial): `headerLine`'s Share line; on Card: the time zone lines, `eachItem`, `CountedView`, pending, unit words, Usage; in Setup: `ConnectAgainPanel`, the sample's `ConnectionsPanel`; `VersionsScreen`…
  - **Linux** (partial): The sample's Chat tab and its Setup (there, dimmed: the sample's chat and setup aren't on Linux); folding the head while scrolling
- **TafDialPanel**
  - **macOS 12** (not for it): Not reachable on the iPad at the pin: it opens `DoorsView` only as `.only(door.id)`, so the remote never shows. To reopen if the iPad turns it on. As Windows (tafs-windows#242).
  - **Windows** (not for it): Not reachable at the pin: the iPad opens `DoorsView` only as `.only(door.id)` from Setup › Starts when, so the remote's panel never shows. Reopen it if the iPad turns it on.
  - **Linux** (partial): Ask Coach on a run's page opened from Plan & usage; the error line pinned with the remote at the foot (a sheet scrolls: both sit under the dial)
  - **Android** (not for it): not reachable on the iPad at the pin (it opens `DoorsView` only as `.only(door.id)`)
- **TafKnobRemote**
  - **macOS 12** (not for it): Keeps the remote above the phone's tab bar. The Mac has no tab bar.
  - **Windows** (not for it): Not reachable at the pin, as `TafDialPanel`. Reopen it if the iPad turns it on.
  - **Linux** (not for it): Keeps the remote above the phone's tab bar.
  - **Android** (not for it): not reachable on the iPad at the pin (it opens `DoorsView` only as `.only(door.id)`)
- **TafKnobView**
  - **macOS 12** (not for it): The same as `TafDialPanel` (only `TafKnobView.color` is reused, by Coach's chat). As Windows (tafs-windows#242).
  - **Windows** (not for it): Not reachable at the pin, as `TafDialPanel` (only `TafKnobView.color` is reused, by Coach's chat). Reopen it if the iPad turns it on.
  - **Linux** (partial): The stops' labels turning with their ring (upright here); the rings' spring and a door's arrival pulse; Haptics
  - **Android** (not for it): not reachable on the iPad at the pin (it opens `DoorsView` only as `.only(door.id)`)
- **TafPresentationViews**
  - **macOS 12** (partial): `NextStepRow`'s buttons; a call's receipt from its row
  - **Windows** (partial): `TafActivityList`'s held calls, Card-unit exceptions, work, runs, `RetainedRuns`, `TafKnocksPanel`, and All apps; a row's receipt; `NextStepRow`; `ConnectedAppSheet` and a node's week…
  - **Linux** (partial): Signing a connection in again from a next step (planque's grants aren't read here: Connect apps instead)
- **TafSetupViews**
  - **macOS 12** (partial): Not sent with Send again (the taf's work); Make it live and approving an app event, each signed
  - **Linux** (partial): Slack's channels (planque's grants aren't read here, so `SlackDoorSheet` says to connect first)
- **TafsBar**
  - **macOS 12** (not for it): The phone's bar along the foot. The Mac has the sidebar (`HomeSplitView`).
  - **Windows** (not for it): The phone's bar along the foot. A wide window has the sidebar.
  - **Linux** (not for it): The phone's bar along the foot. A wide screen has the sidebar.
- **TrialViews**
  - **macOS 12** (not started)
- **UsageView**
  - **macOS 12** (partial): Raise the limit (an approval), also as a raise request's answer; who bills the plan (`billingPanel`) and Manage. Buying credits and Solo are not for macOS 12 (`PlanViews.swift`'s store views)
  - **Windows** (partial): Raise the limit (it moves money: "approve on another device" where its button would be, by design for this release); buying packs and Solo (KIFF's web billing on Windows, its address not settled)…
  - **Linux** (partial): Buying packs and Solo, and approving a pack (the store, held for the money question); Manage and Cancel Solo (Apple's page)
- **WorkLog**
  - **macOS 12** (partial): `SteadyMarkdown`
  - **Linux** (partial): a connected tool's title from planque (`toolTitle`): its name in KIFF in words stands for it

## Phones: iPhone and Android, feature by feature

What one phone has and the other doesn't, beyond the screens above. Each was checked in the code on main, not only in the docs.

| # | Feature | iPhone/iPad | Android | Where it's said |
| -: | :- | :- | :- | :- |
| 1 | KIFF's words for a connection, v2 (`POST /v1/profiles/words?v=2`) | done (tafs#1762) | done (1.27.7) | coach.md, "KIFF's words for a connection" |
| 2 | A failed run's refused line, with Retry | in review: tafs#1867 (Coach's chat; a taf's chat: tafs#1766) | done (`chatFailed`, `ChatEnd.Failed`) | android-app.md:24-30, ios-app.md |
| 3 | An answered connect card: its Card line opens that Card as signed | in review: tafs#1771 | done | coach.md:55-60 |
| 4 | View-only read from `approval_mode` in `GET /v1/me` | done (tafs#1772) | done | approvals.md:158-162 |
| 5 | A removed taf's connections ended by tafs cloud, not the phone | done (`AppModel.removeTaf`) | done (1.17.1) | connect-apps.md:128-133 |
| 6 | planque's `vendor_refused_client` in planque's own words | done (tafs#1765) | done (`ErrorWords.PLANQUE_WORDED`) | android-app.md:708, ios-app.md |
| 7 | The approval window (one Face ID or fingerprint, then taps) | done (`KIFFClient.window`) | done | approvals.md:182-190 |
| 8 | A taf's loop card ("Still reading": Read on, Stop here) | not started: no `tafs-loop`, no `answerTafCard` | done | chat.md:579 |
| 9 | Following a run to its end (long follow, a stale stream dropped) | in review: tafs#1860 | done (1.22) | chat.md:515-525 |
| 10 | Coach's answered card pinned | done | not started | chat.md:254 |
| 11 | How tafs works: Chrome and the emailed code | in review: tafs#1758 | in review: carried by tafs#1758 (#1759 merged into its branch) | onboarding.md |
| 12 | Coach's Start screen: its tools and Cards signed in one go | done (`start.words`, `/v1/connections/batch`) | done (1.20) | coach.md:140-159 |
| 13 | An API door's address only ever on tafs cloud's own host (as Windows and Linux) | in review: tafs#1768 | done (tafs#1770) | doors.md |
| 14 | A re-pairing reads the Chromes and connections again, and signs only what it showed | in review: tafs#1859 | done (tafs#1761) | android-app.md, "Pairing a Chrome" |
| 15 | A re-pairing after a reinstall starts on the one Chrome alike | done (tafs#1780) | done (tafs#1781) | chrome.md, "Your Chrome" 1 |
| 16 | The release card: Allow a saved sign-in into the owner's Chrome (`tafs-chrome/release/v1`) | not started (designed: tafs#1786) | not started (designed: tafs#1786) | chrome.md, "The phone's release card" |

## Your Chrome

The design's six items (chrome.md, "Your Chrome"). The desktops' Account › Chrome is the ChromeViews row above.

| # | Item | iPhone/iPad | Android | Chrome extension |
| -: | :- | :- | :- | :- |
| 1 | Re-pairing replaces, never adds; only what this phone signed moves | done (tafs#1755) | done (tafs#1756) | done |
| 2 | Use another Chrome for a connection (planque's `use_another_chrome`, signed for the new Chrome) | in review: tafs#1776 | done (tafs#1783) | not for it: planque tells it |
| 3 | Unpairing ends its connections, and says which | done (tafs#1751) | done (tafs#1756) | not for it |
| 4 | Each Chrome says what it's used for; a connection names its Chrome | partial: Account › Chrome and the held card; a connection's page in tafs#1776 | done (tafs#1756, #1777) | not for it |
| 5 | One fingerprint for pairing and the extension's KIFF device | not started: one Face ID, two signatures; no `one_touch` | done (`confirmChromeOneTouch`) | done |
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

| # | Feature | Apple Watch | Wear OS | Why, or what's missing |
| -: | :- | :- | :- | :- |
| 1 | Needs you on the wrist: the count and each held call | done | done | |
| 2 | Refuse a held call | done | done | takes power away, so the watch may; needs the watch's lock |
| 3 | Halt a taf, or every taf | done | done | |
| 4 | Approve anything | not for it | not for it | the approval key lives on the phone, behind Face ID or the fingerprint |
| 5 | Resume a halted taf | not for it | not for it | it gives power back: phone only |
| 6 | Ask by voice or text | done | done | |
| 7 | Check before sending (Re-record, Delete, Type, Quick reply) | done | done | |
| 8 | Keep for later | not started | done | Apple Watch keeps no draft yet |
| 9 | Send, then "Coach is answering" | done | done | |
| 10 | The answer: Hear it, and Reply | partial | done | Apple Watch: Reply done; Hear (text to speech) not built |
| 11 | An answer you can't miss, with the app put away | partial | done | Apple Watch: iOS doesn't promise the background time, and there's no push for watch asks yet |
| 12 | Coach as one conversation | done | done | |
| 13 | Chat with one taf | done | done | tafs cloud's switch still decides per account |
| 14 | A taf's page and its settings | done | done | |
| 15 | Your tafs, with their state | done | done | |
| 16 | Today's calendar | done | done | |
| 17 | The Account page | done | done | |
| 18 | Sign out on this watch alone | done | done | |
| 19 | Sign in, pair a device, add a passkey, change a Card | not for it | not for it | no sign-in of its own; the keys stay on the phone |
| 20 | The inbox kept on the watch | done | done | |
| 21 | The inbox's read and dismissed, in step with the phone | not started | not started | waits on tafs cloud's piece |
| 22 | Your brief | partial | partial | built; off (`briefOnWatch`, `BRIEF_ON_WATCH`) until the owner's yes |
| 23 | "From your watch" on the phone | done | done | |
| 24 | A complication or tile (count, Ask tafs) | not started | done | Apple Watch: planned only |
| 25 | The phone makes the text when the watch's recognizer gives up | partial | done | Apple Watch: built, off (`WatchVoice.on`) until the privacy page says so and the owner says yes |
| 26 | Play and Pause the recording before sending | not started | partial | Apple Watch: W12 held for the owner's yes. Wear: test builds only (`KEEP_AUDIO`) |
| 27 | Opening at once from what the watch kept | partial | done | Apple Watch: built, off (`WatchCache.on`) |
| 28 | Open on phone | not for it | done | watchOS can't open the iPhone app |
| 29 | The all-clear picture | done | done | |
| 30 | Saying why the phone can't be reached, with Try again | done | done | |
| 31 | Working without the phone | not for it | not for it | the phone holds the session and the keys |
| 32 | In the store | partial | partial | Apple Watch: only the TestFlight build carries it. Wear: the internal track; production is the owner's |

## Today's cross-device work

What landed or is in review on 23 Nov, device by device. "—" means the feature isn't that device's.

| Feature | iPhone/iPad | Android | macOS 12 | Windows | Linux | Chrome extension |
| :- | :- | :- | :- | :- | :- | :- |
| Trials of a new version on the security key | done | done | in review: tafs-macos#182 | done | in review: tafs-linux#96 | — |
| Sign in again and Replace the key on a connection | done | done | not for it: made on the phone (planque takes no security key) | done (tafs-windows#268) | partial: on the phone (connecting is the phone's here) | — |
| A connection's own page from planque's row, with its actions | done | done | partial | in review: tafs-windows#269 | partial | — |
| An approval that lands after its run ended says so (tafs cloud's `run_ended`, tafs#1812 in review) | in review: tafs#1814 (the chat's line, with Retry) | built, not yet a PR (the chat's line, with Retry) | not started | in review: tafs-windows#271 (said on Needs you, not in the chat) | built, not yet a PR | — |
| The side panel is the taf in this Chrome (tafs#1799), no client named (tafs#1810), the read after an act waits for its navigation (tafs#1811) | — | — | — | — | — | done |

## Docs that say otherwise

What a doc says that the code on main doesn't. Each gets its fix in the PR that touches it. (drive.md, connect-apps.md, coach.md and chrome.md's planque line were fixed with this page's second PR.)

- **macOS 12 and Linux checklists** have no ChromeShotView row yet (new on the iPad since their pin); it's counted here as not started.
