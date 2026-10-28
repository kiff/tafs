# Held calls and approvals

When a taf asks for something outside its Card, KIFF holds the call. Nothing is sent
until the owner answers, and nobody answering means refused. This page covers how a held
call reaches the owner, how an answer is signed, and what happens next. Why KIFF decides
every action, and the first held calls on a phone, are in
[kiff-decides.md](kiff-decides.md).

Code: in the app, `NeedsYouView` (`HeldCallCard`, `AlertsCard`, `AlertsOfferSheet`),
`HaltScreen`, and the "Owner answers", Alerts and Halt sections of `AppModel`;
`Services/ApprovalSigner.swift`, `AlertAnswers.swift`, `HoldNotification.swift`,
`LockScreen.swift`, `PushDelegate.swift`, `HaltAllIntent.swift`; `KIFFClient`'s
card-exception routes. In tafs cloud, `runtime/src/cloud/push.ts`, `forward.ts`,
`accounts.ts`, `levels.ts`, and `alert`, `push`, `alerts`, `goOn` and `haltRoute` in
`handler.ts`.

## Where held calls come from

The app reads the held calls from KIFF itself, with the person's session
(`GET /v1/me/card-exceptions?status=held`), with amounts in the unit KIFF declares. tafs
cloud never vouches for one. A held call shows:

- in **Needs you**, the tab of everything waiting;
- in the taf's **chat**, where the taf made it ([chat.md](chat.md#held-calls-in-the-chat));
- on the **lock screen**: a notification for each new one, and a Live Activity counting
  down the first one, in the Dynamic Island too (`LockScreen`).

Each card says what the taf asks (the action, the amount and on what), why KIFF held it,
and how long is left before it lapses.

## Answering

| Answer | When it's offered | What KIFF does |
|---|---|---|
| **Approve once** | The call is over a per-call limit, so approving this one is enough | Sends this call, once |
| **Refuse** | Always | Refuses it; nothing is sent |
| A **next step** | When the hold offers some | Refuses it, with the step for the taf |
| **Extra room** | The call is over a total the Card keeps across calls (a day's), and the person manages the account's Cards | Gives the Card just the room this call needs, until that total resets |

Over a total, approving once can't pass it, and the card says so. Someone who may use
Coach also gets "Change the limit in Coach mode", which opens Coach on that taf
([coach.md](coach.md#improving-a-taf)).

Only someone whose role answers held calls (`holds.answer`) gets the buttons. Anyone else
on the account who sees the hold sees the card with no Approve or Refuse, and a line
saying someone who approves on the account answers it (`AppModel.mayAnswerHolds`). If
KIFF still refuses an answer for the role (`role_forbidden`), the card says so in words.

Coach's own held call to install a version is answered by installing it from its card:
the version goes live, then Coach hears.

### How an answer is signed

Every answer is signed on the phone and counts only once KIFF verifies the signature
against the phone's key (`ApprovalSigner`). A server never vouches for an owner's answer.

- The message is KIFF's `kiff.answer.v1`: the account, the hold, the answer
  (`approve-once`, `reject` or `change-card`), the next step if any, the hold's version as
  the owner saw it, and when it was signed (`KIFFClient.answerMessage`).
- On an iPhone or iPad the phone holds **two keys** in the Secure Enclave:
  - the **current key** has no access control. It signs what reads or takes authority
    away: Refuse, Halt all, Coach's and planque's requests.
  - the **approval key** signs only what grants authority: Approve, Extra room, issuing a
    Card, starting a trial, Resume. Its own access control requires Face ID, Touch ID or
    the passcode, so the key checks the prompt, not the app's code. One confirmed Face ID
    covers one confirmed step.
- KIFF holds both keys for the session: the current one from sign-in, the approval key
  registered once (`POST /v1/me/device/approval-key`). If KIFF holds an approval key
  that isn't this phone's, approving waits for a new sign-in.
- A held call is answered on the phone only when KIFF checks the phone's signature on it:
  the session holds the phone's key, and the hold says which version the owner saw
  (`canAnswer`). Otherwise the card links to answering it in KIFF.
- KIFF may refuse one person's answer for good, for example approving a call they asked
  for themselves. The card then says why in words, never a code, and they may still
  refuse it (`refusalWords`, `refusesOnly`).

The simulator has no Secure Enclave; it uses a software key, fine for a demo and never
for production.

## After the answer

1. The answer goes to KIFF: `POST /v1/me/card-exceptions/{id}/approve-once`, `reject` or `change-card`, with its signature.
2. The app asks tafs cloud to continue the paused run at once
   (`POST /v1/tafs/{id}/holds/{exception}/continue`). kiff/agents asks KIFF for the
   answer itself, so this only asks sooner; it vouches for nothing.
3. KIFF's signed alert also reaches tafs cloud, which hands it to kiff/agents as KIFF
   signed it. kiff/agents checks it again and the run goes on with the approved call,
   sent once. If kiff/agents doesn't take it, tafs cloud answers 502 so KIFF delivers it
   again, up to 5 times; after that, the run goes on when the hold expires, as without an
   answer (`alert`, `forward.ts`).
4. The decision stays where the card was, in the chat and in the Record:
   "Approved once by you · 22:41 · Refund €150.00".

## Alerts while tafs is closed

KIFF tells tafs cloud when a call is held, and tafs cloud tells the account's phones
through Apple push (`runtime/src/cloud/push.ts`):

1. The phone registers the account's **alert target** in KIFF, pointed at
   `/v1/kiff/alerts`, with a signed setup call (one Face ID). KIFF shows the target's
   signing secret once, to the phone.
2. The phone hands tafs cloud its push token and that secret (`POST /v1/push`). The
   secret checks KIFF's deliveries; the token is where they go. An account gets alerts on
   50 phones at most. A phone can also hand over the target alone (`POST /v1/alerts`), so a
   paused run hears the answer whether or not a phone turned on alerts.
3. KIFF posts each held call, signed. tafs cloud checks the signature against the
   account's secrets, then pushes a visible alert when a call is held, and a silent one
   when it is answered so the phone clears its alert.

Only phones of people who may answer held calls (`holds.answer`) are alerted, and only
they may hand over the target's secret.

**What an alert says.** KIFF's alert carries no request parameters, so the push says only
"A taf needs you" and the action. On the phone, the notification service extension reads
the hold from KIFF with the session the app keeps and fills in the taf, the amount and on
what, and when it lapses, in the account's time zone (`HoldNotification`). Whatever it
can't read leaves the push as sent. iOS's Show Previews setting applies as usual.

**Answering from the notification** (`AlertAnswers`): both need the device unlocked.
Refuse is signed and sent from the notification, with no Face ID: it only takes
authority away. Approve opens tafs on that hold, and is signed only once Face ID
confirms it there; the unlock that opened the app counts as that Face ID, for that one
answer.

Alerts are offered once, right after sign-in, before the first taf works
(`AlertsOfferSheet`); Needs you offers them again while they're off.

### Notifications

How loudly each notification reaches the owner is decided in tafs cloud as it's sent
(`runtime/src/cloud/levels.ts`):

| Level | For | How |
|---|---|---|
| interrupt | a hold waiting for the owner, a halt | Time Sensitive: comes through a Focus |
| quiet | a task finished, a run that ended, a request answered | No sound or banner; the badge and the chat line update |
| later | anything not urgent while the owner is away | Nothing is pushed: it waits in Coach's chat, gathered into one "While you were away" message |

**Sound**, in Settings under **Notifications & haptics** (`NotificationSettingsView`), is
on by default and set for each device. Off, the phone says so when it registers
(`POST /v1/push` with `sound: false`), and tafs cloud sends every alert to that device
quietly (`silencing` in `push.ts`): it still arrives in Notification Center and Needs you,
with no sound, banner or Time Sensitive. **Haptics**, on the same screen, turns off the
app's taps on that device (`Haptics.enabled`).

## Halting

**Halt** takes all authority from one taf, or with none chosen from every taf, at once
(`HaltScreen`, `/v1/halt` and `/v1/tafs/{id}/halt`):

- It's one tap, no Face ID: it only takes authority away.
- tafs cloud halts it in KIFF and kiff/agents together, and every phone that answers
  hears it through a Focus. It reads **Halted** only once kiff/agents, KIFF's gateway and
  planque all hold it, **Halting…** until then.
- **Resume** is signed with Face ID in KIFF first; tafs cloud resumes kiff/agents only once
  KIFF says it runs again. What the taf was doing before the halt doesn't come back.
- "Halt all tafs" works from Siri and Shortcuts too (`HaltAllTafsIntent`), with the
  device unlocked. It says what happened, and when it can't finish it opens tafs on Halt
  all. Resume stays in the app.
- Halts and resumes show in Activity (`GET /v1/events`).
