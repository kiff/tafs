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
| `TafsMac` | `ios/Mac` and most of `ios/Tafs` | A Mac window on the same model and clients, macOS 12. It holds no signing key and approves nothing: what needs a signature is done on the iPhone |
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
