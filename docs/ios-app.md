# The app's code

tafs for iPhone and iPad is SwiftUI, iOS 16 and later. The project is generated:
`ios/project.yml` → `xcodegen generate` → `Tafs.xcodeproj`, which isn't committed. This
page is a map of the code; what each feature does is on its own page
([README.md](README.md)).

## Targets

| Target | Sources | What it is |
|---|---|---|
| `Tafs` | `ios/Tafs`, `ios/Shared` | The app |
| `TafsWidgets` | `ios/TafsWidgets`, `ios/Shared` | The Live Activity for the first held call, on the lock screen and in the Dynamic Island (iOS 16.1) |
| `TafsNotify` | `ios/Notify` | The notification service extension: fills in a hold's alert from KIFF ([approvals.md](approvals.md#alerts-while-tafs-is-closed)) |
| `TafsTests` | `ios/TafsTests` | Unit tests |
| `TafsUITests` | `ios/TafsUITests` | UI tests |

## The folders of `ios/Tafs`

| Folder | What's in it |
|---|---|
| `App/` | `TafsApp` (the scene, and the bar's tabs: Account, Tafs, Needs you, Record), `AppModel`, `DoorsModel` |
| `KIFF/` | The clients: `KIFFClient` (KIFF's API), `TafsCloud` (+ `TafsCloud+Doors`), `PlanqueClient`; `KIFFMapping` (KIFF's records into the app's), `ReceiptCheck` and `Keccak` (receipts checked on the device), `JCS` (canonical JSON for what's signed), `Keychain`, `SigningTime` |
| `Model/` | The app's own types: `Models` (`Taf`, `Card`, `HeldCall`, `RecordEntry`…), `CardCheck` (sample mode only), `SentMessages`, `SetupComponent`, `SetupShape`, `QuestionAnswers` |
| `Services/` | Signing (`ApprovalSigner`, `Biometry`), alerts (`PushDelegate`, `HoldNotification`, `AlertAnswers`, `LockScreen`), `Purchases`, `Attachments`, `Dictation`, `HaltAllIntent`, `VendorSignIn`, `Routes`, `SampleStore`, `TafTemplates`, `ThisDevice` |
| `Views/` | One file per screen or feature: `HomeSplitView`, `HomeView`, `TafDetailView`, `TafChatView`, `WorkLog`, `SetupChatView`, `CoachMode`, `NeedsYouView`, `RecordView`, `UsageView`, `CreditsViews`, `PlanViews`, `PeopleViews`, `ShareViews`, `DoorsView`, `TrialViews`, `HaltScreen`… |
| `Design/` | `Theme` (colours, light and dark, the amber accent), `Components`, `MarkdownText`, `Scaled` (Dynamic Type), `Keyboard`, `Compat` (what iOS 16 lacks) |

## AppModel

`AppModel` (`App/AppModel.swift`) is the app's one `ObservableObject`, on the main
actor. It holds the tafs, the held calls, the Record, the chats and every state the
screens show, and does every call to KIFF, tafs cloud and planque. The views read it and
call its methods; they hold only their own view state.

- **Modes:** `signedOut`, `sample`, `kiff` ([README.md](README.md#two-modes)).
- **Sessions:** `accountGeneration` moves on with every sign-in and sign-out. Work started
  for one session checks it after each wait, so nothing of an earlier session lands in
  the next.
- **Sections**, marked `// MARK:` in the file: Reading, Signing in with KIFF, People,
  Alerts while tafs is closed, The remembered list, Sample mode, Starting a taf, Runs,
  The owner answers, Stopping, Halt, Plan & usage, Buying, raising the limit, and asking an
  admin, Deleting the account,
  Memory, Versions, Trying a version, Chat, Attachments, A taf's chat live, Doors and live
  updates, A taf's work, Connections, Setup, Coach mode.
- **Errors:** `lastError` is the "Not done" alert, only for something the person did
  that didn't go through. A refresh the app makes by itself never sets it (the 930 test).
  It sets `unreachable` instead: "Can't reach KIFF" when the network failed, "Couldn't
  load from KIFF" when KIFF answered with an error (`refreshWords`). That shows as one
  quiet line under the account's name with a link to status.kiff.dev (`UnreachableLine`).
  The app tries again by itself after 2 s, 10 s, 30 s, 60 s and 120 s (`retryWaits`),
  then waits for the next return to the app or a pull to refresh. The line goes once a
  refresh gets through.
- **Testing:** every client and the clock are injected (`init(signer:kiff:cloud:planque:…)`),
  and tests serve KIFF and tafs cloud from `StubProtocol`. A chat's stream can be given
  directly (`streamOpener`, `resumeOpener`).

## Live updates

A silent push from tafs cloud names what changed on the account (`change` and `taf_id`,
[approvals.md](approvals.md#live-updates)). `PushDelegate` hands it to `AppModel.liveChange`,
which reads that again with the phone's own session and takes nothing from the push:
- `taf`: the home list, and that taf's thread and work where they've been read;
- `halted` and `resumed`: the home list;
- `knock`: that taf's doors, and its thread where it's been read.

Every refresh and every push also reads planque's grants again: tafs cloud never sees them, so
this is how a connection its vendor signed out reaches the list. A change of a kind the app
doesn't know is ignored.

Each taf has one `DoorsModel` (`AppModel.doors(for:)`), shared by Setup › Starts when and its chat,
so a door's knocks sit in the chat at their own times (`ChatItem.knock`). A paired sender's
message, kept from its knock, reads "Paired · Do it or Ignore this message", calmly, and sits
apart from strangers' knocks on the door's own screen. A door shows tafs cloud's words for itself
(`words`: `state`, `tap`, `settings`); the app's own lines stand in only where it sends none.

**A connection its vendor signed out** (`needs_reconnect`): its taf waits on the owner at the
top of the home list, its row reading planque's `status_words` ("Signed out by Stripe · sign in
again"; `AppModel.signedOut`, `waitingOnOwner`). A taf newly waiting goes to the top at once;
otherwise the list holds its settled order. The taf's Card tab offers **Sign in again**
(`AppModel.reconnect`), and Coach's sign-in card says the same for such a grant. Either way it's
one approval, then the vendor's own page, and it's signed in only once planque answers
connected: "Checking the sign-in…" meanwhile (`connectStep`). When planque holds a fresh sign-in already (the account's Google sign-in, say), it answers the
approval `signed_in` with no link and a one-use `confirm` code: the phone confirms it with that code
(`PlanqueClient.confirm`), as it does a browser's, and goes by what planque answers, `listed` (the tools
step next) or `connected`. It's never a second sign-in, and unconfirmed planque would revoke it at
`confirm_by`. A `signed_in` with no code isn't taken as done.

**A run's two lines in the chat** (`TafChatView`, `TafsCloud.ChatMessage.runLines`, `RunLine`): a taf's
message may carry tafs cloud's `delivered`, where the run's result went, and `counted`, what the run did,
counted from the record ("From the record: 5 done · 1 refused"). Each is drawn small and as written,
`delivered` above the taf's words and `counted` under them. A blank one is none, and with none sent
nothing is drawn: the phone counts nothing itself. Only a taf's message draws them.

**A connection that was refused** (`TafsCloud.ChatMessage.refusedLines`, `RefusedLines`): a taf's message
may carry tafs cloud's `refused` ([chat.md](chat.md)), the failed step's words, the server's reason ("Why:
…") and what to do next. They're drawn under the taf's words and under `counted`, never folded into its
steps, the first in the failure colour. A blank line is left out, and with none sent nothing is drawn.

**What a run used:** under a run's line, in the work and in Its runs, tafs cloud's `requests[].credits.words` as sent
("34 credits", or why it used none; `TafWork.Request.creditsLine`). A run KIFF hasn't counted has no line, and the phone
counts nothing.

**A taf's work:** the Activity tab of a taf on tafs cloud (`TafActivityList`, chosen by
`TafDetailView.pane`) shows the taf's Needs you items, then `GET /v1/tafs/{id}/work`
([reliability.md](reliability.md#the-tafs-view); `WorkPanel`, `AppModel.loadWork`): tafs
cloud's line, then its recent requests and skipped firings, newest first, in tafs cloud's words.
A request that needs the owner is amber, a failed one red, a skipped firing dim. A door reply
given up on offers **Send again** to whoever manages the taf (`AppModel.sendAgain`), and so does a
place of the taf's own that wasn't delivered (an action at a connected app, or a hand-off the
receiving taf didn't finish): each is sent again by its own place, the door's reply or the
delivery's `key` (`Delivery.sendPlace`). Then its
runs (`RunsPanel`): when it runs next, in tafs cloud's `next_run_words` ("Next run Mon 08:00", with
the day when it isn't today; the app's own time only from a tafs cloud that sends none), or that it
works from its chat, and **Run now**; then the runs it kept (`RetainedRuns`), which stand for its
latest run's report, with the firings that started no run among them by time (the first page's
`skipped`, a Run now whose check couldn't read among them, `RunsPage.rows`), dim. The home list says the next run the same way. A taf
whose `work.attention` is set says what went wrong last in place of its next run, and its row and
page drop the **Working** chip (`Taf.attention`). Its Cards keep tafs cloud's order (`Taf.shownCards`):
the one in front is the one that has used most of its limit. The Record shows an action's whole
title with its amount ("Save a dispute response · $800"), never the verb alone. What needs the owner
comes first, then the work, then the runs, as on Android (`TafActivityList.parts`). Under the health lines, the work shows its **oldest waiting case**
(`oldest_waiting.words`, tafs cloud's line, amber once `late`) and, for whoever manages the taf,
**Answer within** (`PromiseRow`): the promise as tafs cloud keeps it (`promise.hours`) or Not set,
and a menu of None, 1, 2, 4 or 8 hours, or 1, 2 or 7 days (whole days read as days, `PromiseRow.label`),
that sends the hours in `PUT /v1/tafs/{id}/promise` (`AppModel.setPromise`). The app keeps no copy; a promise that couldn't be set says why under the
work. The phones that answer its holds are told when a case waits longer; tafs cloud sends that
alert, and it opens the taf.

**A taf that isn't healthy** ([reliability.md](reliability.md#its-health); `HealthLines`,
`TafsCloud.Health`): skipped work is never healthy, so the phone says why and what fixes it.
- **Its page:** under the work's line, each of tafs cloud's `health.lines`, amber when at risk and
  red when failing, from the work read or else Home's. Under a line, the fixes tafs cloud decided
  for it (`health.fixes`):
  - **`coach`** opens Coach on this taf with `say` in its box, nothing sent
    (`AppModel.askCoachAbout`), for someone who may use Coach and manages the taf.
  - **`credits`** opens Plan & usage: "Add credits", or "Ask an admin for credits", as tafs cloud
    words it for who's reading.
  - Any other kind, or a fix with no words, is no button.
- **Home** (iPhone and iPad), in tafs cloud's order: an otherwise idle taf that isn't healthy
  shows the list's `work.line`, which then says its first reason, amber or red
  (`Taf.healthNow`). A taf that's halted, needs its owner, is working, retrying, waiting or
  paused says that, as before. A signed-out connection still comes first.
- **On its page,** an idle taf's work line is its first health line, so it's said once, with its
  colour and fix.
- **Healthy, or no health read,** draws nothing. A `health` that isn't one never fails the
  read it's on (`HealthTests`).

**Dictation** goes in at the box's insertion point, with what was after it kept after the words
heard (`Dictation.cursor`, from iOS 18, which says where the point is); before that, at the end.

## Launch arguments

Used by CI's screenshots and the UI tests (`Screenshots` in `AppModel.swift`); each runs
the app in sample mode.

| Argument | What it does |
|---|---|
| `-screenshots YES` | Sample mode with the screenshots' data |
| `-tab account \| tafs \| needs-you \| record` | Opens that tab |
| `-screen taf \| card \| setup \| starts \| results \| alerts \| chat \| new \| solo \| buy-credits` | Opens that screen (`starts` and `results`: Setup's screens with `Screenshots.setupKnob`; `alerts`: Account › Alerts, with `-tab account`) |
| `-screen calendar \| calendar-week` | Opens Calendar on today, in Day mode, or in Week mode on today's week (the Today pill already today): an iPad's whole-app sidebar selects its Calendar item, the phone opens it as Home's button does; over the sample it reads nothing from tafs cloud. CI's `languages` set shoots both, and the Overview (`-tab tafs`), on the 13-inch iPad in French and Portuguese with `-signedIn YES` (`fr-ipad-calendar-week.png`…) |
| `-landscape YES` | The iPad's window turned to landscape, for its screenshots |
| `-signedIn YES` | The iPad's sidebar laid out as for a signed-in account, its screens and Coach above the account, over the sample's tafs: a layout shot, nothing read from KIFF |
| `-pack <id>` | The Add credits screen with that pack alone |
| `-narrow YES` | Laid out at Slide Over's width on an iPad |
| `-sampleReplySeconds <s>` | The sample taf answers slowly enough for a UI test to see each part |
| `-listSettlesOnReturn YES` | The list opens on its first taf, and its last taf moves to the top each time the app comes back (`ListTapUITests`) |

## The taf layout and KIFF's words

- **The bar** (`TafsBar`, in `TafsApp`'s `RootView`): **Account · Tafs · Needs you · Record**,
  then **Coach**, a filled ink button that opens Coach mode, shown only when
  `signedIn && mode == .kiff && mayUseCoach`. Along the foot on a compact width, below the
  screens so a chat's field stays above it, and hidden while the app's own keyboard is up (not
  for a system alert's, such as "Sign in to Apple Account", or another app's beside it). Each tab is
  built the first time it's opened and then kept. Account is `SettingsView`; ⌘1–3 open Tafs,
  Needs you and Record, ⌘, Account, ⌘K Coach.
- **On an iPad's full width, one sidebar for the whole app** (`HomeSplitView(tab:coachAvailable:)`,
  the owner's layout), in place of a rail beside the Tafs tab's own sidebar:
  - **at the top,** the mascot and "tafs": the way to the tafs list (the Overview screen, every taf),
    which is what opens first;
  - **the app's screens as items:** Needs you (with its count), Record, Calendar, Usage (Plan &
    usage) and Connect apps, then the held calls. With Calendar an item, the Overview has no calendar
    button of its own (`calendarInSidebar`);
  - **only the tafs relevant now** (`AppModel.relevantNow`): one with an item in Needs you (a held
    call, a step it handed over, a knock at its door), or a run or a change in the last 24 hours
    (`last_run`, `last_change`), and
    those being set up. The rest are in the list behind the mascot;
  - **at its foot, always in sight:** Coach, then the account button, which says who is signed in
    and opens Account beside the sidebar.
  
  Each item opens its existing screen beside the sidebar (`HomeSelection`). The app's tab and the
  selection follow each other, so a notification, a drive or a shortcut that opens a tab opens its
  screen here; ⌘1 from a screen goes back to the tafs list. Each item is in the drive's
  registry, as the rail's were. Account and Record are built again on each pick, not kept as the
  phone's tabs are. The iPhone keeps the bar.
- **Plan & usage** (`UsageView`, opened from Settings' Plan card and Home's plan line), with
  credits as the balance ([accounts.md](accounts.md#credits-and-plans)). Every line is tafs
  cloud's or KIFF's, as written; the app adds nothing but the order:
  - **The balance** (`BalancePanel`), from `GET /v1/usage/balance`: `words`, a bar at
    `meter_pct` (none when it's `null`), `detail`, the `warning`, then **Raise the limit** where
    `raise` is set, **Add credits** (`BuyCreditsView`) where `buy.packs` is, and Solo
    (`SoloView`) where `buy.solo` is, under **Choose a plan**. Under Solo's line, `buy.coach`
    says what Coach's help includes, tafs cloud's sentence as kiff.dev says it; none sent, or a
    blank one, no line. "Add credits" shows only once Solo runs. A 404 shows no balance, and a
    failed read keeps what was read with tafs cloud's words above it.
  - **The packs** (`AppModel.offeredPacks`): the First month pack comes first, only where
    `buy.first_month` is set and `ready`, then the packs that can be bought again
    (`Store.offered(firstMonth:)`). Not ready, it isn't listed: never alone or greyed. KIFF
    lists it with no App Store product today, so iPhone and iPad don't show it yet.
  - **Who manages billing** is KIFF's `billing.manage` in the account's capabilities
    (`AppModel.managesBilling`), as on Android: they see members' open requests, with Raise the
    limit or Approve, and never **Ask an admin**; anyone else sees Ask an admin, or their own
    request waiting. Not KIFF's usage `can_add_tasks`, which it has sent as false since credits.
  - **Restore purchases** stays on Solo's screen and Add credits, as the App Store asks. A
    restore that finds nothing says "Nothing to restore.", never "Purchases restored."
    (`PurchaseOutcome.nothingToRestore`).
  - **Raise the limit** (`AppModel.raiseLimit`): Face ID, then KIFF's `PUT /v1/me/usage/limit`
    with `raise.from_cents`, signed by the device key, as Go on was. Then the balance is read
    again. If that read fails, only **Read it again** is offered, which signs nothing. A
    `limit_changed` refusal reads the balance again and offers the raise from the new limit;
    `limit_ceiling` says to contact KIFF. While the phone can't approve, `ApprovalGate` stands
    in the button's place.
  - **Credits** (`CreditsViews.swift`): the **Credits** panel (`CreditsPanel`), from
    `GET /v1/usage/credits` ([accounts.md](accounts.md#credits)): Last period · This period, the
    period's `words`, each taf's row, Coach's row when there is one, then `not_counted` and
    `note`. The last period is read again with each refresh once it has been read. A taf's row
    opens its runs (`TafCreditsView`, 50 a page with More runs), each with its time as a short
    time in the account's zone, as on Android, and a run opens its request
    (`KnobRequestDetail`) while `opens` is true. The row for tafs removed since doesn't open. A
    404 shows no panel, and a 503 shows tafs cloud's `words`. The panel is left out where the
    balance's `credits_panel` is false (no plan and no run lately); a tafs cloud without the
    flag shows it (`AppModel.showsCreditsPanel`).
  - **Who bills and requests** (`PlanViews.swift`), after the balance and before the credits,
    as on Android: from KIFF's usage read, which the app reads only for `plan`, `store`,
    `billing` and `requests`. Someone who manages billing answers a raise with **Raise the
    limit** and a pack by buying it for that request, or declines either, signed. Anyone else
    asks an admin (`AskAdminView`): for a raise on Company (KIFF's `team`), for a pack on Solo
    while packs are on sale, with the request lines both apps share.
  - **A taf's Usage row** (its Card tab, for those who manage it) opens its credits this period,
    its runs (`TafCreditsView`), as on Android.
  - **Settings' Plan card** (`PlanRow`) shows `plan_line` in `plan_tone`'s colour, and
    **Home** (`PlanLine`) shows `home_line` when it's set. Both are read with each refresh.
  - **A chat, Coach or Run now refused for credits** (409 `credits_used_up`, `limit_reached` or
    `no_plan`) shows tafs cloud's `words`, with no Retry, and the message stays in the composer.
  - **Run now that starts no run is no failure** (`runNowWords`): a taf that checks its work first
    answers `session: null` with tafs cloud's `words` (nothing new since the last run, or why its
    check ran nothing), shown as they are under Run now until the next one. The app writes no line
    of its own, and an older tafs cloud's bare `null` shows nothing.
  - **Gone:** the tasks, packs of tasks and counted tasks, AI use and Go on, and the plan's
    trial.
- **Record** (`RecordView`): signed in, a search box and Export receipts above the list
  ([The Record across tafs](#the-record-across-tafs)); chips for "All tafs" and each taf (its
  colour) above the type filter, combined with it. Each entry starts with its taf's dot and name; each day's header
  shows that day's spend (`RecordView.spent`, a sum per currency of the money moved).
- **A taf's tabs** (`TafDetailView`): **Chat · Activity · Card · Setup**. Activity: for whoever
  manages it, its doors' knocks first (`TafKnocksPanel`: Do it, Pair them, Ignore, as on Android),
  then the taf's Needs you items, its work and runs, then its record by day (`ActivityFilter`: All, Done, Held,
  Refused). Card: the Card, its limits, how they're counted, usage, Halt, Revoke. Setup: The job
  (Improve in Coach), Starts when, Connected apps (`ConnectionsPanel`: **Choose actions**, through Coach, for one signed in with none chosen, then **Connect an app…**), Results go to, what it
  remembers, Versions, Pause (sample mode) and Remove for good. Remove's confirmation says what goes with the taf
  before Face ID: tafs cloud's words from `GET /v1/tafs/{id}/removal` ("Qonto goes with it. Stripe stays: Chargebacks
  uses it."), read when Remove is tapped and ending nothing (`AppModel.removalWords`). Without them, it says only
  what always goes.
  The removal leaves the taf's connections to tafs cloud, which ends them in every status as one of its steps: the
  phone sends no disconnect of its own and asks for one Face ID, the removal's. The result says "Its access to
  apps is removed." only when tafs cloud lists `connections` as removed, and names "access to apps" among the steps
  left when it lists it as failed (`AppModel.removalCards`, `removalStep`).
- **Setup › Starts when** (`StartsWhenView`, `TafSetupViews.swift`), from `GET …/knob`
  `wakes`: its schedule with − and + through the stop's `size.choices` (`adjustKnob`), Skip it /
  Run after (`setting.busy`), app events with **Approve** (`AppEventApprovalSheet`, the signed
  subscription at planque) or **Listening** and **Stop**, doors with **Edit** (`DoorsView.only`,
  one door's screen) and **Add a door…**, follow-ups with **Remove** (`DELETE …/follow-ups/{fid}`),
  and **Pause every start** (`POST …/pause`; a 502 reads "Not every start could be paused. Try
  again."). A change made here (`PUT …/knob`) waits as the taf's next version in `KnobPendingPanel`, made live with Face ID.
- **Setup › Results go to** (`ResultsGoToView`): Reply to the sender, Alert our phones and Email
  me change at once (`PUT …/results`, `setResultPlaces`), switched from the live taf's `results`, never
  a waiting version's (`ResultsGoToView.liveBuiltIns`); each connected app's place turns
  through its stop's other choice, as the next version (`PUT …/knob`). At least one of tafs' own places stays on,
  as tafs cloud requires (`PUT …/results` and `PUT …/knob` alike): the last one's switch is
  disabled, even while an app's place is on. Replies given up on show **Send again**, and a failed read of its work or Send again is said
  there (`workErrors`).
- **Account › Alerts** (`NotificationSettingsView`): two lock-screen examples, Alerts on this
  device (Turn on alerts while off), Sound (off and greyed while alerts are off, its choice
  kept), and What alerts you, read-only, drawn only with something in it; "A taf answered
  someone" lists the tafs whose results include `app` (`CloudTaf.alertsPhones`). Turn on alerts,
  here and in the offer of alerts, asks for notifications and no Face ID: KIFF's alert target is
  signed by the device key, which needs no prompt (`turnOnAlerts`, `AlertsTests`).
- **The AI-data consent** (`AIConsentView`, `TafsCloud+AIConsent.swift`; docs/accounts.md, "Your data and the AI"): a
  full screen with tafs cloud's words as sent (`GET /v1/me/ai-consent`: title, lines, Allow, Not now), and no line of
  the app's own. It comes up after sign-in while the person hasn't allowed it, and whenever tafs cloud refuses a
  model turn for want of it (403 `ai_consent_required`, from any route: read once in `TafsCloud.refusal`). **Allow**
  sends the version of the words on the screen (`POST {version}`); an older screen's yes is refused with the new
  words, which then show. **Not now** closes it and sends nothing; a refresh doesn't put it up again, a refused turn
  does. **Account** shows tafs cloud's "AI use: allowed" with **Withdraw** (`DELETE`), or the screen's title to open
  it. A tafs cloud without the route asks nothing.
  A screen missing its title, a line, Allow or Not now isn't drawn, as on Android. A message or a card's answer refused
  for want of it goes back where it was (the composer, with its files; the card, as chosen), with no Not sent row.
- **Account › Language:** the language tafs's lines are in, by its own name ("Français", "Polski",
  `AppLanguage.name`), so someone in a language they don't read still finds theirs, and **Change
  language**, which opens tafs's page in Settings, where iOS lists the app's 8 languages. No picker
  of the app's own, as on Android (`settings.language_title`, `settings.change_language`).
- **Account says who is signed in once** (`AccountLines`): the account's name, then "Signed in as" only when the
  email differs from it (a Solo account is named after its owner's email). The account's id is on the account's
  own screen only. People & groups' first line is the count alone for such an account.
- **Setup › Connected tools** (`ConnectedToolsRing`): one node per app (`ConnectedToolsRing.nodes`),
  the connection with actions in front; another sign-in to the same app is under Other
  connections in its sheet, with its own Disconnect. Starts when and Results go to are rows
  with a caret, no "Open" beside them (it read as their value).
- **Dark:** each `Theme` colour has a light and a dark value (`Color(light:dark:)`), the values
  the owner reviewed, the same as Android: background 141311, panel 1F1E1B, raised 2A2824, ink
  F2EFE9, soft A8A49C, dim 96918A, line F2EFE9 at 12%, amber FF8A1F, amberText F0A45A, ok 4CC38A,
  no FF8A73, blue and coach 7FA2FF. Text on an ink, Coach, green or red fill is `Theme.onFill`
  (white in light, 141311 in dark); text on the brand gradient stays white in both.
  `AccessibilityTests` checks contrast in both.
- **Checks:** `SetupUITests` covers the bar, Starts when (an event's filters before its
  approval) and Results go to, with `Screenshots.setupKnob`. CI's screenshots add Setup, its two
  screens, Account and Alerts, dark on iPhone and iPad, and the iPad in landscape (the iPhone
  is portrait only).
- **KIFF's words for a connection** (`ProfileWords`): a grant whose request carries a
  `kiff.profile.v1` shows KIFF's `kiff.words.v1` for it, read with the person's session
  (`POST /v1/profiles/words`) and checked against the profile's digest and their own before
  they're shown, verbatim and in order. Approving signs both digests into planque's approve
  body (`kiff_profile`). The tests use KIFF's vectors in `TafsTests/ProfileVectors/`.
- **The Slack door** (`SlackDoorSheet`, from Add a door… in Setup › Starts when): the taf's Slack bot
  connections (planque's grants, connected, the taf's agent, marked `connector:composio:slackbot`,
  or `request.connector.toolkit` until the mark is there), then the channels it sees
  (`GET …/grants/{id}/channels`, from planque). Picking a channel asks Face ID once: planque
  subscribes it (`POST …/grants/{id}/triggers {account, slug, channels}`, device-signed; planque
  builds the subscription from the channels), then tafs cloud makes the door
  (`POST …/doors {app: "slack", grant, channel}`). The door keeps the subscription's id (`trigger`); if the door can't be made, the subscription
  is taken back. Removing it is the reverse: the signed `DELETE …/grants/{grant}/triggers/{trigger}`,
  then the door. If the phone cannot sign the subscription's removal, the door stays and the app asks the owner to reconnect first. Pairing asks for a Slack member ID.
- **Connect apps** (`ConnectAppsView`, from Account): `GET /v1/connect/apps`, searched as the
  owner types, in tafs cloud's order and words. Rows show only the app's catalog title;
  internal provider ids are not shown. "Not listed? Ask Coach" appears when `by_key`.
  A row's Connect button is as wide as its words, never the row: stretched across an iPad it read as a field.
  Connecting (`POST /v1/connect/apps/{entry}`) opens the approval as a sheet over the list,
  with KIFF's words: approve first, then sign in at the vendor, as for any connection. An
  API-key vendor takes its key in the sheet: planque gets the key in `Planque-Vendor-Key`, and
  the device signs only its SHA-256 with the grant's digest. Its list and open approval are cleared when the account changes; delayed replies from the previous account or a cancelled search are discarded.
  **Coach's approval card holds no field for a key** (`SetupApproval`): a secret typed in the chat reads as handing it
  to Coach. When planque says the connection is approved with its key and it isn't connected yet, the card's one button,
  "Give the key on a secure sheet", opens this same sheet full screen, outside the chat (`ConnectApprovalSheet`,
  `needsKey`). Once the key is given and its receipt closed, the card is approved as any other: tafs cloud reads the
  grant, connected, and answers Coach. The key never reaches tafs cloud or Coach.
  **An approved card folds to one line that says what was signed:** once KIFF took the signature for the card's draft
  (`AgentDraft.signed`: confirmed, or its key claimed since) and the draft holds tools, the line names the Card first,
  before Coach's title for the card and also when it reads "Already approved", and draws KIFF's own lines for the draft
  this phone read under it (`OutcomeLine.detail`), never the connection's words for its key. A card answered while
  its draft isn't signed keeps Coach's title and draws no lines. A tap opens the whole card again.
  **The key's sheet looks and acts protected** (`docs/secure-connection.md`). Its words are tafs cloud's, read by the
  grant (`GET /v1/connect/grants/{grant}/sheet`, `TafsCloud.ConnectionSheet`), with this phone's signed grants
  read (`Planque-Device-Signature`), as Connect apps is:
  - **The title:** the vendor's logo (tafs cloud's own, or none), a lock, the host, and tafs cloud's title.
  - **Three plain lines:** what it may do, that the key is sealed, and that it can be disconnected. The phone draws
    the words; a line's `kind` picks its icon. **"Read only" is checked against the grant the phone read from the
    connection server** (`SecureKey.saysTrue`): at least one tool, every one a read. A sheet that says it of any
    other grant shows "This request doesn't match what it shows", with nothing to paste or sign.
  - **The key is pasted, never typed.** The sheet has no text field. Apple's own paste button reads the clipboard only
    on that tap, so iOS asks nothing more. Only the key's last 4 characters are drawn (`SecureKey.last4`; none for a
    key shorter than 12), and the clipboard is emptied. Under the pasted key, tafs cloud's line for what to do next
    (`paste.next`, "Key pasted. Tap Connect") where it sends one.
  - **One legal line**, with its two links as tafs cloud sends them. A link that isn't https is words alone. It sits above
    Connect: what the tap agrees to is in sight before the tap.
  - **Connect**, behind Face ID, right under the pasted key, the Card when it signs both, and the legal line: never below the
    technical lines. The key goes to the connection server as before.
  - **Details**, collapsed: tafs cloud's technical lines, and the connection server's own text, which its digest covers.
    KIFF's words for a profile, and a digest that doesn't match, stay in sight above it.
  - **The receipt**, on the sheet: tafs cloud's line with the last 4, **Done**, and **Disconnect** (asked first, then
    Face ID). The key is forgotten as soon as it's given, and the last 4 when the sheet closes. Disconnecting there
    leaves Coach's card unapproved.
  When tafs cloud's words can't be read, the sheet draws the app's own lines as before, with the same paste button
  and no text field.
  **A later approval card whose connection is already connected** carries tafs cloud's mark (`connection: {done,
  words}`, `SetupComponent.Connection`): the card draws that line and the Card, never the connection's text again.
  **A taf's own Connect again** (`connect_again: {grant, app, title, words, done_words}` on its Setup page,
  `ConnectAgainPanel`, `AppModel.connectAgain`): when a connection's sign-in expired, the taf's Setup shows tafs
  cloud's words and its button first, for whoever manages it. The button signs that same connection in again
  (`reconnectApp`, planque's approve for `grant`), so the taf keeps its tools and Card, never a trip through every
  app; the taf is read again, and once planque says it's connected the taf shows `done_words`. Read leniently: one
  without its connection or words reads as none.
  **What the fingerprint covers** is tafs cloud's mark too (`signs: {card, words}`, `SetupComponent.Signs`), set from
  the same decision as the step it then asks, since the phone learns the step only after the tap. With `card: false`
  the Card is the one approved next: drawn under tafs cloud's words ("Its Card: you approve it next") with its lines
  dimmed, never under its own title as if signed now, and not drawn at all without words. An app's first sign-in has
  no draft yet; its card still draws tafs cloud's words when it sends them (`SetupApproval.cardNextWords`), so the
  owner knows a second approval follows, as on Android. With `card: true`, or no
  mark from an older tafs cloud, the card draws the Card as before. The mark comes on Setup's thread read with this
  phone's signed read of its connections (`Planque-Device-Signature`) and the steps it signs
  (`?can=tools_and_cards,key_and_cards,signs`, `AppModel.stepsSignedHere`); without the proof, the cards come unmarked.
  `signs` says this phone draws the mark, on the read and on Approve: only then is a held sign-in's first tools asked
  with their Card in one fingerprint; a phone without it keeps two steps.
  At Approve, the step tafs cloud asks must sign the Card as the mark said (`AppModel.stepMatchesMark`): a Card drawn
  as signed with only the connection asked, or drawn as next and asked now, is out of date. Nothing is signed, and the
  card is read again, as for `read_again`.
  **Whether Approve is a tap** is on the mark too (`signs.takes`, `"tap"` or `"fingerprint"`, docs/chat.md), read
  as `Signs.takes`. KIFF's approval-window words ("No fingerprint for the next 4 minutes") may go only on a `"tap"`
  card (`SetupComponent.windowWords`, as Android 1.27.9's `windowWordsFor`): never on one that takes the fingerprint
  or has no mark. The iPhone holds no approval window yet, so its cards draw no such line and Approve always names
  Face ID; the gate is there for when it does.
  **A sign-in card names its app**: the card's `service`, else the connection's app as Connect apps names it, else
  planque's vendor in people's words (`AppModel.signInApp`, `appName`): "Sign in at Gmail", and "Sign in at the
  vendor" only when no app is known, never a connection's status. When the connection takes no approval now
  (signed in already, or past approving), Sign in asks tafs cloud's approval route, which takes a sign-in card too
  (docs/api.md): tafs cloud answers Coach and the card shows its note in the person's words, never "This connection
  is listed." Where tafs cloud can't be asked, the app's line stands, as on Android 1.27.8.
  **The key and its Card under one Face ID** (`AppModel.approveKeyAndCard`, `RequestsTogether`), when the sheet is
  opened from Coach's card and that card carries the taf's Card as a draft:
  - The sheet draws **Its Card**: KIFF's own lines for the draft this phone read from KIFF.
  - On Connect, the phone tells tafs cloud it can sign the step (`can: ["key_and_cards"]`). tafs cloud asks for it
    only when it's on there; otherwise nothing is signed here and the key goes by itself, as before.
  - The phone checks the ask is this card's (`keyAndCardIsThisCards`): its grant and the digest shown, its draft and
    the version shown. Anything else is refused and nothing is signed.
  - It builds both requests in full with one `signed_at` (`PlanqueClient.prepareKey`, `KIFFClient.prepareConfirmDraft`),
    and the approval key signs once, over `kiff.requests.v1`, the account, the time and the two request messages'
    hashes, sorted.
  - It sends the key first, then the Card's confirm, each with `v3=` and `with=sha256:` naming the other's hash. The
    Card is confirmed only for a key that went in.
  - It reports what came of both, and tafs cloud checks them before Coach hears "approved". Closing the receipt then
    doesn't approve the card again.
  - If it doesn't finish, the card's note says why on the sheet, and the key is never given by itself after that.
  **Tools added to a connected app and the Card over them under one Face ID** (`AppModel.approveToolsAndCard`), from
  Approve on Coach's card when it carries the taf's Card as a draft:
  - The phone tells tafs cloud it can sign the step (`can: ["tools_and_cards"]`) only for a Card the card drew
    (`cardDrawn`): the draft read, with at least one of its Cards' lines (limits and all) on the card, at the version shown. tafs cloud
    asks for it only when it's on there and the app is already connected; otherwise it answers as before, a step
    at a time. Approve waits until a card's Card is read, so what's signed is on screen, and it stays there until
    the fingerprint.
  - The phone checks the ask is this card's (`toolsAndCardIsThisCards`): its grant, planque's approve body exactly as
    this phone builds it for the digest shown (with KIFF's words for its profile), its draft and the version shown.
    Anything else is refused and nothing is signed.
  - It builds planque's approve and KIFF's confirm in full with one `signed_at` (`PlanqueClient.prepareApprove`,
    `KIFFClient.prepareConfirmDraft`), and the approval key signs once, as for a key.
  - It sends the approve first, then the Card's confirm, each with `v3=` and `with=sha256:`. The Card is confirmed
    only once planque says the app is connected with the tools; a sign-in planque holds is confirmed first, as on
    any approval.
  - It reports what came of both, and tafs cloud answers "approved" only when the tools are in and the Card is
    issued. Tools in with a Card that didn't follow leave the card asking for the Card alone.
- **What a grant's digest covers** (`PlanqueClient.digestCovers`): the phone recomputes planque's
  digest before it shows or signs a grant, and refuses one that doesn't match. Without `digest_v`,
  it covers the request alone (v1). With `digest_v: 2`, it covers `{"lang", "request", "text"}`, so
  the words the person reads (`text`, in their language) and that language are what's signed. An
  unknown version, or a v2 grant without its words or language, never matches. Every request to
  planque carries the person's languages (`Accept-Language`), for the words it writes.
- **Did this happen?** (`DidThisHappenCard`, in Needs you): KIFF's money calls whose outcome
  it can't prove (`GET /v1/me/did-this-happen`), each answered No or "Yes, it did" behind
  Face ID, signed like an approval (`POST /v1/me/did-this-happen/{proposal}`). No gives the
  Card its room back; the first answer stands.
- **Who a taf is shared with** (`ShareLine`, under the taf's name): the groups and people KIFF lists for it, never the
  person reading it. A taf only they use reads "Not shared", and one they share names the others alone. "You" is the
  person KIFF marks so, or the one whose email is the account read's.
- **A message refused because of a halt** (`UnsentBubble`, in a taf's chat and in Coach's): tafs cloud's refusal says
  whether the whole account or the taf is halted (`halted`, kept on `AppModel.UnsentMessage`), never guessed from its
  words. The bubble shows tafs cloud's words and **Resume**, which opens that Halt screen: the account's, in Coach's
  chat too, or the taf's own. Resuming there is the person's act behind Face ID; the bubble resumes nothing by itself.
  Retry then sends the message.
- **A held call's card** (`HeldCallCard`, in Needs you) has the decision on its face: KIFF's
  amount, the action, and the Card rule it crossed, from KIFF's record. Under them, **The taf
  says**: what the call is on and the customer's reason, in the taf's own words, as KIFF keeps
  them with the hold (`taf_about`, a business reference such as "Order 1042"; `taf_reason`).
  - They may come from a customer's text, so each is one plain line, drawn verbatim
    (`KIFFMapping.tafWords`): no markup, no link, line breaks and control characters as spaces,
    cut at KIFF's 120 and 280 characters.
  - They never stand in for KIFF's amount, rule or action, and nothing is read from them.
  - The gateway's `kiff_about` and `kiff_reason` arguments aren't listed again under Details,
    where the ids stay (`TafSaysTests`).
- **Allow this from now on** (`HoldAllow`, `AllowSheetView`; `docs/allow-from-now-on.md`): a third answer on a held
  call's card, beside Approve once and Refuse, that changes the Card so calls like it go on their own.
  - **The button** is the app's own rule, from the held call KIFF sent, with no request (`AppModel.offersAllow`):
    the account's capabilities name `holds.allow` (the service says when the feature is live, and the app never
    guesses: no list, no button), the person manages Cards, the call isn't Coach's install, and every breach is one a Card change can lift
    (`per_action_exceeded`, `limit_reached`, `resource_not_on_card`). The held call keeps its agent and its
    breaches' outcomes for this (`HeldCall.agent`, `breachOutcomes`).
  - **The tap** posts `/v1/tafs/{id}/holds/{exception}/allow` (`{}`, or `{"round": true}` for "Round up to …"). The
    app never rounds. No offer, for a reason this app knows or not, shows tafs cloud's words with nothing to sign.
  - **The sheet** shows tafs cloud's change before and after, KIFF's own lines for each Card that changes, what it
    would have let go when tafs cloud says, and the switch "Let this one go out too".
  - **The phone reads KIFF's draft itself and signs only an exact match** (`HoldAllow.matches`): the draft is for
    the held call's agent (and made from this call, when KIFF says); it retires no Card and adds none; each changed
    Card is one the offer names and reads exactly as the Card in force with the one offered change made: a limit
    moved, in the offer's unit (and window, when it names one), or, for a `resource` change, exactly the scope's values
    in force plus the offered one. Every other term is compared (`HoldAllow.Terms`): each action's caps, scope, states
    and next steps; each total's window, measure and per-item parameter; what happens past a limit; who holds the Card;
    where it applies; when it starts and ends, compared as the instants the times name, so one written with another
    offset is the same. A text that can't be read as a time equals only the same text, never a time. A draft that sets how long a hold waits, or revokes, is refused. Anything else shows "This change isn't what was offered", with no
    sign button. A draft that couldn't be read shows the error alone.
  - **One Face ID** signs the version the phone read (`tafs.cards.v1`). With the switch on, the confirm names the
    held call and the version of it the person saw, so KIFF lets it go under the same signature. With it off, the
    Card changes and the call still waits. Rounding up keeps the person's switch.

### Connect apps

A connected app (“Connected ✓”) opens its connections (`AppConnectionsView`, `AppModel.connectionGroups`), in four parts:

- **A connected app always opens** its connections, never a tap that does nothing. A device that doesn't read the
  account's connections (connected tools not set up on it, or a session without its key: `readsConnections`) says so
  there, in the shared words, instead of an empty list.
- **One section per taf,** its name as the heading, holding each of the account's live grants for the app (not `disconnected` or `failed`). The tafs with a connection that needs the owner come first (a sign-in waiting, signed out, or a last call that failed), then the rest by name. A row shows the connection's name as the connection service gives it (`title`, such as “Gmail · Demo Shop”; `Grant.name`), else a direct sign-in's or a key's account, else the app's name. A managed app's `account` is the provider's id, kept only to be sent back with a door's trigger, and never drawn. Then the connection's status words, and “Also on Chargebacks' Card” when another taf's Card uses it. When the connection service draws its rows (`used_by`, `health`, `actions`; `Grant.rowFromPlanque`), each row is
  its name, "Used by" its tafs, and its health in its words, as sent; a tap opens `ConnectionDetailView`: the health,
  the tafs on it, and only the actions it offers that the app knows (`sign_in_again` by `approve` or `key`, `remove`,
  `remove_unfinished`). Remove is there, never beside the row, and asks once more in its words with `stops` before
  Face ID; a leftover gets only its own removal. Every request to the connection service sends `X-Time-Zone`, for
  the times in its words. Without those fields, the row is the app's own, as before.
- **On a row,** whoever manages the account's Cards has **Sign in again** and **Remove access**; or **Cancel** on a sign-in that never came back (“Cancel the sign-in to Stripe?”, “Keep waiting”); or **Cancel the request** on one nobody answered yet (`Grant.pendingRequest`: pending and holding no sign-in), since nothing was connected (`ConnectionFixWords.endWord`, the same rule on a taf's Connected apps). Remove access asks first, naming the one taf it ends (“Remove access to Stripe for Refunds?”, with Cancel). For a connection made with a key it says the key is deleted from tafs once no taf uses it (a key another taf's connection shares stays for that taf), never that anything is revoked at the vendor (`DisconnectWords.message(key:)`); for a sign-in it says where it's revoked. It also names any other taf whose Card draws on the connection's tools, which stops too (“Chargebacks stops too.”), and says other connections to the app stay. Face ID then asks the same words, and the device key signs the connection service's `DELETE` ([api.md](api.md#disconnecting-and-removed-tafs)). The list is read again after.
- **From removed tafs** comes between the tafs and Other connections. It holds the connections whose `request.agent` tafs cloud names in `removed_tafs`, and only those, each with the removed taf's name (the grant's `agent_name`). The app sends its signed grants read with the read (`connectApps(proof:)`). A connection a live taf's Card still uses says so (“Kept: Chargebacks' Card uses it”). **Clear these connections** shows only when at least one listed connection has an empty `used_by`, and ends exactly those: it asks first (“Clear 2 connections from removed tafs?”), then one Face ID, and the device key signs each disconnect. The line under them says how it went (“Cleared 1. Kept 1 for Chargebacks, whose Card uses it.”). With no `grants`, the section shows with no Clear. Which to keep is tafs cloud's word, never the phone's (`clearPlan`, `clearRemovedConnections`; the words are `ConnectionGroupWords`, the same as Android's).
- **Other connections** comes last: those whose taf the app can't name, each with the same row as the rest and nothing that acts on them all at once. A taf missing from this person's list may have been removed, but it may also be one they don't see, or an agent that isn't a taf, so nothing there says it was removed.

## A draft's new Cards

KIFF's draft has one Card per tool, so a new taf's approval drew "A new Card" three or five times for
what the owner approves as one. As on Android, `DraftCardsView` (in `Views/TafDetailView.swift`, used
by a version's sheet and a trial) draws the draft's new Cards (no `current`) as one panel, "A new
Card", with every new Card's lines under it, each once, in the draft's order, where the first new Card
is (`DraftCardsView.shown`). A Card the draft changes, or keeps unchanged, keeps its own panel and
place, and the Cards it removes follow, as before. Test:
`VersionsTests.testADraftsNewCardsShowAsOneAndAChangedCardAlone`.

## Home's line

Home's big line ("2 tafs working.") is tafs cloud's, as Android draws it: `GET /v1/home` sends
`headline: {words}` in the person's language, and the app draws `words` as sent
(`TafsCloud.Home.headline`, `AppModel.workingWords`, `headline` in `Views/HomeView.swift`).
tafs cloud counts the tafs at work; with none at work, the tafs ready to work from their chat
("1 taf ready."); else "0 tafs working.". A taf waiting on an app's event, or with something gone
wrong, counts as neither.
- **Read leniently:** a `headline` that isn't an object, or whose `words` aren't a non-blank
  string, is none, and Home still reads.
- **With none** (a tafs cloud from before it, or the list remembered from the last launch before
  Home is read), the app counts its own tafs working (`workingCount`, `home.working`), as before.
- Before the first list it still says "Loading your tafs…", and with the recipes listed it shows no line
  ([Home with no taf](#home-with-no-taf)).

## Home with no taf

An account with no taf sees the jobs a taf can take on Home, where tafs cloud says so
(`GET /v1/recipes`, its `home`; [coach.md](coach.md)), as on Android
([android-app.md](android-app.md#home-with-no-taf)). Every word is tafs cloud's, in the person's
language: the app has none of its own for it.
- **The list** (`HomeRecipes`): `home.words` leads it, then "Say it in your own words"
  (`home.own_words`, its `title` and `line`) where tafs cloud sent it, then a chip per category
  to narrow it (picked again, every recipe shows), then each recipe led by its `headline`, the
  problem it ends, with its `line` under it. While it shows, Home's own "0 tafs working" line
  doesn't.
- **Say it in your own words** (`OwnWordsRow`, as + New's gallery draws it) opens Coach with
  nothing in its box, for someone who may use Coach. tafs cloud sends it only to someone who
  manages tafs. With no job listed it shows under Home's empty line, so there's always a way
  to Coach; Home has no "Connect an app" row any more.
- **Only when `home.shown`** is true and the sheet's `labels` came with it
  (`AppModel.homeListsRecipes`). Otherwise Home shows `home.words` alone, its empty line. A tafs
  cloud from before `home`, or recipes that couldn't be read, leave Home's own "No tafs yet."
- **On iPad** the sidebar is narrow for a headline, so the list takes the pane beside it while
  nothing is open, and the sidebar says `home.words`.
- **A recipe's sheet** (`RecipeSheet`): the headline, the line, `today` (what it costs today),
  then its Card in zones titled by `labels` (`allows`, `holds`, `never`), a zone with nothing
  left out (`Recipes.zones(of:)`), and one button, `labels.hand`. Someone who can't use Coach
  gets no button.
- **Hand it to Coach** (`AppModel.handRecipeToCoach`): a new Coach conversation with the
  recipe's `say` in the message box, to edit and send. Nothing is sent.
- **Words first.** On a phone where Coach isn't started, a handed recipe shows the message box
  with its words where the Start button would be, and Send starts Coach, then sends
  (`AppModel.submitSetup`). Start asks for Face ID once: the same unlock signs the phone's
  enrolment and Coach's draft. A Start that's cancelled sends nothing, and the words stay.
  A phone that can't approve yet gets no box: the intro keeps the words that say why
  (`ApprovalNotReady`), nothing is started or asked, and the handed words wait in the draft
  until it can. While Coach isn't started, that Send confirms: a drive's touch on it is
  refused (docs/drive.md).
- **When it's read:** with each refresh that finds no taf, as well as when Coach opens
  (`HomeRecipesTests`).

## A taf made with no plan

When the account has no plan, tafs cloud makes the taf Coach handed over and starts no run. Its
answer to the result carries `outcome.plan_needed.words` ("Choose a plan to start it.", in the
person's language), and the hand-over card says them under its own words, above the button that
opens the taf (`SetupThread.planNeeded`). Under them, **Choose a plan** (`usage.choose_plan`) opens
Plan & usage on its own screen (`usageRequested`), where a plan is chosen, as on Android. tafs cloud
sends only the words, no plan and no link, so the button names none. No words, or none that are
text, add nothing: no line and no button.

## Connect apps

The same rows as Android, from the same tafs cloud fields (`GET /v1/connect/apps`: `title`, `name` and `subtitle` when sent, `logo`, `state` or
`connected`, `method` (else `how`), and `line`, `whose` and `used_by` when sent; `ConnectRow`):
- **Yours first, then Discover:** connected apps (or ones to sign in again) under Yours, the rest under Discover,
  each in tafs cloud's order, with search. An app under Yours is never under Discover again in another catalog form
  (`ConnectRow.split`): Stripe connected through a managed provider's entry (`connector:composio:stripe`) leaves out
  its reviewed entry (`stripe@3`), matched by the entry's last part without its version, or by vendor, each folded
  as tafs cloud folds them (lower case, no spaces, dots, dashes or underscores).
- **No taf to connect for:** opened from Settings on an account where no taf can have an app, "Apps connect for a taf.
  Make one first…" heads Discover, where Connect needs a taf, and never Yours; with nothing under Discover it doesn't
  show (`ConnectRow.saysMakeATaf`).
- **On a phone, two lines:** the name, then tafs cloud's `line` as sent ("Sign in · Connected · Used by Refunds"). From a
  tafs cloud without it, the app says the same: how it connects ("Sign in" or "With a key"), whose it is (`whose`, as
  sent), where it stands, and which tafs use it ("Used by …", from `used_by`, or this phone's planque read). A ✓ for a connected app; else a compact Connect, as wide as its words.
- **On an iPad's full width, a table:** App, How it connects, Whose, Status, laid out by `ConnectColumns`, the same
  in the header and every row. The App column gets its room first (200 points at least), so names read whole in
  portrait and landscape: the other three are 150, 130 and 230 points while there's room, then give way together
  down to 96, 84 and 180. A name is one line, cut at its end only as a last resort, never broken inside a word.
- **An app's name is tafs cloud's `name`, else its `title`** (`App.displayName`), with `subtitle` under it when sent,
  in a smaller, softer style on up to two lines: a reviewed entry reads as "Qonto", with "Invoicing: reads, and
  invoice drafts from quotes" under it; a managed app keeps its name ("Gmail") with nothing under it. Both are read
  leniently: blank or not words reads as none, so a tafs cloud before them, or a blank `name`, draws the `title` as
  sent. The app never cuts titles apart itself (`vendor` is planque's key, sometimes a slug such as `gmail`), and
  `title` and `vendor` still match an app across its catalog forms. The same name heads the app's own screen and
  stands in for a connection that has no name of its own.
- **A connected app opens its detail** (`AppConnectionsView`): what each connection can read and do, which taf it
  serves, and Disconnect. The whole row opens it, name, logo and second line included, not only its ✓.
  Its footer ("Removing access takes its tools from that taf only…") shows only when a connection there is one of
  the account's tafs' (`DisconnectWords.showsFooter`): with no taf, it applies to nothing.

## A taf whose setup stopped partway

Home lists tafs only. A Coach chat left before the hand-over leaves a draft with no taf: tafs cloud
lists it in `setting_up`, each by the name Coach gave it, and the app draws it among the tafs with no
state, so never "Ready" (`AppModel.settingUp`). With `setting_up` sent, `others` only repeats it for older
apps and isn't drawn, so no KIFF agent shows; from a tafs cloud before it, `others` is drawn as before.
The person whose Coach chat it was gets `finish: {taf, session, words}` (docs/tafs.md), and `discard: {words}`:
a Discard beside it (`DiscardSettingUpButton`) whose confirmation is all tafs cloud's words. Its yes revokes the
agent's Cards in KIFF (from `others`' repeat of it), then calls `POST /v1/home/setting-up/{kiff_agent}/discard`
signed as a removal is (the approval key over the POST and its empty body, behind Face ID), and Home is read again
(`AppModel.discardSettingUp`). The row says `words` ("Finish setting up") in amber in place of what it's
doing. A tap opens Coach on `session`, where it stopped (`AppModel.finishSetup`): reading that
chat is what makes the taf at tafs cloud, and the next Home read lists it with the tafs. Only
Coach's own chat (`taf` is Coach's), for someone who may use Coach (`canFinishSetup`); anything
else, or a `finish` with no chat or words, opens as any other agent's row, on iPhone and iPad.

## Every ready-made job, at any time

The jobs stay reachable after the first taf, from `GET /v1/recipes/gallery`, read with each
refresh (`AppModel.loadGallery`). tafs cloud decides what shows and in what order; the app draws
it, in tafs cloud's words.
- **+ New** (Home's +, the iPad sidebar's New taf) opens the gallery (`RecipeGallerySheet`):
  `words.title`, then "Say it in your own words" (`words.own_words`), which opens Coach with
  nothing in it, then `words.all` ("All jobs") over the chips and every job as on Home. A job opens the same sheet, with the
  gallery's `labels`. From a tafs cloud without the route (404), + New opens Coach as before.
- **The strip** (`RecipeStrip`): under the tafs list, `strip.title` and the jobs in `strip.ids`,
  in that order, only while `strip.shown`. tafs cloud leaves out jobs the account runs and puts
  first those whose apps are connected; the app picks nothing. On iPad it's a sidebar section.
  Under the jobs, one row opens every job to browse, in tafs cloud's words (`words.see_all`, or its
  heading `words.all` from a tafs cloud from before it; `Gallery.seeAll`): only when there are
  more jobs than the strip shows, on iPhone and iPad.
- **Explore jobs** (`ExploreRow`, a sidebar row on iPad): a row on Home in tafs cloud's words
  (`words.explore`), with or without tafs, and for someone who may not use Coach too. It opens the
  gallery to browse (`GalleryMode.browse`): the same chips and jobs under `words.explore`, without
  the own-words row, and nothing is created. A tafs cloud that sends no `words.explore` draws no
  row: the app has no words of its own for it.
- **A job's sheet** (`RecipeSheet`) is read only: the headline, what it costs today, its Card as
  does / asks / never, then `proved` (tafs cloud's line where a proof run passed) and `sample`
  (what it reported after that run, under `words.sample`), both drawn as sent and only when they
  are words. Its one button hands the job to Coach, for someone who may use it: "Try it with
  Coach" (`words.try`) while browsing, the sheet's `labels.hand` from + New. Nothing is set up
  until it's tapped.
- **App logos** (`AppMarks`): each job's apps by name, after the app's logo, and the same in
  Connect apps. A logo loads only from tafs cloud, by the path it sent: `/v1/logos/` and a key of
  32 lowercase hex, as on Android (`TafsCloud.logoURL`). An absolute URL or any other path loads
  nothing, so no one else sees the phone's address. With no logo, or one that doesn't load, the name alone.
- **A colour per category** (`RecipeTint`): tafs cloud's token, `#RRGGBB` for light and dark,
  on the chip and the card's edge. One the app can't read is no colour, never a guess
  (`RecipeGalleryTests`).

## Coach's tools

As on Android ([coach.md](coach.md#coachs-tools-step)). The phone builds none of it: the tools,
their addresses, Coach's Cards and the step's words are tafs cloud's.

- **The read** (`TafsCloud.setupRead`, `CoachTools.read`): `GET /v1/setup`'s `tools`, with each
  opening of Coach and after a Start. The `connect` batch and the `card` draft are kept as the
  bytes tafs cloud wrote.
- **The step** (`CoachToolsSheet`): a sheet at the end of a first Start, and on Coach's screen
  after installing a version with its Card while the tools are still to give
  (`coachToolsAsked`), with tafs cloud's title, intro, groups and ask. The install's Face ID
  gives none of them. Each tool and KIFF's words for the Cards are folded under `details`.
  It has **Give** and **Not now**.
- **The first start under one Face ID** (`TafsCloud.CoachStart`, `AppModel.startCoachTogether`): with no Coach yet,
  `GET /v1/setup` may carry `start`, the requests tafs cloud built for it. **Start** then opens the step with those
  words and KIFF's own words for Coach's Cards (the draft's `cards`, from KIFF's preview), and its one button
  starts Coach and gives its tools under the one Face ID Start asks: the batch for the
  gateway and Coach's draft with its Cards, each built in full with one `signed_at` and signed once, together, by
  the approval key (`kiff.requests.v1`, as a key and its Card are). The batch goes first, the draft once it answered,
  then tafs cloud claims the draft's key (`POST /v1/setup`). With every tool connected, `start` carries the draft
  alone and it goes signed as one request. A draft KIFF refuses after the batch went in is started again from the
  next read, which carries the draft alone, under a new Face ID. The phone signs only `POST /v1/connections/batch`
  on the gateway and `POST /v1/me/agent-drafts`, and only with KIFF's words for the Cards on the draft; anything else,
  or no `start`, starts Coach as before. Send, with words handed over before Coach exists, opens the same step and keeps
  the words in the composer.
- **Start's own screen** (`start.words`, `CoachStart.oneScreen`): where tafs cloud sends `start.words` `{lead, start,
  does}`, Coach's intro draws `lead`, then what's signed as Android draws it (`CoachToolsGiven(start: true)`): Coach's
  Cards in KIFF's words, then every group and tool folded under `details`, with no group lines up front. Then one
  button, `start`, with `does` under it. That one
  tap signs and starts, so no step opens after it and nothing signed is left unread. With words handed over, Send in
  the box below is that tap: it signs, starts Coach, then sends. Without a line or a button in `start.words`, or from
  a tafs cloud from before them, Start opens the step as above.
- **A Coach that exists, with tools and Cards missing** (`tools.requests`, `CoachTools.together`): `tools` may carry
  the pair a first start does, `connect` and `draft`, under `kiff.requests.v1`. Giving then signs both hashes once
  with the approval key, under the step's one Face ID, and sends the batch, then the draft
  (`AppModel.sendTogether`, shared with the first start). Coach exists, so nothing is claimed after it. KIFF's words
  for the Cards are drawn first (the pair's own when `card` didn't come). Without the pair, a key KIFF doesn't hold,
  or a draft without KIFF's words, it's the steps below.
- **Giving** (`AppModel.giveCoachTools`): one Face ID. Under it the phone signs and sends the
  batch to KIFF's gateway (`POST /v1/connections/batch`), then the Cards' draft to KIFF
  (`POST /v1/me/agent-drafts`), each as it came. Where the Cards didn't come with the batch,
  they're read once it's in and given in the same step. `given` sends nothing. `taken` says
  which name a vendor's tool holds, and `unknown` asks to try again; neither asks or sends.
- **After Not now** (`CoachToolsRow`): one line above the conversation, tafs cloud's `row`, which
  opens the same step. It's never a panel there, so the message box keeps its room.
- **A phone that can't approve yet** reads why in the button's place (`ApprovalGate`), and
  gives nothing. The button confirms: a drive's touch on it is refused.
- **Nothing is given silently.** Before, the phone connected its own list of seven tools and
  drafted six Cards inside any step the person had just confirmed, and remembered that on the
  phone. That list, those Cards and that memory are gone. A held `version_install` of Coach's
  reads as an install once tafs cloud says the tools are given.

## The calendar

The tafs' scheduled work still to come (kiff/tafs#534), opened from the sidebar's
Calendar item on an iPad's full width, from the calendar icon at the top right of the
Overview beside the Tafs tab's own sidebar (an iPad's narrower split; never beside the
whole app's sidebar, which already has Calendar, `OverviewView.showsCalendar`) and, on
the iPhone, beside + in the home's bar. `Views/CalendarView.swift` draws Day, Week and Month,
Week first, with Today, previous and next, and a date to pick. Today is a pill as wide as
its word in every language ("Aujourd'hui", "Hoje", "Vandaag"), on one line with room inside
its stroke; with today already shown a tap does nothing and its word stays readable, never
faded. On a phone the range
("9 – 15 Nov") has a line of its own above the buttons, so it's never cut. tafs cloud computes
every occurrence (`GET /v1/calendar`, docs/api.md): live schedules' firings in the
account's zone, follow-ups and runs' next tries, only for the tafs the person may
see, none for a paused taf. Each comes with its `day`, `time` and `minute` in that
zone, so the app places it and converts nothing. `Model/CalendarModel.swift`
(`CalendarMath`) only counts dates (Monday-first weeks, the month's six-week grid,
one read per grid), groups events by day and hour, and picks the layout: wide
(`CalendarLayout.of`: at least 700 points wide, on its side, a tablet's height),
with a month navigator at the upper left, the calendar beside it and the agenda
under them; compact otherwise, with a week strip and the selected day's
work under it. In Week the strip is exactly the seven days its heading names, sharing
the width at any width (`CalendarMath.stripDays`): an upright iPad showed "9 – 15 novembre"
over a strip from 9 to 21. In Day it scrolls the month's weeks. What it shows (`CalendarState`) is kept on `AppModel`, so a rotation
or a new layout keeps it, and cleared with the rest of an account's state. A tap
opens the taf. Read-only; no new dependency. Tests: `TafsTests/CalendarTests.swift`.

## The Record across tafs

Signed in, the Record tab also searches across the person's tafs, opens one case, and
exports receipts ([api.md](api.md): `GET /v1/record`, `/v1/record/cases/{about}`,
`/v1/record/receipts`; `Views/RecordSearchViews.swift`, `KIFF/TafsCloud+Record.swift`,
`AppModel.searchRecord`), the same reads and words as Android:

- **Search:** the box's placeholder is tafs cloud's `hint` ("Search by order, invoice, amount
  or action"), read once when the tab opens; the app's own line stands in until it comes.
  What's typed is searched 400 ms after the last key (`AppModel.recordQueryTyped`), so a word
  typed letter by letter is one search; the Search key searches at once. The results take the list's place: tafs cloud's `groups`, each
  call drawn as its taf's Activity draws it (`SourceActivityRow`, with its receipt and its own
  next step) under its taf's name, and **More** for `next_cursor`. A taf the phone doesn't
  list shows the call as tafs cloud words it, without a next step. A cursor tafs cloud refuses
  (409, a call made since) searches again. Nothing found shows `none_words`. Clearing the box
  shows the Record as before. Never by a customer's name: KIFF keeps none.
- **One case:** a call with an `about` ("Order 1042 ›") opens a sheet (`RecordCaseSheet`) with
  every call about the same thing, oldest first. The step that didn't go through is named
  first, in tafs cloud's words (`failed.words`), and its own next step on its row is the only
  retry, through the taf under its Card. The Record never sends anything again itself. A
  reference with a `/` (an invoice's "F-2026/001") goes escaped, as one segment.
- **Export receipts,** for someone who oversees or manages every taf (`AppModel.seesEveryTaf`;
  tafs cloud's 403 still decides): Last 7, 30 or 90 days, today among them, as the account's
  calendar days, inside tafs cloud's 92. Two files go to the share sheet: tafs cloud's answer
  byte for byte as JSON, so KIFF's receipts are exactly as issued, never re-encoded, and its
  CSV. They're written under the export folder, replaced at each export and removed at
  sign-out with the opened files. `missing` shows as a line ("2 calls have no receipt yet.").

Tests: `TafsTests/RecordSearchTests.swift`.

## Your brief (Missions, draft)

"Your brief" takes Needs you's place once there is one (Missions design v2.1 §7; `BriefView`,
`KIFF/TafsCloud+Brief.swift`). Until then, and for anyone tafs cloud doesn't send one to, Needs you is
exactly as today. The draft draws the sample's brief only (`-brief YES` over the sample,
`Brief.sample`); tafs cloud's `GET /v1/brief?label=` isn't read until its `TAFS_MISSIONS` flag is on.

- **Read leniently, in v2.1's names:** `decisions` (`question`, `answers` with `recommended`, `why`,
  `meanwhile`, `deadline`, `urgent`, `later`, `ref`), `more`, `done` (cases with `state` and
  `next_check`, `value`, `log`), `next_move`, `rest`, `credits`, `labels` and the headings' `words`. A
  part the app can't read is left out, never the brief.
- **Drawn as sent:** decisions first, each with its taf, Coach's recommendation and its why, what
  happens meanwhile, why it can't wait when it can't, and its answers (the recommended one filled, a
  decline in red, Later beside them). Then one next move, then Done case by case (green once closed,
  amber while waiting), with its value and the mission's log. Then the rest in one line, which opens
  today's Needs you list. On an iPad's width, decisions beside Done, the next move under Done.
- **Label chips** (All, two labels, "+N"; `LabelChips`) filter the brief, as tafs cloud filters it.
  The same chips sit over the tafs list (Home on a phone, the Overview on an iPad) and filter it
  without changing its order; each label counts its listed tafs and shows an amber dot while one waits
  for the person. Each taf's row carries its labels as small grey chips (`TafLabels`). No labels, no
  chips: the list is as today.
- **A recommendation is shown, never acted on.** An answer goes through the route its decision already
  has (`ref`: a held call, a step, a connection, a proposal); nothing is signed from the brief.
- **A mission's log** (`MissionLogView`, `GET /v1/missions/{id}/log`): read only, newest first, by
  day; each entry's taf or Coach or You, its kind in colour and word, its evidence and value; a removed
  taf's entries greyed and marked.

## Coach waiting

A Coach chat paused on a card its person answers (an approval, a sign-in, questions, a confirm), as
Home lists it ([api.md](api.md) › Coach waiting; `KIFF/TafsCloud+CoachWaiting.swift`,
`CoachWaitingCard` in `Views/NeedsYouView.swift`, `AppModel.openCoachWaiting`):

- **Where:** Needs you lists Home's `coach_waiting` after the steps, oldest first, one per chat, in
  tafs cloud's words only: `words.line` ("Coach is waiting for your approval.") and `words.open`
  ("Open Coach"). Needs you never says nothing is waiting while one is listed, and Home's count is
  tafs cloud's `needs_you`, which has each once.
- **Not the chat on screen:** while Coach is open on a chat (`AppModel.coachChatOnScreen`: `setupChat`, or
  the thread's session), that chat's row isn't drawn and isn't counted (`coachWaiting` is Home's
  `coachWaitingListed` less it). Of tafs cloud's `needs_you` count only the rows hidden come off, never below
  0, and the line under the count is then the app's own count words, not tafs cloud's line that counts it.
  Closed, the row and its count are back as Home listed them. Open Coach takes the row and the badge
  together, in the same tap.
- **Read leniently:** one without its chat (`session`) or either line is left out, never Home with it.
- **The way in** opens Coach on that chat (`openSetupChat(session)`), where its card waits, on one tap. Someone who may not
  use Coach (`mayUseCoach`) sees the line alone, never a button that does nothing. The Set aside swipe is on
  the row's line alone, never over its buttons, so no drag recognizer sits around Open Coach.
- **At once:** when Coach's run pauses on a card while its chat is open (its stream's live line ends on
  `waiting_for_you`, or it ends `PAUSED`), the app reads Home once as that stream ends
  (`AppModel.stillWaitsOnYou`), so the row and the badge come while Coach is still open, not after it closes.
- **When the run ends:** a run that ends on its stream, however it ends (`COMPLETED`, `CANCELLED`,
  `ERROR`; `AppModel.readsHomeAtEnd`), reads Home once too, and so does a run seen ending on a read of the
  thread (at work at the last read, idle now, with no stream), or handed over. A row of that chat that
  waited on the person goes, with its badge, without waiting for the next read. A stream that drops with
  no end reads nothing.
- **The line's date** ("depuis le 14 nov.") is tafs cloud's, inside `words.line`; the app draws it as sent.

**Set aside** is "not now", never an approval: nothing is signed, and no Face ID. Only signed in (not the
sample), and only what tafs cloud offers: KIFF's holds, "Did this happen?" and steps never get it. As on
Android since 86 (`AppModel.setAsideCoach`, `CoachWaitingCard` and `SetAllAsideButton` in
`Views/NeedsYouView.swift`):

- **One chat:** a row whose `words.set_aside` tafs cloud sends can be swiped away either way (a mostly
  sideways drag past 96 points, beside the scroll), and has an outline button with the same words, and
  an accessibility action, so a swipe isn't the only way. Each calls
  `POST /v1/home/coach-waiting/{session}/set-aside`, with this phone's signed read of its connections
  (`Planque-Device-Signature`) when it has one, so tafs cloud can withdraw a connection that chat asked for.
  tafs cloud answers `{set_aside: [session], withdrawn}`.
- **Every chat:** with two or more rows and Home's `coach_waiting_clear`, its **Set all aside** sits above
  them. It asks once, in tafs cloud's words (`words.title` names the count, "End 2 waiting Coach chats?",
  then `text`, `yes` and `no`), then calls `POST /v1/home/coach-waiting/set-aside`, with no body.
- **One call per gesture:** the swipe asks only as it ends, once. A chat already being set aside
  (`settingAside`, marked before anything is awaited) sends nothing again until tafs cloud answers, nor does
  Set all aside meanwhile.
- **While tafs cloud answers:** a row being set aside isn't drawn, and the badge leaves it out (never below
  0), so a swipe that took never springs back looking untouched (as Android 94). Set all aside hides every
  row it covers the same way. A Home read meanwhile doesn't draw it again. A refusal brings the row back.
- **After:** the row stays gone once tafs cloud takes it, with a success tick, and Home is read again: the badge is
  tafs cloud's `needs_you` count as sent. A refusal brings the row back and says why in tafs cloud's words
  (`lastError`), with a warning tick.
- **Coach driving the app** can't set a chat aside: Set aside, Set all aside and its ask's yes carry the
  confirm mark ([drive.md](drive.md)), so a driven touch on either button is refused and nothing is sent
  (`DriveTests.testATouchOnSetAsideIsRefusedBeforeAnythingRuns`). The row that carries the swipe and its
  accessibility action is marked too, though the drive registers neither; its Open Coach is marked as not
  confirming.
- **The pencil** (New conversation) in Coach opened on a taf ("Improve <taf>") starts a plain Coach chat,
  on no taf (`newCoachConversation`), and so does New taf at the chat's top. It stays plain: Coach closed
  and opened again on the same taf's page doesn't go back to that taf's mode; another taf's page opens on
  that taf. On an empty chat about a taf it opens a plain one too: only an empty plain chat stays, where
  the pencil shows its top (`coachChatIsEmpty` is false while the chat improves a taf), as on Android.
- **A new conversation:** a chat set aside is never the one Coach goes on in. When it's the chat shown (or
  the latest one shown together), Coach starts a new conversation (`newSetupChat`), so a new job's first
  message asks for a new chat (`new_chat`) and never names the ended session. When Coach next picks its
  newest chat by itself and that's one set aside on this phone, it starts a new conversation instead. These
  are kept in memory only, never written to the phone (docs/data.md). Reopened from Previous conversations by the
  person, the chat opens as any other.
- **A new job after a hand-over:** every message to Coach says the app follows a new chat
  (`new_chat_after_handover: true`, [api.md](api.md) › `POST /v1/tafs/{id}/messages`). When tafs cloud
  starts one because the chat shown already handed a taf over, its answer, or the stream's `session` event,
  says `new_session: true` with another session, and Coach follows it (`followNewSetupChat`): the new chat
  is the one shown (`setupChat`), with the message just sent and the answer streaming into it, and the old
  chat stays under Previous conversations. The message shows once: it's matched to tafs cloud's copy by
  id, as any send. The same session, or no `new_session`, keeps the chat shown. Needs you's rows are tafs
  cloud's, by session, so they follow at the next Home read; this phone keeps no current-chat id of its own.
  Set aside's `words.done` ("You'll find it in Previous conversations.") isn't drawn yet.

Tests: `TafsTests/CoachWaitingTests.swift`; a run's end, `SetupTests.testACoachRunEndingReadsHome`; the
pencil, `VersionsTests.testThePencilInATafsCoachModeStartsAPlainChat` and
`VersionsTests.testThePencilOnAnEmptyChatAboutATafOpensAPlainOne`. One tap opening the chat is pinned
by the card's structure only; the tap itself needs a device.

## Your step

A step a taf may never take itself, prepared and handed to its owner ([api.md](api.md) › Your
step; `KIFF/TafsCloud+YourStep.swift`, `Views/YourStepCard.swift`, `AppModel.answerYourStep`):

- **Where:** Needs you lists Home's `your_steps` first, oldest first, as Android does, each card
  naming its taf. A taf's page shows the step on the request that waits on it (its
  `your_step`), with Home's copy when Home has it. The card is tafs cloud's words only: `title`,
  what, why, and `done` and `skip`.
- **The vendor's page:** a button only when tafs cloud sent `link`, `link_host` and `words.open`,
  the link is https, and `link_host` is the link's own host. The host shows under the button,
  so the person sees where it goes. The app never decides from a URL it parsed itself, and a
  link without all of that shows no button. A taf's request carries no link.
- **The answer** goes to KIFF, never to tafs cloud. The hand-off is read first
  (`GET /v1/me/handoffs/{handoff}`) for the version the phone signs; one whose `status` isn't
  `waiting` isn't sent, and the shared words say it has ended. Then
  `POST /v1/me/handoffs/{handoff}/answer` with `done` or `skipped`, signed with the device key
  over `kiff.handoff.v1` (`KIFFClient.handoffMessage`, pinned by KIFF's vector), under Face ID
  or the passcode as a Refuse is, never the approval key, which signs only what grants
  authority. The prompt is tafs cloud's words: "Done · {what}". Only a person who answers the
  account's holds, with the session's device key, sees the buttons. A 409 means it was answered
  meanwhile, or it ended: it leaves Needs you. Answered here, it stays out of Needs
  you and its count while Home still lists it, until the run hears the answer.
- **Its notification** (`handoff` in the payload) opens Needs you on that step.

Tests: `TafsTests/YourStepTests.swift`.

## Tests and CI

- **Unit tests** (`TafsTests`) sit by feature: `ChatTests`, `WorkLogTests`,
  `ApprovalKeyTests`, `AttachmentsTests`, `DoorsTests`, `RecordFieldsTests`,
  `PurchasesTests`, and one file per device retest (`Retest755Tests`…), which pins what
  that test found.
- **UI tests** (`TafsUITests`): `ChatPinUITests` holds how a sent message, the work block
  and the answer sit in the chat ([chat.md](chat.md#the-sent-message-stays-on-top)).
  Every UI test class is a `TafsUITestCase`: its `launch(_:)` puts one interruption monitor in
  place that closes the simulator's own alerts, such as "Sign in to Apple Account", by their
  own button (Not Now, Cancel, Later or Close, else the last one), since XCTest's default
  handler can't and holds the test for minutes; then a tap on the status bar lets it run.
  `typeInto(_:text:)` types once the field has the keyboard, tapping it once more if the
  monitor took the first tap.
- **CI** (`.github/workflows/ios.yml`) builds and tests, on a simulator, every pull request
  and push to main that changes what the app is built from (`ios/`, `app/strings/`, the
  workflow); a tafs cloud or Android change doesn't take a macOS runner. It builds once, then
  runs the unit tests on every such change; the iPhone's UI tests only when the screens
  (views, design, assets, `TafsApp.swift`) or the UI tests change; and the iPad's list tests
  only when the iPad's layout changes. When a pull request touches the screens,
  it takes a key set of screenshots, uploaded as the `screenshots` artifact. A `v*` tag or a
  manual run also uploads to TestFlight: Team at once, and the external Beta testers after
  Apple's review unless the run turns that off. A manual run skips each kind of test main
  already passed at an earlier commit with nothing changed since that it tests (the app for
  the unit tests, the screens for the UI tests, the iPad's layout for the iPad's), so an
  upload can go straight to the archive; a tag runs everything.
- **Screenshot runs** are manual runs with `upload` off and `screenshots` set (`full`, `key`
  or `languages`; it's `none` by default). They wait for an empty queue: one stops at once,
  before it takes a macOS runner, while another manual or tag run is queued or running, so
  a release build never waits behind screenshots. Dispatch it again once that run is
  through.

```bash
cd ios && xcodegen generate
xcodebuild test -project Tafs.xcodeproj -scheme Tafs \
  -destination 'platform=iOS Simulator,name=iPhone 14 Pro'
```

## What a refusal says

No raw reason reaches the person. tafs cloud's `words`, and its `error`, which it writes for the person in their
language, are shown as they are. KIFF's and planque's own reasons (an English line, a code, a vendor's words) never are:
`KIFFError.server` and `PlanqueError.server` say a plain line of the app's own by what happened (`PlainError`): signed
out (401), not allowed (403), changed meanwhile (409), busy (429), down (5xx), offline, or else "didn't understand each
other". planque's own `words` on a refusal (`PlanqueClient.refusal`), its line for the person in their language, come
first and are shown as they are: a sign-in to another account says so, never "changed meanwhile", which read as "try
again". The raw reason stays in the error, for the code that acts on it (a refusal's code, a 409's reason) and for a
drive's diagnostics.

## Words and languages

The app's own lines are in the shared `app/strings/en/`, one file per feature, by key (`needs_you.approve_once` is in
`en/needs_you.json`), the same files Android reads; the servers' own lines stay theirs ([languages.md](languages.md)). `app/strings/generate.py`
writes `Localizable.xcstrings` and `Design/Words.swift`, a typed accessor per key (`Words.NeedsYou.approveOnce`,
`Words.Connect.Disconnect.title(vendor:)`), so a missing key or a wrong value doesn't compile. Nobody edits those
two files by hand: change the JSON and run the generator, and `--check` fails CI when they differ. A count picks
its plural form from the catalog's `_plural` entry, in the app's language; its number is always the first value,
written as that language writes it (1,000 in English). The catalog's `_language` entry names the language its lines
are in, and `Words` writes numbers in it, never in the device's region: an English line on a German-region iPhone
reads "1,000 runs", not "1.000 runs".
The notification extension compiles Model and KIFF, so it has `Words.swift` and the catalog too.

The app ships the eight languages `app/strings/languages.json` lists, and iOS picks the phone's (or the
language chosen for tafs in Settings), else English. What the servers say comes in the same language: every request to
KIFF and tafs cloud carries `Accept-Language` with the language the screens resolved to, then the phone's other
languages the app ships (`Words.languages`), at most four, then `en` (`KIFFClient.screenLanguages`). A language only
Siri's phrases are in never counts. The calendar's titles follow the language's order (`calendar.title.*`), and its
month view names the month on its own (`calendar.month_title.*`, the nominative in Polish). Halt all's Siri lines
are `halt_all.*`, and its phrases are in each language's `AppShortcuts.strings`. Apple reads an App Intent's
keys as its words and refuses a build whose title or description contains "siri" (90626), so no intent key or
phrase does, which `test_strings` checks. The iOS workflow's
`languages` screenshot set (a manual run with `"screenshots": "languages"`) takes the main screens in each language.

What stays in code, never in a language: JSON keys, codes and states compared with a server's, tool names, URL
schemes, what's sent to a server as data (the labels KIFF stores, a door's app and kind), every signed message,
the sample's templates and the knob's server-shaped sample, and the drive's lines only Coach reads. A screen
never reads an error's or a line's words to know what it is: a Record line the phone wrote carries `byYou`, and an
error the screen shows only for itself carries where it came from (`AppModel.fail(_:from:)`). The drive contract
tests read a `Words` accessor in the sources as the English it says (`DriveTests.readable`), so a confirming
control is still found by its words.

No English is left in the app's code outside that list: `app/strings/ios_literals.py` scans the sources literal by
literal, and the strings tests fail on any it finds that isn't kept by a rule or by name in `ios_kept.json`, with
the reason.

A line that differs by platform keeps a key of its own: the device's name (`{device}`, iPhone or iPad), Face ID and
Touch ID, the App Store and Apple ID, iOS's Settings paths. A few lines stay in code on purpose: the words the app
reads from KIFF's own answers (" · it didn't go through: "), and the sign-in's Terms line, whose markdown link
`Text` renders only from a literal.

The pages the app opens follow the lines' language (`KIFFClient.appLanguage`, the first of the languages the screens
resolved to), never the browser's:

- **tafs's own Terms and Privacy policy** (Sign-in, Account › Legal, Plan & usage): `kiff.dev/tafs/terms` and
  `kiff.dev/tafs/privacy`, the pages Google's consent screen names (`Legal`), as Android 1.27.8 opens. They're in
  English only until kiff.dev serves them in other languages, so outside English the Account and Plan & usage links
  say so ("Terms (auf Englisch)", `settings.legal_in_english`, `Legal.label`). Before, they opened KIFF's pages
  (`/terms#tafs`, `/de/terms#tafs`).
- **KIFF's approval page**, for a sign-in and for approving again after Face ID changed: the link KIFF answers gets
  `lang=<language>`, and a link that already names one keeps it (`DeviceStart.inAppLanguage`).
- **A held call's page in KIFF**: `/needs-you/{id}?lang=<language>` (`KIFFClient.answerPage`).

## Haptics

One rule, as on Android (`Haptics`, `Design/Scaled.swift`):
- **A light tap on every button press,** played once in `DriveButton`, the style under `PrimaryButtonStyle`,
  `OutlineButtonStyle` and `.drivablePlain`. Every new button taps without wiring. A press and the action it
  starts tap once: a second tap within 150 ms is the same one. A drive's activation doesn't tap.
  The bar's tabs and the sidebar's foot are such buttons; Coach's own button, on the bar and in the
  sidebar, taps too (`Haptics.tap()`), as on Android 88.
- **A success tick** when an approval goes through (a held call approved, Coach's card approved) or a send
  does (to Coach or a taf).
- **A warning tick** when something the person just did is refused or fails (`lastError`, within 15 s of their own press,
  approval or send): also Coach's card when its approval fails (`AppModel.feel(approving:)`, a signature not given,
  tafs cloud's refusal or no answer) and a send kept unsent (`Delivery.feel`). One failure warns once, within a
  second. A background read that fails never buzzes.
- **A selection tick** (`Haptics.select`) when the person changes a taf's tab (Chat, Activity, Card, Setup)
  or picks another item in the iPad's sidebar; the same one again, or a change made for them, plays nothing.
- **A step tick** on dial and selector stops.
- The system's generators only (`UIImpactFeedbackGenerator`, `UINotificationFeedbackGenerator`,
  `UISelectionFeedbackGenerator`), so the phone's own System Haptics setting is followed too.
- **Set aside** and **Set all aside** tap as they're asked (a swipe too), then tick: success when tafs cloud
  sets the chat aside, a warning when it refuses (`AppModel.setAsideCoach`).
- **Settings › Haptics** turns all of it off on this device (`HapticsTests`).

## Conventions

- The phone decides nothing in KIFF mode: it shows what KIFF and tafs cloud say.
- What grants authority is signed by the approval key behind Face ID or Touch ID only, never
  the passcode; what takes it away is not ([approvals.md](approvals.md#how-an-answer-is-signed)).
- Words on screen are plain and short, and name things as the owner knows them: a tool by
  its connection's title, never its name in KIFF; a reason in words, never a code.
- A comment says why, often with the issue or device test it comes from
  ("the 845 test", `kiff/tafs#206`).

## Account and paged taf presentation

`ThemePreference` and the root's `preferredColorScheme` apply the saved
Light/Dark/System choice. Account contains the searchable cloud time-zone
picker and phone alert controls. Each alert-kind choice is sent to cloud;
Off removes this phone's registration. Time zone changes retain KIFF's signed
setup flow. The setup read sends this phone's zone (`X-Time-Zone`); when tafs
cloud set a new account's zone from it and answers `time_zone`, the account
takes it and no offer is asked.

`TafsCloud+Presentation` decodes the same DTOs used on Android. `SourcePages`
and `AppModel` cache pages and validators per taf and view, clearing on account
changes. `TafPresentationViews` draws server-defined Activity, runs, connected
apps and Workflow. The service supplies names, limits, mappings, counts and
summaries. Coach overlays the mounted screen with a right-edge transition;
Back restores the existing taf and tab. `WalletLayout` measures full Card
faces before stacking them. The API contracts and recent-run history bound
are in [api.md](api.md).
