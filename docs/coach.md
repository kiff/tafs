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
- **An answered approval** folds to one line saying what was approved. On Android, when it had a
  Card, a tap opens it again to that Card's lines, KIFF's draft at the version signed, with nothing
  to answer; another tap folds it. planque's grant isn't shown there: it reads as it is now, and a
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
   your tools didn't work. Tap Start to try again." It never says signed out, since KIFF's
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
   (Android: `AppModel.giveCoachTools`, `CoachToolsPanel`.)

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
   `cron`, when it runs, or `unscheduled`. tafs cloud starts it as the
   person KIFF names and answers the card itself (`answered_by: "tafs cloud"`): kiff/agents
   claims the key from Coach's draft, and the taf joins the account's list
   ([tafs.md](tafs.md#starting-a-taf)). The conversation carries the card's answer
   (`answer.by`, `answer.outcome`: `{taf_id}` or `{failed}`), and the card shows it:
   set up, with Open; why it wasn't, with no Try again, since Coach hands it over again
   itself; and until the answer comes, "Setting up…". The app starts nothing itself
   (Android: `CoachWords.handOver`, `HandOverCard`; it reads the list again once for a taf
   set up that isn't listed yet). The iPhone app does the same.
   A taf's next version is still made by the app, from an improvement's result.

A taf whose tools can't find its work by themselves is made **unscheduled**: it works
from its chat.

## Where results go

When the owner says where a result should land, Coach finds the app's write (the taf's
connection's catalog, or a search across the catalog and a connection asked for), fixes the
arguments that pick the place from the owner's words (a list read on the taf's connection,
with the owner's OK, and a pick by name in a questions card, never an id asked for), says
what each field takes in words (`fill`), and drafts the place with `taf_dial` as the taf's
next version, which the owner installs (`runtime/src/cloud/setupdef.ts`). A place moves no
money: a money tool is never a place, and tafs cloud refuses one the taf's Cards count in money,
at the dial and when the version goes live (`places.ts`). Another taf can be the place: the result becomes its
work, one hop, never back and never Coach. Opened from a delivery that wasn't made ("Rae
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
When the owner installs a version Coach made, tafs cloud tells the owner's Coach (`[taf <name>
version N live]`): the app shows "Version N is live." in Coach's chat at once, and Coach ends
the improvement's plan on its next turn. A version Coach didn't make tells it nothing.

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
| `taf_run` (read) | One run of a taf so far |
| `taf_task` | Hands a taf a task or a message: a new run, or to a run at work |
| `run_stop` | Stops a run; its held calls are withdrawn |
| `taf_pause` | Pauses or resumes a taf's schedule |
| `taf_results` | Sets where a taf's results go, all places at once ([doors.md](doors.md#where-results-go)). With built-in places only, KIFF holds the change for the owner, who approves it in tafs; with a place at a connected app or another taf (`{place: "action", …, fill}`, `{place: "taf", taf, name, fill}`, as `PUT /v1/tafs/{id}/knob` takes them), it drafts the taf's next version as `taf_dial` does |
| `taf_dial` | Drafts how a taf runs, all its triggers, what it does when busy and where results go, as `PUT /v1/tafs/{id}/knob` takes them ([api.md](api.md)): the taf's next version, nothing running from it. Coach shows `{type: "knob", version, taf_id}` and installs it with `version_install`, held for the owner. Its Card, `coach-taf-dial`, allows 20 a day and holds nothing |
| `version_try` | Tries a version: KIFF decides each of its writes and sends none |
| `version_install` | Installs a version. KIFF holds this for the owner, who approves it in tafs: the app stops what's in the way, confirms the version's Card and makes it live |

Answering a held call is never a tool.

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

## Recipes

A new Coach conversation opens to **recipes** instead of a blank page (kiff/tafs#476): jobs worth
giving a taf, each with the words to say to Coach. Tapping one fills Coach's message box with
those words; the person edits them (the limits are examples) and sends. Coach then works as for
any request: it connects the tools from the catalog, drafts the Card and hands the taf over. Recipes
are suggestions, not finished products: there is no recipe-specific code, and when Coach can't
build one, the gap is fixed generically (a connection, how Coach picks tools, a Card rule), so a
person can prompt Coach freely. The five starter jobs tafs began with are recipes now.

The source is one file per recipe in [`recipes/`](../recipes/README.md): its title, category
(money, customers, operations, errands), one line on what the taf does, the vendors each
connection needs (any of several where one stands in for another), what its Card allows, holds and
never does, in words. `runtime/scripts/recipes.mjs` reads the files into `runtime/src/recipes.ts`,
and a test fails when they differ.

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

**Android** reads them each time Coach opens, with Coach's setup (`AppModel.loadRecipes`), and
draws them in an empty conversation that isn't improving a taf (`CoachStarts`): each category's
title, then its recipes' `title` and `line`. A tap sets the conversation's draft to `say`. Recipes
not read leave the conversation open without them, with no error: Coach still takes any job.
