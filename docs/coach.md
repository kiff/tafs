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
  **Previous conversations** (`CoachHeader`). Under it, the plan in tafs cloud's one line
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
  brings kiff/agents' words for it; never "Sending your answer…" after tafs cloud answered, even if
  the screen was torn down meanwhile (a vendor's sign-in page opening).
- **Coach's tools in one tap:** when tafs cloud sends the connect batch before Coach's Card (the
  Card is drafted over tools KIFF holds), the app reads the tools again after the batch and gives
  the Card in the same flow. A
  taf is improved from a hold's **Change the limit in Coach mode**, which opens Coach on a
  new conversation about it (`AppModel.improveInCoach`).
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
   on the phone.
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
   how much a day. KIFF takes a Card only for a tool that is connected.
3. **Approve the Card.** The owner reads the Card on its approval card and confirms it
   with one Face ID, which signs exactly the draft version shown. An editor's draft
   waits for an admin to confirm it in KIFF.
4. **Start the taf.** The result card hands over the taf. tafs cloud starts it as the
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

## Connections

A taf's connections are on its Card tab (`ConnectionsPanel`): whose keys, which tools,
what happened last. **Disconnect** removes the vendor's tools from KIFF and revokes the
sign-in at the vendor, after Face ID. A connection none of whose tools is on the live
version's Card is shown as left over: the taf doesn't use it now, but an older version
may.

Reading connections asks for no Face ID; it is signed so planque shows them only to the
enrolled phone.

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

Its result card makes the version (`POST /v1/tafs/{id}/versions`), once per result:
nothing goes live.

## Versions

A taf's versions are on their own screen (`VersionsPanel`), like app builds: newest
first, the live one marked (`GET /v1/tafs/{id}/versions`).

**Install this version** makes one live, forward or back, with one Face ID
(`AppModel.versionCard`). When the version brings a Card revision, it's three steps, so
nothing ever runs with one version's definition under another's Cards:

1. tafs cloud begins the switch (`POST /v1/tafs/{id}/versions/{n}/switch`): from here
   nothing of the taf runs. A run in the way answers 409 naming it; the app can stop it
   first (`stop_blocking`).
2. The owner reads the Card the version brings (`VersionCardSheet`: each Card as it is
   now and as it will be), and the Face ID signs its confirmation in KIFF.
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
| `tafs_state` (read) | Every taf: its live version and versions, a switch under way, its schedule, its open runs and what each waits on |
| `taf_run` (read) | One run of a taf so far |
| `taf_task` | Hands a taf a task or a message: a new run, or to a run at work |
| `run_stop` | Stops a run; its held calls are withdrawn |
| `taf_pause` | Pauses or resumes a taf's schedule |
| `taf_results` | Sets where a taf's results go, all places at once ([doors.md](doors.md#where-results-go)). KIFF holds every change for the owner, who approves it in tafs |
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
