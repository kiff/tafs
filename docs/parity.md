# Parity: every feature on every device

One row per feature, one column per device: **done**, **partial** (what's missing is named), **not started**, or **not for it** (and why). The iPad's view files are the feature list for the apps with screens; the phones, the watches and the Chrome extension have their own tables below. A pull request that moves a row updates it, and the desktop columns follow each repo's `docs/port-checklist.md`.

**Read from:** kiff/tafs main `a805de3` (iOS, Android, Wear OS, Apple Watch, the extension, docs); kiff/tafs-macos `4f77f27`, kiff/tafs-windows `5df8afe` and kiff/tafs-linux `18ab591`, each `docs/port-checklist.md` (judged against the iPad at the pin `9843655`). Phone gaps were checked in the code, not only in the docs; a doc note that the code contradicts is listed under [Docs that say otherwise](#docs-that-say-otherwise).

- **Mac 13+** is the iPad app built with Mac Catalyst ([mac-app.md](mac-app.md)): it follows the iPad unless that page says it differs.
- **macOS 12, Windows, Linux** are native ports ([kiff/tafs-macos](https://github.com/kiff/tafs-macos), [kiff/tafs-windows](https://github.com/kiff/tafs-windows), [kiff/tafs-linux](https://github.com/kiff/tafs-linux)).
- **Wear OS and Apple Watch** are companions to the phone: a glance, Needs you, Refuse, Halt and asking by voice. They never approve and have no sign-in of their own ([watch-app.md](watch-app.md), [watch-wear.md](watch-wear.md)). The iPad's screens are **not for them**; their own table is [Watches](#watches).
- **The Chrome extension** is a view-only KIFF device and a second browser driver ([chrome.md](chrome.md)). It never approves, so the iPad's screens are **not for it**; its own table is [Chrome extension](#chrome-extension).

## Totals

| Device | Totals | Counted over |
| :- | :- | :- |
| iPhone/iPad | done 54, partial 4 | the 58 iPad view files |
| Mac 13+ | done 52, partial 6 | the 58 iPad view files |
| macOS 12 | done 11, partial 27, not started 13, not for it 7 | the 58 iPad view files |
| Windows | done 28, partial 21, not started 1, not for it 8 | the 58 iPad view files |
| Linux | done 29, partial 22, not started 1, not for it 6 | the 58 iPad view files |
| Android | done 52, partial 3, not for it 3 | the 58 iPad view files |
| Wear OS | done 24, partial 3, not started 1, not for it 4 | the [Watches](#watches) table |
| Apple Watch | done 17, partial 6, not started 4, not for it 5 | the [Watches](#watches) table |
| Chrome extension | done 7, partial 3, not for it 2 | the [Chrome extension](#chrome-extension) table |

**The three biggest gaps**
1. **macOS 12** is furthest behind: 13 screens not started (12 in its checklist, plus ChromeShotView, new since its pin) and 27 partial. Most partials wait on approving on this Mac with a security key (ApprovalSetupView: "no Mac approves yet").
2. **iPhone is behind Android on six phone features** ([Phones](#phones-iphone-and-android-feature-by-feature) 1, 2, 3, 4, 6, 8): KIFF's words v2, a failed run's line with Retry, the Card line opening the signed Card, view-only from `GET /v1/me`, `vendor_refused_client`, and the loop card.
3. **"Your Chrome" 2 (switching a connection to another Chrome) is on neither phone**, though planque's route is on main. iPhone also lacks item 5 (one fingerprint), which Android has.

## The iPad's screens

Each cell is the device's state; "Missing" lines below the table name what a partial lacks and why a device has no such screen.

| iPad view file | What | iPhone/iPad | Mac 13+ | macOS 12 | Windows | Linux | Android |
| :- | :- | :- | :- | :- | :- | :- | :- |
| AIConsentView | The AI-data consent screen, and Account's row for it | done | done | done | done | done | done |
| APIDoorViews | A door "From another application": its count and cap, last request, keys, New key, Revoke, the instructions | done | done | not started | done | partial | done |
| AccountPreferencesView | Account's theme and time zone pickers | done | done | done | done | done | done |
| AllowSheetView | "Allow this from now on" | done | done | not started | done | not for it | done |
| AppEventApprovalSheet | An app event's filters before signing a subscription | done | done | not started | done | partial | done |
| ApprovalSetupView | This device's approval readiness, and the gate in place of an approval's button | done | partial | partial | done | done | done |
| AttachViews | Attaching photos and files in a chat | done | done | partial | partial | done | done |
| BriefView | "Your brief": decisions first with Coach's recommendation, the next move, what got done | done | done | partial | partial | done | done |
| CalendarView | Calendar: Day, Week, Month | done | done | partial | partial | done | done |
| ChromeShotView | Screenshots of a run's acts in the owner's Chrome | done | done | not started | not started | not started | done |
| ChromeViews | Account › Chrome: paired Chromes, Pair a Chrome, Unpair, once planque offers it | partial | partial | not started | done | done | partial |
| CoachMode | Coach mode over the app: the panel, its toolbar, the phone's sheet | done | done | partial | done | partial | done |
| CoachToolsStep | Coach's tools still to give, and what's given as Start's screen draws it | done | done | not started | partial | partial | done |
| ConnectAppsView | Connect apps, a connected app's detail, connecting's approval | partial | partial | partial | partial | partial | done |
| CreditsViews | The credits the account's tafs used, and one taf's runs | done | done | partial | partial | partial | done |
| DeleteAccountView | Deleting the account | done | done | not started | done | done | done |
| DevicesView | Account › Devices | done | done | partial | done | done | done |
| DiscardSettingUp | Discard a taf being set up | done | done | not started | done | done | done |
| DoorsView | A taf's Doors tab | done | done | not started | partial | partial | done |
| DriveViews | The phone's: Coach drives the phone's own screen (docs/drive.md is the iOS and Android contract)… | done | done | not for it | not for it | partial | done |
| ExportChatsView | Export my chats | done | done | done | done | done | done |
| FilesViews | Files: the account's or a taf's, newest first, the list beside the open file | done | done | not started | partial | partial | done |
| HaltScreen | A halt of one taf or every taf | done | done | done | done | done | done |
| HomeRecipes | Home with no taf: the recipes | done | done | partial | done | done | done |
| HomeSplitView | The sidebar for the whole app and what it opens | done | done | partial | partial | partial | done |
| HomeView | The phone's Home. A wide window uses HomeSplitView. Its first Home (lines 137–152: Ask Coach and How tafs work… | done | done | not for it | not for it | not for it | done |
| MicButton | The iPad's promise is that recognition runs on the device only… | done | done | done | not for it | not for it | done |
| NeedsYouView | Needs you: held calls, "Did this happen?", steps, Coach's waits, knocks, alerts | done | done | partial | partial | done | done |
| NotificationSettingsView | Account › Alerts | done | done | partial | done | partial | done |
| OnboardingCards | The three cards on how tafs works, after sign-in and from How tafs works | done | done | done | done | done | partial |
| OnboardingView | The welcome: what tafs is, the tick for both Terms with the privacy links and the age line… | done | done | partial | partial | done | done |
| PairComputerView | The phone's side of pairing: the camera reads a computer's code. | done | done | not for it | not for it | not for it | done |
| PairSignInView | Signing in from another phone | done | partial | done | partial | done | done |
| PeopleViews | People & groups, naming a group, adding someone | done | done | partial | done | done | done |
| PlanViews | Buying credits and Solo, asking an admin, declining a request | done | done | partial | partial | partial | done |
| QuestionsCard | Several questions Coach asks at once: each question's choices, Something else…, Send answers… | done | done | partial | done | done | done |
| RecipeGallery | The jobs' gallery | done | done | done | done | done | done |
| RecordSearchViews | The Record's search, its results and a case | done | done | partial | done | done | done |
| RecordView | The Record, its rows, filters and receipts | done | done | partial | partial | done | done |
| ReportAnswerSheet | Report an answer | done | done | done | done | done | done |
| ReportPaymentSheet | "Report this payment" from a receipt: KIFF marks it and the taf is halted | done | done | not started | done | done | done |
| SetupChatView | Coach's conversation | partial | partial | partial | done | partial | partial |
| SetupShapePanel | What Coach has made so far | done | done | not started | done | partial | done |
| ShareViews | Sharing a taf (`ShareLine`, `ShareTafSheet`), and the Overview (`OverviewView`) | done | done | partial | partial | done | done |
| SignInView | `SignInView`: Sign in with KIFF. Also `SettingsView`, `PlanRow`, `AccountView`, `AccountLines` (Account) | done | done | partial | partial | done | done |
| TafChatView | A taf's chat | partial | partial | partial | partial | partial | done |
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
| WorkLog | What a taf or Coach does while it answers, and what it did | done | done | partial | partial | partial | done |
| YourStepCard | A step a taf hands the person | done | done | done | done | done | done |

### Missing, and why not

Per file, each device that isn't done. The desktop lines are short forms of the checklist's own; the checklist has the whole line.

- **APIDoorViews**
  - **macOS 12** (not started): DoorsModel
  - **Linux** (partial): `NewKeySheet` and `IssuedKeyView` (a key is only made signed, so never here); Coach's `DoorDraftCard` (a draft is approved signed: with Coach's drafts)
- **AllowSheetView**
  - **macOS 12** (not started): HoldAllow
  - **Linux** (not for it): "Allow this from now on" comes with answering a held call on the device, which the iPad does only with the session's key (`canAnswer`). A session with no key, as this computer's, answers in KIFF.
- **AppEventApprovalSheet**
  - **macOS 12** (not started)
  - **Linux** (partial): `driveTerms` (Coach driving the app isn't on Linux)
- **ApprovalSetupView**
  - **Mac 13+** (partial): Touch ID approval not yet checked on a real signed Mac (CI only); a Mac signed in through the browser is view-only (docs/mac-app.md)
  - **macOS 12** (partial): `ReadinessReader`, `ApprovalGate`, `ApprovalNotice`: no Mac approves yet
- **AttachViews**
  - **macOS 12** (partial): Take photo (this Mac's camera); Quick Look in place (a file opens in the app this Mac opens its kind with)
  - **Windows** (partial): Take a photo (`CameraPicker`)
- **BriefView**
  - **macOS 12** (partial): A held call's own page (Approve isn't on macOS 12 yet); the mission log's Ask Coach; alert routing by `brief_kind`; the sample brief
  - **Windows** (partial): Read again, branch by branch, against the pin's file (it's new since d14217f): the mission log, the two columns on a wide window
- **CalendarView**
  - **macOS 12** (partial): Opening a taf from an event
  - **Windows** (partial): tafs cloud's scheduled work (`GET /v1/calendar`): its chips, its dots, "more" and halted words, a tap that opens the taf; the wide layout (the navigator beside, the agenda…
- **ChromeShotView**
  - **macOS 12** (not started): new on the iPad since the checklist's pin (9843655)
  - **Windows** (not started): new on the iPad since the checklist's pin (9843655)
  - **Linux** (not started): new on the iPad since the checklist's pin (9843655)
- **ChromeViews**
  - **iPhone/iPad** (partial): "Your Chrome" 2 (switch a connection) and 5 (one signature for both acts); a connection's Chrome on the taf's page (see Your Chrome)
  - **Mac 13+** (partial): "Your Chrome" 2 (switch a connection) and 5 (one signature for both acts); a connection's Chrome on the taf's page (see Your Chrome)
  - **macOS 12** (not started): AppModel.chromes
  - **Android** (partial): "Your Chrome" 1, 3, 4 (re-pair pick, Unpair says what it ends, Used for): tafs#1756 in review; 2 not started
- **CoachMode**
  - **macOS 12** (partial): The dimmed ground and its tap outside, and closing by a drag past the narrowest or a swipe: on the Mac the screen beside Coach stays usable, and Back or Esc close it. `PhoneCoachPanel` is the phone's
  - **Linux** (partial): The swipe of the conversation that closes it (touch); the slide in and out; `PhoneCoachPanel` (a phone's)
- **CoachToolsStep**
  - **macOS 12** (not started)
  - **Windows** (partial): `CoachToolsStep` for a first start without tafs cloud's words
  - **Linux** (partial): Giving them (signed, the phone's); `CoachToolsGiven`'s Start screen (`start: true`), which belongs to Coach's first Start (signed); opening the step after a first Start
- **ConnectAppsView**
  - **iPhone/iPad** (partial): planque's `vendor_refused_client` in planque's words (see Phones 6)
  - **Mac 13+** (partial): planque's `vendor_refused_client` in planque's words (see Phones 6)
  - **macOS 12** (partial): Connecting and Sign in again from here; Connect off with "Make one first" for no taf; the rest of `ConnectApprovalSheet` (the approval, the key's sheet, the receipt's logo, host…
  - **Windows** (partial): Connecting (Connect is dimmed): "Which taf?" (`StartSheet`), `ConnectApprovalSheet`, the vendor's sign-in; `ConnectionDetailView`, Sign in again, Replace the key, `misplaced`…
  - **Linux** (partial): `startConnect` (connecting is done on the phone: a request started here could never be approved here); planque's read of the connections (`readsConnections`: set up with planque, signed)…
- **CreditsViews**
  - **macOS 12** (partial): Opening a taf's run (`KnobRequestDetail`); a taf's line in `CreditsPanel` opening its runs; the period before on a taf's runs
  - **Windows** (partial): tafs cloud's reads, their loading and errors; a taf's row opening its runs (`TafCreditsView`, More runs); `CreditsRunRow`; a run's request
  - **Linux** (partial): Pull to refresh (Try again instead, while nothing is read)
- **DeleteAccountView**
  - **macOS 12** (not started)
- **DevicesView**
  - **macOS 12** (partial): Remove and Add a passkey (each signed); Add a device is the phone's (`PairComputerView`)
- **DiscardSettingUp**
  - **macOS 12** (not started)
- **DoorsView**
  - **macOS 12** (not started)
  - **Windows** (partial): `DoorDial` (the doors' own dial when the taf's can't be read), `DoorRim`, `AddDoorSheet`'s WhatsApp and Slack, the Slack sheet (planque's subscription), and the API door (`APIDoorViews`)
  - **Linux** (partial): `SlackDoorSheet` (Starts when's); WhatsApp's signup (it comes back to the iPhone's own address: the shared line says where it's added); `DoorRim` on the avatar
- **DriveViews**
  - **macOS 12** (not for it): The phone's: Coach drives the phone's own screen (tafs docs/drive.md is the iOS and Android contract). As Windows (tafs-windows#242).
  - **Windows** (not for it): The phone's: Coach drives the phone's own screen (docs/drive.md is the iOS and Android contract), so there's nothing on Windows for it to drive.
  - **Linux** (partial): Approving a drive (signed, the phone's) and Not now: a drive card is answered on the phone, never here
- **FilesViews**
  - **macOS 12** (not started)
  - **Windows** (partial): `ChatFileChips` (waits on tafs cloud naming a message's run); a preview of a PDF or other type with no text start (the iPad's QuickLook)
  - **Linux** (partial): Share (Linux has no share sheet: Download is it); a type with no text start drawn from its bytes (no system previewer here: its details and Download only)…
- **HaltScreen**
  - **Mac 13+** (done): no Siri tip (`SiriTipView` isn't on Mac Catalyst)
- **HomeRecipes**
  - **macOS 12** (partial): Handing a recipe to a new Coach conversation; Own words opening a new conversation (`startNewTaf`)
- **HomeSplitView**
  - **macOS 12** (partial): Shared with you; Other agents; tafs being set up and Discard; the recipes strip and Explore; + New's gallery; the Unreachable and Plan lines at the foot
  - **Windows** (partial): A held call opening its taf's page (it opens Needs you); Shared with you; Other agents; tafs being set up, Finish and Discard; Explore and the recipes strip; + New and its gallery; the halt-all row…
  - **Linux** (partial): Discard itself (signed); the sample's Coach (title only); pull to refresh; an open trial's row under its taf (`trialTaf`, `sidebarSelects`): a trial here is a sheet over its taf's page…
- **HomeView**
  - **macOS 12** (not for it): The phone's compact home (`TafsTab` draws it only below regular width, HomeSplitView.swift:10-14). This Mac always has the sidebar: its Overview is `ShareViews`' `OverviewView`…
  - **Windows** (not for it): The phone's Home. A wide window uses HomeSplitView. Its first Home (lines 137–152: Ask Coach and How tafs works over an empty Home) is every desktop's, and is on Windows (`OverviewPage`…
  - **Linux** (not for it): The phone's Home. A wide screen uses HomeSplitView. Its first Home (Ask Coach and How tafs works with no taf, lines 137–152) is on Linux's Overview, as on every desktop…
- **MicButton**
  - **Windows** (not for it): The iPad's promise is that recognition runs on the device only, so no audio leaves it (`Dictation.swift`: "no audio leaves the phone"; `voice.voice_becomes_text_device_not`)…
  - **Linux** (not for it): The iPad's dictation is on the device only (`requiresOnDeviceRecognition`), and a Linux desktop has no on-device speech recognition: no mic, as the iPad draws none where no on-device locale exists…
- **NeedsYouView**
  - **macOS 12** (partial): Extra room; next steps; Allow from now on; Refuse's Touch ID or password confirmation; `CoachWaitingCard`'s swipe (its button is on the Mac); `AlertsCard`; knocks
  - **Windows** (partial): KIFF's held calls (#68, merged), with their notifications (#69, merged); answering here (#78, merged, the answers' signing in Core: approve, extra room, next steps, refuse; Allow from now on, #80…
- **NotificationSettingsView**
  - **macOS 12** (partial): `AlertingTafsView` (which tafs alert); alerts with tafs closed, which need a signed build
  - **Linux** (partial): What alerts you and `AlertingTafsView` (a phone's push choices, read with its push token, which a computer hasn't: the iPad draws none of it there either); Haptics (a phone's)
- **OnboardingCards**
  - **Android** (partial): How tafs works: the Chrome and emailed-code sections (iOS in tafs#1758; Android has the words, no view)
- **OnboardingView**
  - **macOS 12** (partial): Try it with sample data
  - **Windows** (partial): Try it with sample data: the sample opens only with `--sample`, for screenshots
- **PairComputerView**
  - **macOS 12** (not for it): The phone's side of pairing: its camera reads a computer's code. This Mac is the computer being added.
  - **Windows** (not for it): The phone's side of pairing: the camera reads a computer's code.
  - **Linux** (not for it): The phone's side of pairing: the camera reads a computer's code. The computer's side is tafs-macos's, ported in [pairing](pairing.md) (`pairing.rs`, `tafs-core/pairing.rs`).
- **PairSignInView**
  - **Mac 13+** (partial): built (the iPad's "from another phone"); not yet tried on a real Mac (docs/mac-app.md)
  - **Windows** (partial): An expired code replaced by itself, with its line (Windows shows the expiry and Show a new code); the iPad's sign-in words for it (Windows uses Add this Windows PC's)
- **PeopleViews**
  - **macOS 12** (partial): Making, renaming or deleting a group and changing its people (each signed)
- **PlanViews**
  - **macOS 12** (partial): Nothing to port: `BuyCreditsView` and `SoloView` are the App Store's, not for macOS 12
  - **Windows** (partial): Buying a pack or Solo: the App Store's purchase is the iPad's (StoreKit); on Windows it's KIFF's web billing, whose address isn't settled yet
  - **Linux** (partial): `BuyCreditsView`, `SoloView` and their offers (held for the owner's money question)
- **QuestionsCard**
  - **macOS 12** (partial): An answer sent the old way, as a message, read back in words (`QuestionAnswers.words`); a refusal for want of the AI-data consent keeping the card quietly (it says tafs cloud's why here)…
- **RecordSearchViews**
  - **macOS 12** (partial): A case's steps as `SourceActivityRow`, with each step's next step
- **RecordView**
  - **macOS 12** (partial): a row's `onText`; "no longer waiting" for a held call; the receipt's payment check (`on_hash`); a taf's detail
  - **Windows** (partial): `ReceiptView`'s signature check; search and Export receipts; reads folded into one line a day, and the day's spending; what a line acted on; tafs' own events; answers given here; "no longer waiting"…
- **ReportPaymentSheet**
  - **macOS 12** (not started)
- **SetupChatView**
  - **iPhone/iPad** (partial): KIFF's words v2; the answered connect card reopening the signed Card (see Phones 1, 3)
  - **Mac 13+** (partial): KIFF's words v2; the answered connect card reopening the signed Card (see Phones 1, 3)
  - **macOS 12** (partial): Approving a connection or a Card; attachments; `SetupShapePanel`, `CoachToolsStep`, `DriveCard`; an approval a newer one for the same connection replaced, folded as history (`replaced`)…
  - **Linux** (partial): Approve, Decline, the sign-in's button and Not now (`ApprovalGate`'s line instead); planque's grant on an approval (the card's own title and words stand for it…
  - **Android** (partial): Coach's answered card isn't pinned (only iOS pins it, docs/chat.md)
- **SetupShapePanel**
  - **macOS 12** (not started): SetupShape
  - **Linux** (partial): The tools it connects, from planque's grants (this computer reads none); shown without `planqueReady`, which this computer never is
- **ShareViews**
  - **macOS 12** (partial): `ShareTafSheet`: sharing is signed with Face ID on the iPad, so it waits for this Mac to approve; the Overview reading its list again by itself
  - **Windows** (partial): What else waits, in tafs cloud's words; a held call opening its taf's page (it opens Needs you).
- **SignInView**
  - **macOS 12** (partial): Delete account itself: signed by the device key and erasing tafs cloud's, planque's and KIFF's sides (`deleteAccount`); its click says this Mac can't approve, and sends nothing
  - **Windows** (partial): `SignInView`'s Copy. The KIFF account's rows from `GET /v1/me` (KIFF account, Signed in as, `AccountLines`); People & groups under People & apps (#73, merged); the Plan section (`PlanRow`)…
- **TafChatView**
  - **iPhone/iPad** (partial): a failed run's refused line with Retry; the loop card (Read on, Stop here); following a run to its end (see Phones 2, 8, 9)
  - **Mac 13+** (partial): a failed run's refused line with Retry; the loop card (Read on, Stop here); following a run to its end (see Phones 2, 8, 9)
  - **macOS 12** (partial): What Coach's conversation also misses (attachments, questions); `SteadyMarkdown`
  - **Windows** (partial): The mic (MicButton), and the thread's other actions
  - **Linux** (partial): `SteadyMarkdown`; amounts in `failedWords`; `OutcomeLine` from the Record; knocks in the thread; attachments and the mic; the sample's chat
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
  - **Linux** (partial): Pull to refresh; Slack's channels (planque's grants aren't read here, so `SlackDoorSheet` says to connect first)
- **TafsBar**
  - **macOS 12** (not for it): The phone's bar along the foot. The Mac has the sidebar (`HomeSplitView`).
  - **Windows** (not for it): The phone's bar along the foot. A wide window has the sidebar.
  - **Linux** (not for it): The phone's bar along the foot. A wide screen has the sidebar.
- **TrialViews**
  - **macOS 12** (not started)
- **UsageView**
  - **macOS 12** (partial): Raise the limit (an approval), also as a raise request's answer; who bills the plan (`billingPanel`) and Manage. Buying credits and Solo are not for macOS 12 (`PlanViews.swift`'s store views)
  - **Windows** (partial): Raise the limit (it moves money: "approve on another device" where its button would be, by design for this release); buying packs and Solo (KIFF's web billing on Windows, its address not settled)…
  - **Linux** (partial): Buying packs and Solo, and approving a pack (the store, held for the money question); Manage and Cancel Solo (Apple's page); refreshing in place
- **WorkLog**
  - **macOS 12** (partial): `SteadyMarkdown`
  - **Windows** (partial): `LiveWork` (the stream); `WorkSummary` in a taf's chat (#83, merged); `SteadyMarkdown`
  - **Linux** (partial): `SteadyMarkdown`; a connected tool's title from planque (`toolTitle`): its name in KIFF in words stands for it

## Phones: iPhone and Android, feature by feature

What one phone has and the other doesn't, beyond the screens above. Each was checked in the code on main, not only in the docs.

| # | Feature | iPhone/iPad | Android | Where it's said |
| -: | :- | :- | :- | :- |
| 1 | KIFF's words for a connection, v2 (`POST /v1/profiles/words?v=2`) | not started: asks v1 (`KIFFClient.swift:1926`) | done (1.27.7) | coach.md, "KIFF's words for a connection" |
| 2 | A failed run's refused line, with Retry | not started: `TafsCloud.Thread` has no `failed` | done (`chatFailed`, `ChatEnd.Failed`) | android-app.md:24-30 |
| 3 | An answered connect card: its Card line opens that Card as signed | partial: the line is there; a tap opens the live card | done | coach.md:55-60 |
| 4 | View-only read from `approval_mode` in `GET /v1/me` | partial: read only from the sign-in answer (`DeviceToken.viewOnly`) | done | approvals.md:158-162 |
| 5 | A removed taf's connections ended by tafs cloud, not the phone | done (`AppModel.removeTaf`) | done (1.17.1) | connect-apps.md:128-133 |
| 6 | planque's `vendor_refused_client` in planque's own words | not started | done (`ErrorWords.PLANQUE_WORDED`) | android-app.md:708 |
| 7 | The approval window (one Face ID or fingerprint, then taps) | done (`KIFFClient.window`) | done | approvals.md:182-190 |
| 8 | A taf's loop card ("Still reading": Read on, Stop here) | not started: no `tafs-loop`, no `answerTafCard` | done | chat.md:579 |
| 9 | Following a run to its end (long follow, a stale stream dropped) | partial: 30 reads 4 s apart per message | done (1.22) | chat.md:515-525 |
| 10 | Coach's answered card pinned | done | not started | chat.md:254 |
| 11 | How tafs works: Chrome and the emailed code | in review: tafs#1758 | in review: tafs#1759 (on #1758) | onboarding.md |
| 12 | Coach's Start screen: its tools and Cards signed in one go | done (`start.words`, `/v1/connections/batch`) | done (1.20) | coach.md:140-159 |

## Your Chrome

The design's six items (chrome.md, "Your Chrome"). The desktops' Account › Chrome is the ChromeViews row above.

| # | Item | iPhone/iPad | Android | Chrome extension |
| -: | :- | :- | :- | :- |
| 1 | Re-pairing replaces, never adds; only what this phone signed moves | done (tafs#1755) | in review: tafs#1756 | done |
| 2 | Switch a connection to another Chrome | not started (planque's `PUT …/grants/{id}/chrome` is on planque main) | not started (same) | not for it: planque tells it |
| 3 | Unpairing ends its connections, and says which | done (tafs#1751) | in review: tafs#1756 | not for it |
| 4 | Each Chrome says what it's used for; a connection names its Chrome | partial: Account › Chrome and the held card; not yet the taf's page | partial: the held card; Account › Chrome in tafs#1756 | not for it |
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
| Re-pairing replaces (Your Chrome 1), one fingerprint (5), isolation (6) | done | |
| Sealed values (Saved sign-ins) | partial | the extension checks the release; the phone's card that signs `tafs-chrome/release/v1` isn't built |
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

## Docs that say otherwise

What a doc says that the code on main doesn't. Each gets its fix in the PR that touches it, or one docs PR.

- **drive.md:473,** "iOS has no approval window": iPhone and iPad have it (`KIFFClient.window`, `AppModel.windowWords`), as approvals.md:182-190 says.
- **connect-apps.md:133,** "iOS follows": iOS already leaves a removed taf's connections to tafs cloud (`AppModel.removeTaf`).
- **coach.md:159,** "iOS follows": iOS draws Start's screen and signs the batch with the draft (`SetupChatView`, `KIFFClient.swift:1513`).
- **chrome.md, "Not done yet":** "planque's `extension` driver, the relay" are on planque main (`chrome_driver.go`, `chrome_relay.go`). "Your Chrome (design… not built yet)": items 1, 3, 5 and 6 are built (tables above).
- **tafs-macos `docs/port-checklist.md`, Counts:** says "Ported 9, partial 27, not started 17, not for macOS 12 4"; its tables hold 11, 27, 12 and 7.
- **tafs-windows `docs/port-checklist.md`, Coverage:** says "Ported 27, partial 21, not started 1, not for Windows 8"; its tables hold 28, 21, 0 and 8 (ReportPaymentSheet moved with #249).
- **All three desktop checklists** are read against the pin `9843655`: ChromeShotView, new on the iPad since, is in none of them.
