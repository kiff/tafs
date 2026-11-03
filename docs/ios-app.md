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
| `App/` | `TafsApp` (the scene, the three tabs: Tafs, Needs you, Record), `AppModel`, `DoorsModel` |
| `KIFF/` | The clients: `KIFFClient` (KIFF's API), `TafsCloud` (+ `TafsCloud+Doors`), `PlanqueClient`; `KIFFMapping` (KIFF's records into the app's), `ReceiptCheck` and `Keccak` (receipts checked on the device), `JCS` (canonical JSON for what's signed), `Keychain`, `SigningTime` |
| `Model/` | The app's own types: `Models` (`Taf`, `Card`, `HeldCall`, `RecordEntry`…), `CardCheck` (sample mode only), `SentMessages`, `SetupComponent`, `SetupShape`, `QuestionAnswers` |
| `Services/` | Signing (`ApprovalSigner`, `Biometry`), alerts (`PushDelegate`, `HoldNotification`, `AlertAnswers`, `LockScreen`), `Purchases`, `Attachments`, `Dictation`, `HaltAllIntent`, `VendorSignIn`, `Routes`, `SampleStore`, `TafTemplates`, `ThisDevice` |
| `Views/` | One file per screen or feature: `HomeSplitView`, `HomeView`, `TafDetailView`, `TafChatView`, `WorkLog`, `SetupChatView`, `CoachMode`, `NeedsYouView`, `RecordView`, `UsageView`, `PeopleViews`, `ShareViews`, `DoorsView`, `TrialViews`, `HaltScreen`… |
| `Design/` | `Theme` (colours, the amber accent), `Components`, `MarkdownText`, `Scaled` (Dynamic Type), `Keyboard`, `Compat` (what iOS 16 lacks) |

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
  The owner answers, Stopping, Halt, Usage, Tasks and plans, Deleting the account,
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

Each taf has one `DoorsModel` (`AppModel.doors(for:)`), shared by its Doors tab and its chat,
so a door's knocks sit in the chat at their own times (`ChatItem.knock`). A paired sender's
message, kept from its knock, reads "Paired · Do it or Ignore this message", calmly, and sits
apart from strangers' knocks in the Doors tab. A door shows tafs cloud's words for itself
(`words`: `state`, `tap`, `settings`); the app's own lines stand in only where it sends none.

**A connection its vendor signed out** (`needs_reconnect`): its taf waits on the owner at the
top of the home list, its row reading planque's `status_words` ("Signed out by Stripe · sign in
again"; `AppModel.signedOut`, `waitingOnOwner`). A taf newly waiting goes to the top at once;
otherwise the list holds its settled order. The taf's Card tab offers **Sign in again**
(`AppModel.reconnect`), and Coach's sign-in card says the same for such a grant. Either way it's
one approval, then the vendor's own page, and it's signed in only once planque answers
connected: "Checking the sign-in…" meanwhile (`connectStep`).

**A taf's work:** the Activity tab shows `GET /v1/tafs/{id}/work`
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
| `-tab tafs \| needs-you \| record` | Opens that tab |
| `-screen taf \| card \| doors \| chat \| new \| solo \| add-tasks` | Opens that screen |
| `-pack <id>` | The add-tasks screen with that pack alone |
| `-narrow YES` | Laid out at Slide Over's width on an iPad |
| `-sampleReplySeconds <s>` | The sample taf answers slowly enough for a UI test to see each part |
| `-listSettlesOnReturn YES` | The list opens on its first taf, and its last taf moves to the top each time the app comes back (`ListTapUITests`) |

## The dial and KIFF's words

- **The dial** (`TafKnobView`, in the taf's Doors tab): **When**, **What**, **Where**
  surround its **Why**, with today's done, working, retrying and needs-you counts on the rim.
  `GET /v1/tafs/{id}/knob` supplies the ring names, purpose, numbered recipe steps and stop
  words. The legacy `when` rules are not drawn. Ring order follows `rings` as sent;
  cloud supplies the empty-recipe and Add a door stops. **Showing** filters all three rings by connection grant, or shows **This taf**
  (stops without a grant). The menu has no connection limit. Rings with more than six stops show a window of
  labels; **Back/Next** still walk every stop, across empty rings and around the end.
  Choosing a stop turns its ring to the pointer; the face shows its value and hint.
  Text in the lower half flips 180° relative to its stop, based on the ring's current turn,
  so labels never read upside down. Each label fits inside its ring segment and truncates
  with an ellipsis; the face bounds its title, value and hint too. VoiceOver reads the full
  source words, including text omitted visually.
- **The remote** (`TafDialPanel` and `TafKnobRemote`): stays in the bottom safe area, above
  the tab bar, while the dial and door details scroll. **Lower/Raise** select only the neighbouring value from
  the stop's server-supplied `size.choices`, within its bounds. There are no vendor presets
  in the app. A choice's complete setting goes to `PUT …/knob`, then iOS reads the returned
  draft through `GET …/knob?version=N`. Subsequent choices build on that draft; if its
  refresh fails, stale choices are disabled until a successful refresh. `pending` supplies the version and change count; it alone shows the Make it live button.
  Pending settings are labelled **not live**. **Make it live** reads the newest draft and uses the existing version switch
  and biometric signature (reading a changed Card first). A tweak alone never switches it.
  The controls for changes are shown only to someone KIFF says manages the taf. Pause uses
  the existing pause/resume route. The power button is disabled when the service sends
  `can_pause: false`; older responses keep it available. A refused pause shows the
  service's `words`, or its error message when words are absent. The search-shaped **Ask Coach or find anything** button
  opens Coach in the taf's context.
- **An app event's approval:** a stop with `ok: approve` shows **Approve with Face ID**
  (or Touch ID). Its sheet shows the complete filters before confirmation. The phone signs planque's trigger request with the exact `account`,
  `slug` and filter object supplied by the connected grant and the dial; it adds no filters.
  It then records planque's returned subscription with the trigger id and the live dial's
  `version`. The service's updated knob supplies the approved state. A changed live version
  is refused; a failed recording keeps a receipt so retrying does not subscribe or ask Face ID
  twice. A lost response is resolved by reading the server's current state. Account reset
  discards receipts and in-flight results. Provider refusals retain the needed state and
  show the provider's words. `AppEventApprovalTests` covers these paths with synthetic services.
- **Doors and needs you:** the face opens/closes the chosen door, runs the taf once or removes the chosen
  follow-up by the id cloud supplied.
  An approvable held call takes an unchosen face, or the Needs you button selects it,
  for **approve once**, using the existing signed answer.
  Each stop's `ok` supplies its face action, with no action when absent. Door `look` supplies
  the lit, dashed, resting or amber arc, on When's outer edge; an increase in the server's
  message count pulses its arrival indicator for three seconds.
  The server's last When stop is **Add a door**, opening Coach. Existing knock cards retain **Do it**,
  **Pair them**, and **Ignore**, with pairing and door details beneath the dial.
  If the knob cannot be read, the existing doors-only dial remains available.
- **Its work:** the count under the dial opens the first unfinished request, otherwise the newest,
  with Back to the list. Each request reads
  `GET …/requests/{run}`, showing the service's ordered steps, delivery state, **Try the
  reply now** when allowed, and **Ask Coach**. Nothing in this screen infers a work outcome.
- **Coach's dial card:** `draft.groups` displays each connection's lines and **This taf**,
  as cloud supplies them. Older responses fall back to words by ring. One confirmation
  uses the usual version switch for the entire draft.
- **Dial checks:** `KnobNavigationTests` covers grant filtering, empty rings, crowded rings,
  bounded sizes and complete setting preservation. `VersionsTests` covers two edits,
  stale selections, a failed draft read and the absence of an automatic switch.
  `KnobUITests` uses the synthetic `-screenshots YES -screen knob` fixture with 12 services.
  It checks that the remote stays above the tab bar before and after scrolling, a disabled
  power button, and full accessibility labels when text is long. The regular `-screen doors`
  screenshots use that same dial, including the sample email knock.
- **KIFF's words for a connection** (`ProfileWords`): a grant whose request carries a
  `kiff.profile.v1` shows KIFF's `kiff.words.v1` for it, read with the person's session
  (`POST /v1/profiles/words`) and checked against the profile's digest and their own before
  they're shown, verbatim and in order. Approving signs both digests into planque's approve
  body (`kiff_profile`). The tests use KIFF's vectors in `TafsTests/ProfileVectors/`.
- **The Slack door** (`SlackDoorSheet`, from Add a door in Doors): the taf's Slack bot
  connections (planque's grants, connected, the taf's agent, marked `connector:composio:slackbot`,
  or `request.connector.toolkit` until the mark is there), then the channels it sees
  (`GET …/grants/{id}/channels`, from planque). Picking a channel asks Face ID once: planque
  subscribes it (`POST …/grants/{id}/triggers {account, slug, channels}`, device-signed; planque
  builds the subscription from the channels), then tafs cloud makes the door
  (`POST …/doors {app: "slack", grant, channel}`). The door keeps the subscription's id (`trigger`); if the door can't be made, the subscription
  is taken back. Removing it is the reverse: the signed `DELETE …/grants/{grant}/triggers/{trigger}`,
  then the door. If the phone cannot sign the subscription's removal, the door stays and the app asks the owner to reconnect first. Pairing asks for a Slack member ID.
- **Connect apps** (`ConnectAppsView`, from Settings): `GET /v1/connect/apps`, searched as the
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

## Conventions

- The phone decides nothing in KIFF mode: it shows what KIFF and tafs cloud say.
- What grants authority is signed by the approval key behind Face ID or Touch ID only, never
  the passcode; what takes it away is not ([approvals.md](approvals.md#how-an-answer-is-signed)).
- Words on screen are plain and short, and name things as the owner knows them: a tool by
  its connection's title, never its name in KIFF; a reason in words, never a code.
- A comment says why, often with the issue or device test it comes from
  ("the 845 test", `kiff/tafs#206`).
