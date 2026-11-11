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
connected: "Checking the sign-in…" meanwhile (`connectStep`).

**A taf's work:** the Activity tab shows the taf's Needs you items, then `GET /v1/tafs/{id}/work`
([reliability.md](reliability.md#the-tafs-view); `WorkPanel`, `AppModel.loadWork`): tafs
cloud's line, then its recent requests and skipped firings, newest first, in tafs cloud's words.
A request that needs the owner is amber, a failed one red, a skipped firing dim. A door reply
given up on offers **Send again** to whoever manages the taf (`AppModel.sendAgain`).

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
| `-landscape YES` | The iPad's window turned to landscape, for its screenshots |
| `-pack <id>` | The Add credits screen with that pack alone |
| `-narrow YES` | Laid out at Slide Over's width on an iPad |
| `-sampleReplySeconds <s>` | The sample taf answers slowly enough for a UI test to see each part |
| `-listSettlesOnReturn YES` | The list opens on its first taf, and its last taf moves to the top each time the app comes back (`ListTapUITests`) |

## The taf layout and KIFF's words

- **The bar** (`TafsBar`, in `TafsApp`'s `RootView`): **Account · Tafs · Needs you · Record**,
  then **Coach**, a filled ink button that opens Coach mode, shown only when
  `signedIn && mode == .kiff && mayUseCoach`. Along the foot on a compact width, below the
  screens so a chat's field stays above it, and hidden while the keyboard is up; a rail down
  the left on a regular width, Coach at its foot. Each tab is built the first time it's
  opened and then kept. Account is `SettingsView`; ⌘1–3 open Tafs, Needs you and Record, ⌘,
  Account, ⌘K Coach.
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
- **Record** (`RecordView`): chips for "All tafs" and each taf (its colour) above the type
  filter, combined with it. Each entry starts with its taf's dot and name; each day's header
  shows that day's spend (`RecordView.spent`, a sum per currency of the money moved).
- **A taf's tabs** (`TafDetailView`): **Chat · Activity · Card · Setup**. Activity: the taf's
  Needs you items, its work and runs, then its record by day (`ActivityFilter`: All, Done, Held,
  Refused). Card: the Card, its limits, how they're counted, usage, Halt, Revoke. Setup: The job
  (Improve in Coach), Starts when, Connected apps (`ConnectionsPanel`: **Choose actions**, through Coach, for one signed in with none chosen, then **Connect an app…**), Results go to, what it
  remembers, Versions, Pause (sample mode) and Remove for good.
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
  device (Turn on alerts while off), Sound, and What alerts you, read-only; "A taf answered
  someone" lists the tafs whose results include `app` (`CloudTaf.alertsPhones`).
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
  Connecting (`POST /v1/connect/apps/{entry}`) opens the approval as a sheet over the list,
  with KIFF's words: approve first, then sign in at the vendor, as for any connection. An
  API-key vendor takes its key in the sheet: planque gets the key in `Planque-Vendor-Key`, and
  the device signs only its SHA-256 with the grant's digest. Its list and open approval are cleared when the account changes; delayed replies from the previous account or a cancelled search are discarded.
- **Did this happen?** (`DidThisHappenCard`, in Needs you): KIFF's money calls whose outcome
  it can't prove (`GET /v1/me/did-this-happen`), each answered No or "Yes, it did" behind
  Face ID, signed like an approval (`POST /v1/me/did-this-happen/{proposal}`). No gives the
  Card its room back; the first answer stands.
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

### Connect apps

A connected app (“Connected ✓”) opens its connections (`AppConnectionsView`, `AppModel.connectionGroups`), in four parts:

- **One section per taf,** its name as the heading, holding each of the account's live grants for the app (not `disconnected` or `failed`). The tafs with a connection that needs the owner come first (a sign-in waiting, signed out, or a last call that failed), then the rest by name. A row shows the vendor's account when the connection service gives one, else the app's name, then the connection's status words, and “Also on Chargebacks' Card” when another taf's Card uses it.
- **On a row,** whoever manages the account's Cards has **Sign in again** and **Disconnect**, or **Cancel** on a sign-in that never came back (“Cancel the sign-in to Stripe?”, “Keep waiting”). Disconnect asks first, naming the one taf it ends (“Disconnect Stripe from Refunds?”, with Cancel). It also names any other taf whose Card draws on the connection's tools, which stops too (“Chargebacks stops too.”), and says other connections to the app stay. Face ID then asks the same words, and the device key signs the connection service's `DELETE` ([api.md](api.md#disconnecting-and-removed-tafs)). The list is read again after.
- **From removed tafs** comes between the tafs and Other connections. It holds the connections whose `request.agent` tafs cloud names in `removed_tafs`, and only those, each with the removed taf's name (the grant's `agent_name`). The app sends its signed grants read with the read (`connectApps(proof:)`). A connection a live taf's Card still uses says so (“Kept: Chargebacks' Card uses it”). **Clear these connections** shows only when at least one listed connection has an empty `used_by`, and ends exactly those: it asks first (“Clear 2 connections from removed tafs?”), then one Face ID, and the device key signs each disconnect. The line under them says how it went (“Cleared 1. Kept 1 for Chargebacks, whose Card uses it.”). With no `grants`, the section shows with no Clear. Which to keep is tafs cloud's word, never the phone's (`clearPlan`, `clearRemovedConnections`; the words are `ConnectionGroupWords`, the same as Android's).
- **Other connections** comes last: those whose taf the app can't name, each with the same row as the rest and nothing that acts on them all at once. A taf missing from this person's list may have been removed, but it may also be one they don't see, or an agent that isn't a taf, so nothing there says it was removed.

## Home with no taf

An account with no taf sees the jobs a taf can take on Home, where tafs cloud says so
(`GET /v1/recipes`, its `home`; [coach.md](coach.md)), as on Android
([android-app.md](android-app.md#home-with-no-taf)). Every word is tafs cloud's, in the person's
language: the app has none of its own for it.
- **The list** (`HomeRecipes`): `home.words` leads it, then a chip per category to narrow it
  (picked again, every recipe shows), then each recipe led by its `headline`, the problem it
  ends, with its `line` under it. While it shows, Home's own "0 tafs working" line doesn't.
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

The tafs' scheduled work still to come (kiff/tafs#534), opened from the calendar
icon at the top right of the Overview (the iPad's sidebar) and, on the iPhone,
beside + in the home's bar. `Views/CalendarView.swift` draws Day, Week and Month,
Week first, with Today, previous and next, and a date to pick. tafs cloud computes
every occurrence (`GET /v1/calendar`, docs/api.md): live schedules' firings in the
account's zone, follow-ups and runs' next tries, only for the tafs the person may
see, none for a paused taf. Each comes with its `day`, `time` and `minute` in that
zone, so the app places it and converts nothing. `Model/CalendarModel.swift`
(`CalendarMath`) only counts dates (Monday-first weeks, the month's six-week grid,
one read per grid), groups events by day and hour, and picks the layout: wide
(`CalendarLayout.of`: at least 700 points wide, on its side, a tablet's height),
with a month navigator at the upper left, the calendar beside it and the agenda
under them; compact otherwise, with a scrolling week strip and the selected day's
work under it. What it shows (`CalendarState`) is kept on `AppModel`, so a rotation
or a new layout keeps it, and cleared with the rest of an account's state. A tap
opens the taf. Read-only; no new dependency. Tests: `TafsTests/CalendarTests.swift`.

## Tests and CI

- **Unit tests** (`TafsTests`) sit by feature: `ChatTests`, `WorkLogTests`,
  `ApprovalKeyTests`, `AttachmentsTests`, `DoorsTests`, `RecordFieldsTests`,
  `PurchasesTests`, and one file per device retest (`Retest755Tests`…), which pins what
  that test found.
- **UI tests** (`TafsUITests`): `ChatPinUITests` holds how a sent message, the work block
  and the answer sit in the chat ([chat.md](chat.md#the-sent-message-stays-on-top)).
- **CI** (`.github/workflows/ios.yml`) builds and tests every push and pull request on a
  simulator. When a change touches the screens, it takes screenshots and uploads them as
  the `screenshots` artifact. A `v*` tag or a manual run also uploads to TestFlight: Team
  at once, and the external Beta testers after Apple's review unless the run turns that
  off.

```bash
cd ios && xcodegen generate
xcodebuild test -project Tafs.xcodeproj -scheme Tafs \
  -destination 'platform=iOS Simulator,name=iPhone 14 Pro'
```

## Words and languages

The app's own lines are in the shared `app/strings/en.json`, by key (`needs_you.approve_once`), the same file
Android reads; the servers' own lines stay theirs ([languages.md](languages.md)). `app/strings/generate.py`
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
are `siri.halt_all.*`, and its phrases are in each language's `AppShortcuts.strings`. The iOS workflow's
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

- **kiff.dev's Terms and Privacy policy** (Sign-in, Settings, Plan & usage): `/terms#tafs` in English, `/de/terms#tafs` in
  German, and so on (`Legal`).
- **KIFF's approval page**, for a sign-in and for approving again after Face ID changed: the link KIFF answers gets
  `lang=<language>`, and a link that already names one keeps it (`DeviceStart.inAppLanguage`).
- **A held call's page in KIFF**: `/needs-you/{id}?lang=<language>` (`KIFFClient.answerPage`).

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
setup flow.

`TafsCloud+Presentation` decodes the same DTOs used on Android. `SourcePages`
and `AppModel` cache pages and validators per taf and view, clearing on account
changes. `TafPresentationViews` draws server-defined Activity, runs, connected
apps and Workflow. The service supplies names, limits, mappings, counts and
summaries. Coach overlays the mounted screen with a right-edge transition;
Back restores the existing taf and tab. `WalletLayout` measures full Card
faces before stacking them. The API contracts and recent-run history bound
are in [api.md](api.md).
