# Parity: every feature on every device

One row per feature, one column per device: **done**, **partial** (what's missing is named), **not started**, or **not for it** (and why); a watch row reads **built** while its code has merged but the owner hasn't seen it work ([Watches](#watches)). The iPad's view files are the feature list for the apps with screens; the phones, the watches and the Chrome extension have their own tables below. A PR that moves a row updates this page, and the desktop columns follow each repo's `docs/port-checklist.md`.

**Read from:** kiff/tafs main `ab36368` (iOS, Android, Wear OS, Apple Watch, the extension, docs); kiff/tafs-macos `56da6cf`, kiff/tafs-windows `e5441d8` and kiff/tafs-linux `7475d27`, each `docs/port-checklist.md` (judged against the iPad at the pin `9843655`). Phone gaps were checked in the code, not only in the docs; a doc note that the code contradicts is listed under [Docs that say otherwise](#docs-that-say-otherwise).

- **Mac 13+** is the iPad app built with Mac Catalyst ([mac-app.md](mac-app.md)): it follows the iPad unless that page says it differs.
- **macOS 12, Windows, Linux** are native ports ([kiff/tafs-macos](https://github.com/kiff/tafs-macos), [kiff/tafs-windows](https://github.com/kiff/tafs-windows), [kiff/tafs-linux](https://github.com/kiff/tafs-linux)).
- **Wear OS and Apple Watch** are companions to the phone: a glance, Needs you, Refuse, Halt and asking by voice. They never approve and have no sign-in of their own ([watch-app.md](watch-app.md), [watch-wear.md](watch-wear.md)). The iPad's screens are **not for them**; their own table is [Watches](#watches).
- **The Chrome extension** is a view-only KIFF device and a second browser driver ([chrome.md](chrome.md)). It never approves, so the iPad's screens are **not for it**; its own table is [Chrome extension](#chrome-extension).

## Totals

| Device | Totals | Counted over |
| :- | :- | :- |
| iPhone/iPad | done 58 | the 58 iPad view files |
| Mac 13+ | done 56, partial 2 | the 58 iPad view files |
| macOS 12 | done 50, not for it 8 | the 58 iPad view files |
| Windows | done 50, not for it 8 | the 58 iPad view files |
| Linux | done 53, not for it 5 | the 58 iPad view files |
| Android | done 55, not for it 3 | the 58 iPad view files |
| Wear OS | built 26, partial 3, not for it 4 | the [Watches](#watches) table |
| Apple Watch | built 24, partial 4, not started 1, not for it 4 | the [Watches](#watches) table |
| Chrome extension | done 10, partial 3, not for it 2 | the [Chrome extension](#chrome-extension) table |

**The three biggest gaps**
1. **The desktop ports are level with the iPad.** macOS 12 has 50 screens done and 8 not for it: its last three, the alerts, count as done since a signed `PUSH=1` build showed a held call's alert with tafs closed (tafs-macos#262). Windows has 50 done and 8 not for it, Linux 53 done and 5 not for it, each with its reason.
2. **Mac 13+ and the Chrome extension** are what's partial on a computer: Mac 13+'s 2 wait on the owner's check on a signed Mac ([mac-app.md](mac-app.md#the-owners-check-on-a-signed-mac)), and the extension's 3 are named in its [table](#chrome-extension).
3. **The watches wait on the owner's own checks**: every merged row reads built until the owner has seen it work. **Apple Watch is behind Wear OS** ([Watches](#watches)): the complication's count, and two switches built but off until the owner's yes or the privacy page (the brief, and the phone making the text). (iPhone/iPad and Android are level: the loop card landed last, tafs#1861.)

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
| BriefView | "Your brief": decisions first with Coach's recommendation, the next move, what got done | done | done | done | done | done | done |
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
| NeedsYouView | Needs you: held calls, "Did this happen?", steps, Coach's waits, knocks, alerts | done | done | done | done | done | done |
| NotificationSettingsView | Account › Alerts | done | done | done | done | done | done |
| OnboardingCards | The three cards on how tafs works, after sign-in and from How tafs works | done | done | done | done | done | done |
| OnboardingView | The welcome: what tafs is, the tick for both Terms with the privacy links and the age line… | done | done | done | done | done | done |
| PairComputerView | The phone's side of pairing: the camera reads a computer's code. | done | done | not for it | not for it | not for it | done |
| PairSignInView | Signing in from another phone | done | partial | done | done | done | done |
| PeopleViews | People & groups, naming a group, adding someone (adding someone or naming a manager is approved as sharing is) | done | done | done | done | done | done |
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
- **PairComputerView**
  - **macOS 12** (not for it): The phone's side of pairing: its camera reads a computer's code. This Mac is the computer being added.
  - **Windows** (not for it): The phone's side of pairing: the camera reads a computer's code.
  - **Linux** (not for it): The phone's side of pairing: the camera reads a computer's code, and pairing grants approval authority, which KIFF takes only signed by a phone's approval key (`approval_key_required`)…
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
| 2 | A failed run's refused line, with Retry; Try again under it only on tafs cloud's `failed.retry`, in its `retry_label` | done (a taf's chat: tafs#1766; Coach's: tafs#1867); Try again on `retry`: not yet | done (`chatFailed`, `ChatEnd.Failed`; Try again: `failedRetry`) | android-app.md:24-30, ios-app.md |
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
| 17 | Delete a Coach conversation for good (`DELETE /v1/tafs/{id}/chats/{session}`, tafs cloud's words in each chats-list read's `delete`; the 409's `error`) | not yet: in review for iOS 1.0.1 (tafs#2107; offered only in tafs cloud's words, no stand-ins) | done (`AppModel.deleteCoachChat`; the watch's copy put empty) | android-app.md, "Deleting a conversation" |
| 18 | A phone that serves a watch registers for background pushes only while alerts are off (`alerts: false`, tafs#2100), so the watch stays in step; the blocked-alerts prompt with a settings link and a re-check; the one-time "Your watch is using tafs" prompt | done for iOS 1.0.1 (tafs#2101) | not yet: in the 146 batch (`w146-phone`), on the same `alert.*` strings | ios-app.md, android-app.md; privacy line: kiff/cloud#165, merged with the first of the two builds |
| 19 | The Card step before Coach's first approval (tafs#2091's words; what's shown equals what's signed, compared on structured fields, failing closed), and Coach takes no typing until its tools are approved | not yet: waits on tafs cloud's `start.words` and per-tool fields | not yet: in the 146 batch, same wait | coach.md, "Coach's tools step" |

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
| A taf on the tab you're on: a message from the side panel joins that tab, and carries the page only on the taf's own signed sites | done (tafs#2024, #2025, #2030, #2040, #2048, #2051) | Chrome 0.2.12–0.2.22, with page consent "2"; the taf reads a joined tab only at its exact address and opens anything else in a tab of its own; tafs cloud checks the sites again and gives a page turn only `browse_open` and `browse_read`. Since 0.2.17, in any tab of a run: only the run's own step moves it, and the person's own move hands the tab back to them as it is (`given_back`); the extension never asks for a blocked address again; the fence covers navigations only, and what an allowed site's page sends by itself is that site's own (`requests.e2e.test.js`). Since 0.2.18, a step the taf couldn't do says why in plain words in the side panel, in the person's language (`step_refused_<code>`, tafs#2049, #2054, #2055). Since 0.2.19, Chrome's own fence blocks a sensitive site under a taf's site (a bank label, Chrome's own pages, a site under a listed public suffix, an address with user info) before any request, with the code the extension's own check gives (tafs#2059). Since 0.2.20, a taf never acts on a control the page made unusable (disabled, in a disabled fieldset, inert, `aria-disabled`, a read-only text field, a disabled option), and can't "uncheck" a radio (tafs#2061). Since 0.2.21, a step that ran (a click, Back or a hand-over) whose page is then too large to send is answered done with no page (`page_dropped`), never as a failure, so the taf reads again and never repeats it; planque tells the taf it ran (tafs#2068, planque#431). Since 25 Nov, planque's list of a Chrome's connections carries each one's phone-signed sites (planque#433); before it, the extension never had them, so no taf message ever carried the page in production. Since 0.2.22, the side panel says why a message went without the page (no page consent, with a banner above the message box; a sensitive site; a site switched off; not the taf's site), and a turn that failed says so under the message in tafs cloud's words, with Try again only where nothing was done (tafs#2077, #2075). planque allows no build from 0.2.12 to 0.2.18 (planque#424, #425, #429), and allows 0.2.19 to 0.2.22 (planque#428, #430, #432, #434). Open: a dialog a page raises as it loads reaches Chrome as a real dialog, and the run waits; the guard goes in once the page has committed (`docs/chrome.md`). It reaches the Web Store with the current build's package (the store row below) |
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
| 10 | The answer: Hear it, Reply, and Read more for the whole answer | built (Hear: tafs#1868, `WatchHear`, a voice of the app's own language only) | built | Read more reads the whole answer, Coach's too (`?full=1`, tafs#2020), on both watches and the phones' From your watch days (tafs#2021, tafs#2018). Wear 0.4.9: answers drawn and spoken as plain text (no raw markdown); the Apple Watch follows. |
| 11 | An answer you can't miss, with the app put away | built | built | failed on the owner's Galaxy Watch 5 check of Wear 0.4.7 (25 Nov); both rebuilt to the owner's 8 calls. Apple Watch: the iPhone posts the answer, a taf chat's under the taf's name, and the watch shows the iPhone's notification (`CoachFollow`, tafs#2018). Wear OS: the push starts a job on the phone that hands the answer to the watch as a Data Layer item, delivered and notified with the watch's app closed (tafs#2021). Android 146: a phone that serves the watch registers for its background pushes with alerts off (`alerts: false`), so the answer reaches a watch whose phone never turned alerts on (the owner's S23, 25 Nov); the iPhone follows with the same rule (tafs#2101). Android 146 also keeps the taf list for the watch as it changes (`kept/tafs`) and answers the watch's reads within its 10 s; the phone's blocked-alerts dialog has Open notification settings and a re-check, and "Your watch is using tafs" shows once (the iPhone in tafs#2101) |
| 12 | Coach as one conversation, opening on the newest, in step with the phone | built | built | failed on the owner's Galaxy Watch 5 check of Wear 0.4.7 (25 Nov); rebuilt in tafs#2021 and tafs#2018: one thread with the phone's, pushed live, opening at the bottom |
| 13 | Chat with one taf: send in the thread with your time and ✓, the answer under it | built | built | failed on the owner's Galaxy Watch 5 check of Wear 0.4.7 (25 Nov) (Send gave "Open on phone"); rebuilt in tafs#2021 and tafs#2018. A message from another device reaches the watch by tafs cloud's `taf_chat` push (tafs#2022). tafs cloud's switch still decides per account, and the watch says it's off before recording Wear 0.4.9: while the chat shows "answering", the watch follows the answer itself (every 5 s, 2 minutes at most), and the words just sent show whole; the Apple Watch follows. |
| 14 | A taf's page and its settings | built | built | |
| 15 | Your tafs, with their state | built | built | |
| 16 | Today's calendar | built | built | |
| 17 | The Account page | built | built | |
| 18 | Sign out on this watch alone | built | built | |
| 19 | Sign in, pair a device, add a passkey, change a Card | not for it | not for it | no sign-in of its own; the keys stay on the phone |
| 20 | The inbox kept on the watch | built | built | Wear OS: failed on the owner's Galaxy Watch 5 check of Wear 0.4.7 (25 Nov); rebuilt in tafs#2021, kept encrypted (AES-GCM under the watch's Keystore key), only on a watch with a screen lock Wear 0.4.9: a reopen no longer drops lines the encrypted store missed, and a taf's answer is never filed here; the Apple Watch follows if its store can fail the same way. |
| 21 | The inbox's read and dismissed, in step with the phone | built (`InboxModel.sync`, `inbox_marks` / `inbox_mark`) | built (`WatchModel.syncInbox`, `inbox-marks` / `inbox-mark`) | tafs cloud keeps them (`/v1/watch/inbox`), by id alone |
| 22 | Your brief | partial | partial | built; off (`briefOnWatch`, `BRIEF_ON_WATCH`) until the owner's yes |
| 23 | "From your watch" on the phone | built | built | |
| 24 | A complication or tile (count, Ask tafs) | partial | built | Apple Watch: Ask tafs on the face and in the Smart Stack (`TafsWatchWidgets`), opening Coach's home listening; the count of what waits comes with an App Group |
| 25 | The phone makes the text when the watch's recognizer gives up | partial | built | Apple Watch: built, off (`WatchVoice.on`) until the privacy page says so and the owner says yes |
| 26 | Play and Pause the recording before sending | not started | partial | Apple Watch: W12 held for the owner's yes. Wear: test builds only (`KEEP_AUDIO`) |
| 27 | Opening at once from what the watch kept, with no "Asking your phone…" | built | built | Wear OS: failed on the owner's Galaxy Watch 5 check of Wear 0.4.7 (25 Nov). Both rebuilt to keep Coach's and each taf's thread (30 messages, 20 threads, 30 days unopened, no amounts), at rest encrypted on Wear and protected on the Apple Watch (tafs#2021, tafs#2018), and on with the Privacy line (kiff/cloud#151): Wear 0.4.8 (`KEEP_THREADS`, tafs#2027) and the Apple Watch (`WatchThreads.on`, tafs#2028) Wear 0.4.9: a slow list read keeps the last taf list (never empties) and is read again; the phone pushes the list as `kept/tafs` on change (Android 146); the Apple Watch follows. |
| 28 | Open on phone, and "The rest is on your phone" in the thread | built | built | Wear OS: failed on the owner's Galaxy Watch 5 check of Wear 0.4.7 (25 Nov); rebuilt in tafs#2021. Apple Watch: the iPhone opens the chat when tafs is in front, else posts a notification whose tap opens it (`WatchOpen`, tafs#2018) Wear 0.4.9: a message sent from the watch keeps its turn, so a cut answer has Read more; a cut line from the phone has Open on phone; the Apple Watch follows. |
| 29 | The all-clear picture | built | built | |
| 30 | Saying the phone can't be reached, with retries on their own | built | built | the owner's call: no Try again. One calm "Linking tafs. Keep your phone nearby and connected." (`watch.linking`) on the first link, retries with backoff (tafs#2021, tafs#2018) Wear 0.4.9: a send with no receipt shows the calm linking screen and sends again by itself under the same id (no Retry until 10 minutes pass); the pages' and a taf page's small line is the calm one, retrying by itself; the Apple Watch follows. |
| 31 | Working without the phone | not for it | not for it | the phone holds the session and the keys |
| 32 | In the store | partial | partial | Apple Watch: only the TestFlight build carries it. Wear: the internal track; production is the owner's |
| 33 | One account's data never shows under another's: a change of the phone's sign-in, or a sign-out the watch didn't hear, wipes what it kept | built (tafs#2092: the mark checked before anything is drawn, Retry only where safe; tafs#2097: the person's acts go only to the sign-in they were made under) | built (tafs#2093) | Wear OS: the phone's random account mark on every reply and kept item, checked before anything is drawn and failing closed, and `kept/signed-out` at sign-out (docs/watch-wear.md). Apple Watch: the same rule (docs/watch-app.md), in iOS 1.0.1. Both read built until the owner's check |

## One layout on every app

The rule: on a wide window, every app draws a page the same way: the iPad, the Mac, Windows, Linux and an Android tablet. The widths are the iPad's and the Mac's caps as they stood, now centred.

Where each app stands: done on the iPhone, iPad and Mac 13+ (tafs#2009), Android (tafs#2011, and the brief's own cap, 1100, in tafs#2013), macOS 12 (tafs-macos#264), Linux (tafs-linux#136) and Windows (tafs-windows#342).

- **A centred column, capped.** Each page is one column, centred in the window, at most:
  - 720 points for reading pages: Account, Devices, People, Alerts, Chrome, Needs you, Connect apps, Plan & usage;
  - 980 for list pages: Overview, sharing, an app's connections;
  - 640 for the recipe gallery;
  - 1100 for the Brief and 1180 for Calendar.

  Text and rows inside the column stay aligned left, and a window narrower than the cap is unchanged. Each app keeps these caps as named constants, in one place, so no page sets its own number.
- **An explanation sits beside its action.** On Account, Chrome's text goes under the Chrome row, with its button. How sign-in works goes into the sign-in section, said once. How it works is one entry. No section is only an explanation.

## The owner's findings of 24–25 Nov, device by device

Every finding from the owner's walk-throughs of the Chrome extension, the Galaxy Watch 5 and Windows, read again on each repo's main. Each cell is **fixed** (with the change), **n/a** (and why), **built** (a watch's code merged, before the owner's check) or **open** (what's left). "—" means the device has nothing that finding is about.

**Chrome extension (A–F)**

| Finding | iPhone/iPad, Mac 13+ | Android | macOS 12, Windows, Linux | Chrome extension |
| :- | :- | :- | :- | :- |
| A. Coach said it never read a page it had read | — | — | — | fixed in tafs cloud: Coach's history says which of its turns read a page, and the host (tafs#2000) |
| B. A Wikipedia taf refused an article's address | — | — | — | fixed for new tafs: planque opens `https://<language>.wikipedia.org/wiki/<Title>` for a grant on wikipedia.org (planque#411), and `a-browser-taf@5`, `@6` say so (tafs#2002, tafs#2019). **Open** for tafs already set up: they keep their old instructions until the tafs upgrade runs, which waits on the owner |
| C. The box still said "working" after the taf's last answer | n/a: the panel's own state (the fix is in the extension only) | n/a, same | n/a, same | fixed (tafs#2001, 0.2.9) |
| D. The tab group kept "· working" after Stop | — | — | — | fixed: "is done" on Stop (tafs#2001) |
| E. Chat as the apps do it: the composer pinned, your message at once with a working line, attachments, microphone | the reference | the reference | the reference; no microphone on Windows or Linux (speech would leave the device) | fixed: pinned composer and your message at once (tafs#2001, and with a short chat tafs#2017), attachments (tafs#2005). Microphone n/a, as on Windows and Linux: Chrome's speech recognition sends the audio off the device |
| F. Back, New conversation and ⋮ scroll away | n/a: the system's own bar | n/a, same | n/a, same | fixed: a sticky header (tafs#2001) |

**Your tab and the page (Chrome 0.2.12–0.2.22)**

| Finding | Chrome extension |
| :- | :- |
| A run started from the side panel should work in the tab you're on | fixed (tafs#2024, 0.2.12) |
| A step of another run must never work in a tab joined by an earlier one | fixed (tafs#2025, 0.2.13) |
| A joined tab was sent to another address | fixed for an open: a joined tab is read only at its own address, and any other open goes to the run's own tab (tafs#2030, 0.2.14). An act (a link clicked, a form sent, back) moving a joined tab within the run's sites is the feature: the work happens in your tab, where you see it. The store and Privacy words say so |
| A taf's message carries the page you're on | fixed: only on that taf's phone-signed sites, under page consent "2", checked again by tafs cloud, and a page turn only reads (tafs#2030) |
| The person's own Back in a joined tab, to a page they had before the run, was read and acted on | fixed: only the run's own step adds a page the taf may read; any other move hands the tab back (tafs#2040, 0.2.15), and planque passes the taf no page at another address (planque#422) |
| In a tab of the run's own, an address the person typed, or their Back or Forward, was read | fixed: the same rule in every tab of a run (tafs#2048, 0.2.16) |
| Two tafs of the same name shared a kept tab and group | fixed: kept by grant, never by name (tafs#2048, 0.2.16) |
| After the fence stopped a navigation, the extension asked for that address again, unfenced | fixed: it never does, in any tab; the tab goes back to the person as it is, and their own next try loads (tafs#2051, 0.2.17). planque allows no build from 0.2.12 to 0.2.16 (planque#424, #425) |
| A sensitive site under a taf's site (`bank.example.com` under `example.com`) could be reached once by a link | fixed: Chrome's own fence blocks it before any request, in a run's own tab and in a joined one (tafs#2059, 0.2.19). planque allows no build from 0.2.12 to 0.2.18 (planque#429). Open: planque still allows 0.1.0–0.2.11, whose fence has the same gap |
| A taf's act landed on a control the person can't use (a field in a disabled fieldset, a read-only field, a disabled option), or answered ok to "uncheck" a radio that stayed checked | fixed: refused `disabled`, or `bad_step` for the radio (tafs#2061, 0.2.20) |
| A click that ran was answered as a failure when the page then grew too large to send, so the taf could click Submit again | fixed: answered done with no page (`page_dropped`), and planque tells the taf it ran and not to repeat it; a Pay or order click was already refused before anything is pressed (tafs#2068, planque#431, 0.2.21) |
| A taf message never carried the page in production: planque's connections had no phone-signed sites, which the extension reads them from (its e2e's fake planque sent them, so every test passed) | fixed: planque sends each connection's `sites_signed`, the same pair its steps carry (planque#433); the extension's tests and fake planque are now built from planque's own contract files (tafs#2077) |
| A message sent without the page, or a turn that failed, said nothing in the side panel | fixed: a banner above the message box when there's no page consent, a line under the message saying why the page didn't go, and the failed turn's line in tafs cloud's words, with Try again only when the run's record shows nothing was done (tafs#2077, 0.2.22; tafs#2075) |
| A dialog a page raises as it loads (an `alert`, a `confirm`) reaches Chrome as a real dialog, and the run waits until it's answered | open: the guard goes in once the page has committed, so the page's own script comes first (`gaps2.e2e.test.js` pins it). A sandbox on a run's own tabs closed it in a spike, but it could answer a page the person opened in that tab, so it doesn't ship |
| What an allowed site's page sends by itself (a `fetch`, an image, a beacon) after the taf typed there | not a gap: the fence covers navigations only, by design, as when the person types there; the store and Privacy words say so (tafs#2052) |

The other devices have no tab to join: — on each.

**The watch (the owner's 8 calls)**

| Call | Apple Watch | Wear OS | Phones |
| :- | :- | :- | :- |
| 1. Kept on the watch, opening at once | built (tafs#2018, #2028) | built (tafs#2021, #2027) | — |
| 2. One thread with the phone's, live | built | built | fixed: the phones push it (tafs#2018, tafs#2021) |
| 3. Opens on the newest message | built | built | fixed: both phones' taf chats open at the bottom (`TafChatView`) |
| 4. Send in the thread, with your time and ✓ | built | built | — |
| 5. Nothing replaces the chat; Open on phone opens it | built (`WatchOpen`) | built | — |
| 6. No Try again: retries on their own, one calm "Linking tafs" | built | built | — |
| 7. An answer you can't miss, with the app put away | built (the iPhone's notification) | built (tafs#2021) | — |
| 8. The whole answer from its preview | built (`?full=1`, tafs#2020) | built | fixed: the phones' From your watch (tafs#2020) |

Each watch cell moves to done, or to "broken on the owner's check", from the owner's check of the current builds ([Watches](#watches)).

**Windows (notes 1–5)**

| Note | iPhone/iPad, Mac 13+ | Android | macOS 12 | Windows | Linux |
| :- | :- | :- | :- | :- | :- |
| 1. One centred column on every page | fixed (tafs#2009) | fixed (tafs#2011, #2013) | fixed (tafs-macos#264) | fixed (tafs-windows#342) | fixed (tafs-linux#136) |
| 2. Plan & usage and Calendar cut off when maximized | fixed by note 1's caps | fixed by note 1's caps | fixed by note 1's caps | fixed (tafs-windows#342) | fixed by note 1's caps |
| 3. Connect apps: a long status ("Volver a conectar") cut, and Ask Coach over it | fixed: a long button goes under the status (`BesideOrUnder`, tafs#2035) | n/a: a row has one action at a time (Connect, Sign in again or Ask Coach), and every line wraps; drawn whole at 360 dp in es, de, fr, pl and en (`ConnectAppsNarrowTest`) | fixed: the button goes under a worded status (tafs-macos#268) | fixed (tafs-windows#342) | fixed: the status wraps, the button under it (tafs-linux#139) |
| 4. Account's explanations beside their actions | fixed (tafs#2009) | fixed (tafs#2011) | fixed (tafs-macos#264) | fixed (tafs-windows#342) | fixed (tafs-linux#136) |
| 5a. Overview cut "1 persona" in a medium window | fixed: two lines, the count held whole (`peopleLine`, tafs#2035) | fixed: who uses a taf, under its name, two lines, the count held whole (`PeopleWords.peopleLine`, tafs#2042; Android 144 on Play's internal track) | fixed: two lines, the count held whole (tafs-macos#268) | fixed: it wraps (tafs-windows#342) | fixed: two lines, the count held whole (tafs-linux#139) |
| 5b. Connect apps' lines in English in another language | fixed at the source: planque's catalogue in every app language (planque#414), asked in the person's language (tafs#2015) | fixed, same | fixed, same | fixed, same; checked in Spanish | fixed, same |

**Cut answers**

| Finding | Where | State |
| :- | :- | :- |
| Coach's answer to a watch ask stopped at 140 characters, with nothing to open it | both watches, and the phones' From your watch | fixed in tafs cloud: `?full=1` gives the whole answer (tafs#2020); built on the watches |
| A taf said its read was cut, in bytes | every taf, on any device | fixed for new tafs: the tafs' shared instructions read on and never talk about size (tafs#2019, `a-browser-taf@6`). **Open** for tafs already set up, until the tafs upgrade runs |

**Coach, from the end-to-end runs (tafs cloud, so every device alike)**

| Finding | State |
| :- | :- |
| Coach drafted a held Card with no total, KIFF refused it ("a mandate with no aggregate bounds nothing"), and Coach asked the owner to change their own rule | fixed: the held Card's one total is KIFF's own count, drafted by Coach, and a refusal says how (`draft-the-card@47`, tafs#2045) |
| Coach added four letters to a tool's name, and KIFF refused it ("not declared") | partial: tool names are copied from planque letter for letter, with no example to build from (`draft-the-card@47`, `set-up-a-taf@15`, tafs#2045, #2047), and the draft still lands. But the E2E of 25 Nov on those skills was still refused twice first (`read_charge_…` and `stripe_api_read_…`, once each), so fewer, not none |
| "Every refund held, over €50 refused" was drafted by hand eleven times: KIFF refused a held Card with no amount cap, tafs cloud refused each way out, and Coach asked the owner to change their rules, said it waited for an approval that had been refused, and answered "what was refused?" with a new choice | fixed: tafs cloud builds a money tool's Cards from the agreed rules, the held Card carrying the refused Card's cap (refused past it, never held), and Approve answers any other draft with the exact Cards; Coach says nothing after an approval, its line on a refused card leaves the chat, and a refusal asked about is quoted (`cardsFromRules`, `draft-the-card@48`) |

**Open, in one place:**
1. Tafs set up before tonight keep their old instructions (B, and the cut read) until the tafs upgrade runs. That waits on the owner.

## Today's cross-device work

What landed on 23 Nov, device by device, read again against each repo's main on 25 Nov. "—" means the feature isn't that device's. What's still open is listed under the table.

| Feature | iPhone/iPad | Android | macOS 12 | Windows | Linux | Chrome extension |
| :- | :- | :- | :- | :- | :- | :- |
| Trials of a new version on the security key | done | done | done (tafs-macos#182) | done | done (tafs-linux#96) | — |
| Sign in again and Replace the key on a connection | done | done | not for it: made on the phone (planque takes no security key) | done (tafs-windows#268) | done: drawn up to planque's signature, which is the phone's (granting access is never done from a computer; its checklist's ConnectAppsView) | — |
| Sign in again shows the connection's approval sheet first, and signs only the request it showed | done (tafs#1878) | done (tafs#1826) | as the row above | as the row above | as the row above | — |
| A sealed sign-in fill's release card | held: no passwords go through the extension (the owner's decision) | merged and off, stays off (tafs#1882) | — | — | — | — |
| "Allow this from now on" checks its own `matches`: a revision Card's `current.id`, when sent, must equal `revises`; and a Card key the check doesn't read is refused (`vectors/agent-draft-keys.json`, over KIFF's draft pinned in `contracts/kiff-agent-draft-from-hold.json`) | done (tafs#1891, tafs#2006) | done (tafs#1893, tafs#2006) | done (tafs-macos#222, tafs-macos#265) | done (tafs-windows#330, tafs-windows#343) | done (tafs-linux#121, tafs-linux#137) | — |
| A connection's own page from planque's row, with its actions | done | done | done: its approvals are the phone's, since planque takes no security key (its checklist's ConnectAppsView) | done (tafs-windows#269) | done: Remove access with the security key, Sign in again drawn up to the phone's signature | — |
| An approval that lands after its run ended says so (tafs cloud's `run_ended`, tafs#1812) | done (tafs#1814: the chat's line, with Retry) | done (tafs#1816: the chat's line, with Retry) | done (tafs-macos#263: said on Needs you, not in the chat) | done (tafs-windows#271: said on Needs you, not in the chat) | done (tafs-linux#99) | — |
| An API door's example safe to paste into a shell (a plain path only, quoted; `vectors/api-door-endpoint.json`, tafs#1877) | done (tafs#1875) | done (tafs#1874) | done (tafs-macos#205) | done (tafs-windows#323) | done (tafs-linux#111) | — |
| The side panel is the taf in this Chrome (tafs#1799), no client named (tafs#1810), the read after an act waits for its navigation (tafs#1811) | — | — | — | — | — | done |
| Try again under a failed run's line, shown only when tafs cloud's `failed` says `retry`, in its `retry_label` (`contracts/conversation-failed.json`) | not yet: Retry shows on every failed run and sends the message again, even after the run acted; `retry` isn't read (`AppModel.retryChatRun`) | done (tafs#2084) | not yet: the pin has `retry` since tafs-macos#271, nothing reads it | not yet: the pin has `retry` since tafs-windows#347, nothing reads it | not yet: the pin has `retry` since tafs-linux#141, nothing reads it | done (0.2.22, tafs#2077) |

**Still open:** Try again on a failed run's line, on the iPhone and iPad and on the three desktops. Every other row above is done on each device it's for, except the sealed sign-in fill's release card, which stays held and off.

## 25 Nov, device by device

What merged or opened on 25 Nov, read against kiff/tafs main `56319b8` and each desktop repo's main. **done** means merged on main (a phone or watch row still waits for its build to ship); **not yet** says what's missing. "—" means the feature isn't that device's.

| Feature | iPhone/iPad | Android | Apple Watch | Wear OS | macOS 12 | Windows | Linux | Chrome extension |
| :- | :- | :- | :- | :- | :- | :- | :- | :- |
| A watch wipes what it kept when the phone's sign-in changes or it signs out | — | — | done (tafs#2092, #2097) | done (tafs#2093) | — | — | — | — |
| Background-only pushes for a phone that serves a watch, with alerts off (tafs#2100, live) | done (tafs#2101) | not yet: 146 batch | — | — | — | — | — | — |
| The alerts prompts: blocked alerts with a settings link, and "Your watch is using tafs" once | done (tafs#2101) | not yet: 146 batch | — | — | — | — | — | — |
| Delete a Coach conversation for good (tafs#2095 live; words tafs#2096, merged) | not yet: in review (tafs#2107) | done (tafs#2099) | not yet: its kept Coach thread can hold a phone conversation's turns, and tafs#2107 tells it to drop them; its own conversations can't be deleted yet | done for the phone's conversation (tafs#2099: the kept copy put empty); its own conversations can't be deleted yet | not yet: the pinned `coach-chats` contract has no `delete` until the next pin bump | not yet: as macOS 12 | not yet: as macOS 12 | not yet: the side panel's chats can't delete |
| The Card step before Coach's first approval (tafs#2091's words), shown = signed | not yet: waits on tafs cloud's fields | not yet: 146 batch, same wait | — | — | not yet: same wait (a computer signs with its passkey: `card_step_give_passkey`) | not yet: as macOS 12 | not yet: as macOS 12 | — (never approves) |
| Coach takes no typing until its tools are approved | not yet: with the Card step | not yet: with the Card step | — | — | not yet | not yet | not yet | not yet: the side panel's Coach |
| Try again under a failed run's line, only on tafs cloud's `failed.retry` | not yet (see the row above, 23 Nov) | done (tafs#2084) | — | — | not yet | not yet | not yet | done (0.2.22) |
| Sign-out leaves nothing of the account: its alerts and the files opened from its chats | done (`AppModel.forgetSession`) | opened files: done (`openedFiles` at sign-out); its token is taken back, but no code takes away alerts already in the shade (not yet) | — | — | alerts: done (tafs-macos#272) | done | opened files: done (tafs-linux#142); alerts were already withdrawn | — |
| The fence: kiff.dev never opened, merchant consoles sensitive, money sent out refused as a payment step | done (sensitive list, tafs#2103) | done (sensitive list, tafs#2103) | — | — | — | — | — | done on main (tafs#2103); ships in 0.2.23. tafs cloud's page reads match (tafs#2105) |
| The page shows a taf at work: a glow, "… is working here" at the top, a drawn pointer, and no act without its aim | — | — | — | — | — | — | — | not yet: 0.2.23 in review (tafs#2108) |

**Still open from 25 Nov:**
- The Card step and the composer lock on every device, once tafs cloud sends their fields.
- Android's 146: background-only push, the alerts prompts, the Card step and the composer lock.
- Delete on the desktops and in Chrome.
- iOS's Try again.
- Chrome 0.2.23's signs.

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

