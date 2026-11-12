# Coach drives the app

Coach can operate the tafs app on the owner's phone while they watch, one step at a
time, the way a person debugging the app over USB does: it looks at the screen, then
taps, types, swipes or scrolls inside the app. It never confirms anything. This page
is the contract between tafs cloud and the apps (kiff/tafs#544). Both apps follow it,
and each has a contract test against it.

The guide for the person running tafs, in plain words, is
[coach-drives-the-app.md](coach-drives-the-app.md).

## How a drive starts

A drive is the person's, one request at a time.

1. The person asks Coach to do something in the app, or accepts Coach's suggestion. Coach
   shows a **drive card**: `{type: "drive", id, text}`, where `text` names the request in
   the person's words ("Run Chargebacks").
2. The app shows the card as an approval prompt that names the request, with Approve and
   Not now.
3. **Approve** is signed with the **approval key**, behind the fingerprint or Face ID:
   - the app sends `POST /v1/coach/drive {session, task, component}` with
     `KIFF-Device-Signature` made by the approval key;
   - `session` is Coach's chat, `task` the card's text, and `component` the card's id.

   tafs cloud asks KIFF who signed (`POST /v1/me/device-signature`). It starts the drive
   only for KIFF's `key: "critical"`, outside any approval window. The device key, or a
   tap inside a window, is refused with 403 and `code: critical_key_required`, and
   nothing starts. A `component` that isn't a drive card of theirs is 404, and nothing
   starts either. Coach's card is answered `approved: the drive is on`.

### What the approval key signs

The phone signs `kiff.request.v1` over the request it sends to tafs cloud, exactly as for
any setup call to KIFF. These are the lines, joined by `\n`:

```
kiff.request.v1
<tenant id>
POST
/v1/coach/drive
<sha256 of the body as sent, lowercase hex>
<signed_at, RFC 3339>
```

The header is `KIFF-Device-Signature: t=<signed_at>,v1=<base64 DER ECDSA>`, and
`signed_at` is within 5 minutes of KIFF's clock. The path is tafs cloud's, with the query
if any, and the body's hash is of the exact bytes sent.

A test vector for both apps' contract tests:

| | |
|---|---|
| Tenant | `t_acme` |
| Body | `{"session":"s_1","task":"Run Chargebacks","component":"d1"}` |
| sha256 | `fea4f2ebc2091cd2cbf48e1cc3b6285603bcc194cddea23f5737dfdc2a78414f` |
| `signed_at` | `2026-11-06T00:30:00Z` |

The message is then:

```
kiff.request.v1
t_acme
POST
/v1/coach/drive
fea4f2ebc2091cd2cbf48e1cc3b6285603bcc194cddea23f5737dfdc2a78414f
2026-11-06T00:30:00Z
```
4. **Not now** answers the card `declined` (`POST /v1/tafs/{coach}/components/{id}/answer`).
   That route takes no other answer for a drive card.

There's no standing approval. The next request takes a new card. A new drive ends the
person's earlier one, if any.

**The drive is the chat's that approved it.** `app_look` and `app_act` declare
`agent_session`, which kiff/agents fills with the chat each call comes from (its run's
session), over anything the model put there. The model never sees it, and KIFF forwards it
inside the call's permit. tafs cloud refuses a drive tool whose named chat isn't the drive's.
A call that names no chat (a connection made before the tools declared it) falls back: every
Coach run of that person at work must be in the drive's chat. Either way, another chat, on
another phone or the same one, can't look or act while the drive is on.

## Routes

All of these are the person's, signed in as always:

| Route | What |
|---|---|
| `POST /v1/coach/drive {session, task, component?}` | Start, signed with the approval key (above). `session` is one of the person's chats with Coach (404 otherwise). Answers `{drive: {id, session, task, started}}`. |
| `GET /v1/coach/drive/{drive}/stream` | The drive's requests, on the **streaming** function's address, as server-sent events (below). |
| `POST /v1/coach/drive/{drive}/results/{request}` | The app's answer to one request (below). |
| `POST /v1/coach/drive/pause {drive}` | The person touched the app: pause it ([below](#when-the-person-touches-the-app)). Pausing one already paused changes nothing. |
| `POST /v1/coach/drive/resume {drive}` | The person tapped **Continue**: go on. Resuming one that isn't paused changes nothing. |
| `POST /v1/coach/drive/stop {drive, why}` | End it. `why` is one of the ends below. |
| `GET /v1/coach/drive` | The drive as it stands (`paused`, the time it paused, while it's paused; `ended` with `why` and `words` once over), and its `steps` for the "What I did" card. `{drive: null}` when there's none. |

## The stream

The app opens the stream as soon as the drive starts, and keeps it open while the app is
in front. The stream sends these events:

- `drive {drive}`: first.
- `request {id, tool, args, at, start_within_ms}`: one step for the app to do. Do it, then
  post the result. **Start it within `start_within_ms`, by the app's own clock from when the
  event came, or never:** past that, Coach has given up on it and may have sent its next
  step. Answer a late one `outcome: refused`, `line: "Too late"`, and don't do it. tafs
  cloud never sends a request Coach has given up on, or will within 2 seconds, so a stream
  that reconnects late doesn't replay stale steps.
- `ended {why, words}`: the drive is over. Stop at once: drop the border, the caption and
  the dot, and say `words` in the caption: the end in the person's own words
  ([What the person is told](#what-the-person-is-told)).
- `reopen`: the stream lived its 14 minutes. Open it again.
- A comment line (`: ping`) every 15 seconds.

A drive that ended never comes back. After a restart, the app reads `GET /v1/coach/drive`:
a drive with `ended` is over.

## Requests

`tool` is `app_look` or `app_act`.

### `app_look {tree?, diagnostics?, wait?, timeout_s?}`

- With `wait`, first wait at most `timeout_s` seconds (1 to 20) for that event. The events
  are `screen_shown`, `sheet_opened`, `sheet_closed`, `refreshed`, `prompt_shown`,
  `prompt_done` and `notification_shown`. Then look, and answer `waited {event, came}`.
- Always answer `image`: a screenshot of the app's own windows only.
- With `tree`, answer the view tree too: each element's `id`, `words`, `role`, `bounds`
  (in the screenshot's pixels), `actions` and `confirm: true` for a confirming control.
- With `diagnostics`, answer the app's recent errors and refusals in its own words, and
  failed requests by route template and status, never a body, header, query or token.
  Add the app's version and build, platform, orientation, size class, dark mode and font
  scale.

### `app_act {action, line, at? | element?, to?, ms?, direction?, text?}`

- `line`: the caption to show for this step, at most 60 characters.
- `at`: a point in the last screenshot's pixels. `element`: an id from the last tree.
  Never both.
- **Coordinates:** the screenshot is scaled down, and the tree's `bounds` are in its
  pixels too. The app turns a point into the screen's by multiplying by `screen pixels /
  screenshot pixels`, then sends it to the topmost of its windows that contains the point
  (a sheet or a dialog over the screen), less that window's offset. A contract test pins
  this with a scaled screenshot and a sheet.
- The actions:
  - `tap` and `long_press` at `at` or `element`;
  - `swipe` from `at` to `to` over `ms` milliseconds;
  - `scroll` in `direction`, in `element` when named;
  - `type`: `text` into the focused field;
  - `back`;
  - `done`: the task is done. Answer it. The drive ends whatever the app answers, or if it
    doesn't.
- Before any touch, show the dot at the target for a moment.
- **Hit-test every touch before dispatching it.** One that lands on a control with the
  confirm mark is refused, and nothing is dispatched: `outcome: refused`, `line: "That's
  yours to confirm"`. A `type` into a secret field is refused the same way: `"That's
  yours to fill"`.

## Results

`POST /v1/coach/drive/{drive}/results/{request}` with:

```json
{
  "outcome": "done",
  "line": "Tapped Run now",
  "image": { "media_type": "image/jpeg", "data": "<base64>" },
  "tree": [],
  "diagnostics": {},
  "touched": { "words": "Run now", "taf": "Chargebacks" },
  "waited": { "event": "screen_shown", "came": true }
}
```

- `outcome` is `done` or `refused`, and `line` is the app's own words for what it did or
  why not.
- Every answer carries `image`: the screen after the step. A JPEG about 1,100 pixels on
  its long side fits.

| Field | Limit |
|---|---|
| `image` | 200,000 bytes; larger is 413 |
| `tree` | 60,000 characters as JSON |
| `diagnostics` | 60,000 characters as JSON |

- `touched` is what the step touched, in the words on screen, and the taf whose screen it
  was. It's the record's words ("Coach: Tapping Run now · Run now · Chargebacks").
- Coach's call waits at most 20 seconds for the answer. An answer that comes later still
  makes its step in the record, if the app started it in time, but Coach doesn't read it:
  it looks again.

## Coach never confirms

Enforced in the app, with no prompt:

1. **The confirm mark.** Every control that confirms carries it, and a driven touch on
   one is refused before dispatch. These are:
   - Approve, and any approval tapped inside an open approval window;
   - Disconnect, and a connection's fix (Replace the key, Sign in again), in Connect apps and on a taf's own app;
   - Remove a taf;
   - Halt and resume;
   - the answers to "Did this happen?";
   - extra room;
   - buying a pack or a plan;
   - dismissing a card that signs;
   - deleting the account, a group or a door, and unpairing someone from a door;
   - signing out another device, and removing someone from a group;
   - revoking a Card, forgetting a memory note, installing a version or going back to one;
   - anything else whose words take something away or grant it: on Android a contract
     test reads every control's words (Delete, Remove, Disconnect, Halt, Revoke, Sign
     out, Buy, Resume, Approve, Install, Refuse, Pair, Unpair, Forget, Go back) and fails
     on one without the mark.
2. **The signer's drive rule.** While a drive is on, every confirming signature needs the
   biometric prompt. The approval window is suspended, and the device key's approval and
   answer paths refuse a signature without the prompt.
3. **The keys need the person.** The approval key requires the fingerprint or Face ID on
   each use, and that prompt is the system's window, which no screenshot or driven touch
   reaches.
4. **A secret field is the person's to fill.** `type` into a focused field with the
   secret mark (a vendor's key, a password) is refused, and nothing is typed:
   `outcome: refused`, `line: "That's yours to fill"`. An instruction slipped into what
   Coach reads can't put its own words into a key field.

KIFF still decides every call Coach's tools make, under Coach's Cards: `coach-app-look`
allows 2,000 a day and `coach-app-act` allows 1,000 a day, refused past that.

## Never captured

The screenshot holds the app's own windows only, never the status bar, the keyboard,
other apps or the system's prompts. "The app's own windows" means all of them, composited
in their order. A dialog, a sheet or a popup is a window of its own:

- **Android:** `WindowInspector.getGlobalWindowViews()` lists them, and `PixelCopy`
  copies each that has a `Window`. A menu's popup, which has none, is drawn in software. A
  dialog's dim is drawn under it, as the person sees it.
- **iOS:** each of the scene's windows, drawn with `drawHierarchy`.

These are blanked in it:

- a field marked secret: a vendor's key, a password, a recovery code;
- an open approval's terms. Coach can't act on them, and the tree names only the
  approval's title ("An approval is open: Stripe for Refunds").

Each platform's contract test fails on a secret field or a confirming control without
its mark.

The border, the caption and the dot are drawn in a window of their own, which the
capture leaves out, or are hidden for the frame it takes. Drawn in the screen's own
window, they would be in the screenshot on both platforms.

## When the person touches the app

The person's own input never waits for Coach, and Coach never acts under it. A touch pauses the
drive; only a clear takeover ends it.

- **Any touch, key or gesture of the person's pauses the drive at once, and is delivered.** It
  does what it would do with no drive on: a button acts, a row opens, a field takes focus.
  - **The app's own pause comes first.** From the person's first touch, the app refuses every
    request of the drive with `outcome: refused`, `line: "Paused"` until Continue, whatever
    tafs cloud says. That covers a request already on the stream, or one sent before tafs cloud
    has the pause.
  - The app posts `POST /v1/coach/drive/pause {drive}`. If that fails, the app keeps its own
    pause and posts it again; only Continue (`resume`) lifts it.
  - The step under way stops before its next event. A step that dispatched nothing yet answers
    `outcome: refused`, `line: "Paused"`; one that already acted answers as it would have.
  - Coach's events are never mixed into the person's: the event being dispatched when they
    touch finishes first.
- **While it's paused:**
  - The border dims, and the caption reads **Paused · Continue / Stop**.
  - tafs cloud sends the app no request. Coach's look or act is answered at once with "The
    person is using the app", and tafs cloud parks Coach's run the way a held call does: paused,
    waiting on an event, so no invocation runs and no turns or credits are used while the person
    has the app.
  - `resume`, or the drive's end, is that event: the run goes on, and its first step is a look.
  - The person uses the app as usual, and every touch of theirs is delivered.
- **Continue** puts the keyboard away first: the field keeps its text and loses focus, so nothing
  the keyboard commits later reads as the person's typing. Then the app posts
  `POST /v1/coach/drive/resume {drive}`, and the caption goes back to Coach's line. Coach's first
  step after it is a look: tafs cloud refuses an `app_act` until Coach has looked again, since
  the screen may have changed.
- **Stop** ends the drive as `stopped`.
- **A pause counts as nothing done:** paused for 15 minutes, the drive ends as `idle`.
- **A halt while paused ends the drive at once** as `halted`. tafs cloud settles the parked run,
  which stops as halted, without waiting for Coach's next call.
- **Only the drive's own person** can pause or resume it. Anyone else's `pause` or `resume` is
  refused with 404, as another person's drive is.

**A clear takeover ends the drive,** paused or not. Coach then says it's letting the person
carry on.

| The person | Ends as |
|---|---|
| goes back: the back gesture, a back key or button | `owner_took_over` |
| types in a field (the change is kept) | `owner_took_over` |
| taps a control for the third time since the drive paused, with no Continue between | `owner_took_over` |
| leaves the app: Home, Recents, a vendor's sign-in, the share sheet | `left_app` |
| taps Stop on the caption, or the Coach button | `stopped` |

A tap on a control is one delivered to something that acts: a button, a row, a toggle, a link,
a tab. A scroll, a swipe that only scrolls, and a touch on empty space don't count. The tap that
paused the drive counts as the first when it landed on a control. The count starts again from
zero at Continue.

## When a drive ends

The app posts `POST /v1/coach/drive/stop {drive, why}` and stops at once when:

| `why` | When |
|---|---|
| `owner_took_over` | A clear takeover ([above](#when-the-person-touches-the-app)): the person's back, their typing in a field, or their third tap on a control since the drive paused. That input is delivered. |
| `left_app` | The app leaves the foreground: a vendor's sign-in, a permission dialog, the share sheet, Recents, Home. |
| `stopped` | The Coach button, or the caption's Stop. |
| `locked` | The screen locks. |
| `signed_out` | The person signs out. |

tafs cloud ends a drive itself in these cases:

| `why` | When |
|---|---|
| `halted` | The account is halted, seen at Coach's next call, or at once while the drive is paused. |
| `done` | Coach's `done`. |
| `idle` | Nothing was done for 15 minutes, a pause included. |
| `replaced` | Another drive started. |

### What the person is told

`words`, in the stream's `ended`, the stop's answer and `GET /v1/coach/drive`, is the end in
the person's own words: the caption says it, and the "What I did" card says why it ended.
They're the words an app keeps for an end whose words didn't come, so the caption reads
the same either way. Coach, and the account's events, have their own words for it.

| `why` | `words` |
|---|---|
| `owner_took_over` | You took over, so Coach let you carry on |
| `left_app` | The app left the screen, so Coach stopped |
| `stopped` | You stopped Coach |
| `locked` | The phone locked, so Coach stopped |
| `signed_out` | You signed out, so Coach stopped |
| `halted` | The account is halted, so Coach stopped |
| `done` | Coach is done |
| `idle` | Nothing was done for 15 minutes, so Coach stopped |
| `replaced` | Another drive started |

### How each platform tells the person's input from Coach's

- **Android:**
  - **Every window of the app is guarded,** not only the activity's. The activity's `Window.Callback` is wrapped. So is each dialog's and sheet's, since every sheet, dialog, date picker and menu opens through one wrapper (`DriveSheet`, `DriveAlert`, `DriveDialog`, `DriveDatePicker`, `DriveMenu`). A test fails the build on a raw one.
  - **An event is Coach's only when the app is dispatching it right then,** matched by the `downTime` it created. Anything else is the person's: the guard pauses the drive, then hands the event on as usual. That includes a touch on a sheet's scrim, which the window's callback has before any view. A menu's popup has no `Window`, so a guard on its content catches touches inside it. A touch outside it reaches the menu's dismissal, which runs inside that touch's dispatch: one that isn't Coach's pauses the drive and dismisses the menu, even while one of Coach's steps is under way (that step stops, as above).
  - **A tap on a control** is counted when the person's up event lands on a node with a click action, by the same hit-test Coach's touches go through.
  - **The person's back** (a key, or the back gesture, through an `OnBackInvokedCallback` at overlay priority from Android 13) ends it as `owner_took_over`, and goes back. Their touch on the Coach button or on the caption's Stop ends it as `stopped`. A dismissal Coach didn't make, if one gets past the guard, pauses it.
  - **Coach's own `back`** is the topmost window's own dispatcher: a sheet or a dialog closes, a menu folds, a screen goes back. On the first screen it's refused ("Nothing to go back to"), since back there would leave the app.
  - **The drive ends in `ON_PAUSE`:** a Custom Tab, a system dialog, the share sheet, Recents and Home all pause the activity, and a translucent one (a permission dialog, the share sheet) never stops it. The app's own dialogs and sheets don't pause it. The screen turning off ends it as `locked`.
  - **Volume keys stay the person's** and don't pause the drive.
- **iOS:**
  - Coach's steps never come through the touch stream: they're the app's registered actions and `insertText`. So any `UITouch`, key press or gesture is the person's.
  - **The overlay takes no touch** but its caption's Continue and Stop: it draws above the app's other windows and passes every other touch through (its `hitTest` returns nil). The app's windows each carry a passive recognizer that sees every touch as it begins, without delaying or taking it (`cancelsTouchesInView` off, recognizing alongside every other recognizer), so the person's first touch pauses the drive and still reaches the control under it, edge swipes included. A window that opens mid-drive, such as a menu's, gets one too.
  - **A tap on a control** is counted when the registry has an element with a tap action under the touch, and the finger lifts within 12 points of where it went down (`DriveSession.isControl`). A field isn't one: a tap on it only takes the focus.
  - The caption's Stop ends the drive as `stopped`, and so does the Coach button, by its own action once the touch reaches it. A back swipe from the edge, or a navigation back button, ends it as `owner_took_over`, and goes back: the app sees it as a navigation stack that is shallower once the touch has settled (`DriveHands.wentBack`). A sheet that closes took its stack with it, so closing one only pauses.
  - **A request refused as "Paused" carries no screen:** the person is using the app, and Coach looks again after Continue.
  - **A `pause` or `resume` that answers `ended`** ends the drive in the app with those words. **One answered 404** means the drive isn't there for this person any more: the app posts nothing more, ends the drive with the end `GET /v1/coach/drive` gives for it, else its own "Coach stopped", and never as `owner_took_over`. A network error or a 5xx is posted again, at 1, 2, 4, then every 8 seconds, while the app keeps its own pause.
  - The drive ends on `willResignActive` or `sceneDidEnterBackground` (`left_app`), and when protected data becomes unavailable (`locked`).

### Typing

- **The keyboard stays down while Coach drives.** Coach types through the focused field itself: on Android, its `InsertTextAtCursor` or `SetText` semantics; on iOS, `insertText`. An open keyboard could commit text after a step ended, such as an autocorrection or a finished composition, which would read as the person's.
- **A field's change while none of Coach's steps is under way is the person's:** it's kept, and it ends the drive as `owner_took_over`. Every text field's changes go through one check (`driveTyped` on Android). A test fails the build on a field without it. A value the app sets itself, such as a restored draft, isn't a change. Tapping into a field only pauses the drive; the keyboard then comes up as usual.

## What each app sends

### The tree

A flat JSON array, the lowest window first, so the topmost window's nodes come last. A node is left out when it has neither words nor actions.

```json
{"id": "0.12", "words": "Run now", "role": "button", "bounds": {"x": 40, "y": 610, "w": 180, "h": 44}, "actions": ["tap"], "confirm": true}
```

| Field | What |
|---|---|
| `id` | Unique in the tree. `element` names it in the next `app_act`. |
| `words` | The words on screen. A secret field is "Secret field", with no value. An open approval is a single node, "An approval is open: <title>", and none of its insides. |
| `role` | `button`, `tab`, `text_field`, `toggle`, `checkbox`, `radio`, `image`, `text`, `scroll` or `menu`. |
| `bounds` | In the screenshot's pixels, from the top left. |
| `actions` | Some of `tap`, `long_press`, `swipe`, `scroll`, `type`. |
| `confirm` | Only on a confirming control, or one inside a container that confirms. |

### Diagnostics

```json
{"app": {"version": "25", "build": "250", "platform": "android 16", "orientation": "portrait", "size_class": "compact", "dark": false, "font_scale": 1.0},
 "errors": [{"at": "2026-11-06T01:20:00Z", "words": "Coach can't drive: ..."}],
 "requests": [{"at": "2026-11-06T01:20:00Z", "route": "GET /v1/tafs/{id}/work", "status": 500}]}
```

- The newest 30 of each, in memory only.
- A route keeps only its own words, with every other part as `{id}`, and never a query.

### The app's lines

The same words on both apps.

| When | `line` |
|---|---|
| A look | "Looked"; "Looked once screen_shown came"; "Looked: sheet_closed didn't come in time" |
| A tap, a long press | "Tapped Run now", "Long-pressed Refunds" (the words of the control under the point) |
| A swipe, a scroll | "Swiped", "Scrolled down" |
| Typing | "Typed into Message" (the field's own words) |
| Back, done | "Went back", "Done" |
| Refused | "That's yours to confirm"; "That's yours to fill"; "Too late"; "Nothing Coach can use there"; "That isn't on screen now"; "No field is focused"; "Nothing to go back to"; "Can't scroll down there"; "This app can't do that step" |

A scroll's `direction` is where the content comes from: `down` shows what's below.

### The caption

- **While Coach acts:** Coach's `line`.
- **Before its first step:** "Coach is driving: <task>".
- **While it's paused:** "Paused", with **Continue** and **Stop**.
- **The card that asks to drive** says what a touch does: "… It never confirms anything. Touch the
  app to pause it, or tap Stop to end it." (`drive.card_note`, `drive.card_note_scrolls`, in each
  app's strings).
- **Once it's over:** `words`, for 3 seconds. When tafs cloud's words didn't come, the app's own words for the same end: "You took over, so Coach let you carry on", "The app left the screen, so Coach stopped", "You stopped Coach", "The phone locked, so Coach stopped", "You signed out, so Coach stopped", "The account is halted, so Coach stopped", "Coach is done", "Nothing was done for 15 minutes, so Coach stopped", "Another drive started".

### How each app sees and acts

- **Android:**
  - **The tree** is Compose's semantics per window (`RootForTest.semanticsOwner`), the tree TalkBack and the UI tests read.
  - **The marks** are semantics properties: `Modifier.confirms()`, `Modifier.secret()` (a `password()` field counts too) and `Modifier.approvalTerms(title)`. Everything behind `ApprovalGate`, which holds what the approval key signs, carries the confirm mark.
  - **A touch** is `MotionEvent`s dispatched to the topmost window under the point, after the hit-test.
  - **Scrolling** is `ScrollBy`.
  - **The border, the caption and the dot** are application windows of their own: the glow and the dot take no touch, and the caption takes only its own. The capture and Coach's touches leave them out.
- **iOS:** with VoiceOver off, the platform builds no accessibility tree. So the app keeps a registry of what Coach can use: each control registers its id, words, role, frame, actions and marks (`.drivable`; the app's button styles register every button). That registry is the tree.
  - A gesture runs the registered action of the element under the point:
    - tap: activate;
    - long press: its menu;
    - swipe: a row's action, or a scroll;
    - back: the top sheet's dismiss, or the navigation's pop.
  - A point with no registered action is refused ("Nothing Coach can use there").
  - **What isn't in the registry, Coach can't touch.** Alerts, confirmation dialogs, menus, context menus and a row's swipe actions are never registered, so whatever confirms inside them is out of Coach's reach. While one is open, Coach's back is its Cancel. Some settings rows, toggles, pickers and toolbar buttons aren't registered yet, and are refused the same way.
  - **Text fields** are found on screen by their text views, named by their placeholder. A secure field is secret.
  - **Words:** a control that registered none is read from the screenshot.
  - **A test fails the build** on a listed confirming control without its mark, and on any control whose words confirm without it, string constants included.
  - The overlay window draws the border, the caption and the dot, and the capture leaves it out.
  - **Locking:** iOS doesn't say a lock apart from leaving the app. When the app resigns active, it reports `locked` if protected data is unavailable within 0.6 s, and `left_app` otherwise. A real lock may read as `left_app`; either way the drive ends.
  - **iOS has no approval window,** so the signer's drive rule there is Face ID or Touch ID on every approval, with no reuse of a recent unlock.

### What the app reports, and when it starts

- **The events a look can wait on:**
  - `screen_shown`: another screen, tab, sheet or Coach's chat;
  - `sheet_opened` / `sheet_closed`: any sheet, dialog or menu;
  - `refreshed`: a refresh ended;
  - `prompt_shown` / `prompt_done`: the system's fingerprint or Face ID prompt;
  - `notification_shown`.
- **One request at a time,** in the order they came. Each is checked against `start_within_ms` when it starts.
- **After the app restarts,** a drive still on at tafs cloud ended with the app, so the app posts its stop as `left_app`.
- **While Coach drives,** its chat minimizes into the Coach button, which shows it's acting. At the end the chat opens again, with the drive's end in the thread at its time: the task, why it ended, and each step (a `drive` item, kiff/tafs#568). No card of the app's own follows the thread.

## What tafs cloud records

- **The drive's steps:** each `app_act` the app did, with its caption and what it
  touched (`GET /v1/coach/drive`). A step again under the same caption is the same
  step gone on: one line, with every touch of it in order ("Setup, Card").
- **The drive's end, in Coach's chat:** when it ends, once, the request, why (in the
  person's words) and the steps, kept 30 days with the chat. The thread shows it at
  its time (`{type: "drive", drive}` in `items`, [chat.md](chat.md)).
- **The account's events:** `drive_started`, `drive_step` (a step that touched
  something) and `drive_ended`, each with its own `title` and `detail` in tafs cloud's
  words, so an app shows them without new words of its own.
- **Coach's session:** each tool call and its result, as for every Coach tool.
