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
  **Previous conversations** (`CoachHeader`). New conversation is never greyed out: on a plain
  conversation with nothing in it yet, it keeps that one and shows its top; otherwise, an empty one
  about a taf ("Improve Refunds") included, it opens a plain new one (Android `CoachWords.newChatStays`,
  1.27.6; iOS's New conversation button keeps an empty one, `coachChatIsEmpty`, and follows). A message to a chat that
  already handed a taf over goes to a new chat tafs cloud starts (`new_chat_after_handover`, [api.md](api.md)): Android
  sends the flag and shows that chat from then on, with the message on its way (`AppModel.coachSession`, 1.27.7); iOS
  follows. Under it, the plan in tafs cloud's one line
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
- **Previous conversations** lists them newest first. On Android, a long press asks about that chat by its title: Archive,
  or, under Archived, Put back. There's no menu that could open over another row.
- **The empty chat** shows the same starts as iOS (`CoachWords.EXAMPLES`,
  `IMPROVE_EXAMPLES`).
- **Coach's news** of the tasks it handed tafs shows under the thread, with "While you
  were away" together (`CoachLines`).

## Starting Coach, once per account

Before the first message, one Face ID (`AppModel.startSetup`):

1. The phone enrolls with planque: planque gets the phone's key, and a KIFF key that
   connects tools and does nothing else, minted with a signed setup call and never kept
   on the phone. The phone asks first with no key: while the key planque holds still stands
   (another device enrolled it), planque answers `kept: true` and no key is minted, since a phone
   can't revoke a connector key and a new one would only be left live in KIFF. Only when
   planque says it needs one (`409 needs_key`) does it mint one and hand it over. A 2xx without `kept: true` mints nothing and stops with "Connecting your apps didn't work. Tap Start to try again.", on every app until planque says what it means. Any other
   answer (planque not reached, a 5xx, a refused signature, a 400 for a body it couldn't read) mints
   nothing, since a second enrolment would likely fail too and leave the key live: the app
   says why, and the next Start asks again. Only a 409 with no code, an older planque's, says
   another device connects the account's apps; planque names each of its own 409s (iPhone and iPad: `AppModel.enrolPlanque`; Android: `AppModel.enrolForCoach`). If planque can't check the phone with KIFF (401), the app says "Connecting
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
   **Only the step that was shown is given.** Give reads `GET /v1/setup` again, and when its
   batch or its Cards' draft isn't the one the step showed, nothing is signed or sent: the step
   is drawn again from that read, with "What you're asked to approve changed since it was
   shown.", and the next Give gives exactly that. Cards tafs cloud drafts only once the batch is
   in were never on the step, so the batch goes, the step is drawn again with KIFF's words for
   the Cards, and a second Give gives them.
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
  first, then `give` and `not_now`; the button says what it does, with no question over it). The connections' `lines` and KIFF's words for the Card
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
  the `start.words.start` button (the lead says what Start does, once: there's no line under the button). There's no second screen, and nothing is signed unread. (1.19 opened
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

iPhone and iPad do the same (`SetupChatView`'s Start screen, `KIFFClient`'s `/v1/connections/batch`).

## What Coach shows

Coach writes messages and shows cards (`SetupComponent.Kind`). A card grants nothing by
itself: an approval names planque's grant and KIFF's draft, which the app reads from
those services, and the phone signs there. A card speaks for itself: Coach doesn't restate it before or after ("I'm waiting for…", "Next, approve…") and says
only what the card doesn't (`connect-an-app@24`).

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
   wait. A result with `cron` and no `check` is answered failed, in words Coach acts on. A check whose list (`items`) isn't a list in what the reads Coach made in that conversation answered is answered failed too, with the lists those reads do have, and nothing is made; with no read that answered JSON, or one cut short, the hand-over goes on. tafs cloud starts it as the
   person KIFF names and answers the card itself (`answered_by: "tafs cloud"`): kiff/agents
   claims the key from Coach's draft, and the taf joins the account's list
   ([tafs.md](tafs.md#starting-a-taf)). The conversation carries the card's answer
   (`answer.by`, `answer.outcome`: `{taf_id}`, with `check` when its first check couldn't read, or `{failed}`), and the card shows it.
   A first check that finds the taf's key not stored yet (the hand-over has just claimed it) is tried again, up to
   4 times over a few seconds; still not stored, `check` says so in plain words, never the secrets store's own:
   set up, with Open; why it wasn't, with no Try again, since Coach hands it over again
   itself; and until the answer comes, "Setting up…". A first check that read but didn't find what it named leaves the taf
   on plain timing, at most once an hour, and `check` says so: Coach fixes the check with `taf_dial`. A result whose Card the owner hasn't
   approved yet (KIFF's `draft_not_ready`) with no approval or sign-in card waiting beside it in the same run is answered at
   once, in the words below: nothing asks the owner for that Card (a redraft shown with no approval of its own), so
   Coach shows its approval, and the result once the owner approved it. That result, shown again under the same id, is a
   hand-over of its own: each is held per call (10 minutes, so a repeat note or a second phone makes one taf), never
   per card id, and its wait for the Card starts again; a note a hold skips is logged (`handover_held`). With one waiting beside it, the
   result isn't answered at first: it waits, and the taf is made once they
   approve, with the next note, chat read or sweep: the sweep tries a waiting hand-over every 5 minutes for
   its first hour, then on the deletions' backoff, so no one need open the app. Each try is logged. Still
   unapproved 15 minutes after the run paused on it, nothing is asking for that Card, so the result is answered in plain words (`{failed: "The
   Card for <taf> was never approved, so <taf> isn't set up yet: approve its Card to finish"}`), and Coach
   asks for the Card. The app starts nothing itself
   (Android: `CoachWords.handOver`, `HandOverCard`; for a taf set up that isn't listed yet it reads the list
   again, and, while Home doesn't list it, up to 3 more times 3 seconds apart (`AppModel.SET_UP_READS`), so the
   card never stays on "Setting up…" until a pull, Android 1.27.6). The iPhone app reads the list again once.
   A taf's next version is still made by the app, from an improvement's result.

A taf whose tools can't find its work by themselves is made **unscheduled**: it works
from its chat.

A taf made before checks existed keeps its schedule as it was: every firing wakes it. Coach
adds a check at its next dial change (`taf_dial`'s `check`), which goes live with that
version. A taf with a check keeps one timetable: kiff/agents keeps a check's cursor per
schedule, so two would hand the same work twice, and the dial refuses a second one with
the nearest single timetable (skills `set-up-a-taf@4`, `the-dial@2`).
Changing what a taf's check reads is no second timetable: `taf_dial` with the same wakes and the
new `check` replaces the taf's check. A check moved to another app moves the runs' reads with it, in a
version from `improve-a-taf` whose instructions read there and name none of the old tools. A wake never
takes input: the words a schedule's run opens with can't be changed from Coach, so what runs do changes
in the taf's instructions. A version's note says only what its instructions or draft carry: a new
summary alone changes no run (skills `the-dial@8`, `improve-a-taf@3`).

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
  So is a draft read without its Cards, on every grant: each check here reads them, so none is
  skipped, and nothing is signed. A draft with no Card at all (KIFF's `null`, or an empty list)
  is answered `failed: …` instead, so Coach drafts one rather than the card asking it again.
  `POST /v1/connect/grants/{grant}/check` runs the same checks with nothing signed, for a caller
  that confirms the draft itself (the e2e), and answers with each refusal's reason code, and
  `redraft: true` on a refusal a new draft mends (below). It also says `checked_on_read`:
  whether the chat's read checks this card at all, by the rule below (`checkedOnReadFor`), with
  the `can` the caller sends, so the e2e takes the product's rule rather than a copy of it.
  The same checks run as the phone reads the chat, before an approval card over a draft is
  drawn (a connected app's Card alone, or the Card signed with added tools or a key, `tools_and_cards` and
  `key_and_cards`), with the grants that read has, read with the phone's proof. A refusal a new draft mends (no Card, a vendor name, a tool on no Card or a stray one,
  an on-demand app's write let run, a lifetime count or total, a day's total twice, a count on a
  write with an amount limit) goes to Coach
  then, as the card's answer, and the card is drawn answered, never with Approve: the owner
  approves the redraft, one Card, not a refused one and then another. It's told once per draft
  version, across phones (a claim, then the answer kept a day). Anything else, or a read past
  its time, or a person whose role can't manage Cards or who hasn't agreed to AI use, draws the
  card as before, and the tap checks it again. Every refusal a new draft mends, at the tap too,
  goes on after its reason: "Refused by tafs cloud's check, not by the owner: this approval is
  closed and nothing waits for them." On a grant not yet connected, it adds that its tools (or its
  key) weren't approved either and come with the new approval. A `count_on_amount` adds that such
  a count is never one of the owner's rules, to drop without asking them. Then "Draft it again now
  and show a new approval." and, last, "Change only what this names; every other line stays
  exactly as agreed." (Coach read a bare `failed: …` as an approval still waiting.)
  **No Card is offered before its rules are agreed.** An approval over a Card draft, in a chat
  whose newest rules notice isn't agreed yet, is answered on the chat's read with Approve's own
  refusal (`rules_unagreed`), before the card is drawn and with no read of the draft: the chat's
  read has the agreement already (`rulesAgreement` over its events), so a draft KIFF is slow to
  return can't let the card through with Approve. Coach shows the rules' confirm and drafts once
  the owner agrees; the card reads "Approve · it didn't go through: Coach drafted this before you
  agreed to the rules it showed…". Once per card, across phones (`rulesFirst`).
  **The owner reads why, on every read.** What goes to Coach is written for Coach, and kiff/agents
  records only that, so the owner's own reason is kept beside it: whenever tafs cloud answers an
  approval card in the person's place with a failure (the check on the read, Approve's refusal at
  the tap, an approval that settled as failed), its note is kept for the card (per account, chat
  and card, 30 days), and every read of the chat words the card's line with it: "Approve · it
  didn't go through: Coach drafted this before you agreed to the rules it showed…". Only where no
  reason was kept does the line say "it didn't go through" alone (`keepApprovalNote`,
  `fillApprovalNotes`). The apps draw the line as sent.
  A Card covers a tool by the name planque registered for this taf (`request.tools[].name`),
  never the vendor's own (`tool`). When the vendor's name was taken in KIFF, planque gives the
  tool a free one (`list_charges_noyy`). A draft whose Card names the vendor's name
  (`list_charges`) while the registered name is on no Card is answered `failed: …` too. The
  answer names both, so Coach drafts it again over the registered name. That vendor name is
  another connection's tool in KIFF, whatever else holds it.
  A draft that counts one of the taf's write tools over its whole life with no `per` (count,
  `lifetime`, one total on the tool for every item) is answered `failed: …` too, naming the tool
  and its `resource_argument`: such a total stops the taf after its first item, so a limit for
  each item ("one invoice per order, ever") is a total `per` that argument, beside the day's
  total. A draft that caps one of the taf's write tools' amount over its whole life with no `per`
  (`sum(…)`, `lifetime`) is answered `failed: …` too, naming the tool and the total: the owner
  agrees a limit for each call and a day's total, never a lifetime one. A sum per item and a
  lifetime count per item are signed.
  A day's total of calls is drafted with `measure: effects` (`draft-the-card@43`, kiff/work#17, 21 Nov 2026): a call KIFF knows had no
  effect (the app refused it and did nothing, or it never left) gives its room back, which an attempts total never does.
  A money tool's amount limit stays a sum.
  A draft that limits one of the taf's write tools by its amount (a per-call cap or a total)
  and also counts its calls (`count`, a limit above 0), on the same Card or another, is
  answered `failed: …` too (`count_on_amount`), naming the tool, the count and its item if it
  has one: the owner agreed the amounts, and a count beside them holds calls those amounts allow
  (1,001 one-cent refunds under a €50 cap). A count per item is refused the same way unless it's
  the shape of the owner's limit for each item: `lifetime` or `rolling_7d`, `per` the tool's own
  `resource_argument` ("one refund per charge, ever"). So "1 a day for each payment intent" on a
  refund is refused, and so is a count per another argument, or any count per item on a tool
  with no `resource_argument`. A count of 0 held (each call asks the owner), and a count on a
  read or on a write with no amount limit, are signed. The draft doesn't mark which limits the
  owner gave, so a made-up count in the owner's shape still passes.
  **The rules the owner agreed, as data** (`rules.ts`). A rules `notice` may carry, beside its
  lines, `rules: [{tool, over: "ask" | "refuse"}]`: one entry per tool the draft will cover, at
  most 64, `tool` as planque names it before KIFF's suffix (`send_reply`, not `send_reply_k3f9`),
  since the rules are agreed before the tools are declared. The `confirm` that agrees to them
  names it (`agrees: "<notice id>"`), and the owner agrees by tapping its option `agree`. The
  apps draw both cards as before and ignore the fields. At the check, tafs cloud reads the card's
  conversation with the person's access and takes the newest agreement: only the person's own
  tap counts (an answer tafs cloud gave itself, words written while the card waited, or a card
  that stopped waiting doesn't), and `agrees` must name a rules notice shown in that
  conversation. Then each Card covering a tool of the taf's connections has `on_exceed: "owner"`
  where its rule says ask and `"refuse"` where it says refuse, matched through
  `request.tools[].tool` → `name` (a Card that says nothing holds, as KIFF does); otherwise it's
  answered `failed: …` naming the tool, the rule and the Card's `on_exceed` (`rules_on_exceed`).
  A write with no rule is refused too (`rules_missing`); a read with none, and a rule for a tool
  no connection has, change nothing. A rules notice shown after the agreed one (or never agreed),
  or the agreed one shown again with other rules, blocks the draft until the owner agrees to it
  (`rules_unagreed`). These go to Coach on the chat's read like the refusals above; the two that
  need the owner's agreement end "Show the rules for the owner to agree first, then draft it
  again and show a new approval." in place of "Draft it again now…", and then that nothing waits for the
  owner until Coach shows the rules' confirm, so Coach shows it in the same turn and never ends one on
  "waiting for your approval" with no card to tap (`SHOW_NOW`). With no rules notice in the
  conversation, the draft is approved as before and one line is logged ("approval rules: none
  agreed in the conversation"); with `TAFS_RULES_REQUIRED=on` it's refused (`rules_required`),
  once every Coach run sends them. A conversation that can't be read is read again, never trusted.
  The check route (`…/check`) checks them in the conversation its caller shows
  (`conversation`, its events), as the e2e does with the setup it runs itself; with none, it
  doesn't, and says so on every answer (`rules_unchecked: true`). Coach sends the
  fields from `draft-the-card@26`: the rules notice carries `rules: [{tool, over, limits}]`, only
  the owner's figures and as the Card will have them, and its confirm `agrees` with its id, with
  options Agree (value `agree`) and Change something. kiff/agents' component schema takes the
  fields; before that skill, nothing carried them, and every draft was approved as before.
  **The figures the owner agreed.** A rule may also carry the figures the owner read,
  `limits: [{quantity, window, limit}]`, 1 to 4: `quantity` as a Card writes it (`count` or
  `sum(<arg>)`, the argument 1 to 64 letters, digits, `_` or `.`, as kiff/agents' schema takes it; matched exactly, so `sum( amount )` is another quantity), `window` `call` (each
  call's) or `calendar_day` (the day's total), and `limit` a whole number from 0 (never) to
  2^53−1 in the Card's own units (minor units of the connection's currency, as
  `per_action_max.max` and `aggregates[].limit` are). A figure with `per` is a total for each item
  ("one invoice per order, ever": `{quantity: "count", window: "lifetime", per: "order_id",
  limit: 1}`): `per` the argument the Card's aggregate counts it by (1 to 64 letters, digits, `_`
  or `.`), and `window` any of an aggregate's (`calendar_day`, `rolling_1h`, `rolling_24h`,
  `rolling_7d`, `lifetime`), never `call`. A rule's figures are read whole or not at
  all: an empty list, a fifth figure, the same quantity, window and per twice, or a limit that isn't
  such a number leaves the notice with no rules. KIFF's own count (`count`, `calendar_day`, 10,000,
  no `per`), which a notice may list as "KIFF adds it", is never the owner's figure: it is dropped
  from the agreed rules as they're read, so it's neither held to the notice's words nor required on a
  Card (Eval 45062249364: read as agreed, it made Setup agree to it, then require it on the refund's
  Card, until Coach changed the owner's rule). The same notice shown again with another figure
  is other rules, and blocks the draft until agreed. At the check, each agreed figure is on a
  Card covering the tool with exactly that figure: `call` as a `per_action_max` of that quantity
  on the tool's grant, `calendar_day` as an aggregate of that quantity with `window:
  calendar_day` and no `per` (a total for each customer is another total), and a `per` figure as an
  aggregate with that quantity, window and `per` (folded). Missing, or another
  figure for the same quantity, window and per on any Card covering it, looser or stricter, is
  answered `failed: …` with the agreed figure and the shape that holds it (`rules_figures`), and
  Coach drafts it again. A rule with no figures is checked as before. A figure may say what happens
  past it, `over: "ask" | "refuse"`, when that isn't the tool's: "ask past €5 a refund, refuse past
  €50 a day" is one rule, `over: refuse`, whose call figure says `over: ask` (Eval 16: with one `over`
  per tool, the notice couldn't say it, and kiff/agents refused it three times). The rule's `over` is
  the default for a figure that says none. Each Card covering the tool then has the `on_exceed` of
  the agreed figures it carries (the tool's when it carries none), so two figures with different
  answers on one Card are refused: one Card has one `on_exceed` (`rules_on_exceed`, naming the
  split). The same notice shown again with another figure's `over` is other rules. **A limit the
  owner never agreed is refused** (`rules_unagreed_figure`): on a tool whose rule has figures, any
  figure a Card adds for a quantity and window the rule doesn't name, in any window (each call's, a
  day's, `rolling_1h`, `rolling_24h`, `rolling_7d`, `lifetime`). Coach hears that one figure, and to
  draft again without it, changing nothing else. The person reads the rules-figures note (Eval
  43927756622: after a refused draft, Coach signed an hour's €50 held beside the agreed €5 a refund
  and €50 a day refused). A total for each item (`per`) is unagreed unless a figure with the same
  quantity, window and per names it: on a rule that names a `per` figure, always; on a rule that
  names none, only with `TAFS_RULES_PER=on`, switched on once Coach's rules carry `per` figures,
  and until then left to the earlier checks. A count of 0 (each call asks) only asks, so it isn't
  refused here. **A figure the notice's words don't say is refused** (`rules_unsaid`): the owner
  agrees to what they read, so each agreed figure's number, in the Card's units or in hundredths,
  is in the notice's `text` in digits ("€5", "5,00 €", "1.000"), or, for 1, as a word for one that
  is never an article, with its language's per word at most three words after it ("one invoice per
  order", "één factuur per bestelling", "eins pro Bestellung", "jedna faktura dla każdego
  zamówienia"). An article never says 1 ("a", "un", "una", "ein", "een", "um": "una factura enviada
  por correo" says no limit), nor a per word that is mostly a preposition ("na", "za", "je"), nor a
  pair from two languages. Anything else, another figure in words, is unsaid.
  The Cards stay as drafted: Coach hears the figures, shows the rules again with each one in
  digits, and drafts again once the owner agreed, as `rules_unagreed` (Eval 44765665210: the
  notice said "a draft invoice for each one", the Card held 10 a day and 1 per order, and it was
  signed). Coach writes them so from `draft-the-card@41`.
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
  `AppModel.expectedApprovalMessage`, iOS the same). The connect step's whole approve body must also be the one the
  phone builds from what the card showed: `{digest}` with the digest shown, plus `kiff_profile` with the digests of KIFF's
  words only when the card showed them (Android and iOS: `AppModel.shownConnectBody`). Another profile, other words, words
  left out or a field added, and nothing is signed. It signs that message and nothing else, makes that
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


**A message during an approval waits.** Once tafs cloud asks the phone to sign, the person is approving
(a fingerprint, perhaps a vendor's sign-in), and the card's run waits for the report. A message they send
meanwhile, to that chat, doesn't start a new run, which would leave the card waiting for nothing. It's
steered into the run the card waits in (`held: "approval"` in the answer, or in the stream's `session`
event), and Coach reads it once the approval is settled. What that run never reads is sent as the next
message once it's over, as for any held message. The wait lasts as long as the ask (20 minutes). A report
that comes in after the card stopped waiting anyway (a run that went on) still counts: the phone gets the
outcome, never a conflict, and Coach reads "[approval …] The owner's approval was settled after their
message: …" with the next message (go-to-market job 3, 13 Nov).
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
tafs the person manages, it asks "Which taf is this for?" first, even with one: a connection
goes only to a taf the person named, never a guess (iPhone and iPad from tafs#1135, Android 1.30.1). The list is tafs
cloud's `needed_by` for the app when it sends it (Android 1.31.0), and an app no taf needs offers Ask Coach. With
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
  through tafs cloud or planque). Android asks for `kiff.words.v2` (`?v=2`, Android 1.27.7): v1's lines without KIFF's
  bookkeeping (no "isn't pinned to a version", and the lost-answer line only where money moves). It takes v1 or v2 and
  signs whichever KIFF sent; iOS follows;
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
| `taf_task` | Hands a taf a task or a message: a new run, or to a run at work. Its answer reaches Coach at its next step while it works. Once Coach's turn has ended, the task's end starts Coach's next turn in the same chat (`[tafs go on]`), so Coach does what it said it would with the answer and the person sees it there: one Coach turn each, on the person's AI consent, at most 3 in a row per chat in 30 minutes. Past that, or when it can't start, the line waits for the person's next message. The person's line in Coach's chat never shows an answer that is a tool's raw result (a JSON object or list): "It finished: open its run to see its answer." |
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
`{title, intro, groups: [{name, line, tools: [{name, does, why}]}], details, give, not_now, row}`.

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
  opens the same step. It's never a panel above the conversation. When only Cards for tools Coach gained
  since are missing (every tool connected), `row` says so: "Coach has new tools: approve their limits".
- **An account that has Coach, with tools and Cards missing** (`TAFS_COACH_START_ONE=on`): `tools` also
  carries `scheme: "kiff.requests.v1"` and `requests`, the tools batch (`connect`) and the Cards' draft
  for Coach's own agent (`draft`), worded by KIFF's gateway over the tools the batch would connect and
  carrying that `contract_digest`, as a first start's pair. The phone signs both hashes once, sends the
  batch, then the draft; the draft's submit is the confirmation, as for `card`. Without the flag, or when
  KIFF can't word the pair, it's the batch, then the Cards on the next read, as before; `connect` and
  `card` stay for apps that don't read `requests`.
- **The phone signs only the step it showed.** At Give, the app reads `tools` again and compares the batch, the
  Cards' draft and the pair, byte for byte, with what the step showed. On any difference it signs nothing, draws the
  step again from the new read, and the person gives again. Cards drafted only after the batch are drawn before
  they're given, never signed with it (iPhone and iPad: `AppModel.toolsBodies`; Windows: tafs-windows#163).

When a run Coach handed a taf pauses or ends, kiff/agents tells tafs cloud
(`POST /v1/runs/ended`). tafs cloud reads the run back from kiff/agents rather than
trust the note, and Coach hears it in its run at work, or at its next turn. kiff/agents
sends a note at least once, so tafs cloud acts on each at most once: before it tells Coach, keeps
the chat's line or notifies the phones, it claims the run's state as read back (an ended run by its
status, a paused one by its resume count and the calls it waits on, `noteClaim`). The same note again, or two at once, is
answered `taken: true` and does nothing more, once it was told; while another handling still holds it,
the same note is answered 409 `being_told` and does nothing, so kiff/agents keeps it owed and sends it
again at its next wait (a 2xx would settle it). A pause on another call is told again. The claim is a
two-minute lease while the note is told (`NOTE_LEASE_MS`, shorter than kiff/agents' first retry),
then a mark kept as long as the task. A telling that fails releases the lease, and one killed midway
(a timeout, a deploy) leaves only the lease, so either is told when the note comes again. A result card's hand-over and a
door's reply claim their own keys the same way. kiff/agents
announces only to URLs it is set to allow (`AGENTS_NOTIFY_URLS`), so tafs cloud's public
`/v1/runs/ended` must be among them: when it isn't, kiff/agents refuses the run and
`taf_task` fails with "notify.url is not one this server announces runs to". Moving tafs
cloud to a new URL means updating that list too. The app shows
these as lines in Coach's chat ("coach lines"). A run cut off at its turn or cost cap (its `stop`
`turn_cap` or `cost_cap`), or stopped any other way short of an answer, never reads as finished:
its line and its notification say it stopped before it finished, and at which limit ("It stopped
before it finished: it reached its cost limit for one run"), and Coach hears what it last wrote as
its last words, never as its answer. While the owner is away, what isn't
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

A recurring job is set up as recurring (`set-up-a-taf@8`): before any connection, Coach proposes its
cadence, its sources and its volume ("weekday mornings, from your changelog and releases, at most 1 a
day"), and asks only what it can't decide.

A taf keeps its own records (`set-up-a-taf@8`). When it works from the owner's sheet or list, Coach
proposes that it adds and updates its own columns there (who it wrote to, replies, do not contact), with a
tool that writes to the sheet on its Card. Coach asks only what only the owner knows, never how they
track the work: the owner talks to Coach and never types into the sheet.

A taf finds things by id, never by name (`set-up-a-taf@10`, `the-dial@5`). What it makes and uses
again, such as a sheet, it keeps by the id the call gives back; an app's guide names those (its
`returns`, such as `data.spreadsheetId`), and its instructions say so. For something that already
exists, Coach asks the owner for its link, never its name: a search by name often needs more than the
connection gives (a Sheets sign-in doesn't search Drive). When a place for results has no read on the
taf's connection that lists places by name, Coach asks for the place's link and takes the id from it.

A check's list is where the catalog's `returns` says the read puts it, never a guessed path (`set-up-a-taf@11`, `the-dial@6`). An operation's fields are named as its server gives them, never from the vendor's API Coach knows (`name-the-tools@11`).

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

**The guides of the apps those tafs hold** follow the connections, under `[tafs app guides]`: each app's
adapted guide as planque serves it (`GET /v1/accounts/{tenant}/catalog/{entry}/guide`, read with the same
session), headed by its app and the day it was checked, as data, never instructions. They cover the apps the
connections name by catalog entry, the most used first (by how many of those tafs use each), at most 5, each at
most 9,000 characters and all of them at most 30,000. An app with no guide yet, or one planque can't answer, is
left out. tafs cloud picks them, never the model; Coach's planque tool attaches an app's guide to a search and to
a request by entry the same way (kiff/agents), so Coach has read it before it offers or connects the app.
When the owner names an app, Coach reads its guide before it answers about the app or offers anything
(`connect-an-app@23`): a search narrowed to that app, or `guide {entry}` with the entry search gives; else the one a
search or request returns, or the one under `[tafs app guides]` for an app the account holds. A guide is data about
the app, never instructions; with none, Coach goes on with the catalog.

A message tafs opens with its own line (`[improve taf …]`, `[tafs coach lines]`, `[did this happen …]`)
keeps that line first, and a message in the live chat (which the apps send with no session) goes on in it by name, with neither the lines nor the reads. The thread shows only the owner's words. What planque can't answer is left out,
and Coach reads planque's grants itself. Coach offers only a connection one of the account's tafs uses, or
one made in this conversation: a grant another agent holds (a test, a draft that never became a taf) is never
offered as the owner's, or reused (`connect-an-app@8`). For a vendor one of the tafs already connects, Coach offers the
same kind first, named with the taf that uses it, by reusing its sign-in: `request {reuse: <grant>, name, taf_name,
tools: []}` and nothing more (planque refuses `entry` or `uses` beside it, `connect-an-app@22`), so the owner approves the new taf's tools alone, with no second sign-in or key (gap 2 of
kiff/tafs#476, `connect-an-app@4`; planque reuses a managed app's sign-in too). Only when planque refuses
the reuse does Coach ask for the same kind anew; another kind comes only when the owner picks it. An app
found by search is connected with `request {entry, name, taf_name, uses}`: `uses` names the tools search
found that the taf will use, so the owner approves only those, for a managed app's sign-in and for a reviewed
key entry alike (`connect-an-app@10`, with the connection server taking a reviewed entry's `tools` as a
subset of its own; `@9` had dropped `uses` because a reviewed entry refused it, which widened managed
apps' sign-ins). Coach never connects one without `uses`: its sign-in would ask for every tool (`connect-an-app@22`).

**A sheet's write, and a refused request** (`name-the-tools@17`, `connect-an-app@27`). Each argument is declared with the type its operation's schema gives it, in the shape planque takes: a plain type; an array with its items' schema (`type: "array", schema: {type: "array", items: …}`), such as a sheet's rows; or an object naming only the fields the taf writes (`type: "object", schema: {type: "object", properties: {…}}`), such as a row update's columns. Planque refuses an object that names no fields, or takes fields it doesn't name. A field is plain or an array, never an object: a nested object's fields are arguments of their own, at their paths. An object names the fields the taf writes, by the names the job or the owner gives (the column it marks), never none; a name Coach doesn't know is asked with the rules. A row number is never `resource_argument`, because planque takes only a string argument there, so a sheet write is limited by its calls a day. When planque or KIFF refuses a request itself (a name taken, an argument), Coach changes what the refusal named and never sends the same request again.

**What the taf does; what a sign-in allows is the card's** (`connect-an-app@29`). Coach says only what the taf will do with an app ("it reads your sheet's rows"), and never what the app's sign-in allows, either way: never "read-only" or "changes nothing", and never "wider" either. What a sign-in allows is planque's data: tafs cloud draws it on every approval and sign-in card whose grant has `request.connector.usual_access` (`wider.words`, [chat.md](chat.md), `markWider`), the same for every app, so the skill holds no app, scope or access wording. If the owner asks, Coach says the card shows it. Every app that can approve a card draws `wider.words`. Before this, a plan for Google Sheets titled its step "Connect Google Sheets, read-only" and its first card said "it changes nothing", for a sign-in that can write; `@28` then had Coach add its own "wider" sentence, which it sometimes left out (Eval 22b).

**No narrowing, the sign-in's clock and account, and no sign-in again for a tool it lacks** (`connect-an-app@30`). Coach
never narrows what a taf or its connection can do in words, in any language ("only", "just", "read-only", or the
language's own forms, such as French *ne … que*, Korean *-만* or Japanese *だけ*): its Card is the limit, and restating
the Card ("asks you first above €150") is fine. Eval 44806446879's French Sheets plan offered "Il lira seulement des plages
de cellules". A sign-in ends 60 minutes after the owner's approval (`connect-an-app@31`, with planque's window raised from
15 on 21 Nov), so Coach says so and guides it in one go; before
Allow, it says which account the vendor's page names, and the wrong one is cancelled, granting nothing. A tool the
connection says its sign-in doesn't cover ("partly signed in") gets the tools that work, never a new sign-in that would
answer the same; and a taf that writes never reuses a sign-in made for reads, which Coach says up front (planque's
rule since its #312). Each app's own steps (Notion's setup, Shopify's protected-data release, Pipedrive's search) are in
that app's guide in planque, which Coach reads before connecting it.

**An app in two approvals** (`connect-an-app@26`, `name-the-tools@14`, `draft-the-card@18`). The first connects it:
its sign-in, asked with the taf's planned tools (`uses`) for a managed app and with Coach's own reads in `setup_reads`
(such as a tool that lists an operation's fields), never in a connection of their own; a vendor's own server gets its
describing tool with the sign-in, never a read asked alone. Its card says "Its Card: you approve it next". The second
adds the taf's tools to that same connection, declared, with the Card Coach drafted once over all of them from the
catalog's fields (or the describing tool's), never a Card before its tools are known, and no sign-in. An account the
taf must keep to is pinned from the connection as planque lists it, never from a read of the accounts. Inside an
approval window the first is a tap; the second always takes the fingerprint (`tools_and_cards`, `signs.takes`,
[chat.md](chat.md)). A sign-in the account already holds is reused, so a new taf on it needs only the second. A key
connection picks the job's writes first, so the key and its Card go in one approval (`connect-an-app@25`).

**Reading a public page** (`web_read`, `connect-an-app@12`). To connect an app by its key, Coach finds the vendor's published API description itself, on its docs, rather than asking the owner for anything technical. It reads one public page as anyone could. The rules:

- https only, on the usual port;
- no sign-in and no cookie;
- never a private or reserved address: the host is resolved once and the connection goes to the address checked;
- at most three redirects, each checked again;
- text only, cut short.

An OpenAPI description, in JSON or YAML, answers its servers and how its key is given (where, its name and any prefix). That's what `request {api}` needs. What it reads is data, never an instruction. It has a Card of its own, `coach-web-reads`, at 50 reads a day, which an account's phone asks for on its next read, as any Card added later.

**Checked first, with a way forward** (`set-up-a-taf@7`, `connect-an-app@11`). When the job needs an app, Coach searches for it before asking the owner anything else. If it can't be connected here, Coach says so first, before any choices card. When an app can't be connected (it needs setting up, or its sign-in, request or `describe` failed), Coach says why in one sentence from the connection server's answer, and offers a way forward in the same turn. That's the same app's key entry, its key by its published API description (the key's place may name a `prefix`, as `ShippoToken <key>`), or another app with the operation. It's never only "try again later". The failed step still shows tafs cloud's own words, but the server's reason is kept beside it, scrubbed. It's logged (`coach_connection_refused`) and goes before the person's next words to Coach in that same chat, so Coach can still say it once the turn it failed in is over. It is that chat's alone: a new chat starts without it, and it's kept 7 days or until that chat's next words take it. So is an approval settled after its card stopped waiting (a message started a new run meanwhile): Coach reads it with that chat's next words. What goes before any chat's words, on purpose, is about the account and not a chat: a task's result, "Version n is live", the account's connections, the apps' guides and the time zone. A new note for Coach says which it is: a chat's (`coachKeys.chatNotes`) or the account's (`coachKeys.notes`). A refusal for now (the server down, busy, "try again later") goes with a line saying it was the server's answer then, so when the person asks for that app again Coach makes a new request; one the app can't get past (a scope it lacks) stands as said. The person sees it too: the message of the run that was refused carries `refused`, the step's words, the reason ("Why: …") and what to do next, which both apps draw under Coach's words, until a request for that app goes through in the same run ([chat.md](chat.md)). A vendor's sign-in comes before its key, even when
search finds both; a key is given on a secure sheet on the phone, outside the chat, straight to planque,
and never reaches Coach (`connect-an-app@7`). Before a key request, Coach asks planque to `describe` the API description: its operations as planque names them (name, title, read or write), with no grant and no key. A big description (over 200 operations) answers only its count, and Coach describes it again with words from the job (`q`), getting at most 50 matches, best first (`connect-an-app@15`). Each tool on the key request carries its full spec, as `name-the-tools` sets it on a connected grant (pins in `fixed_arguments`, `resource_argument`, the amount with its unit, `read_only`), from the operation's arguments as `describe` gives them, `{name, type, required, in}` (`connect-an-app@20`, `name-the-tools@9`). planque refuses a `resource_argument` that isn't one of the operation's string arguments before declaring. The request then names the job's operations as its tools, by those names, never none (`connect-an-app@14`): planque declares a key request's tools to KIFF before the key only when it names them, and only a declared request lets the owner sign the key and the taf's Card in one approval; without them, the key comes first and the Card after. A mode
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
changed rule, shown in a new rules notice and agreed again before the draft (`draft-the-card@7`). Each line of the rules notice says where its rule is kept when the Card doesn't hold it: "the connection keeps it" (an argument the connection fixes, such as one workspace), "KIFF adds it" (the disclosure line), or "in its instructions" (what KIFF can't count) (`draft-the-card@13`). A limit the owner sets on what a write may touch (only one column, one field, one folder) is a rule like the others: pinned with `fixed_arguments` where the tool takes it, else written into the taf's instructions, and its line says which (`draft-the-card@29`; Eval 17's introductions: "It only writes to that column" was in the notice and held nowhere). The rules' confirm offers only Agree and Change something: another rule Coach would offer (a lower limit, held instead of refused) is its own question before the rules are shown, never a tap on the agreement, so the Card is drafted from the answer and never from an option the owner didn't choose. Each tool's `on_exceed` is its agreed line's: `owner` only where that line says the owner is asked, `refuse` otherwise (`draft-the-card@19`). Before the approval, Coach checks each line's tool is the one planque lists for what the rule says, and once the owner approved a connection's Card it never drafts it again: if it finds something wrong, it says what and stops (`draft-the-card@20`). tafs cloud holds it to that: once a conversation approved a connection's Card, another draft over that connection in the same conversation is answered `failed` and never signed (`approval.ts`, `approvedDraftKey`, kept 7 days); the same draft again is the approval it was, and another conversation, such as a later Improve, drafts as its own. At the approval, every tool the taf will hold must be on a Card, the draft's or one in force: this connection's, and those of each of the same taf's other connected apps (`approval.ts`, `uncoveredElsewhere`), since a tool KIFF refuses stops the taf's session at its first read. The rules name each read tool the taf gets, by what it does ("List rows"), never "each read tool": that read as every tool the connection offers, ones the taf isn't given included (`draft-the-card@21`). A tool's amount over a day is counted once across the draft's Cards, on the Card whose on_exceed is the agreed answer; a held per-action limit's Card has no day's total, and no limit the owner didn't agree to is added. tafs cloud holds it to that: a draft that counts a tool's amount over a day twice isn't signed, and Coach hears both totals (`approval.ts`, `dayTotalTwice`). Reads go on a Card of their own, whose one count, 10,000 reads a day (`READS_A_DAY`, the same in the skill and in Approve's `uncovered` hint), is KIFF's (KIFF refuses a Card with no total: "a mandate with no aggregate bounds nothing"), shown as "KIFF adds it", never as the owner's limit, and with no number in the skill's example for Coach to copy (Evals 10 to 14 drafted "1,000 a day" on the refund). A write's line holds only the limits the owner agreed (`draft-the-card@24`), since a count nobody agreed can stop the taf at its limit and was never in the rules the owner saw. No other ceiling, count or total goes on as a safety margin either: when the count was refused, Coach put a lifetime total on the refund instead. Approve refuses both shapes (`lifetime_sum`, `count_on_amount`), and Coach redrafts. A refusal that says "Draft it again" was never shown to the owner, so Coach does what it says at once and shows only the new draft's approval, never a card for the refused draft or an ask to approve it (`draft-the-card@27`; in Eval 16, Coach spent its last turns asking the owner to approve the refused draft).

A taf that writes to people names who they come from: Coach asks once, with its other questions,
offering first "tafs, your AI assistant", so the taf speaks as itself (`writes_for` "tafs": a recipient
reads "on behalf of tafs", never "your" assistant), then the owner's name, then the company's, never the mail app's default (gap 16; `draft-the-card@11`), and its result's taf carries the
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
an operation that `sends`, with `resource.outside`, sends to people who may be outside the company, and a draft
(`none_until_approved`) drafts a message that reaches them as written once the owner sends it (a draft email or
reply), so it carries the disclosure too, whatever the job or recipe. A draft counts **by default**, so a forgotten
mark still discloses; one that stays out of it says `resource.outside: false`, as a draft invoice does (the
disclosure is about who wrote a message, and an invoice has its own legal rules). A reviewed
catalog entry's profile is the reviewed one, and one Coach drafted counts too when it says so, since that can
only add a disclosure. tafs cloud reads grants only on the phone's Approve, so each Approve of a connection, or
of its Card, notes its sending tools for the taf's KIFF agent, replacing that connection's earlier note. The write holds a claim on the note for at most 10 seconds, so two Approves of the same taf at once both keep their entries; one that finds the claim held waits and tries again, and past its tries the Approve fails, to be tapped again. A write gives its claim back only within those 10 seconds; one that took longer leaves it to expire, since another write may hold it by then. Coach's
result is then checked against the note: a new taf, or an Improve, whose taf has a noted tool that neither its
`disclose` nor the taf's existing disclosure names is refused, naming each tool ("disclose doesn't name
send_email, which reaches people outside the company …"), and Coach sends it again with the tool named. A note
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
`read_skill` stays as Coach's own fallback, by the names in the map. `a-browser-taf` is read that
way, only when the start's `[tafs connections]` has a Chrome line ("- Chrome: paired: Work Mac", or
"offered, none paired yet"). tafs cloud writes that line only while planque lists the account's
paired Chromes (`chromes` in its connections answer, while the owner's own Chrome is offered), so
Coach never offers a taf in Chrome while it's off. A request from the watch
opens a conversation of its own with one skill, `ask-a-taf`, named-only: only tafs cloud's signed
watch route loads it, never a message's text, Coach's map doesn't list it, and that conversation reads
no other skill and is offered only `tafs_state`, `taf_task` and `read_skill` ([watch-ask.md](watch-ask.md)).
A tool's answer that imitates
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

The gallery shows only the jobs whose apps connect today (rule 13): each of a recipe's connections needs an
app in `CONNECTS_TODAY` (`runtime/src/cloud/recipes.ts`), the apps kiff.dev's apps list says work, each
proved by a check that connected it. A job whose app can't connect yet ("Chase overdue invoices",
"Answer questions about orders") stays out, and comes back with no other change once its app is
added there, as "Follow up new leads within the hour" did when Typeform was proved, and "Book and
move appointments" when Google Calendar was. "File receipts" waits for Google Sheets, whose sign-in
Coach must never call read-only first.

**How an app is proved.** An app joins `CONNECTS_TODAY` only after one of two checks has connected it:

- **By its key:** the app's published API description reads through planque's own parser, with the auth it
  names, and lists the job's tools. An app with no published description can't take this route.
- **By a sign-in:** a tester completes the app's sign-in through Coach on a phone, planque shows the grant
  `connected`, and one read-only call returns data. The taf never runs and nothing is written. Then the taf
  is archived and the grant disconnected.

The result is posted as one line: the app, `connected`, the read's tool and a count, with no ids or account
names. Then kiff.dev's apps list marks the app as working, and a tafs PR adds it to `CONNECTS_TODAY`, with a
test that its jobs are listed. That list is moving to one source: planque's catalog marks each entry a check
connected with `proved: {at, link}`, and with `TAFS_PROVED_FROM_CATALOG=on` tafs cloud reads the apps that
connect today from it (`connectsOf`), for the recipes, the gallery and the app each job draws, so a proof is
one data change. A catalog that can't be read, or that marks nothing proved yet, keeps `CONNECTS_TODAY`, so no
read empties the gallery. The flag stays off until kiff.dev reads the same mark. A proof covers the account it ran on: an app whose accounts live in another
region (a separate API address) is proved for that region only.

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
