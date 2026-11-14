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
and how long is left before it lapses. While the call waits, the card says "Nothing is
sent until you approve.", and so do its notification and VoiceOver's reading of it. The
taf's own message about the hold leaves that out: it's said where the owner answers.

## Answering

| Answer | When it's offered | What KIFF does |
|---|---|---|
| **Approve once** | The call is over a per-call limit, so approving this one is enough | Sends this call, once |
| **Refuse** | Always | Refuses it; nothing is sent |
| A **next step** | When the hold offers some | Refuses it, with the step for the taf |
| **Extra room** | The call is over a total the Card keeps across calls (a day's), and the person manages the account's Cards | Gives the Card just the room this call needs, until that total resets |

**Over a total and a limit for one call, it takes two answers,** as KIFF keeps them: extra
room raises the total and doesn't itself pass the limit for one call. KIFF keeps the hold's
id: after extra room it's `changed`, and when the taf tries the call again it's back as
`held`, over the limit for one call only. The card then says plainly that the first step
went through and why one more is needed, in KIFF's own words (`reason_text`): "Extra room
went through. This call is held again for a limit of its own: €60 is over the €50 limit
for one call. Approve it once to let it go, or refuse it." (Android: `SecondStep`,
`roomGiven`). A held call's reason is KIFF's `reason_text` wherever KIFF sends it. Where KIFF
sends none, the app writes it, never the machine's reason: money in the app's money words, and
a count of calls as "Each call asks you first" for a limit of 0, otherwise "20 calls a day:
this would be the 21st", in each window's words (`KiffMapping.countReason`). A call Coach's
own agent holds is titled **Coach**, with Coach's mark, never "A taf" (`AppState.holder`).

Over a total, approving once can't pass it, and the card says so. Someone who may use
Coach also gets "Change the limit in Coach mode", which opens Coach on that taf
([coach.md](coach.md#improving-a-taf)).

Only someone whose role answers held calls (`holds.answer`) gets the buttons. Anyone else
on the account who sees the hold sees the card with no Approve or Refuse, and a line
saying someone who approves on the account answers it (`AppModel.mayAnswerHolds`). If
KIFF still refuses an answer for the role (`role_forbidden`), the card says so in words.

Coach's own held call to install a version is answered by installing it from its card:
the version goes live, then Coach hears. On Android it takes one fingerprint, whose prompt
says "as Coach asked":

- **With a Card:** the draft's confirm names the held call
  (`{"version", "hold": {"id", "accounting_version"}}`), and KIFF approves it under the
  confirm's signature, answering `hold: {id, status}`. With `approved`, nothing else is
  signed or sent. Any other status, or no `hold` at all from a KIFF that ignores it, and the
  call is answered as any other once the version is live, behind its own fingerprint. KIFF
  takes the hold only with the approval key, so a confirm signed inside a window has no
  prompt of its own, and the call's is the only one.
- **Without a Card:** the approval of Coach's call is signed before the version goes live,
  and sent once it is. Backing out at the prompt leaves the live version and Coach's call
  as they were.

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
    Card, starting a trial, Resume, sharing, connecting a tool, starting Coach. Its own
    access control requires the faces or fingerprints enrolled when it was made
    (`.biometryCurrentSet`), never the passcode, so the key checks the prompt, not the app's
    code; each of those steps' prompts offers Face ID or Touch ID only. One confirmed Face ID
    covers one confirmed step. The screen lock never approves.
  - a face or fingerprint enrolled since the key was made ends it for good. The app finds
    that with no prompt, at each refresh: the enrolled set (`evaluatedPolicyDomainState`,
    saved with the key) differs. Only two known values that differ count, so a lockout after
    a few misses, or a face not available, leaves a good key alone. It then makes a new key
    and says so on the home screen and in Needs you, named by what this device asks for
    (`approval.key_gone_biometry`): "Your Face ID changed or wasn't recognized, so this device
    can't approve until you approve it again in your browser", with **Approve again in your
    browser**, in place of Approve; Refuse stays. While the device itself can't approve (Face ID
    off for tafs, none enrolled), its own words show instead, once (`showsApprovalKeyLost`):
    the two never show together.
  - **re-linking, with no sign-out** (`AppModel.startRelink`): the new key signs KIFF's
    `kiff.approval-key-relink.v1` message (the account, the SHA-256 of the device key and of
    the new key, the time) under Face ID or Touch ID, and the device key signs
    `POST /v1/me/device/relink`. The phone shows the code and opens KIFF's page, where the
    person approves it, only with the phone in hand. It polls `device/token` until
    `{"status":"relinked"}`, then approves with the new key. The session, its alerts and its
    list stay. "Waiting for your browser…" has **Cancel**. A KIFF without re-link (404) says to
    sign in again from Settings, and **Sign in again instead** stays under the line. A prompt closed unrecognized,
    while the key is still good, says "Try again, or sign in again if your Face ID
    changed" (`approval.not_recognized_biometry`).
  - the approval key is kept under `tafs.approval-key.v2`. The first key, under
    `tafs.approval-key`, also took the passcode; the app deletes it, so each iPhone and iPad
    signs in again once before approving. Its first launch finds that KIFF holds the old key
    (`registerApprovalKey` gets 409 with another fingerprint) and says so before any tap.
- On an Android phone the two keys are in the Android Keystore (StrongBox, else the TEE),
  and the approval key is stricter (`KeystoreKeys`, `DeviceSigner`):
  - it signs **once per fingerprint**: timeout 0, a strong biometric only, unlocked through
    `BiometricPrompt` with the signature as its `CryptoObject`. Unlocking the phone counts
    for nothing, and the screen lock never approves.
  - every request that grants authority asks for its own fingerprint, with the step's
    words. Approve, Resume, sharing a taf, starting a trial and installing a version with
    its Card each take one. Installing Coach's held version takes one too (see above; two
    with a KIFF that doesn't take the hold with the Card), and starting Coach the first time takes its "Connect your apps"
    confirmation and a fingerprint for each of its two drafts, then signs the connect batch
    tafs cloud gave and asks a fingerprint for Coach's Card ("Give Coach its Card").
  - a phone with no strong biometric enrolled has no approval key and approves nothing: it
    says to add a fingerprint. Refusing still works.
  - adding a fingerprint turns the key off for good. The phone makes a new one and says it
    can't approve until a new sign-in. **Sign in again** in Settings ends the session at
    KIFF first, the old approval key with it, then signs out; the next sign-in, approved
    in the browser, registers the new key as a new device.
  - **said at launch, not after a tap** (Android): the app checks the key at start, with no
    prompt (`ApprovalSigning.approvalKeyUsable`). When it's off, or KIFF holds another, the home
    screen, Needs you and the approval cards say "Your fingerprints changed, or none was
    recognized, so this phone can't approve until you sign in again", with **Sign in again**,
    in place of a button that would fail; Refuse stays. A prompt closed without a recognized
    finger, while the key is still good, says "Try again, or sign in again if your fingerprints
    changed". The screen lock never approves, on either app.
  - **Approve again in your browser** (Android, `AppModel.relink`): where KIFF can re-link the
    phone, the line says "…so this phone can't approve until you approve it again in your
    browser", with that button and **Sign in again instead** under it. Its new approval key
    (already made when the old one was turned off) proves itself first, behind one fingerprint,
    and the device key signs the request, `POST /v1/me/device/relink`. The phone then shows KIFF's
    code ("approve only with this phone in your hands") with **Open KIFF to approve** and Cancel,
    and polls `device/token` until `relinked`. It counts only if KIFF's fingerprint is the new
    key's and the key still signs. The session, its alerts and its list stay; nothing is signed
    in anew. A code that expires, or isn't approved, leaves the phone as it was and says so; a
    KIFF without the route (404) leaves **Sign in again**. A phone with no fingerprint is told to
    add one, and no browser opens. iOS does the same.
- KIFF holds both keys for the session: the current one from sign-in, the approval key
  registered once (`POST /v1/me/device/approval-key`), or replaced by a re-link the owner
  approved in the browser. If KIFF holds an approval key that isn't this phone's, approving
  waits for Approve again or a new sign-in.
- A held call is answered on the phone only when KIFF checks the phone's signature on it:
  the session holds the phone's key, and the hold says which version the owner saw
  (`canAnswer`). Otherwise the card links to answering it in KIFF.
- KIFF may refuse one person's answer for good, for example approving a call they asked
  for themselves. The card then says why in words, never a code, and they may still
  refuse it (`refusalWords`, `refusesOnly`).

The simulator has no Secure Enclave; it uses a software key, fine for a demo and never
for production.

### One fingerprint, then taps: the approval window

KIFF keeps an approval window (kiff/tafs#494): one fingerprint opens it, and for a few
minutes a routine approval is a tap. Each approval is still signed on the phone, checked and
recorded; only the prompt goes. Which acts are routine is KIFF's and planque's to decide,
never the app's. On Android (`KiffClient.window`, `AppModel.holdWindow`) and on iPhone and iPad
(`KIFFClient.window`, `AppModel.windowWords`, with Face ID where Android asks for the fingerprint):

- **Opening.** Only on tafs cloud's hint: an approval card's ask with `key: window`
  (connecting an app; a draft KIFF says widens nothing). The approval key signs KIFF's
  `kiff.window.v1` (`POST /v1/me/window`, `{message, expires, nonce}`, five minutes at most),
  behind one fingerprint in the card's words. The app opens none on its own guess, and a KIFF
  without the route (404) leaves the step signing as it did.
- **Inside it.** What the approval key would sign goes with the device key and the window's
  id, `t=…,v2=…,w=<id>`, over `kiff.request.v2` to KIFF or `planque.request.v2` to planque,
  the window's id last. planque's calls are signed this way only for the step that took the
  window. The report to tafs cloud is signed as before (v1). On iPhone and iPad, KIFF's calls
  too are signed in the window only for that step; anywhere else the approval key signs them,
  behind Face ID, as before.
- **Refused.** KIFF's `critical_key_required` (the act takes the fingerprint every time, such
  as **No** to "Did this happen?") is signed again by the approval key, once (on iPhone and
  iPad behind a new Face ID: the approval key signs only under one). KIFF's
  `window_required` forgets the window and does the same. planque's `window_required` on an
  approval card opens a new window, one fingerprint, and the call is made once more.
- **Words.** While a window is held, Approve on an approval card reads "Approve" and KIFF's
  words go under it ("No fingerprint for the next 4 minutes", `GET /v1/me/window`), read
  again after each approval and on coming back to the app. tafs cloud marks each waiting card
  with `signs.takes` ([chat.md](chat.md)): the words go only under a `"tap"` card, never under one
  whose Approve takes the fingerprint (a Card signed with its tools or key). Android 97 draws
  by it; on iPhone and iPad the line under Approve is KIFF's words on a `"tap"` card while a
  window is held (`SetupComponent.windowWords`, `SetupApproval.approveLine`), and names Face ID
  anywhere else.
- **Closing.** After 30 seconds in the background, and at once when the screen turns off, the
  app closes it (`POST /v1/me/window/{id}/close`, device-signed); before a drive starts too. iOS
  may suspend the app before its 30 seconds: then it closes on coming back, if that was 30
  seconds or more. The app forgets it at sign-out and at a new sign-in. KIFF ends it itself at sign-out,
  at its cap (15 minutes from the fingerprint), on a halt or a new approval key. Whether a window
  is open is never decided on the phone: an ended one comes back as `window_required`.

## Set up approvals on this device

Approving needs the approval key, and the approval key needs a fingerprint or face the phone
counts as strong. The app asks the phone where it stands and says so before an approval is
needed, never only at a prompt that fails.

- **The states,** each in one line where the approval would be (`ApprovalReadiness`,
  `ApprovalWords.cantApprove` on Android):
  - set up: approvals work, and nothing stands in the button's place;
  - none enrolled: "Approving needs a fingerprint on this phone.", with **Open settings**, which
    on Android opens the phone's own screen to add one (`ACTION_BIOMETRIC_ENROLL`, asking for a
    strong biometric; its security settings where that screen isn't there), and **Check again**;
  - temporarily unavailable (the sensor locked after tries, busy, an update pending): try again
    in a moment, with **Check again**;
  - unsupported (no strong biometric at all): approve from another supported device, with
    neither button.
- **No screen of its own on Android** (1.20): the states show where they're needed, at Coach's
  Start and at each approval, never as a step before sign-in.
- **Sign-in never waits on it** (Android 1.20). Without approvals the person can still sign in,
  review, refuse and halt.
- **At Coach's Start, approvals are set up:** while the phone can't approve, it says "Setting up
  a taf needs approvals" with the state and what to do, in Start's place. Once it can, Start
  registers the approval key with KIFF if KIFF doesn't hold it yet (a phone that signed in before
  it could approve), and signs nothing unless KIFF holds it.
- **At each approval** (Approve once, Extra room, Install, Issue, Resume, Coach's Approve, a
  trial's Install): while the phone can't approve, the state and what to do stand in the
  button's place (`ApprovalGate`). Refuse, Decline and Halt stay. When it can, the button
  says what it does, under the card that shows what is being approved, before the prompt.
- **After the prompt:** an answer that wasn't sent says why ("Not sent: it wasn't confirmed.", or
  the fingerprint's own words) and that the hold stays: "It's kept in Needs you." (`ApprovalWords.KEPT`).
- **The sample** signs nothing, so its buttons never wait on the phone (`gateReadiness`).
- **Read again** each time the app comes back, so returning from the phone's settings shows
  the new state at once.
- **A new fingerprint** ends the approval key, and the screen says so up front: "If you add
  another fingerprint later, approving on this phone stops until you approve it again."
  ([How an answer is signed](#how-an-answer-is-signed)).

### On iPhone and iPad

The same gates, from what `canEvaluatePolicy` says for Face ID or Touch ID
(`ApprovalReadiness` in `Biometry.swift`, the gates in `ApprovalSetupView.swift`):

- **The states,** each from its `LAError`:
  - set up: approvals work;
  - none enrolled (`biometryNotEnrolled`): the path in words, **Settings → Face ID & Passcode**
    (Touch ID & Passcode on a device with Touch ID), and no button: iOS opens no page of
    Settings for an app but its own, which can't add a face or a fingerprint;
  - no passcode (`passcodeNotSet`): set a passcode first, at the same path, then add Face ID
    or Touch ID;
  - turned off for tafs (`biometryNotAvailable` on a device that has Face ID or Touch ID):
    **Open Settings** opens tafs' settings, where its Face ID switch is;
  - temporarily unavailable: locked after too many tries (`biometryLockout`), "lock this
    iPhone, unlock it with its passcode, then check again"; anything else, "try again in a
    moment"; both with **Check again**;
  - unsupported (`biometryNotAvailable` with no Face ID or Touch ID): approve on another
    iPhone, iPad or Android phone signed in to the account.
- **Sign-in never waits on it** (as on Android 1.20): there's no screen of its own before
  sign-in. The states show where they're needed, at Coach's Start and at each approval.
- **Before the first taf:** Coach's start says approving is needed to finish it, and Start is
  gated, since it asks for Face ID. Once it can, Start registers the approval key with KIFF
  under its one Face ID when KIFF doesn't hold it yet (a phone that signed in before it could
  approve). The one-Face-ID start, the pair or the draft alone, signs nothing while KIFF doesn't
  hold the key, and says "KIFF didn't accept this device's approval key. Try again.", the line
  both apps use; from the second refusal in a row, with the support address under it
  (`AppModel.approvalKeyUnheld`, `approval.key_not_accepted`).
- **At each approval** (Approve once, Extra room, Install from Needs you, Coach's Approve,
  Issue): while the device can't approve, the state and what to do stand in the button's place
  (`ApprovalGate`). A version's or a trial's Install, in a toolbar or a confirmation, stays
  where it is, disabled, with the words beside it (`ApprovalNotice`).
- **One readiness for every screen:** the model's `approvalReadiness`, read again when the app
  comes back and on any **Check again**, so a notice and the button it stands for change
  together. Sample mode signs nothing for real and is never blocked. The Simulator counts as
  set up, as its signer lets the demo through.
- **After an approval:** "Cancelled", "didn't recognise you", or the device's state, each with
  "The request is kept".

## Did this happen?

A money call whose outcome KIFF can't prove is counted on its Card until the owner says it
didn't happen. KIFF lists them (`GET /v1/me/did-this-happen`) for whoever answers held calls:
its answer was lost, the tool refused it with nothing showing no money moved, or it's one of the
first three calls of a new money operation (`probation`). Each shows in Needs you in KIFF's
words (`words`, `status_words`), under the app's own "Did this happen?", with **No** and
**Yes, it did**.

Either answer is one fingerprint, signed by the approval key (`POST …/did-this-happen/{proposal}`
with `{happened}`): **No** gives the Card its room back, **Yes** keeps it counted, and KIFF keeps
the first answer. Answered, the call leaves the list and the Card's numbers are read again. On
Android: `AppModel.answerDidThisHappen`, `UnsureCallCard`.

Where KIFF sends each call's context, the card shows it as sent: which taf (`taf.name`), when
(`when_words`), the amount (`amount_words`), what it acted on (`counterparty`), why KIFF can't
tell (`why`), and **See it at …** for the vendor's own record (`record_url`, https only). Its
answers are then KIFF's (`answers`), in KIFF's order and words, each with its `detail` under it.
**Yes** and **No** go as above. **Not sure, help me check** decides nothing. It is signed by the
device key alone, with no fingerprint (`POST …/did-this-happen/{proposal}/check`), and KIFF
marks the call as being checked by this person for a day. Then tafs cloud opens a new conversation
with Coach on that one call (`POST /v1/setup/check-call {proposal}`), and the app shows it. The
card stays until it's answered. A KIFF that sends no `answers` gets the app's **No** and **Yes, it
did**, and no Not sure. On Android: `AppModel.checkDidThisHappen`.

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
through Apple push for an iPhone and Firebase Cloud Messaging for an Android phone (`runtime/src/cloud/push.ts`, `apns.ts`, `fcm.ts`):

1. The phone registers the account's **alert target** in KIFF, pointed at
   `/v1/kiff/alerts`, with a signed setup call. The device key signs it, not the approval key:
   it grants no authority. On iPhone and iPad there's no Face ID for it, so the offer of alerts
   a new person sees asks only for notifications; Android still asks the person to
   confirm it first. KIFF shows the target's signing secret once, to the phone.
2. The phone hands tafs cloud its push token and that secret (`POST /v1/push`; an Android phone says `platform: "android"` and gives Firebase's token). The
   secret checks KIFF's deliveries; the token is where they go. An account gets alerts on
   50 phones at most. A phone can also hand over the target alone (`POST /v1/alerts`), so a
   paused run hears the answer whether or not a phone turned on alerts. A secret tafs cloud
   didn't take is kept on the phone and handed over again with no new confirmation: by Turn on
   alerts, and on Android also at the next launch (`AppModel.syncPush`).
3. KIFF posts each held call, signed. tafs cloud checks the signature against the
   account's secrets, then pushes a visible alert when a call is held, and a silent one
   when it is answered so the phone clears its alert.

Only phones of people who may answer held calls (`holds.answer`) are alerted, and only
they may hand over the target's secret.

**A vendor signing a connection out.** When a vendor ends the sign-in planque holds for a
taf's connection, KIFF sends `connection_signed_out` with the grant, the taf's agent and the
vendor's name. tafs cloud finds the taf that runs that agent and alerts the same phones:
"Stripe signed Refunds out · sign in again". Tapping it opens the taf (`open: "taf"`), where
its Card tab offers **Sign in again**. It sounds but isn't Time Sensitive, since nothing waits on
a deadline. It never says why the vendor ended the sign-in, and no run waits on it, so it isn't
handed to kiff/agents. KIFF's resend of the same event tells nobody twice, and an agent that no
taf runs any more tells nobody.

**A refund that failed, or a charge refunded twice.** KIFF follows a refund a taf sent until
the payment processor says how it ended. It sends `refund_notice` when that refund failed or
was cancelled (KIFF gives its room on the Card back), and when a charge the taf refunded was
also refunded outside KIFF, for example in the processor's dashboard. It also sends it when the
owner answered "No" to "Did this happen?" about a refund that then shows up at the processor
after all: KIFF takes that refund's room on the Card again. The notice carries the kind
(`refund_failed`, `refunded_twice` or `refund_after_no`), the taf's agent and the call. tafs cloud alerts the
phones of the people who answer: "A refund Refunds sent failed at the payment processor. Its
room on the Card came back." or "A charge Refunds refunded was also refunded outside KIFF. Check
it before anything more is refunded." or "A refund Refunds sent, which you said didn't happen,
went through at the payment processor. Its room on the Card is taken again. Check the request
wasn't refunded twice." Tapping it opens the taf. Each phone hears it in its own
language. It sounds, and isn't Time Sensitive. No run waits on it, so it isn't handed to
kiff/agents. KIFF's resend of the same event tells nobody twice, and an agent that no taf runs
any more tells nobody.

**On Android** the push is a Firebase data message the phone builds its notification from:
`kind` (`alert` shows one, `answered` takes it away), the words, where tapping it goes
(`open`), the call or request it is about, and how loud it is (`level`). A door's alert
opens its taf (`open: "taf"` with `taf_id`, the taf's id in tafs cloud); the phone opens it
once the list has it, and a door's later alert for the same taf replaces the earlier one. It carries no
amount and no argument, as an iPhone's push doesn't. A phone whose Sound is off gets
`level: "quiet"`. Google refusing a token it no longer knows forgets that phone; any other
failure answers KIFF 502, so it sends the alert again. tafs cloud sends to Android only
where `FCM_PROJECT_ID` and `FCM_SECRET_ID` are set (the Firebase project's service-account
key in Secrets Manager); without them an Android phone's registration is refused (503).

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
| interrupt | a hold waiting for the owner, a halt, a schedule whose runs keep failing to start | Time Sensitive: comes through a Focus |
| quiet | a task finished, a run that ended, a request answered | No sound or banner; the badge and the chat line update |
| later | anything not urgent while the owner is away | Nothing is pushed: it waits in Coach's chat, gathered into one "While you were away" message |

**A schedule whose runs keep failing to start** (`sweepFailingSchedules`, on the sweep). kiff/agents
keeps a schedule's `failing` when its runs fail before any turn (the model refusing them for want of
credits, say), and hands the same items to the next run, but it can't reach a phone. tafs cloud tells the
phones that answer holds, once for each run of failures, opening the taf:
- **a recurring schedule:** once two firings in a row failed, or one failed six hours ago with none working
  since. "Its last 2 scheduled runs couldn't start. It tries again at each scheduled time. Open it to see
  why." Not for one its owner turned off.
- **a run that stopped part-way with its work dropped** (kiff/agents' `failing.outcome` is
  `failed_unfinished`): at once, since the next run that works ends the run of failures, and even when
  that run of failures was already said as one that couldn't start. An app check's
  run failed or was cut short after it may have changed something, or three runs in a row didn't finish
  the same items, so kiff/agents doesn't hand them to another run. "Its scheduled run stopped part-way.
  What it was working on won't be tried again. Open it to see what it did."
  A later part-way stop in the same run of failures dropped other work, so it's said again, at most
  once in each six-hour window for that schedule, counted from the start of the run of failures.
- **a follow-up the taf set, or a one-time run:** at once, since it's off after its firing and its work
  won't run again by itself. "A follow-up it set for itself didn't run, and won't try again. Open it to
  see why." Not while it's still on (its owner moved it during the run), and never said again a month on.
- **nothing for a halted taf or account,** which tries nothing until it's resumed; it's said once it is.
- **nothing for our own outage:** a recurring schedule whose last counted run failed because the AI
  provider wasn't serving us (`code: "ai_unavailable"`) says nothing, and tries again at its next time. A
  failure of the taf's own later in the same run of failures is still said. A follow-up or a one-time run
  that didn't run for it is still said, since its work won't run again.

**A taf whose runs fail one after another** (`sweepHealth`, on the sweep). A taf that turns failing
([reliability.md](reliability.md#its-health)) is said once for that bad spell, to the same phones, opening
the taf: "Its last 3 runs failed. Open it to see why." Only what the alert above hasn't said already: a
run of failures at its schedule, and work a run dropped, are that alert's. One that recovers and fails
again is another spell, said again. At risk shows in the app and sends nothing. Runs started from the
taf's chat don't count toward this alert, since whoever asked saw them fail. Nothing is said while the
taf is halted or its schedule is off. The sweep reads a taf's runs only when its schedule has fired since
the last pass.

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
- **A message or Run now refused for a halt** answers 409 with `{code: "halted", halted: "account" | "taf",
  words}`: `halted` says what is halted, so the app offers that Resume on the refusal itself, and the words
  name no screen to resume from. When the account's halt can't be read, the words are the taf's and there's
  no `halted`, so no Resume is offered on a guess.
- "Halt all tafs" works from Siri and Shortcuts too (`HaltAllTafsIntent`), with the
  device unlocked. It says what happened, and when it can't finish it opens tafs on Halt
  all. Resume stays in the app.
- **Home's "Halt all" row** shows to someone who manages Cards while a taf runs, and
  whenever the account is halted or halting, with or without a taf: a halt outlives the
  tafs it was made over, and that row is where it is resumed from. On iPhone and iPad the
  account's halt is read with each refresh that finds no taf (`showsAccountHalt`).
- Halts and resumes show in Activity (`GET /v1/events`).

## Live updates

When something on the account changes, tafs cloud sends every phone of the account a silent push
(`tellChange` in `runtime/src/cloud/push.ts`), on APNs and FCM alike. It carries only `change` and
the taf's id (`taf_id`), never words, names or amounts:
- `taf`: a taf was made, changed (its version, where its results go, its schedule) or removed;
- `halted` and `resumed`: a halt, of one taf or the account;
- `knock`: someone unpaired wrote to a door.

A held call and its answer already arrive this way (`hold_id`, `answered`). The phone reads again
what the kind names, with its own session, so a person who may not see the taf learns nothing from
the push. The same change is told at most once every few seconds. While the app is open it also reads
again when it comes to the front, which covers a push that came late or not at all.
