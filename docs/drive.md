# Coach drives the app

Coach can operate the tafs app on the owner's phone while they watch, one step at a
time, the way a person debugging the app over USB does: it looks at the screen, then
taps, types, swipes or scrolls inside the app. It never confirms anything. This page
is the contract between tafs cloud and the apps (kiff/tafs#544). Both apps follow it,
and each has a contract test against it.

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

**The drive is the chat's that approved it.** Coach's calls don't say which chat they come
from. So tafs cloud refuses a drive tool unless every Coach run of that person at work is
in the drive's chat. Another chat, on another phone or the same one, can't look or act
while the drive is on.

## Routes

All of these are the person's, signed in as always:

| Route | What |
|---|---|
| `POST /v1/coach/drive {session, task, component?}` | Start, signed with the approval key (above). Answers `{drive: {id, session, task, started}}`. |
| `GET /v1/coach/drive/{drive}/stream` | The drive's requests, on the **streaming** function's address, as server-sent events (below). |
| `POST /v1/coach/drive/{drive}/results/{request}` | The app's answer to one request (below). |
| `POST /v1/coach/drive/stop {drive, why}` | End it. `why` is one of the ends below. |
| `GET /v1/coach/drive` | The drive as it stands (`ended` with `why` and `words` once over), and its `steps` for the "What I did" card. `{drive: null}` when there's none. |

## The stream

The app opens the stream as soon as the drive starts, and keeps it open while the app is
in front. The stream sends these events:

- `drive {drive}`: first.
- `request {id, tool, args, at}`: one step for the app to do. Do it, then post the result.
- `ended {why, words}`: the drive is over. Stop at once: drop the border, the caption and
  the dot, and say `words` in the caption.
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
  - `done`: the task is done. Answer it, then the drive ends.
- Before any touch, show the dot at the target for a moment.
- **Hit-test every touch before dispatching it.** One that lands on a control with the
  confirm mark is refused, and nothing is dispatched: `outcome: refused`, `line: "That's
  yours to confirm"`.

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
- Coach's call waits at most 20 seconds for the answer. A late answer is dropped, and
  Coach looks again.

## Coach never confirms

Enforced in the app, with no prompt:

1. **The confirm mark.** Every control that confirms carries it, and a driven touch on
   one is refused before dispatch. These are:
   - Approve, and any approval tapped inside an open approval window;
   - Disconnect;
   - Remove a taf;
   - Halt and resume;
   - the answers to "Did this happen?";
   - extra room;
   - buying a pack;
   - dismissing a card that signs.
2. **The signer's drive rule.** While a drive is on, every confirming signature needs the
   biometric prompt. The approval window is suspended, and the device key's approval and
   answer paths refuse a signature without the prompt.
3. **The keys need the person.** The approval key requires the fingerprint or Face ID on
   each use, and that prompt is the system's window, which no screenshot or driven touch
   reaches.

KIFF still decides every call Coach's tools make, under Coach's Cards: `coach-app-look`
allows 2,000 a day and `coach-app-act` allows 1,000 a day, refused past that.

## Never captured

The screenshot holds the app's own windows only, never the status bar, the keyboard,
other apps or the system's prompts. "The app's own windows" means all of them, composited
in their order. A dialog, a sheet or a popup is a window of its own:

- **Android:** `WindowInspector.getGlobalWindowViews()` lists them, and `PixelCopy`
  copies each that has a `Window`.
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

## When a drive ends

The app posts `POST /v1/coach/drive/stop {drive, why}` and stops at once when:

| `why` | When |
|---|---|
| `owner_took_over` | The person touches the app themselves: any touch, key or gesture the drive didn't dispatch. That input isn't delivered. |
| `left_app` | The app leaves the foreground: a vendor's sign-in, a permission dialog, the share sheet, Recents, Home. |
| `stopped` | The Coach button, the border's Stop, or the person's back gesture. |
| `locked` | The screen locks. |
| `signed_out` | The person signs out. |

tafs cloud ends a drive itself in these cases:

| `why` | When |
|---|---|
| `halted` | The account is halted, seen at Coach's next call. |
| `done` | Coach's `done`. |
| `idle` | Nothing was done for 15 minutes. |
| `replaced` | Another drive started. |

### How each platform tells the person's input from Coach's

- **Android:** catch every event on **every** window of the app, not only the
  activity's. A dialog, a sheet and a popup have their own `Window.Callback`; wrap each,
  from `WindowInspector.getGlobalWindowViews()`. An event is Coach's only when the app is
  dispatching it right then, matched by the `downTime` and `eventTime` it created.
  Anything else is the person's, and isn't delivered. End the drive in `ON_PAUSE`: a
  Custom Tab, a system dialog, the share sheet, Recents and Home all pause the activity,
  and a translucent one (a permission dialog, the share sheet) never stops it. The
  app's own dialogs and sheets don't pause it.
- **iOS:** Coach's steps never come through the touch stream (accessibility activation,
  `insertText`), so any `UITouch`, key press or gesture is the person's. End the drive on
  `sceneDidEnterBackground` or `willResignActive`.

## What tafs cloud records

- **The drive's steps:** each `app_act` the app did, with its caption and what it
  touched, for the "What I did" card (`GET /v1/coach/drive`).
- **The account's events:** `drive_started`, `drive_step` (a step that touched
  something) and `drive_ended`, each with its own `title` and `detail` in tafs cloud's
  words, so an app shows them without new words of its own.
- **Coach's session:** each tool call and its result, as for every Coach tool.
