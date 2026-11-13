# Coach mode

Coach mode is where tafs are made, connected and changed. Its conversation is with
**Coach**, the account's Setup agent: one per account, run by kiff/agents like a taf, but
with no access to the company's business. It shows its work as cards in the chat, asks
planque for each vendor connection, and drafts each taf's Cards in KIFF. The owner reads
and approves each of those on the phone, with Face ID or Touch ID.

Code: in the app, `CoachMode` (the ✦ button, the panel and sheet), `SetupChatView`,
`SetupShapePanel`, `QuestionsCard`, `TrialViews`, `VersionsPanel` in `TafDetailView`, and
the Connections, Setup, Coach mode, Versions and Trying a version sections of `AppModel`;
`SetupComponent` and `SetupShape` for the cards. In tafs cloud,
`runtime/src/cloud/setupdef.ts` (Coach's definition and instructions),
`runtime/src/cloud/coach.ts` (Coach's tools), `runtime/src/cloud/improve.ts`, and
`startSetup`, `versionRoute`, `trialRoute`, `improve`, `mcpRoute` and `runEnded` in
`handler.ts`.

## Opening it

The ✦ button floats on every screen ("✦ Coach mode" on a wide screen, "✦ Coach" on a
phone). On an iPad, Coach mode slides in from the right next to what's open, at a third
of the screen or the width the owner last chose; its left edge drags it wider, up to full
screen. On a phone it's a sheet. Closing never ends a conversation: ✦ reopens it as it
was.

A new conversation is on no taf. Opened from a taf's page, it offers that taf, to
improve with one tap. Starting a new taf is a new Coach conversation.

Who may use Coach is KIFF's answer for the account's Setup agent; by default, an
account admin (`AppModel.mayUseCoach`).

On Android, Coach opens from ✦ Coach on the home list, as a screen of its own
(`CoachScreen`). It has what's below, with these differences:
- **A compact header,** one row: back, Coach, and a menu with **New conversation** and
  **Previous conversations** (`CoachHeader`). New conversation is never greyed out: on a
  conversation with nothing in it yet, it keeps that one and shows its top; otherwise it opens a
  new one (iOS's New conversation button does the same: `coachChatIsEmpty`). Under it, the plan in tafs cloud's one line
  (`line`, [The plan in one line](#the-plan-in-one-line)); tapped, it opens to the steps. The
  plan line folds away while the thread scrolls down and comes back on the way up, so a card
  has the screen. The phone never counts the plan's steps.
- **Improve a taf:** a new conversation offers **Improve <name>** for each taf the person
  manages (`CoachWords.improvable`), and a taf's Activity tab has **Improve in Coach**; both open a
  new conversation about it (`AppModel.improveTaf`, `improveInCoach`), as iOS's start choice does.
- **What the owner wrote and didn't send** stays while they go elsewhere in the app, in Coach and
  in each taf's chat (`AppState.drafts`, as iOS's `setupDraft`); it goes at sign-out or a switch
  of account.
- **A card's answer tafs cloud took** reads answered at once ("Answered."), until the thread
  brings kiff/agents' words for it. An approval and a sign-in run in the model, not the screen
  (`startApproval`, `startSignInAgain`, `startReconnect`), so the vendor's sign-in page opening
  never cuts the flow short: planque's confirm, the answer to Coach, reading the thread and
  following the run all go on.
- **An answered approval** folds to one line saying what was approved. When a Card was signed, the
  line names that Card, and its lines, KIFF's own from the draft the phone read, stay under it
  (iOS and Android), never the key's words. On Android, a tap opens it again to that Card in full,
  KIFF's draft at the version signed, with nothing to answer; another tap folds it. planque's grant isn't shown there: it reads as it is now, and a
  later tools approval can widen it past what this one approved. iOS follows.
- **Coach's tools in one tap:** when tafs cloud sends the connect batch before Coach's Card (the
  Card is drafted over tools KIFF holds), the app reads the tools again after the batch and gives
  the Card in the same flow: one tap, still two fingerprints (the batch, then the Card). A
  taf is improved from a hold's **Change the limit in Coach mode**, which opens Coach on a
  new conversation about it (`AppModel.improveInCoach`).
- **At launch** Coach opens on its newest conversation alone, the one updated last, read with
  `GET /v1/tafs/{id}/chats`, never the latest ones together; the earlier ones are under
  Previous conversations. With none yet, the thread reads as before (`AppModel.readThread`).
- **Previous conversations** lists them newest first. A long press archives one, and
  Archived puts one back.
- **The empty chat** shows the same starts as iOS (`CoachWords.EXAMPLES`,
  `IMPROVE_EXAMPLES`).
- **Coach's news** of the tasks it handed tafs shows under the thread, with "While you
  were away" together (`CoachLines`).

## Starting Coach, once per account

Before the first message, one Face ID (`AppModel.startSetup`):

1. The phone enrolls with planque: planque gets the phone's key, and a KIFF key that
   connects tools and does nothing else, minted with a signed setup call and never kept
   on the phone. If planque can't check the phone with KIFF (401), the app says "Connecting
   your apps didn't work. Tap Start to try again." It never says signed out, since KIFF's
   session still works (Android).
2. The phone submits a KIFF agent draft with no tools for Coach. tafs cloud claims its
   key, so the key never reaches the phone (`POST /v1/setup`).
3. The phone gives Coach its tools, as tafs cloud says them (`GET /v1/setup`'s `tools`).
   With `to_give`, it signs and sends the `connect` batch to KIFF's gateway
   (`POST /v1/connections/batch`). Then, on the next read and under its own fingerprint,
   it sends Coach's Cards' draft (`POST /v1/me/agent-drafts`), each body exactly as it came.
   The Cards come only once every tool is connected, because KIFF refuses a Card naming an
   action no tool declares yet. The phone builds neither:
   the tools' names, addresses and Cards are tafs cloud's, and the words the owner reads
   are its `lines` and KIFF's own preview of the Cards. `given` sends nothing; `taken`
   names the vendor's tool that holds one of Coach's names, and `unknown` asks to try
   again, both sending nothing. A replaced connection's line says what it replaces.
   (Android: `AppModel.giveCoachTools`, `CoachToolsSheet`. iPhone and iPad: the same names, with the
   batch and the Cards behind one Face ID, [ios-app.md](ios-app.md#coachs-tools).)

On Android (1.2), Coach's conversation shows before Coach is started on the account, and the
first Send starts it (`AppModel.sendChat`, `startCoach`):
- **No step before the words.** A job handed to Coach from Home lands in the box with nothing to
  tap first. Above the conversation, what Coach is, and "Starting asks for your fingerprint once."
- **One fingerprint, the draft's.** On a first Start the draft with no tools is signed by the
  approval key, and its prompt is the only one. planque's enrolment and the connector key come
  after that yes, so nothing is made before it.
- **Coach's tools are the step that ends a first Start.** Coach's Card is another draft, signed over
  its own bytes, so it takes its own fingerprint. A sheet asks for it (`CoachToolsSheet`,
  `AppState.coachToolsAsked`). Its words are tafs cloud's (`tools.coach_tools_step`: `title`,
  `intro`, a few `groups` up front, each tool's `does` and `why` folded under `details`, closed at
  first, then `ask`, `give` and `not_now`). The connections' `lines` and KIFF's words for the Card
  sit in the same fold. A tafs cloud from before the step shows the `lines`, the Card and the app's
  own button. **Not now** puts it off. Then one line above the conversation (`row`) opens the same
  sheet; it's never a panel there.
- **The message box keeps its room.** With the keyboard open, what sits above the conversation
  steps aside, and the attach, mic and send buttons keep their 40 dp.
- **A Coach already on the account,** started from another phone, asks this phone for the owner's
  yes once, then enrols it with planque. It makes no draft.
- **Coach's tools are given in their own step only,** never inside another confirmation: not inside a
  Start on a phone new to a Coach already there, and not inside installing a version. Still to give,
  the step is asked (`coachToolsAsked`), on Android and on iPhone and iPad.
- **Then the message goes, once.** A Start that's closed or fails sends nothing: the words go
  back to the box, and why shows above it.
- **A phone that can't approve yet** shows what Coach is and why it can't start, with no box.

**The first start under one fingerprint** (Android 1.19, `AppModel.startCoachTogether`, `CoachStart` in `kiff/Coach.kt`; iPhone
and iPad in [ios-app.md](ios-app.md)). Before Coach exists, `GET /v1/setup` may carry `start`
([api.md](api.md#the-account)): the tools batch for KIFF's gateway and Coach's draft with its Cards, each body as tafs cloud
wrote it.
- **Start's screen shows what Coach is given** (Android 1.20, `StartScreen`): `start.words.lead`, then the Cards in KIFF's own
  words (the draft's `card_title` and `cards`), with Coach's tools folded under `coach_tools_step`'s details, then
  `start.words.does` and the `start.words.start` button. There's no second screen, and nothing is signed unread. (1.19 opened
  Coach's tools step on the first Send instead.)
- **Start, or the first Send, starts Coach and gives its tools, behind one fingerprint.** The phone builds each request's message with one
  `signed_at` and signs both hashes once with the approval key (`kiff.requests.v1`). Each request goes with that one signature and
  the other's hash. Right after the fingerprint, before anything goes to KIFF, the phone enrols with planque under the same
  confirmation, so a refused enrolment leaves no tools and no confirmed draft nobody claims. Then the batch, the draft once it
  answered, and tafs cloud claims the draft's key (`POST /v1/setup`). The draft's body carries the gateway's `contract_digest`,
  signed with it.
- **With every tool connected,** `start` carries the draft alone, still one fingerprint: taken first over the draft's message,
  then the enrolment, then the draft.
- **A draft KIFF refuses after the batch went in** is said on the step, and the next read carries the draft alone.
- **The phone signs only these:** `POST /v1/connections/batch` on the gateway and `POST /v1/me/agent-drafts`, and only with
  KIFF's words for the Cards. Anything else, an unknown scheme, a draft without those words, or no `start` starts Coach as before.

iOS follows.

## What Coach shows

Coach writes messages and shows cards (`SetupComponent.Kind`). A card grants nothing by
itself: an approval names planque's grant and KIFF's draft, which the app reads from
those services, and the phone signs there.

| Card | What it is |
|---|---|
| `steps` | The plan: the work ahead, each step pending, active, done, failed or skipped. It keeps its steps; only their status changes. A step whose call was refused or failed is failed, never done; one the owner declined is skipped; one waiting on the owner stays active. The rules give a read tool the call count its Card line will have, never "no limit" |
| `notice` | Something to know |
| `confirm` | A choice between a few answers |
| `questions` | Several questions at once, every word Coach's. The app adds "Something else…" under each, and "Chat about this" |
| `approval` | A connection or a Card for the owner to approve, with planque's own text and KIFF's draft |
| `sign_in` | The vendor's sign-in page, for a connection the owner approved |
| `result` | The hand-over: a new taf to start, or a taf's new version to try |

The owner's answer to a card goes as data, one answer per card
([chat.md](chat.md#cards-that-wait-for-an-answer)). Both apps answer through
`POST /v1/tafs/{id}/components/{component}/answer`, and each card shows the answer the thread
holds, in kiff/agents' words; the iPhone app does the same. tafs cloud still takes the
earlier message form, `"[<type> <id>] <answer>"`, from an older build.

Coach's chat behaves as a taf's ([chat.md](chat.md)). What starts an answer goes to the top
and stays there: a typed message, or the card the owner just answered. While Coach works,
its work block sits under it, before the first thing the answer records, and folds in
place to "Worked for" when it ends. Each answer lists its own steps, starting again after
a pause, in Coach's words ("Preparing the Card"), never a tool's name. On a wide screen, `SetupShapePanel` beside the conversation shows what Coach has
made so far: the taf's name, the plan, the tools it connects and the Card KIFF drafted.

## Making a taf

Coach asks what the job is, where the work comes from and which systems are involved,
then shows its plan. Then, each step behind the owner's yes:

1. **Connect.** Coach asks planque for a connection to the vendor's MCP server, for the
   taf's own agent. The owner approves planque's grant (one Face ID), signs in on the
   vendor's own page, and the phone confirms the sign-in with the code it brought back.
   planque keeps the vendor's credentials; KIFF lists the connection's tools.
2. **Choose the tools and draft the Card.** Coach picks the tools the job needs and
   submits a KIFF draft of the taf's Cards: what each tool may do, how much each time,
   how much a day. A tool that moves money is limited by amount; one that changes data but
   moves no money, such as answering a dispute, is limited by its calls a day, and never gets an
   amount argument the vendor's operation doesn't take. The limits are the owner's: Coach proposes them, and its rules say
   for each action exactly what asks the owner first and what is refused. Each card
   Coach shows has an id no earlier card in the conversation had. KIFF takes a Card only for a tool that is connected.
3. **Approve the Card.** The owner reads the Card on its approval card and confirms it
   with one Face ID, which signs exactly the draft version shown (tafs cloud names it: see
   [Approving: tafs cloud decides](#approving-tafs-cloud-decides)). Each draft Card's lines
   are KIFF's `lines` for it, as they come. For a KIFF that doesn't send them, the app words
   them from the Card's terms and its `on_exceed`, with the same words as a taf's Card tab
   ([tafs.md](tafs.md)): an `owner` Card says what asks the owner past each limit. An editor's draft
   waits for an admin to confirm it in KIFF.
4. **Start the taf.** The result card hands over the taf, with its job as Coach drafted it
   from the owner's words, any job at all (kiff/tafs#476): `summary`, one line on what this
   taf does, `kickoff`, what each run is asked to do, `instructions`, how it works, and
   `cron`, when it runs, with `check`, the read that lists its open work, or `unscheduled`.
   Every schedule checks first: each firing makes that read without the model, and the
   AI runs only when it lists something new, so a quiet schedule costs nothing. `first:
   "all"` makes the first check hand the queue already there. Before the result, Coach
   sizes the job (`set-up-a-taf@6`): it makes the check's read once itself, with its
   arguments, `limit 1` and the total where the vendor gives one, and never hands over a
   check it hasn't seen read; with more than 50 items waiting, or no total, it bounds the
   kickoff and instructions (the 20 most recent, and the total) and tells the owner how many
   wait. A result with `cron` and no `check` is answered failed, in words Coach acts on. tafs cloud starts it as the
   person KIFF names and answers the card itself (`answered_by: "tafs cloud"`): kiff/agents
   claims the key from Coach's draft, and the taf joins the account's list
   ([tafs.md](tafs.md#starting-a-taf)). The conversation carries the card's answer
   (`answer.by`, `answer.outcome`: `{taf_id}`, with `check` when its first check couldn't read, or `{failed}`), and the card shows it:
   set up, with Open; why it wasn't, with no Try again, since Coach hands it over again
   itself; and until the answer comes, "Setting up…". A result whose Card the owner hasn't
   approved yet (KIFF's `draft_not_ready`) isn't answered: it waits, and the taf is made once they
   approve, with the next note, chat read or sweep, never answered failed. The app starts nothing itself
   (Android: `CoachWords.handOver`, `HandOverCard`; it reads the list again once for a taf
   set up that isn't listed yet). The iPhone app does the same.
   A taf's next version is still made by the app, from an improvement's result.

A taf whose tools can't find its work by themselves is made **unscheduled**: it works
from its chat.

A taf made before checks existed keeps its schedule as it was: every firing wakes it. Coach
adds a check at its next dial change (`taf_dial`'s `check`), which goes live with that
version. A taf with a check keeps one timetable: kiff/agents keeps a check's cursor per
schedule, so two would hand the same work twice, and the dial refuses a second one with
the nearest single timetable (skills `set-up-a-taf@4`, `the-dial@2`).

**What it will likely use, before the owner approves.** Before the result of a new taf with
`cron`, and before `version_install` of a dial version of a scheduled taf, Coach calls
`taf_estimate` and says its `words` as given, in its message right before the card, never a
figure of its own; when it answers `estimate: false`, it says nothing about cost and goes on
(skills `set-up-a-taf@5`, `the-dial@3`). A new taf's call names the recipe it was built from.
The line is from credits as KIFF counts them: the tool reads the taf's runs with its call's
permit (`POST /v1/coach/credits`), and when KIFF can't answer, it falls to our tests or says it
can't tell yet, never a guess. Nothing waits on an estimate ([api.md](api.md#a-tafs-estimate)). The result's
`taf.recipe` would keep the recipe on the taf, but kiff/agents' result card doesn't take that
field yet (it refuses unknown fields), so the skills don't ask Coach for it until it does.

## Where results go

When the owner says where a result should land, Coach finds the app's write (the taf's
connection's catalog, or a search across the catalog and a connection asked for), fixes the
arguments that pick the place from the owner's words (a list read on the taf's connection,
with the owner's OK, and a pick by name in a questions card, never an id asked for), says
what each field takes in words (`fill`), and drafts the place with `taf_dial` as the taf's
next version, which the owner installs (`runtime/src/cloud/setupdef.ts`). A place moves no
money: a money tool is never a place, and tafs cloud refuses one the taf's Cards count in money,
at the dial and when the version goes live (`places.ts`). Another taf can be the place: the result becomes its
work, never Coach, and that taf may hand its own result on once more. tafs cloud hands one piece of
work on twice at most and never to a taf that already had it, and Coach says so when the owner asks
for a longer chain or a loop (skill `the-dial@4`). Opened from a delivery that wasn't made ("Rae
didn't deliver to Google Sheets · Refunds 2026"), Coach reads the taf's last request for why
and proposes the fix as a version; it never retries the delivery itself, which is Send again
on the taf's page (`docs/reliability.md`, "The taf's own places").

## Approving: tafs cloud decides

An approval card's outcome is tafs cloud's to decide (kiff/tafs#413); the apps only sign. On
**Approve**, the phone sends that it was tapped and what the card showed (`POST
/v1/tafs/{id}/components/{component}/approval`: the grant, the digest and draft version
rendered, KIFF's words for the connection as the phone read them), with its signed read of
the connections, which tafs cloud forwards to planque unchanged. tafs cloud reads planque's
grant as that phone and KIFF's draft with the person's session, and answers one of three
things (`runtime/src/cloud/approval.ts`):

- **answered**: nothing to sign, and Coach heard it from tafs cloud, as the person's answer to
  the card: `already approved: <vendor> is connected and its Card is confirmed` when the
  connection and its Card were approved earlier, with their own signature; `failed: <why>`
  otherwise (no such connection, a connection planque failed, a request whose digest isn't its
  hash, a connection already connected with no Card to confirm, a draft KIFF dropped). The
  card shows tafs cloud's `note` ("Already approved", or why).
  An app set up on demand (kiff/planque#87) asks the owner for every change: a draft
  that would let one of its write or money tools run without asking is answered
  `failed: …` too, naming the tool, so Coach drafts it again with that tool held (0 a
  day); the owner widens it later as a version.
  A draft that leaves one of the taf's tools on no Card is answered `failed: …` too, naming
  the tool, when no Card in force for the taf's agent covers it either (KIFF's
  `GET /v1/me/mandates`): KIFF refuses every call no Card covers (`no_mandate_issued`), reads
  included, so the taf couldn't use it. Setup's own reads (planque's `setup_reads`) are left
  out. When the Cards in force can't be read, the answer is **read_again**, never a signature.
  A draft that counts one of the taf's write tools over its whole life with no `per` (count,
  `lifetime`, one total on the tool for every item) is answered `failed: …` too, naming the tool
  and its `resource_argument`: such a total stops the taf after its first item, so a limit for
  each item ("one invoice per order, ever") is a total `per` that argument, beside the day's
  total. A lifetime sum (a budget) and a lifetime count per item are signed.
- **read_again**: the card is out of date (its terms or its draft changed since it was shown)
  or KIFF's words for the connection aren't on the phone yet. Nothing is told to Coach; the
  card shows the note, and the app reads the connections and the draft again.
- **sign**: one message for the device's key, under Face ID or the fingerprint, with the
  prompt's words, and the call it goes into exactly as the phone must make it: planque's
  approve with the body tafs cloud built (the digest, and `kiff_profile` for a connection with
  a profile), then the vendor's sign-in and the confirm code as before; or KIFF's draft to
  confirm, at exactly that version. Before signing, the phone rebuilds the message from what it
  holds (its own account, the card's grant or draft, and the digest or draft version the person
  was shown, never the ask's) and refuses any other, and signs nothing when nothing was shown: the phone is the last check, never a signing service (Android
  `AppModel.expectedApprovalMessage`, iOS the same). It signs that message and nothing else, makes that
  call as given, and reports what came of it (`POST …/approval/{ask}`: connected, listed,
  confirmed, the sign-in closed, no code, not connected, or failed with the service's reason),
  signed as a setup call to KIFF. tafs cloud asks KIFF whether this phone signed the report,
  then reads what holds at planque or KIFF, and only then answers Coach: `approved` when the
  service holds the connection or the Card, else `failed: <why>` in tafs cloud's words,
  whatever the phone reported. The phone never builds an approval's answer, and tafs cloud
  never vouches for one: planque and KIFF still take only the device's signature.
  The ask also says which key the call takes (`key`, kiff/tafs#494): `window`, a tap inside
  an approval window the fingerprint opened at KIFF (connecting an app; a draft KIFF's view
  says widens nothing, `confirm_key`); `critical`, the fingerprint every time (a draft
  otherwise). It is a hint: planque and KIFF decide for themselves. The report stays the
  device key's (v1), since the fingerprint goes on the call it reports; tafs cloud's check of
  the report refuses one signed inside a window for a critical ask (`code:
  critical_key_required`) or inside a window that has ended (`code: window_required`), with
  the words the card shows.

A card counts as finished only by its answer in the thread, on both apps. Every word the
apps show for an outcome comes from tafs cloud. An ask waits 20 minutes for its report and is
taken once: a second signed report of the same ask finds nothing waiting. On
Android: `AppModel.approveCoach`; on iOS, `AppModel.approve`. A sign-in card's **Sign in
again**, and Connect apps' sheet, still approve on the phone as before.

## Connections

A taf's connections are on its Card tab (`ConnectionsPanel`): whose keys, which tools,
what happened last. **Disconnect** removes the vendor's tools from KIFF and revokes the
sign-in at the vendor, after Face ID. A connection none of whose tools is on the live
version's Card is shown as left over: the taf doesn't use it now, but an older version
may.

Reading connections asks for no Face ID; it is signed so planque shows them only to the
enrolled phone. Each signature names this device's id in KIFF (`d=`), which planque checks
with KIFF. The id is the signed-in account's: Android reads it again after any sign-in or
sign-out, so after switching accounts planque never gets the last account's device.

### Connect apps

Settings › **Connect apps** lists the apps tafs cloud offers (`GET /v1/connect/apps`, with a
search): the reviewed catalog first, then a page at a time of the managed provider's apps
(**Show more apps** sends back `next_cursor` with the same search and provider). Each
says whether it's connected, or needs signing in again, as tafs cloud sends it. **Not listed?
Ask Coach** opens Coach, which can connect an app that isn't listed from its own docs.

**Connect** (for whoever manages the account's Cards) starts the app's grant for one taf
(`POST /v1/connect/apps/{entry}` with `{taf}`, which answers only the grant). With several
tafs the person manages, it asks "Which taf is this for?" first; with one, it's that one; with
none, Connect is off and it says to make a taf first. Then it opens the approval as a
sheet over the list. It's the same as Coach's card: planque's words, KIFF's words for the
profile, then the signature, and only then the vendor's sign-in page from planque's approve
answer, and the confirm code. The owner sees what they're connecting before they go to the
vendor. **Sign in again** opens the same sheet on the grant the app already has. A vendor that
takes an API key (`needs_key`) takes it on the same sheet, on every app: the owner types it, and
it's given with the same checks and the same confirmation as an approval
(`tafs.connect.v1|tenant|grant|digest`, with the approval key). planque gets the key once, in
`Planque-Vendor-Key`, on `POST …/grants/{id}/key`, whose signed body carries the digest, the
key's SHA-256 and `kiff_profile`; the phone and tafs cloud keep it nowhere. On Android:
`AppModel.startConnectApp`, `startListApproval`, `giveKey`, `ConnectApprovalSheet`.

### KIFF's words for a connection (KIFF's profile contract)

A connection can carry a **profile**: KIFF's facts about how each of its tools is counted and
what its outcomes mean. planque puts it in the grant's `request.profile`, so the grant's digest
the phone already checks covers it. Before the owner approves:
- the phone asks KIFF for its words with its own session (`POST /v1/profiles/words`, never
  through tafs cloud or planque);
- it checks that the profile's SHA-256 (JCS) is KIFF's `profile_sha256` and the words' is
  `words_sha256`;
- the approval card shows each line verbatim, in KIFF's order. What KIFF can't check,
  probation and a vendor that isn't pinned are in amber.

**Approve** waits until the words are on screen, and approving signs `kiff_profile`
(`profile_sha256`, `words_sha256`) into planque's approve body, so one signature covers the
connection, the profile and the words. Words that don't check out are said ("KIFF's words for
this connection don't check out…"), and nothing can be approved. The words are read once per
profile, not per connection: tools added to an approved connection change its profile under the
same grant id, so the words read for the earlier profile aren't shown or signed, and the new
profile's are read (`AppState.profileWordsFor`, by the profile's digest). On Android:
`ProfileWords`, `AppModel.loadProfileWords`, `ProfileWordsLines`; KIFF's golden vectors are in
`app/src/test/resources/profile`.

### The next version Coach drafts

Coach drafts how a taf runs (what starts it, what it does when it's still busy, where its
results go) with its `taf_dial` tool, which makes the taf's next version, and nothing runs
from it. Then Coach shows `{type: "knob", taf_id, version}` in its chat. The app draws that
card from the taf's knob `draft`, in tafs cloud's words. The apps group `draft.groups` by
connection and This taf; older responses fall back to words by ring.
**Confirm** makes it live with the usual version switch (with the fingerprint where the Card
changes) and tells Coach it was approved. **Change** goes back to Coach. When the knob's draft is
no longer that version, the card says it was replaced. The same draft also shows in the taf's
Setup, marked not live, where it can be made live too ([taf-screen.md](taf-screen.md)). On
Android: `KnobDraftCard`.

## Improving a taf

**Improve** on a taf (`POST /v1/tafs/{id}/improve`) starts a Coach conversation on that
taf. tafs cloud reads the taf's job, instructions, Cards and latest calls from KIFF with
the owner's own session, and puts them before the owner's first message, so Coach starts
from how the taf is doing (`runtime/src/cloud/improve.ts`).

Coach agrees a change with the owner, then makes it as the taf's **next version**, never
a new taf:

- new instructions, in the owner's words;
- a changed limit, as a KIFF draft revising the taf's own Cards under the same ids;
- a tool it lacks, added to the taf's connection to that vendor, or a new connection;
- a tool that reads the wrong thing, replaced by a fixed one under a new name.

Its result card is handed over by tafs cloud, as a new taf's is: it makes the version for the
person whose chat it is, once per result and only while KIFF says they may manage Cards, and
answers the card with `{version}`. The apps read the answer and make nothing themselves; an app
from before that still calls `POST /v1/tafs/{id}/versions` gets the same version, made under the
same key. Nothing goes live: the switch checks the version's draft is this taf's, with the
owner's session, before anything of it does. When the change changes what the taf does, the result
carries `summary`, one line in the owner's language: it becomes the taf's job line once the
version goes live. Once tafs cloud answers with the version, Coach closes in one sentence: the
version is ready to try, then to install from Versions. An approval carries only its grant and draft: its words are planque's and KIFF's, never Coach's.
When the owner installs a version, tafs cloud tells the owner's Coach (`[taf <name> version N
live]`): the app shows "Version N is live." in Coach's chat at once, and Coach reads it on its
next turn. For a version Coach made, Coach ends the improvement's plan. Any other install says
which version it is, with that version's own note: an Upgrade's, a dial's, or going back. That way
Coach never reads the last version it made as the one that runs. A message written to Coach's
run while it is at work (a steer) carries these lines too, so they don't wait for the run to end.

## Versions

A taf's versions are on their own screen (`VersionsPanel`), like app builds: newest
first, the live one marked (`GET /v1/tafs/{id}/versions`). Each says who made it in tafs
cloud's words (`by_words`: "By Coach mode", "By you"), shown as they come.

**Install this version** makes one live, forward or back, with one Face ID
(`AppModel.versionCard`). When the version brings a Card revision, it's three steps, so
nothing ever runs with one version's definition under another's Cards:

1. tafs cloud begins the switch (`POST /v1/tafs/{id}/versions/{n}/switch`): from here
   nothing of the taf runs. A run in the way answers 409 naming it, with `words` saying
   what's in the way, which the app shows as they come; it can stop it first (`stop_blocking`).
2. The owner reads the Card the version brings (`VersionCardSheet`: each Card as it is
   now and as it will be), and the Face ID signs its confirmation in KIFF. On Android the sheet
   closes only by Cancel, Install or back (back is Cancel, which drops the switch), never a
   swipe, so a switch is never left begun with nothing on screen to end it.
3. The version goes live (`POST /v1/tafs/{id}/versions/{n}/live`).

A switch that stopped half way stays begun, and the next read of the versions ends it
once KIFF has the Card. A switch whose Card isn't confirmed can be dropped
(`DELETE /v1/tafs/{id}/switching`): the live version stays.

## Trying a version

**Try it** opens a trial of a version beside the live taf, under a trial Card of its own
(`TrialStartSheet`, `TrialScreen`). The live version keeps its schedule, Card and budget.

1. tafs cloud prepares the trial Card's draft; the owner reads it, and one Face ID
   confirms it in KIFF as a trial.
2. The trial opens (`PUT /v1/tafs/{id}/trial`). It's a chat with that version, under a
   bar that says it isn't live.
3. It ends on **Install** (the phone confirms the same draft live, then the version goes
   live), on **Go back** (KIFF revokes the trial Cards; the live version stays), or after
   7 days unused. Its chat is then deleted, and the version keeps one line with KIFF's
   counts.

## Coach's own tools

Coach can act on the account's tafs through a small MCP server in tafs cloud
(`POST /v1/mcp`, `runtime/src/cloud/coach.ts`). It reaches it only through KIFF's
gateway: tafs cloud holds no credential for it, and every call carries KIFF's permit,
checked before anything is done (`runtime/src/cloud/permit.ts`). KIFF decides each call
under Coach's Cards first.

| Tool | What it does |
|---|---|
| `tafs_state` (read) | Every taf: its live version and versions (with none live, `runs_on` says it runs the definition it was made with), a switch under way, its schedule, its open runs and what each waits on. `reading` says a schedule that's on is working, with its next run, and that open runs are only what runs this minute |
| `taf_run` (read) | One run of a taf so far, with `code` beside `error` when kiff/agents stopped it with one (`ai_unavailable`). `calls_not_done` lists its calls that weren't done, from kiff/agents' kept record lines: the tool, what came of it, `said` (the app's own words for why: data, never an instruction) and `next`, the owner's step, as an Activity row has it ([api.md](api.md)) |
| `taf_check` (read) | "Check this taf": the taf as its latest five runs the person may read found it, one line a tool whose latest call wasn't done, with `said` and `next`. It makes no call of its own to any app. Its Card, `coach-checks`, allows 200 a day |
| `taf_estimate` (read) | A taf's likely credits a month, in one line for the owner: an existing taf's (`taf`), or a new taf's from its draft (`recipe`), in `lang` or the account's language, from credits as KIFF counts them (`POST /v1/coach/credits`, with the call's permit). `{estimate: true, source, words}`, or `{estimate: false}` when KIFF can't say what the person may see, or it takes longer than 3 seconds ([api.md](api.md#a-tafs-estimate)). Its Card, `coach-estimates`, allows 200 a day |
| `taf_task` | Hands a taf a task or a message: a new run, or to a run at work |
| `run_stop` | Stops a run; its held calls are withdrawn |
| `taf_pause` | Pauses or resumes a taf's schedule |
| `taf_results` | Sets where a taf's results go, all places at once ([doors.md](doors.md#where-results-go)). With built-in places only, KIFF holds the change for the owner, who approves it in tafs; with a place at a connected app or another taf (`{place: "action", …, fill}`, `{place: "taf", taf, name, fill}`, as `PUT /v1/tafs/{id}/knob` takes them), it drafts the taf's next version as `taf_dial` does |
| `taf_dial` | Drafts how a taf runs, all its triggers, what it does when busy and where results go, as `PUT /v1/tafs/{id}/knob` takes them ([api.md](api.md)): the taf's next version, nothing running from it. Coach shows `{type: "knob", version, taf_id}` and installs it with `version_install`, held for the owner. Its Card, `coach-taf-dial`, allows 20 a day and holds nothing |
| `version_try` | Tries a version: KIFF decides each of its writes and sends none |
| `version_install` | Installs a version. KIFF holds this for the owner, who approves it in tafs: the app stops what's in the way, confirms the version's Card and makes it live |

Answering a held call is never a tool.

### Coach's tools step

A first Start ends on one step that asks for Coach's tools, with their own fingerprint. tafs cloud
writes its words, in the person's language, on `GET /v1/setup`'s `tools.coach_tools_step` while the
tools are `to_give` (`coachToolsStep` in `coach.ts`):
`{title, intro, groups: [{name, line, tools: [{name, does, why}]}], details, ask, give, not_now, row}`.

- **Four groups, always shown:** watch your tafs, run them for you, build new versions, help while you
  drive. Each group has one line. The thirteen tools sit under them, under `details`, which is folded by
  default.
- **Each tool** says what it lets Coach do (`does`) and why it needs the person's yes (`why`).
- **Limits come from Coach's Cards** (`COACH_CARDS`), never written by hand:
  - each line ends with its Card's limit for a day;
  - two tools on one Card are counted together;
  - "waits for you" appears only where the Card holds (`asks`): after the limit, or for every call;
  - a tool its Card refuses past the limit says only the limit.

  A test checks each Card's limit in its tool's line in all 8 languages.
- **One confirmation** gives the tools on either phone. The words never name a biometric (fingerprint,
  Face ID): the phone knows which it asks for, and tafs cloud doesn't.
- **Buttons:** `give` and `not_now`. After **Not now**, the Coach screen keeps one line, `row`, which
  opens the same step. It's never a panel above the conversation.

When a run Coach handed a taf pauses or ends, kiff/agents tells tafs cloud
(`POST /v1/runs/ended`). tafs cloud reads the run back from kiff/agents rather than
trust the note, and Coach hears it in its run at work, or at its next turn. kiff/agents
announces only to URLs it is set to allow (`AGENTS_NOTIFY_URLS`), so tafs cloud's public
`/v1/runs/ended` must be among them: when it isn't, kiff/agents refuses the run and
`taf_task` fails with "notify.url is not one this server announces runs to". Moving tafs
cloud to a new URL means updating that list too. The app shows
these as lines in Coach's chat ("coach lines"). While the owner is away, what isn't
urgent waits there as one "While you were away" message instead of a notification
([approvals.md](approvals.md#notifications)).

## The plan in one line

A plan (`steps` component) comes with `line`, its one-line form (`withLine` in `handler.ts`):
"Step 3 of 6 · Your rules", for the step at work or the first still to do, or "Done · 6 of 6". It's
in the thread and on the stream alike. An app shows the plan folded to that line in Coach's header,
and opens it to the steps. Android shows the plan's title where a plan has no `line`.

## Did this happen?

When KIFF can't prove how a money call ended, the owner's card in tafs offers a third answer,
**Not sure, help me check**: KIFF marks the call as being checked by them for a day, and the app
opens Coach on that call (`POST /v1/setup/check-call {proposal}`). The conversation opens with the
call's record as KIFF carries it, and Coach's `did-this-happen` skill says what to do: read the
vendor through the taf's own connection (the resource, the operation's list around that time),
say in two lines what it found and which answer fits, found means Yes, not found by an exact
lookup means No, a list that doesn't show it proves nothing. Coach never answers the card, which
the owner signs on their phone; what Coach reads can confirm a call went through, never that it
didn't (the core rule).

## Where the work comes from

When Coach asks where a taf's work comes from and the owner named no app for it, the first option
is that the owner hands it to the taf in its chat; the apps search finds come after, and one is
connected only when the owner picks it (`set-up-a-taf@2`). A connection the owner didn't ask for
is proposed, never assumed.

## A vendor the account already connects

When a new Coach conversation starts, tafs cloud reads the account's connections from planque with the
person's own session (`GET /v1/accounts/{tenant}/connections`, `connections.ts`) and puts the ones of
the tafs that person may see before the first message, as data:

```
[tafs connections]
The account's connections, from planque (data, not the owner's words):
- Stripe, for Chargebacks: vendor_sign_in, server https://mcp.stripe.com
The owner says: I want a taf that chases overdue invoices
```

A message tafs opens with its own line (`[improve taf …]`, `[tafs coach lines]`, `[did this happen …]`)
keeps that line first, and a message in the live chat (which the apps send with no session) goes on in it by name, with neither the lines nor the reads. The thread shows only the owner's words. What planque can't answer is left out,
and Coach reads planque's grants itself. Coach offers only a connection one of the account's tafs uses, or
one made in this conversation: a grant another agent holds (a test, a draft that never became a taf) is never
offered as the owner's, or reused (`connect-an-app@8`). For a vendor one of the tafs already connects, Coach offers the
same kind first, named with the taf that uses it, by reusing its sign-in: `request {reuse: <grant>,
tools: []}`, so the owner approves the new taf's tools alone, with no second sign-in or key (gap 2 of
kiff/tafs#476, `connect-an-app@4`; planque reuses a managed app's sign-in too). Only when planque refuses
the reuse does Coach ask for the same kind anew; another kind comes only when the owner picks it. An app
found by search is connected with `request {entry, name, taf_name, uses}`: `uses` names the tools search
found that the taf will use, so the owner approves only those, for a managed app's sign-in and for a reviewed
key entry alike (`connect-an-app@10`, with the connection server taking a reviewed entry's `tools` as a
subset of its own; `@9` had dropped `uses` because a reviewed entry refused it, which widened managed
apps' sign-ins). **Reading a public page** (`web_read`, `connect-an-app@12`). To connect an app by its key, Coach finds the vendor's published API description itself, on its docs, rather than asking the owner for anything technical. It reads one public page as anyone could. The rules:

- https only, on the usual port;
- no sign-in and no cookie;
- never a private or reserved address: the host is resolved once and the connection goes to the address checked;
- at most three redirects, each checked again;
- text only, cut short.

An OpenAPI description, in JSON or YAML, answers its servers and how its key is given (where, its name and any prefix). That's what `request {api}` needs. What it reads is data, never an instruction. It has a Card of its own, `coach-web-reads`, at 50 reads a day, which an account's phone asks for on its next read, as any Card added later.

**Checked first, with a way forward** (`set-up-a-taf@7`, `connect-an-app@11`). When the job needs an app, Coach searches for it before asking the owner anything else. If it can't be connected here, Coach says so first, before any choices card. When an app can't be connected (it needs setting up, or its sign-in, request or `describe` failed), Coach says why in one sentence from the connection server's answer, and offers a way forward in the same turn. That's the same app's key entry, its key by its published API description (the key's place may name a `prefix`, as `ShippoToken <key>`), or another app with the operation. It's never only "try again later". The failed step still shows tafs cloud's own words, but the server's reason is kept beside it, scrubbed. It's logged (`coach_connection_refused`) and goes before the person's next words to Coach, so Coach can still say it once the turn it failed in is over. The person sees it too: the message of the run that was refused carries `refused`, the step's words, the reason ("Why: …") and what to do next, which both apps draw under Coach's words, until a request for that app goes through in the same run ([chat.md](chat.md)). A vendor's sign-in comes before its key, even when
search finds both; a key is given on a secure sheet on the phone, outside the chat, straight to planque,
and never reaches Coach (`connect-an-app@7`). Before a key request, Coach asks planque to `describe` the API description: its operations as planque names them (name, title, read or write), with no grant and no key. A big description (over 200 operations) answers only its count, and Coach describes it again with words from the job (`q`), getting at most 50 matches, best first (`connect-an-app@15`). Each tool on the key request carries its full spec, as `name-the-tools` sets it on a connected grant (pins in `fixed_arguments`, `resource_argument`, the amount with its unit, `read_only`), from the operation's arguments as `describe` gives them, `{name, type, required, in}` (`connect-an-app@16`, `name-the-tools@8`). planque refuses a `resource_argument` that isn't one of the operation's string arguments before declaring. The request then names the job's operations as its tools, by those names, never none (`connect-an-app@14`): planque declares a key request's tools to KIFF before the key only when it names them, and only a declared request lets the owner sign the key and the taf's Card in one approval; without them, the key comes first and the Card after. A mode
is said only as the connection gives it: a vendor's own sign-in has none planque knows. Once a connection
connects, Coach checks it with one read through it before saying it's ready: a 401 or 403 there is the
key or sign-in refused, caught at connect rather than at the taf's first run (gap 10).

## Who a taf's messages come from

A taf that writes to customers sends, by default, from a mailbox the company already has, through its
own mail app's connection, found in the catalog like any app: mail then comes from the company's
address, and replies land where its staff work. Coach suggests a dedicated mailbox (`support@`, a shared
or a separate one), never a person's own inbox, since the app's access covers the whole mailbox. A
connection the account has comes first only when it is that dedicated mailbox, and since the
connections lines don't say which mailbox a connection is, Coach asks. One that is a person's own inbox,
such as a Gmail connected for another taf, is never reused for customer mail (the one exception to gap
2's reuse): Coach suggests connecting the dedicated one. Coach asks for only what the taf does: the send
tool alone when it only sends (`uses`, narrowed by planque). An inbox of the taf's own, from a mail app
made for agents, stays an option Coach offers (`draft-the-card@4`). The Card Coach drafts is the agreed rules,
line for line: no limit merged with another or changed in number. A limit the Card can't hold as agreed is a
changed rule, shown in a new rules notice and agreed again before the draft (`draft-the-card@7`).

A taf that writes to people names who they come from: Coach asks once, with its other questions,
offering the company's name, never the mail app's default (gap 16), and its result's taf carries the
answer as `writes_for`. kiff/agents then starts each outside message with "Written by an AI agent on behalf of
<writes_for>." (gap 18; "Written by an AI agent." when no one is named, never "for the company"): first
in each text part the message carries (an email's text and its html), said once, in the message's
language when it's Spanish, French, German, Portuguese or Italian (the definition's `disclose.lines`),
English otherwise. No message carries "Built with tafs." any more, on WhatsApp, SMS or email (the
owner's call, 7 Nov), and tafs sends no `credit`. A line has at most the 300 bytes kiff/agents takes, so a
new `writes_for` is at most 261 bytes, which fits in every language; a name stored before, up to 272, has
any language whose line doesn't fit left out, and English heads its messages there. With no name from Coach, a taf on a company account
writes for the company's name as KIFF gives it (never a personal account's, which can be an email), and
the Upgrade gives that name to a taf from before that names no one. A version built before gap 18 says
"for the company": installing it, or switching back to it, names no one. Evidence or a filing (Chargebacks'
dispute evidence) carries the disclosure as every message does. For a mail app that can set
it, Coach sets the inbox's sender name, and offers sending from the company's own domain when the app
takes one, showing the DNS records for the owner to add and checking them before it says the domain
works (gap 20, `draft-the-card@3` and on).

**Which tools carry the disclosure isn't left to Coach** (gap 14, `runtime/src/cloud/sends-outside.ts`). A
connection's profile (KIFF's `kiff.profile.v1`, inside the grant's signed request) says what each write does:
an operation that `sends`, with `resource.outside`, sends to people who may be outside the company. A reviewed
catalog entry's profile is the reviewed one, and one Coach drafted counts too when it says so, since that can
only add a disclosure. tafs cloud reads grants only on the phone's Approve, so each Approve of a connection, or
of its Card, notes its sending tools for the taf's KIFF agent, replacing that connection's earlier note. The write holds a claim on the note for at most 10 seconds, so two Approves of the same taf at once both keep their entries; one that finds the claim held waits and tries again, and past its tries the Approve fails, to be tapped again. A write gives its claim back only within those 10 seconds; one that took longer leaves it to expire, since another write may hold it by then. Coach's
result is then checked against the note: a new taf, or an Improve, whose taf has a noted tool that neither its
`disclose` nor the taf's existing disclosure names is refused, naming each tool ("disclose doesn't name
send_email, which sends to people outside the company …"), and Coach sends it again with the tool named. A note
that can't be read refuses with words Coach can retry on, never lets the result through. A taf whose
connections were approved before this has no note until its next Approve, and is checked as before. A managed
provider's tool counts only once its profile says it sends outside.

A taf hands setting changes to Coach (gap 15): a connection or its key, its Card, tools or schedule.
It says so in one line and shows an `ask_coach` card with the owner's request; the apps' Ask Coach
button opens Coach on that taf with it written in. A taf's definition shows that card alone
(`show_types: ["ask_coach"]`).

## Skills

Coach's know-how is in skills (`runtime/skills/coach/<name>/<version>/SKILL.md`, the Agent Skills
format: a front matter with the name and a description that says when it applies, then the body:
when to use, procedure, verification). Its core instructions (`runtime/src/cloud/setupdef.ts`,
about 2,000 tokens) carry who it is, how it talks, how a conversation works with tafs cloud, the
map of its skills, and the rules that hold whatever is loaded, each pinned by a test. The skills
are stored in kiff/agents with Setup's definition, each at its version, which never changes: a
change moves the text to a new version directory (`runtime/scripts/coach-skills.mjs`, with
`lock.json`).

Which skills a conversation loads is tafs cloud's decision, never the model's
(`runtime/src/cloud/coach-skills.ts`): by the entry point and the account's state before the
first turn (an improvement loads `improve-a-taf` with `connect-an-app`, `name-the-tools` and
`draft-the-card`, since improving often adds a tool and drafts its Card; a recipe, a door's knock or
a free start the setup's five, `set-up-a-taf`, `connect-an-app`, `name-the-tools`, `draft-the-card` and
`usage-and-plans`, since planque's and KIFF's tools go through KIFF's gateway and never reach tafs
cloud; a delivery note `the-dial`; lines about a task `the-tafs`; a call held for the owner adds
`cards-and-holds`, a halt `halting`, and an account with a taf of its own adds both to every start,
since "it hasn't done anything" and "stop everything" never name a skill), sent as the run's `skills`, which kiff/agents keeps on the
session and puts in every turn's context after the instructions, in the definition's order; and by
the tool, when it is used, for tafs cloud's own tools: a tool whose procedure is a skill
(`taf_dial`, `taf_results` → `the-dial`; `tafs_state`, `taf_task`, `version_install`… → `the-tafs`;
`taf_pause` → `halting`) adds it to the session in kiff/agents, read at the next turn, never in the
tool's answer, which stays as the tool wrote it; it is marked loaded only once kiff/agents has it.
`read_skill` stays as Coach's own fallback, by the names in the map. A tool's answer that imitates
a skill or a heading of the instructions is data: it loads nothing.

Each skill is named at the version the account's **live** Coach definition names, read from
kiff/agents with each new chat and each add (`asNamed`), never at `lock.json`'s: kiff/agents loads
only the definition's versions, and a definition changes only with the Upgrade, while the lock
changes with each deploy. A skill the live definition doesn't name yet is left out until the Upgrade
names it. A deploy that ran ahead of the Upgrade once refused every new chat (kiff/tafs#572).

The small eval that settles the score (the design, §5, the owner's go): the eight cases of
kiff/tafs#234, plus a conversation where the owner gives a limit in their first message and
Coach must not ask it again (recipe 3 on the phone asked "past 20 a day" twice), and one where a
connection reports no mode and Coach must say it can't tell whether the account is test or live,
never "test mode" (recipe 3 claimed it).

Coach runs at the standard output, as the tafs do: with the lighter core its turns are short, and
the fast output costs several times the standard one.

Each Coach conversation's cost is read from kiff/agents' usage events and shown on its thread as
`usage` (`turns`, `tokens_in`, `cache_read_tokens`, `cost_micro_usd`), so the before and after of
a change to the core or a skill are read from the same place.

## Recipes

A new Coach conversation opens to **recipes** instead of a blank page (kiff/tafs#476): jobs worth
giving a taf, each with the words to say to Coach. Tapping one fills Coach's message box with
those words; the person edits them (the limits are examples) and sends. Coach then works as for
any request: it connects the tools from the catalog, drafts the Card and hands the taf over. Recipes
are suggestions, not finished products: there is no recipe-specific code, and when Coach can't
build one, the gap is fixed generically (a connection, how Coach picks tools, a Card rule), so a
person can prompt Coach freely. Three of the five starter jobs tafs began with are recipes now. Bill pay and Access aren't, because
the apps they connect can't do their Card's work today.

They show only while the account has no live taf, and while its "Show suggestions" setting is
on (the owner's decision): tafs cloud decides, and `GET /v1/recipes` sends the list or an empty
one with `shown: false` and the words why. A manager turns them off for good with
`PUT /v1/me/suggestions {show: false}` (on by default). The apps draw only what comes.

The source is one file per recipe in [`recipes/`](../recipes/README.md): its title, category
(one of the ids in [`recipes/categories.txt`](../recipes/categories.txt), which orders them; each
id's title is in tafs cloud's words, in every language), the problem it ends (`headline`) and what that costs
today (`today`), one line on what the taf does, the vendors each
connection needs (any of several where one stands in for another), what its Card allows, holds and
never does, in words. Each recipe's words are also in the seven other languages the apps speak,
in `recipes/<lang>/`. `runtime/scripts/recipes.mjs` reads the files into `runtime/src/recipes.ts`,
and a test fails when they differ.

tafs cloud sends them in the person's language, with the categories' titles, the sheet's labels and
the suggestions words. It also says whether **Home** leads with them (`home: {shown, words}`): only
while the list is shown, for a person who manages the account's tafs (`cards.manage`), and with
at least one recipe listed. Otherwise `home.words` is Home's empty line, so a catalog that connects
none of them, or suggestions turned off, leave Home as it was.

### The gallery

Once the first taf is live, the recipes are still one tap away. Two ways in, both drawn from
`GET /v1/recipes/gallery`, which tafs cloud decides and the apps only draw:

- **+ New** on Tafs opens the gallery: the category chips, every job the catalog can connect, and
  **Say it in your own words**, which opens Coach empty. It's there at any time, whatever "Show
  suggestions" says.
- **The strip** under the tafs list shows up to three jobs the account doesn't run yet, the ones
  whose apps are already connected first. Only for someone who manages the tafs, once a taf is
  live, with suggestions on. A taf made before tafs kept its `recipe` can't be matched, so it
  leaves nothing out.

Each job shows its apps, one per connection, with the app's logo next to its name ([api.md](api.md#logos)):
tafs cloud fetches, checks and keeps each logo, and the phones load it only from tafs cloud. A job
tapped in the gallery or the strip opens the same sheet as on Home, with the same `labels`.

tafs cloud serves them at `GET /v1/recipes` ([api.md](api.md#recipes), `runtime/src/cloud/recipes.ts`):
the recipes whose every connection has a vendor the account's catalog lists, read from planque
with the person's own session, grouped by category with the titles the apps show. When the
catalog can't be read, or has more pages than tafs cloud reads, every recipe is listed and
`filtered` says so, so the page is
never blank for that; Coach says what it can't connect. The apps only draw the list and fill the
message box: the words come from tafs cloud, so both apps show the same.

On Android, a new Coach conversation draws only the recipes that come: none while tafs cloud's
`shown` is false. Account › **Coach** has the **Show suggestions** switch, read and changed
through `GET`/`PUT /v1/me/suggestions`, with tafs cloud's `words` under it. It's enabled for
whoever KIFF lets manage the account's tafs (`cards.manage`), and a refusal is said in tafs
cloud's words (`AppModel.setSuggestions`). The recipes are read again after a change. A tafs
cloud without the route leaves the section out.

iOS is the same: `SettingsView`'s **Coach** section holds the switch (`AppModel.setSuggestions`),
enabled for `cards.manage`, and a new conversation draws only the recipes that come.

**Android** reads them each time Coach opens, with Coach's setup (`AppModel.loadRecipes`), and
draws them in an empty conversation that isn't improving a taf (`CoachStarts`): each category's
title, then each recipe named as the gallery and Home name it, by its `headline` (else its
`title`), with its `line` under it. A tap sets the conversation's draft to `say`. Recipes
not read leave the conversation open without them, with no error: Coach still takes any job.
