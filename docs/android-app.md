# The Android app's code

tafs for Android phones and tablets is Kotlin and Jetpack Compose, Android 11 (API 30) and
later. It uses KIFF, tafs cloud and planque exactly as the iPhone app does: the same public
routes, every answer signed on the phone, KIFF deciding every action. It is only the
screen: the backends give its names, words, limits and rules. This page is a map of the
code; what each feature does is on its own page ([README.md](README.md)), with a line
where Android differs.

It asks for the microphone only to dictate, at the first tap of the mic, and doesn't need
one to install (`uses-feature android.hardware.microphone required="false"`): a phone or
tablet without one installs tafs, and its mic says it can't dictate there.

## Coach's screen

Drawn by `CoachScreenTest` from synthetic data, at phone size: the compact header with the plan
in tafs cloud's one line, the plan opened, the conversations menu, and the live line thinking,
working and waiting for you ([coach.md](coach.md), [chat.md](chat.md#the-answer-as-it-comes)).

<p>
  <img src="screenshots/android/coach-card.png" width="180" alt="Coach: the header, the plan line, an answered approval and the rules card">
  <img src="screenshots/android/coach-plan-open.png" width="180" alt="Coach: the plan opened to its six steps">
  <img src="screenshots/android/coach-menu.png" width="180" alt="Coach: the menu with New conversation and Previous conversations">
</p>
<p>
  <img src="screenshots/android/coach-live-thinking.png" width="180" alt="The live line: Thinking">
  <img src="screenshots/android/coach-live-working.png" width="180" alt="The live line: Drafting the Card">
  <img src="screenshots/android/coach-live-waiting.png" width="180" alt="The live line: Waiting for you">
</p>

A taf's page, under its compact header: the sample's Refunds; a chat opening at its newest, with a
stranger's knock and a paired sender's message at their times; and the page folded as the chat
scrolls (the last two drawn by `TafPageScreenTest`, from synthetic data):

<p>
  <img src="screenshots/android/taf-page.png" width="180" alt="A taf's page: one header row, the tabs and the Card's line, then the chat">
  <img src="screenshots/android/taf-chat-newest.png" width="180" alt="A chat opening at its newest message, with a knock and a paired message above it">
  <img src="screenshots/android/taf-page-folded.png" width="180" alt="The page folded: the Card's line gone, the header and tabs kept">
</p>

## Building and testing

```bash
cd android
./gradlew :app:testDebugUnitTest      # the unit tests, against fake KIFF, tafs cloud, planque and Google Play
./gradlew :app:assembleDebug           # a debug build
./gradlew :app:connectedDebugAndroidTest   # the UI tests, on a running emulator or phone
```

Gradle needs a JDK 17 or later; Android Studio's own (`jbr`) does. The build uses the
Android Gradle plugin 9.1 on Gradle 9.3.1, which the wrapper fetches and checks against its
checksum; Android Studio needs to support that plugin version to open the project. On Windows,
`gradlew.bat`. CI (`.github/workflows/android.yml`) runs the unit tests and the debug
builds on every pull request that touches `android/`, and the UI tests on an emulator. The UI
tests run in a debug-only `TestHostActivity` (`createStableComposeRule`), which takes configuration
changes in place: on a just-booted emulator the system settles after the first test starts, and an
ordinary activity recreated then would drop the test's screen ("No compose hierarchies found").

The application id is `dev.kiff.tafs`; the Kotlin packages are `bot.tafs.app`.

**Alerts** need Firebase's settings, `android/app/google-services.json`, which is never
committed: CI writes it from the repository secret `GOOGLE_SERVICES_JSON`. A build without
it has alerts off and says so in Settings.

**Releases** are signed bundles for Google Play, built by `android.yml`'s release job on a
manual dispatch, in the `android-release` environment, which the owner approves each time.
The upload key is never in the repository or a session: `android/make-upload-key.sh` made
it once into the secrets store, and `android/release.sh` builds with it. The app has no native
code of its own. Its only native libraries are two small ones that AndroidX ships already
stripped (`libandroidx.graphics.path.so` for Compose, `libdatastore_shared_counter.so` for
DataStore), with no symbol table or debug sections, so there are no native symbols to put in the
bundle. `ndk.debugSymbolLevel` stays set, so any native code added later sends its symbols. Play's
"no debug symbols" notice is about those two libraries, and it can't be cleared from this build.

**Buying through Google Play** is on (`res/values/flags.xml`'s `play_billing`). Usage offers Solo
and the task packs, at Play's prices, only once KIFF's usage names their Play products and the
account's token, and only to someone who may add tasks. KIFF records each purchase before Play
is told it's done, and Restore purchases sends again what wasn't.
- **Play's offers are read again** each time Usage shows, after every purchase and restore, and
  just before Play's sheet opens (`Purchases.load`, `PlayBillingStore.purchase`), never kept
  from an earlier read. Solo's free trial is offered only while Play lists it for the Google
  account in the Play Store, and the words follow it: "Free for 1 week, then €10.99 a month", or
  "€10.99 a month". When Play can't be reached for that read (no network, its service down), the
  purchase says "Couldn't reach Google Play. Try again.", never that Play doesn't sell it
  (`PlayBillingStore.unreachable`).
- **Packs need a running plan:** the packs, and approving a member's request for one, are offered
  only while KIFF's usage says `can_add_packs` (from an older KIFF, `can_add_tasks`). Before a trial
  starts or after it ends, Solo alone is offered, and packs already held say "Counts once Solo
  starts." (`Usage.packsOffered`).
- **How a purchase or restore went** shows under what was tapped (Start Solo, the pack, Restore
  purchases), read out by TalkBack (`PurchaseNote`). A purchase KIFF hasn't recorded yet offers
  Restore purchases right there.

**Needs you's count** (Home's line and the tab's badge) is tafs cloud's (`needs_you`: its count and
words, covering held calls, "Did this happen?" calls, currency requests and tafs with a knock). When
tafs cloud leaves it out (it couldn't read all of it), the app counts what it has (`needsYouCount`).
Android shows each alert on the lock screen as it comes, with no count of its own there. An app's
sheet with no saved workflow says so once, under a Workflow line of its own (`workflow_words`).

**A Card asked to take another currency** (KIFF's `card_unit` exception, as tafs cloud draws it in
`card_unit_exceptions`): one panel (`CardUnitExceptionPanel`) with tafs cloud's title, words and
history, in Needs you (and its count), at the top of the taf's Setup, and above its Activity, where
the Activity page's rows win over Home's. It is never a held call and never offers Approve once.
Its Coach button opens a new conversation on the taf with tafs cloud's proposal written in, not
sent (`discussCardUnit`); Dismiss reads KIFF's exception again and, only while it's still that
taf's open request, signs KIFF's ordinary refusal against its current version (`dismissCardUnit`). A
dismissed one leaves at once, from Home, Setup and Activity's cached pages, and a page or Setup read
already on its way doesn't bring it back, whether it lands as a page, a 304, a failure or a cancel
(`dismissedCardUnits`). Activity draws the newest page's requests, and its notice when KIFF's
couldn't be read (`card_unit_exceptions_notice`).

**Alerts after signing out.** Signing out turns this phone's alerts off and remembers whether
they were on for that account (`ALERTS_WERE_ON`). Signing in to the same account again registers
this phone again, with no new confirmation, while the account's alerts still reach tafs cloud;
otherwise the app says once that they're off, with **Turn on**. Another account doesn't get them.

**Halt and Resume say what happened on the Halt screen** (`haltError`): a halt or resume that
didn't go through says why there, and the screen never calls a connection by its service's name.
Its button says what's under way, "Halting…" or "Resuming…", even once the screen has turned to
the other button.
Resume's confirmation is bounded: unanswered after 10 seconds, the fingerprint check is
cancelled, the prompt with it, so a late answer sends nothing, and the screen says "The fingerprint
check timed out. Try again." (`RESUME_CONFIRM_MS`). Android can't tell whether its prompt is on
screen, so the wait itself is what's bounded, and only for Resume.

**One account per phone, and signing out asks first.** Account ends with **Sign out**: another
account is signing out, then signing in with it. Sign out, and Sign in again on the line shown
when the phone can't approve, each open a confirmation (`ConfirmSignOut`), so one stray tap
never ends the session. Account › About › Version names the build, e.g. "1.0 (26)"
(`versionWords`).

The status and navigation bars follow the app's own Light/Dark/System choice from the first
frame (`enableEdgeToEdge` with the saved choice in `MainActivity`), and `TafsTheme` keeps their
icons dark on the light ground and light on the dark one when the choice changes.

## The packages of `bot.tafs.app`

| Package | What's in it |
|---|---|
| (root) | `MainActivity` (the bar: Account, Tafs, Needs you, Record, Coach, or a rail on a tablet; the list beside what's open on a tablet), `TafsApplication` (the clients, the keys, alerts, Google Play) |
| `app/` | `AppModel` (the one view model: state, and everything the screens ask of the backends), `SampleStore` |
| `kiff/` | The clients: `KiffClient` (KIFF's API), `TafsCloud`, `Planque`, `Transport`; `KiffMapping` (KIFF's records into the app's), `Stream` (a chat's streamed events), `ReceiptCheck` (receipts checked on the phone), `Jcs`, `SigningTime`, `SecretStore`, the wire types (`Wire`, `Usage`, `Coach`, `Doors`, `Versions`) |
| `model/` | The app's own types and words: `Models` (`Taf`, `Card`, `HeldCall`, `RecordEntry`…), `Chat`, `Attachments`, `CardCheck` (the sample only), and one `…Words` object per feature, for words the backends don't send yet |
| `signing/` | `KeystoreKeys` (the signing key and the approval key, in the Keystore), `DeviceSigner`, `BiometricAuthenticator` |
| `push/` | Alerts through Firebase Cloud Messaging: `TafsMessagingService`, `AndroidPush`, `Alerts` |
| `billing/` | `Purchases` and `PlayBillingStore`: Google Play, behind the flag |
| `files/` | `PhotoPrep`: a photo redrawn as a JPEG, with no metadata |
| `voice/` | `Dictation` and `DeviceSpeech`: dictation, recognised on the phone |
| `views/` | One file per screen or feature: `HomeView`, `TafDetailView`, `TafChatView`, `CoachViews`, `CoachChats`, `NeedsYouView`, `RecordView`, `ReceiptSheet`, `UsageViews`, `TaskRequestViews`, `SettingsView`, `PeopleViews`, `ShareViews`, `DevicesView`, `DoorViews`, `SetupViews`, `EventApproval`, `VersionViews`, `HaltViews`, `AttachViews`, `MicButton`… |
| `design/` | `Theme` (colours, the amber accent, type), `Components`, `MarkdownText` |

## Live updates

A silent push from tafs cloud names what changed on the account (`kind: background` with
`change` and `taf_id`, [approvals.md](approvals.md)), and the app reads that again with its own
session (`AppModel.changed`, `readChange`). Nothing is shown and nothing is taken from the push:
- `taf`: the home list, and that taf's thread where it's been read;
- `halted` and `resumed`: the home list;
- `knock`: that taf's doors, and its thread where it's been read.

Every refresh and every push also reads planque's grants again: tafs cloud never sees them, so
this is how a connection its vendor signed out reaches the list.

**A connection its vendor signed out** (`needs_reconnect`): its taf waits on the owner in the
home list, at the top with the held calls, its row reading planque's `status_words` ("Signed out
by Stripe · sign in again"; `AppState.signedOut`, `waitingOnOwner`). On the taf's Card tab its
connection says the same, with **Sign in again**: one approval, then the vendor's own page
(`AppModel.reconnect`), and "Connected" only once planque answers connected.

A change of a kind the app doesn't know is ignored; coming back to the app reads everything
again. Opening a chat no longer reads it on a timer: a run at work is followed on its stream, and
the timer is left only where nothing else tells the phone (a trial's chat, a run whose stream
can't open or dropped, and a run that goes on after its stream ended at a pause: Coach's, once
tafs cloud answers its result, which the timer follows until Coach's closing words show).

A door shows tafs cloud's words for itself (`words`: `state`, `tap`, `settings`) as they come; the
app's own lines stand in only where tafs cloud sends none.

## A taf's work

The Activity tab shows where the taf's work stands, from `GET /v1/tafs/{id}/work`
([reliability.md](reliability.md#the-tafs-view); `WorkPanel`, `AppModel.loadWork`): tafs cloud's
summary line, then its recent requests and skipped firings, newest first, each in tafs cloud's
words (`Chat.workRows`). A request that needs the owner is amber, a failed one red; a skipped
firing ("Skipped the 9:00 run: the last one was still working", `skipped[]`) isn't a failure, so
it's dim. A delivery given up on after its tries shows **Send again** to whoever manages the taf
(`AppModel.sendAgain`), by its own place (`WorkDelivery.sendPlace`): a door reply sends once more;
a place of the taf's own (an app's action, another taf) goes by its `key`, retried as the taf's
task. It signs nothing, and the work is read again after. One still not delivered offers **Ask
Coach** (`open_coach`): a new conversation about the taf with tafs cloud's `coach_words` in the
message box, sent only by the owner (`askCoachAbout`). The request sheet's **Try the reply now**
is the door reply's alone. A `taf` live update reads it again where the page has.

## When something doesn't go through

The owner reads the app's own words for what happened and what, if anything, they can do
(`ErrorWords`): offline, try again online; signed out, sign in; not allowed by their
role; changed in the meantime; too many tries, wait a minute; down on our side, nothing to
do but try later; anything else, nothing to do on their side. A server's error text never
reaches the screen, and "try again" is said only where trying again can help. The app's own
failures (signing, signing in, a file that didn't upload) keep their words (`OwnWords`).
Coach still hears the server's reason for an approval that failed, as the card's answer,
so it can say what went wrong and ask again.

## The bar

Five places under every screen: **Account** · **Tafs** · **Needs you** · **Record** · **Coach**.
Account opens Settings and Coach opens Coach; the three between are tabs, and Needs you keeps
its count. Coach is a filled button (ink, with the ground's colour as its label), shown only to
someone KIFF lets use Coach, so the bar has four places otherwise. In landscape and on tablets
the same places are a rail on the left, with Coach at its foot (`TabBar`, `TabRail` in
`MainActivity`). Home's header keeps the account's name, and in the sample a new taf; Settings
and Coach are no longer there. Coach from a taf's page is that taf's conversation: the one
already open about it, else a new one about it; from anywhere else, the conversation open as
it was (`AppModel.openCoachOn`). Leaving a conversation stops following it on this phone (work it
accepted goes on at tafs cloud), and a read of it that lands later doesn't replace the one shown.

**Record** filters by taf too: All tafs, then a chip per taf with its colour, combined with the
kind filter. Each entry starts with its taf's colour and name, and each day says what its money
moved came to, by unit (`spentOn`).

## The taf's screen

Four tabs, each with one job (`TafDetailView`):
- **Chat:** talking to it.
- **Activity:** what it did and what waits for you. Its holds and its doors' knocks first (Do it,
  Pair them, Ignore), then its runs, where its work stands (each request opens step by step) and
  its last run, then its Record by day: All, Done, Held, Refused (`ActivityRecord`).
- **Card:** only what's on its Card: the Card, its limits and how they're counted, its usage,
  Halt and Revoke.
- **Setup** (`SetupTab`): how it runs, in sections.
  - **The job**, with Improve in Coach.
  - **Starts when**, one line per start in tafs cloud's words, opening its sheet
    (`StartsWhenSheet`): its schedule (the line, "Next run at 13:00 · Europe/Paris", − and + from
    its `size`, each choice's `setting` sent as it is), and **If it is still working when the next
    start comes: Skip it / Run after** (`busy`, sent in the knob's `setting`); its app events, each
    with its filter in words, **Approve** while it waits for the owner (the signed approval, below)
    and Listening once approved; its doors, each with **Edit** (the door's own screen) then **Add
    a door…**; the follow-ups it set itself, each with **Remove**; and **Pause every start** (off
    when `can_pause` is false; a 502 says "Not every start could be paused. Try again.").
  - **Connected apps**, read from planque: each connection, **Choose actions** for one signed in
    with none chosen yet (Coach), then **Connect an app…**.
  - **Results go to**, the places on, opening its sheet (`ResultsSheet`): tafs' own places (Reply
    to the sender, Alert our phones, Email me), each a switch sent through `PUT …/results`; each
    place in a connected app by its name, a switch from its own on/off choice; **Add a place from
    a connected app…** (Coach). The last place on can't be turned off. A delivery given up on shows
    under **Not sent**, with the place's name for one of the taf's own, and **Send again** by its place.
  - **What it remembers**, **Versions**, Pause (in the sample) and **Remove for good**.

A change to the dial's setting (a size, Skip it / Run after, a place in an app) makes the taf's
next version: "DRAFT · Vn · NOT LIVE" and **Make N changes live** show in the sheet while it waits
(tafs cloud's `pending`), the usual version switch.

**An app's event** (`kind: app_event`) waits for the owner while its `approval` is `needed`:
Approve opens its filters, as tafs cloud gives them, and **Approve with your fingerprint**
approves exactly those (`AppModel.approveKnobEvent`, `EventApprovalSheet`). The live dial is read
again first, and anything changed (version, connection, event or filters, compared in their
canonical form) stops it there. The owner confirms with the approval key over
`tafs.event.v1|tenant|taf|version|trigger|grant|event|sha256(filters)`. planque subscribes the
event with `{account, slug, config}`, signed by this device (`PlanqueClient.subscribeEvent`), and
tafs cloud records planque's id for that version (`POST …/knob/subscriptions {trigger,
subscription, version}`). Whether it's approved is only tafs cloud's answer. A subscription made
but not recorded is kept for this account, so approving again records it, with no second
confirmation or subscription. A refusal is said in planque's or tafs cloud's words.

Every word on Setup's rows is tafs cloud's (each start's `words`, each place's `name`), except
the section titles and tafs' own three places, which are the app's (`SetupWords`).

**A request, step by step** (`RequestSheet`): a request's row in Activity opens it
(`GET /v1/tafs/{id}/requests/{run}`). It shows the request's line, where it came from, and what
happened, each step marked done, failed, not made, waiting or next, every line and time as tafs
cloud wrote it. A run waiting for an app's event (the slow-tools design) says what it waits for in
the connection's words, tafs cloud's `waiting_for`; the step tafs cloud marks `waits_on: "app"` is
drawn as not needing the owner, never in the amber of a hold (`RequestWords.waitsOnApp`). On the
home list, a taf whose work is `waiting_on_app` reads as its work's line, not "Waiting on you" nor
its next run, and a home state the app doesn't know reads as that line too (`KiffMapping`).
**Try the reply now** sends a reply given up on again, for whoever manages the
taf, and **Ask Coach** opens a conversation about the taf.

<p>
  <img src="screenshots/android/home-bar.png" width="160" alt="Home in the sample, with the bar: Account, Tafs, Needs you, Record">
  <img src="screenshots/android/taf-setup.png" width="160" alt="A taf's Setup: the job, Starts when, Connect an app, Results go to">
  <img src="screenshots/android/taf-starts-when.png" width="160" alt="Starts when: its schedule with how often, Skip it or Run after, an app event to approve, a door, a follow-up, Pause every start">
  <img src="screenshots/android/taf-results-dark.png" width="160" alt="Results go to, in dark: reply to the sender, alert our phones, email me, a place in Notion">
  <img src="screenshots/android/taf-activity-dark.png" width="160" alt="A taf's Activity, in dark: All, Done, Held, Refused, by day">
  <img src="screenshots/android/record-by-taf-dark.png" width="160" alt="The Record by taf, in dark: a chip per taf, each entry starting with its taf">
</p>

## Alerts

In Account (Settings), **Alerts** shows how each kind looks on the lock screen (a hold, with
sound; an answer, silent), then **Alerts on this phone**, **Sound** (for a hold or a halt only)
and the line under them. **What alerts you** is read only, at the levels `push/Alerts.kt` uses: a
taf needs you and every taf halted, with sound, always; a taf answered someone, silent, for the
tafs whose results include our phones (`Taf.results` has `app`; "N tafs ›" lists them); a reply
wasn't sent, silent.

## Dark

The app follows the phone's dark setting (`TafsTheme`, `Theme.dark`): every colour in `Theme` has
its dark value, the owner-reviewed warm near-black (background `141311`, panels `1F1E1B` and
`2A2824`, ink `F2EFE9`) with brighter green, amber and red, each 4.5:1 or more on the dark
surfaces. Text on a status fill uses the panel's colour, so it stays readable either way; taf
colours and the Card keep white text.

## Connect apps

Settings › **Connect apps** (`ConnectAppsSheet`), as [coach.md](coach.md#connect-apps) says:
tafs cloud's list, searched as the owner types, and **Connect** opens the approval as a sheet
over the list (`ConnectApprovalSheet`). With several tafs, **Which taf is this for?** is asked
first, in a dialog over the list wherever Connect was tapped, as on iPhone and iPad. When a sign-in
comes back, what happened is said at once, over the list: "Notion is signed in for Refunds. Next,
Coach chooses its actions and you approve them.", with **Choose its actions**, which opens the
taf's Coach (or "… is connected" when it's done). A row tafs cloud sends as signed in
(`signed_in`, or `listed`) reads **Signed in · choose its actions** in place of Connect, and
opens the same Coach. A row is the app's title alone, never the provider's id
for it. Only the newest search's answer is shown: an earlier read that comes back late (the
first, unfiltered one) is dropped. An app that signs in at the vendor is approved, then
signed in. An app that takes an API key (`needs_key`) takes it on the same sheet, as on iPhone
and iPad: a password field, and **Give the key with your fingerprint**, which signs the same
confirmation as an approval and sends the key to planque once (`AppModel.giveKey`,
`PlanqueClient.giveKey`, the key in `Planque-Vendor-Key`, its SHA-256 in the signed body). The
key lives only in the open sheet's field, and nothing on the phone keeps it.

A browser connection's sheet (on a taf's Setup) offers tafs cloud's `site_sign_in` action, to
whoever manages the taf: its `title` ("Sign in to a site") and its line. A tap asks planque, signed
by the device's key with no fingerprint (it grants nothing), for the live sign-in view
(`AppModel.openSiteSignIn`, `PlanqueClient.siteSignIn`, `POST …/grants/{id}/sign-in`). The view
opens only when its URL is on planque's own https origin (scheme, host and port), in a full-screen web view
(`SiteSignInScreen`) under planque's `words`, with **Close** and nothing else. The web view runs the
page's scripts and follows no link off that origin, and closing it clears its cookies (it is the app's only web view). A refusal (a view already open, the connection
not connected) is said on the connection in planque's words. Closing leaves the view to lapse.

An app tafs cloud sends as `needs_setup` (kiff/planque#87) shows tafs cloud's `words` under its
title ("Needs setting up first. Ask tafs to enable it.") and has no Connect: nothing on its row
can be tapped. On a taf's Setup, a connection's sheet shows tafs cloud's `custody_words` under its
status line, the one line on who keeps its sign-in; with none sent, nothing shows. Above it,
`account_words` says which account the connection works in and in which mode ("Kiff Agents ·
Live"), as tafs cloud sends it; with none sent, nothing shows.

## Signing

Two keys in the Android Keystore, in StrongBox where the phone has it
([approvals.md](approvals.md#how-an-answer-is-signed)). The **signing key** signs what
reads or takes authority away. The **approval key** signs what grants it, once per
fingerprint, through the prompt; a new fingerprint turns it off, and the phone asks for a
new sign-in. Whether the phone can approve is read each time the app comes back
(`approvalReadiness`), and while it can't, each approval's button gives way to the state and what
to do ([approvals.md](approvals.md#set-up-approvals-on-this-device)). A debug build on a phone with no screen lock signs without asking, as the iOS
Simulator does; a release never does.

## Tests

`app/src/test` holds the unit tests: each feature against `FakeTransport`, which answers
each route in turn with what the test queued and keeps every request, and
`FakeAuthenticator`, the person at the prompt. `app/src/androidTest` holds the UI tests.
Each one orders `AwakeDevice` before its compose rule: it wakes the device and dismisses the
keyguard, then waits until it is awake and unlocked, since a just-booted emulator may not be,
and an activity launched then never gets a window ("No compose hierarchies found"). It
also closes a system dialog left over from boot, and each test sets its screen with `setShown`,
which waits until there's a Compose hierarchy to read before reading it.

## Account and paged taf presentation

`ThemePreference` and `TafsTheme` apply the saved Light/Dark/System choice.
`SettingsSheet` exposes source-defined phone alerts and time zones. Alert
choices are saved for this device and account and sent on registration; Off
unregisters this phone. Time zone changes keep the existing signed KIFF flow; once KIFF
has the new zone, `AppModel.applyAccountTimeZone` asks tafs cloud to move the schedules
(`POST /v1/account/time-zone`), then drops the pages and Setups read in the old zone and reads
home and each dial again; a page, Setup, dial or work read begun in the old zone is dropped when
it lands. A 503 shows tafs cloud's words, and choosing again retries it. The Card tab names the
clock its times are in, in tafs cloud's words (`time_zone_words`), and, while a schedule still
runs in another zone, what to do about it (`schedule_time_zone_words`).
Its sheets open at full height, so the back key closes them. Signing in from the
sample, or switching account, closes what the last account had open (`accountShown`).

`TafPresentation.kt` decodes cloud presentation DTOs. `SourcePages` and
`AppModel` cache pages and first-page ETags per taf, filter and app selection.
The cloud defines outcomes, display names, limits, step mappings and counts.
`TafPresentationViews` draws the ring, app sheets, Workflow, memory summary,
Activity and recent runs. A list asks for its next page whenever it is at its end with a cursor
and no load running, including when it got there while its first page was refreshing. Under the
last page it shows tafs cloud's `end_words` ("Nothing older in this view").
`AvatarDoorRim` animates source metadata and respects
disabled system animations. Coach overlays the mounted taf so its tab survives.
The API contracts and recent-run history bound are in [api.md](api.md).
