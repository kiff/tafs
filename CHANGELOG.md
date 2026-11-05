# Changelog

User-visible changes and compatibility notes are recorded here. Dates and
version headings refer to releases, not merely to merged pull requests.

## Unreleased

- **tafs cloud, a failed step says why (kiff/tafs#476, gap 9).** The stream's `step` event and the thread's steps carry `failed: true` and `why`: the first line of what the tool answered, in the owner's words, with ids and codes left out. The apps draw `why` under the step, and the " · failed" is the service's word, not theirs (`docs/chat.md`).
- **Coach:** when its dial, install or results tool is refused because the owner hasn't given Coach that tool yet (an account from before it), Coach says so in one sentence and stops, instead of stopping silently (kiff/tafs#476, gap 5).
- **Sign in to a site (kiff/planque#86).** tafs cloud offers `site_sign_in` on a connected browser connection. On Android, the connection's sheet shows it, and a tap opens planque's live sign-in view in a web view, under planque's words, where the owner signs in to the site themselves. Nothing else is shown, and the view never leaves planque's address.
- **Android: any app on demand (kiff/planque#87).** Connect apps shows an app that isn't set up yet with tafs cloud's words in place of Connect, and nothing to tap. A taf's connection sheet says who keeps its sign-in, in tafs cloud's line.
- **Recipes:** the words to say to Coach name the kind of app ("my accounting app", "my email"), never a brand, since an app sold in one country isn't in another and the account may already have a different one; Coach finds the apps by those words and proposes the ones the account has first (kiff/tafs#476, gap 2).
- **tafs cloud, approvals:** a Card draft for an app set up on demand that would let one of its write or money tools run without asking isn't signed: Coach hears which tool, and drafts it again held. The default now holds in tafs cloud, not only in Coach's words (kiff/planque#87).
- **tafs cloud, Connect apps:** an app the connection service lists as needing setting up by an operator comes through as `state: "needs_setup"` with `words` for the apps to show in place of Connect, a taf's Setup connections say who holds the sign-in (`custody_words`) and whether the app was set up on the owner's pick (`on_demand`), and a catalog read that stopped with pages left as `partial`; the recipes route treats a partial read as unfiltered.
- **Coach (any app on demand, kiff/planque#87; kiff/tafs#476 gap 1):** when the owner names no app, Coach searches tools by words across every app the catalog can connect and proposes the apps before connecting; a long catalog is searched by words, never a reason to stop; an app whose sign-in was set up on the owner's pick gets its writes and money drafted to ask the owner each time until the owner widens it.
- **A Telegram door (Android):** Setup › Starts when › Add a door… › Telegram picks the taf's Telegram bot connection and adds its door, with nothing to sign. People are paired from their first message's knock, or by their Telegram user id. Every word of the step and of pairing, and the connection's provider, come from tafs cloud (`bot` on the offered app; `pair_hint`, `pair_from_knock` on the door), so the app keeps none of its own.
- **Android: recipes in Coach (kiff/tafs#476).** A new Coach conversation shows tafs cloud's recipes by category, each with its title and line. A tap puts its words in the message box for the person to edit and send; it never sends. The app's own three example prompts are gone. Without recipes (tafs cloud not reached), the conversation opens without them.
- **tafs cloud: Coach makes any job (kiff/tafs#476).** A taf's job is its own, drafted by Coach from the owner's words: what it does, what each run is asked to do, how it works, and when it runs or that it works from its chat (`POST /v1/tafs` takes `kickoff`, `instructions` and `cron` or `unscheduled`, with no `job`). The five starter jobs keep working for the tafs that have them, and are recipes now.
- **tafs cloud: recipes (kiff/tafs#476).** `GET /v1/recipes` serves the jobs a new Coach conversation opens to, each with the words to say to Coach, by category, filtered to what the account's catalog can connect. The source is `recipes/`, one file per recipe. The first batch is eleven, the five starter jobs among them; a tap fills Coach's message box, and the person edits before sending.
- **tafs cloud, iOS and Android: tafs cloud decides an approval's outcome (kiff/tafs#413).** On Approve, the apps send what the card showed; tafs cloud reads planque's grant and KIFF's draft and says what needs signing, if anything (`POST …/components/{component}/approval`), answering Coach itself with `already approved: …` or `failed: …` when nothing does. The phone signs only the message tafs cloud names, makes the one call as given, and reports what came of it, signed as a setup call (`POST …/approval/{ask}`); tafs cloud asks KIFF whether the phone signed it and reads what holds before Coach hears `approved`. Every word the cards show for an outcome is tafs cloud's. Older apps keep answering approval cards themselves.
- **A Telegram door (tafs cloud):** a taf can be reached through the owner's own Telegram bot, connected in planque, the same way as the Slack door. One door per bot (route `telegram:<grant>`), senders and keys are Telegram user ids, strangers knock, and the taf posts its own answer with the bot's send-message tool under its Card. tafs cloud holds no Telegram token.
- **iOS (v27):** Home's line and the Needs you badge use tafs cloud's `needs_you` count and words; when it sends none, the app counts held calls, currency requests and "Did this happen?" calls. An app's sheet shows `workflow_words` once, under Workflow.
- **Android (v27):** Home's line and the Needs you badge show tafs cloud's count, which also counts a knock at a door. An app's sheet with no saved workflow says "Ask Coach to set its workflow" once, under its own Workflow line. The UI tests no longer fail when the emulator's configuration settles after the first test starts.
- **v27, tafs cloud:** Home gets `needs_you: {count, words}`, the count of what Needs you lists (held calls, "Did this happen?", currency asks, tafs with a knock), so Home, the badge and the lock screen agree with it; left out when KIFF's "Did this happen?" list can't be read. With no saved workflow, an app in Setup sends `workflow_words` for its own Workflow line instead of a line on an action. A currency ask names the action without a currency it ends with ("…, in EUR").
- **v26, tafs cloud:** a schedule saved before the account's time zone changed now follows it by itself, hours as written, the first time someone who manages Cards opens the tafs (a viewer's read changes nothing and still says how to apply it). An app's sheet with no saved workflow says "Ask Coach to set its workflow" once, not on every action.
- **iOS, from the v25 phone check (v26):** a Resume not answered in time says "Face ID timed out. Try again." (or Touch ID). Account › Version reads the version first, then the build ("1.0 (26)"). An app's sheet skips an empty step line.
- **Android, from the v25 phone check (v26):** the Halt button says "Halting…" while a halt goes, never "Resuming…". A Resume not answered in time says "The fingerprint check timed out. Try again." Account › Version reads "1.0 (26)", the version first. Home's headline and the Needs you badge count "Did this happen?" items too, so Home never says nothing needs you while Needs you holds some.
- Currency-request panel failures leave Activity readable with a notice, and panel updates preserve history cursors. Dismissed requests clear from cached views. Resume stops a stalled biometric check after ten seconds and ignores late completion. On iOS, previously enabled alerts return for the same account or show an off notice, and Halt/Resume failures stay on screen.
- **Setup:** an action about money whose operation takes no amount field (contesting a claim on a payment, an invoice reminder) gets no made-up amount argument. Its Card limits it by calls a day.
- KIFF currency exceptions appear once in Needs you, Setup and Activity, with attempt history, a Coach request for the correct taf and signed dismissal. Activity collapses only typed refusals in the open episode; Record keeps every decision. They remain subject to the normal signed Card approval. Connection refusals use KIFF's typed provenance and words while preserving status, amounts and evidence. Time-zone synchronization reports partial failures in displayable words and rechecks a newer confirmed zone before reporting success.
- **Cloud and iOS, from the phone check:** recurring schedules can follow KIFF's confirmed account zone, and Card names that zone. Custom tafs use their own saved job and workflow, with no workflow invented from tool order. iOS keeps paging after a refresh, shows the build in Account and a schedule's current How often value, and opens Coach for the taf selected; iPad workflow rows align to the left.
- Setup's app actions use short step references and distinguish forbidden actions from calls that need approval every time. Workflow summaries collapse consecutive uses of the same app. A taf without a numbered live version says it runs its original definition. Activity and retained runs supply an end-of-history line.
- **Android, from the v24 phone check (v25):** a taf's Activity and its runs keep loading older pages to the end; reaching the end while the first page refreshed stopped them. Account has one way out, **Sign out** (one account per phone), and Version names the build ("25 (1.0)"). The status bar's icons follow the app's own Light/Dark choice from launch. A new time zone moves the tafs' schedules to it, every time on screen is read again in it, and the Card names the clock. Coach from a taf's page opens that taf's conversation, not another taf's. Signing in again to the same account brings this phone's alerts back on, or says they're off and offers to turn them on. A halt or resume that didn't go through says why on the Halt screen, and no screen says "planque". A Card asked to take a currency it has no limit for shows once, in Needs you, Setup and Activity, with Coach to add the limit and Dismiss, never Approve once. A Resume whose fingerprint check isn't answered within 10 seconds is cancelled and says so, and the button comes back.
- **CI:** a pull request waits less. iOS CI runs only for a change to `ios/` or its workflow. A draft runs only the fast checks (tafs cloud's tests, Android's build and unit tests); the iOS simulator and the Android emulator run once it's ready for review. The iPad's list tests run on a pull request only when it touches the iPad's layout, and always on main and release builds. iOS screenshots run beside the tests instead of after them: a key set (Tafs, a taf, Setup and Account, light and dark) on a pull request that changes a screen, the full set (light, dark, largest text, iPad, landscape) for a release build. A manual run picks `full`, `key` or `none`, and can skip the TestFlight upload.
- **Android, build:** the Android Gradle plugin goes to 9.1.1 and Gradle to 9.3.1, which AndroidX Core 1.19.1 and the Compose BOM 2026.09.00 need; both come in with it. No change to what the app does, and the versionCode stays. Google Play Billing goes to 9.1.0; the app's billing code is unchanged.
- From the v24 walkthrough: a workflow step says its title once (tafs cloud sends its words empty when they'd repeat it); on Android the back key closes an app's sheet and the full notes, and signing in from the sample opens the Tafs tab on the account's list.
- Phone-specific alert switches, saved Light/Dark/System appearance and searchable account time zones on Android and iOS. Account groups related actions and keeps sign out last.
- Cloud-paged Activity and recent runs; connected-tool rings with action limits, workflow steps and seven-day statistics; compact memory previews and full notes. Card shows changes to how the taf runs and full wallet-style back cards. Coach returns to the same taf and tab, and chats have a larger single-bubble composer. Android gains the animated door rim.

- **Android, the taf screen (as the owner approved):** the bar is Account · Tafs · Needs you · Record · Coach (a rail in landscape and on tablets), and Settings and Coach leave Home's header. A taf's tabs are Chat · Activity · Card · Setup, each with one job: Activity is what it did and what waits for you, Card only what's on its Card, and Setup how it runs (the job, Starts when, connected apps, Results go to, what it remembers, versions, Remove). Setup replaces the dial and the Doors tab. The Record filters by taf, and each day says what its money moved came to. Account › Alerts shows what alerts you and how. The app follows the phone's dark setting.
- **Android:** in Connect apps, "Which taf is this for?" is asked in a dialog over the list, where Connect was tapped, instead of at the top of the list, as on iPhone and iPad. A sign-in that comes back is said at once, with its next step (Coach chooses its actions), and a signed-in app's row reads "Signed in · choose its actions" and opens the taf's Coach.
- **Android, app events approved on the dial (v21):** an app's event that waits for the owner shows its filters, and one approval with the fingerprint subscribes exactly those at the connection, then tafs cloud records it for that version, as on iPhone and iPad. A subscription made but not recorded is recorded again without asking twice.
- **iPhone and iPad:** the dial remote stays above the tab bar while its contents scroll.
  Long ring and face labels fit with an ellipsis, with full words for VoiceOver. Pause
  follows the service's availability and shows its refusal words. Connect apps rows show
  the catalog title without an internal provider id. App-event stops can approve their
  filters with the device key; the dial shows approval only after the service records it.

- **Android, from the v20 walkthrough:** a long word on the dial ends in "…" within its ring and on the face, and TalkBack reads it whole. ⏻ is off for a taf that can't be paused, and a refused pause is said in tafs cloud's words. With the dial, the Doors tab's own Add a door panel is gone: a new door is Coach's. Connect apps shows each app by its title alone, and a late answer to an earlier search no longer replaces the newest one.
- **Android:** an app that takes an API key is connected from Connect apps, as on iPhone and iPad. Its approval sheet takes the key and gives it to planque with the same confirmation, instead of sending the owner to Coach.
- **iPhone and iPad:** Connect apps clears its list and approval when the account changes and ignores stale search replies. Removing a Slack door waits until the phone can stop its subscription.

### Removed

- The `TafsMac` target and `ios/Mac/`: the Apple app is built for iPhone and iPad only.

### Added

- **Docs:** `docs/taf-screen.md`, the app's screens for the person using them (the bar, a taf's four tabs, Setup › Starts when and Results go to, Account › Alerts, dark), replaces `docs/dial.md`. The README shows the v24 screens from CI on iPhone and iPad (light and dark, with Account's theme), and Android's from the UI tests.

- **Docs:** `docs/connect-apps.md`, Connect apps for the person running tafs; `docs/dial.md` covers app events' approval, ⏻ off for a taf that works from its chat, and labels cut with "…".

- **Docs:** `docs/dial.md`, the dial for the person using it; the README covers Android, the dial, Connect apps, Did this happen? and the Slack door, with the Android screenshots.

- **Android, the settled dial:** When · What · Where around the taf's Why, with "Ask Coach or find
  anything", Showing ▾ one connection's stops, and a remote: ◀ ▶ through the stops, − + the chosen
  stop's one size, ⏻ pause, the face as OK. Changes collect into "Make N changes live". No chips
  and no "+ Add": new things go through Coach.
- **Android, door states on the dial:** a door's When stop is lit when open, dashed when closed,
  grey while resting, amber with a dot while a knock waits, and lights up when a message arrives.
- **Android, the dial's words read upright:** a stop's word in the lower half of its ring turns
  half a turn more, so nothing on the dial reads upside down.
- **Android, the dial follows tafs cloud's rules:** what OK does on each stop, a door's look and
  the change waiting to go live ("DRAFT · Vn · NOT LIVE", "Make N changes live") come from the
  knob, and Add a door and an empty recipe's step ask Coach. A chosen stop turns under the pointer,
  a ring past six stops pages, OK with nothing chosen goes to the first stop, "‹ Why" goes back,
  ⏻ reads the dial again, and the footer is today's work alone. Coach's card says when its dial
  is live.

- **iPhone and iPad, the universal dial:** When, What and Where surround the taf's Why.
  A Showing menu filters any number of connections; Back/Next walk every stop, Lower/Raise
  use the service's fixed choices, and Pause uses the existing pause control. Changes build
  on the pending draft and become live through the biometric version switch. Coach's draft
  card groups the setup by connection. Today's count opens request steps and reply retry.

- **Android, Coach sets the dial:** Coach's dial card shows Wakes it, When and Results go in tafs
  cloud's words, made live with one confirmation, or Change back to Coach; the dial shows the same
  draft with Make it live.
- **Android, a request step by step:** today's work under the dial, or a request in Activity,
  opens what happened, step by step, with Try the reply now and Ask Coach.
- **Android, How this is counted:** under a Card's limits, a row opens KIFF's words for how each
  tool's calls are counted, as approved when it was connected.
- **Android, Did this happen?:** money calls KIFF can't prove show in Needs you in KIFF's words,
  answered No (the Card gets its room back) or Yes, it did, with one fingerprint.
- **Android, Connect apps:** Settings lists the apps tafs cloud offers, searchable, each saying
  whether it's connected; an app nobody listed goes to Coach. Connect asks which taf it's for,
  then opens the approval over the list, with KIFF's words, then the vendor's sign-in.
- **Android, KIFF's words for a connection:** a connection that carries its profile shows
  KIFF's words for it on the approval card, read from KIFF and checked against their digests,
  and the approval signs both. Approve waits until they're shown.
- **Android, the taf's dial:** Wakes it, When and Results go around the job, with a rim that
  counts today's work, in the taf's Doors tab: a door stop picks its door, the face opens or
  closes it or runs the taf now, and a held call can be approved once from the face.
  Turning a result place or If busy makes the taf's next version, shown in tafs
  cloud's words with Make it live.
- **iPhone and iPad, KIFF's words for a connection:** the same as Android: a connection that
  carries its profile shows KIFF's words on the approval card, read with the person's session
  and checked against both digests; the approval signs both, and waits until they're shown.
- **iPhone and iPad, Connect apps:** from Settings, the apps tafs cloud offers, searched as you
  type. Connecting shows its approval over the list, with KIFF's words, then the vendor's
  sign-in; a vendor reached by an API key takes its key there, with Face ID.
- **iPhone and iPad, Did this happen?:** in Needs you, a money call whose outcome KIFF can't
  prove asks you whether it happened. No gives its Card the room back.
- **iPhone and iPad, the taf's dial:** the same dial, drawn from the approved design and laid
  out from the knob API (not yet on a screen).
- **Setup, for any vendor:** its rules name roles, not one payments vendor's tools: the
  argument a call acts on, the read that says its unit, money in its own units, test or live when
  the vendor has both. A vendor with a REST API and no MCP server is connected by its key, which
  the owner gives on the phone, never in the chat. A plan step can name the call it waits on, and
  is done only once that call went through.

- **iPhone and iPad, an empty home for someone a taf is shared with:** "Tafs shared with you show
  here." Only someone who may use Coach reads "Connect a tool to set up your first taf".

- **iPhone and iPad, Signed in as:** Settings › Account › KIFF account labels the person's own
  address "Signed in as", so it never reads as the account's, as on Android.

- **iPhone and iPad, Go on past the AI use bound:** Settings › AI use shows tafs cloud's words as
  they are. While the tafs wait, the billing admin gets **Go on** (one Face ID), and AI use is read
  again after it; anyone else reads who can let them go on, and when it starts again by itself. If
  that read fails, it says the tafs went on and offers Read it again, never a second Go on.

- **Android, Go on:** while the tafs wait past this period's AI use, the billing admin sees **Go
  on** in Plan & usage's AI use. It asks once, then the phone's device key signs it for KIFF. If
  AI use can't be read after it, it says the tafs went on and offers Read it again, never a second
  Go on.
  Anyone else reads tafs cloud's words on who can, and when AI use starts again.
- **Android, a taf shared with you:** its Card reads as issued by the account, by name, never
  "You"; that stays only for a taf you manage. Settings labels your own address "Signed in as".
  An empty home list tells someone who can't use Coach "Tafs shared with you show here."
- **iPhone and iPad, an answered card stays under its turn:** once answered, a card stays after
  Coach's words that asked for it, and Coach's words after the answer come below it, as on Android.
  A "Try again" card no longer shows above the turn that offered it.

- **Android, Set up approvals on this device:** before sign-in, only when the phone can't approve
  yet, a screen says why approvals need a fingerprint and where this phone stands: none set up
  (Open settings goes straight to adding one), the sensor busy just now (Check again), or no
  sensor tafs can use (approve from another device), with Continue without approvals. Before the
  first taf, and at every approval, the same words stand in the button's place until the phone
  can approve; Refuse, Decline and Halt stay. It says up front that adding a fingerprint later
  stops approvals until they're approved again. An answer the fingerprint prompt didn't send says why and "It's kept in Needs you."
- **iPhone and iPad, Set up approvals on this device:** before sign-in, only when the device
  can't approve yet, a screen says where it stands: no Face ID or Touch ID set up (the path in
  Settings, in words, or a passcode first where there is none), turned off for tafs (Open
  Settings), locked after too many tries or unavailable just now (Check again), or none on this
  device (approve on another one), with Continue without approvals. Before the first taf Coach's start says approving is needed, and at
  every approval the same words stand in the button's place, or beside a disabled one, until the
  device can approve; Check again updates both at once. A cancelled or unrecognised approval says
  so, and that the request is kept. It says up front that adding a face or fingerprint later
  means approving this device again.
- **tafs cloud, going on past the AI use bound:** `GET /v1/usage/ai` says whether this person may
  go on (`can_go_on`, the billing admin) and when the AI use starts again by itself
  (`starts_again`). While the tafs wait, its words tell the billing admin to go on and anyone else
  that an admin of the account can, with the day it starts again. A chat refused for AI use says
  the same, pointing to Settings › AI use.
- **tafs cloud, a taf shared with you shows none of its doors:** someone who only uses a taf reads
  no doors, paired senders or knocks from `GET /v1/tafs/{id}/doors`, so other people's addresses
  stay with the people who manage the taf.
- **tafs cloud, the owner's own email is theirs:** a message from the owner's own sign-in email
  that passes the sender check runs as the owner, in their own live chat, so it shows there as
  their message (marked as by email) and an open chat reads it at the next change. Other paired
  senders are unchanged.

- **Android, Copy under an answer:** every finished answer from Coach or a taf has Copy under it,
  which copies the whole answer, and its text can be selected. Report this answer moves under
  More actions (⋯), so it's never the only thing a long press finds.

- **iPhone and iPad, as Android's v19:** Settings opens with a Plan card that opens **Plan &
  usage** (was Account usage), and Home shows one line about the plan only when it needs you,
  with Android's words and thresholds. An ended trial says "Your trial has ended." (with "Start
  Solo to keep your tafs running." where Solo is offered), with no past period end and no red
  meter. Packs are on sale only while a plan runs (`can_add_packs`); held ones say "Counts once
  Solo starts." Coach's New conversation keeps an empty conversation and shows its top. Sign out,
  Switch account and Sign in again ask first. "1 task", never "1 tasks". Signing out also removes
  the last export's files.
- **Android, the plan in sight:** Settings opens with a Plan card ("Trial · 5 days and 18 tasks
  left", "Trial ended · Start Solo", "Solo · 12 of 20 tasks left · Google Play") that opens what
  was Account usage, now **Plan & usage**. Home shows one line about the plan only when it needs
  you: a trial ended, not started or ending within 2 days, or tasks used up or down to a tenth.
- **Android, buying through Google Play:** Usage offers Solo (with its free trial) and the task
  packs at Play's prices, once KIFF names their Play products, to someone who may add tasks. KIFF
  records each purchase before Play is told it's done, and Restore purchases sends again what
  wasn't.
- **Export my chats, on iPhone and iPad:** Settings → Account → **Export my chats** prepares the
  person's own chats, trials and notes and offers `tafs-chats.json` and `tafs-chats.txt` in the
  system's share and save sheet. If it fails, it says so and offers Try again.
- **Exporting your own chats:** `GET /v1/me/chats/export` gives a person their own chats and trials with
  every taf, Coach's included, their attached files' names and their own notes, as JSON and as text.
  It's never another person's, and no one exports it for someone else.
- **Export my chats, on Android:** Settings → Account → **Export my chats** offers the same two
  files through the system's share sheet, says why and offers Try again if it fails, and deletes
  the files when the person signs out.
- **Reporting an answer:** `POST /v1/tafs/{id}/messages/{message}/report` takes a person's report of
  an answer Coach or a taf wrote (offensive, harmful, wrong or other, with a note). tafs cloud keeps it
  with the answer as kiff/agents holds it and emails KIFF, so a person reads it.
- **Report this answer, on iPhone and iPad:** an answer from Coach or a taf (a chat or a trial) has
  **Report this answer** in its menu. A sheet takes one of four reasons and an optional note, and
  thanks the person once it's sent, or says they reported it already.
- **Report this answer, on Android:** a long press on an answer from Coach or a taf (a chat, Coach's
  or a trial) shows **Report this answer**, with the same sheet, reasons and words as on iOS.
  TalkBack names the long press "Report this answer".
- **Coach, the hand-over:** a result's tools are their names in KIFF, never their titles, and
  Coach ends the hand-over with its closing message as text, after the plan update and with no
  tool call after it, also when its first result was refused.
- **Coach, writes that move no money:** answering a dispute or pausing a subscription is limited
  by its calls a day, never by an amount, and Coach never gives a tool an amount argument the
  vendor's operation doesn't take.
- **Coach hears an installed version:** when the owner installs a version Coach made, Coach's
  chat says "Version N is live." at once, and Coach ends that improvement's plan on its next
  turn instead of still asking for the install.
- **Coach, after an improvement:** once tafs cloud answers with the new version, Coach writes
  one closing sentence that the version is ready to try and then install, and never says it's
  still waiting. An approval carries no title of Coach's: its words are planque's and KIFF's.
- **A taf's job line after an improvement:** an improvement that changes what a taf does
  carries Coach's one-line `summary`, which becomes the taf's job line once that version goes
  live; going back to an earlier version brings its line back. The home list reads each
  taf's runs once.

- **A total's pending use, in KIFF's words:** tafs cloud's list passes on KIFF's
  `pending_text` for each Card total, how much of it is from calls the tool refused, which
  count until KIFF can confirm nothing moved. iOS shows it on a taf's Card and on a hold,
  in place of its own "nothing moved, but they used room".
- **iOS, as on Android:** an improvement's version is tafs cloud's, and the card only shows
  it; versions and a blocked switch read tafs cloud's words; an answered approval says what
  it was, and an answered card goes back to its own time; a card Coach shows again under an
  earlier id waits for its own answer; at launch Coach opens on its newest conversation
  alone; tafs cloud's words for a refusal show on every path; and an owner's message is
  keyed by tafs cloud's id, so each chat item shows once.

- **Tafs and Coach, calls under a Card:** a taf makes one call for one request, never smaller
  ones to stay under a limit, and asks the owner when it's refused. A money call that doesn't
  name its customer, such as a coupon, carries who it's for in a field the customer doesn't
  see, such as metadata, so the held call says it. Coach says the limits are the owner's, says for each action what asks first and what is
  refused, gives each card an id no earlier card in the conversation had, and hands over
  each taf with a one-line `summary` of what it does.
- **Android, dictation's language:** where the phone dictates in more than one of the owner's
  languages, a switch before the mic shows the one in use ("FR"), and each tap goes to the
  next, as on iOS. The choice is kept.
- **Android, versions:** who made a version, and what keeps a switch from going live, read in
  tafs cloud's words ("By Coach mode", "By you", "By someone on your account"), as on iOS.
  The app's own copies of both are gone.
- **iPhone and iPad, held calls:** a call Coach's own agent holds is titled **Coach**, with
  Coach's mark, on its card, its alert and the lock screen, never "A taf". Why a call is held
  reads KIFF's own words when sent; a count of calls without them reads "Each call asks you
  first" for a limit of 0, otherwise "20 calls a day: this would be the 21st", never the
  machine's reason.
- **iPhone and iPad, approve again without signing out:** after a face or fingerprint is added,
  **Approve again in your browser** lets the phone approve with its new key once the person
  approves it in KIFF, showing the same code on the phone and the page. The session, alerts and
  list stay. Sign in again remains the fallback.
- **Android, approve again in your browser:** a phone whose fingerprints changed approves again
  without signing out. **Approve again in your browser** proves its new approval key with one
  fingerprint, shows KIFF's code to compare, and waits for the browser, with Cancel; the session,
  its alerts and its list stay. **Sign in again instead** stays under it, and is what a KIFF
  without re-linking offers.
- **iPhone and iPad, approvals by Face ID or Touch ID only:** what grants authority (Approve,
  Extra room, a Card, a trial, Resume, sharing, connecting a tool) takes Face ID or Touch ID,
  never the passcode, and the approval key itself requires the faces or fingerprints enrolled
  when it was made. A new enrollment is found at refresh (a lockout never ends the key): the
  home screen and Needs you say this phone can't approve until a new sign-in, with **Sign in
  again**; Refuse stays. A prompt closed unrecognized says "Try again, or sign in again if your
  Face ID or fingerprints changed". **Compatibility:** the earlier approval key also took the
  passcode, so each iPhone and iPad signs in again once before it can approve.
- **iPhone and iPad, Cards:** a limit says what happens past it. On a Card that asks the owner
  past its limits: "up to $40 each; more asks you first", "$300 in total a day, then it asks
  you", "20 calls a day, then it asks you", and a count of 0 reads "Each call asks you first",
  never "0 calls a day". This applies on a taf's Card and on a draft's Cards, where KIFF's own
  `lines` are shown when it sends them. A Card that refuses past its limits reads as before.
- **Android, Cards:** a limit says what happens past it. On a Card that asks the owner past its
  limits: "up to $40 each; more asks you first", "$300 in total a day, then it asks you", "20 calls
  a day, then it asks you", and a count of 0 reads "Each call asks you first", never "0 calls a
  day". This applies on a taf's Card tab and on a draft's Cards, where KIFF's own `lines` are shown
  when it sends them. A Card that refuses past its limits reads as before. iPhone and iPad follow.
- **iPhone and iPad, sent messages:** a sent message is matched to the thread by tafs cloud's id
  for it, from its send or its stream's first event, never by its words, except a steer or an
  older tafs cloud; a message that answered a card says "Answered the card above".
- **Android, sent messages:** a sent message is matched to the thread by tafs cloud's id for it,
  never by its words, except a steer; and a message that answered a card says so. The retired
  `[type id] answer` form is no longer sent; older threads that hold it still read in words.
- **Android, approvals:** a phone whose approval key is gone (a fingerprint added since) says so at
  launch, on the home screen, in Needs you and on the approval cards, with **Sign in again**, in
  place of a button that would fail. A prompt closed without a recognized finger, while the key
  is still good, says "Try again, or sign in again if your fingerprints changed".

- **iPhone and iPad, this round:** a taf's page has a compact header (avatar, name, state) whose
  version, Share and Card line fold as the chat scrolls down; a door's knocks sit in the chat at
  their own times, and a paired sender's waiting message reads "Paired · Do it or Ignore this
  message". The app reads again what tafs cloud's live updates name, and shows each door's words.
  A taf whose connection its vendor signed out waits on the owner at the top of the home list,
  in planque's words, and its Card tab and Coach's card offer **Sign in again**; a sign-in reads
  as signed in only once planque says connected. The Activity tab shows the taf's work, with
  skipped scheduled runs and **Send again** for a door reply given up on. Dictation goes in at
  the cursor (iOS 18 and later).

- **Android, Coach:** **Improve <taf>** as a start in a new conversation and on a taf's Activity
  tab; what the owner wrote and didn't send stays, in Coach and each taf's chat; a card answer
  tafs cloud took never stays on "Sending your answer…"; and Coach's tools and Card go in one tap
  when the Card comes after the batch.

- **Android, a connection its vendor signed out:** its taf waits on the owner at the top of the
  home list, its row in planque's words ("Signed out by Stripe · sign in again"), and its Card
  tab offers **Sign in again**. planque's grants are read on every refresh and live update.

- **Android, a taf's work:** the Activity tab shows tafs cloud's view of the taf's work: its
  line, its recent requests in tafs cloud's words, skipped scheduled runs (dim, in their place by
  time), and **Send again** for a door reply given up on.

- **Android, a taf's page:** a compact header (back, name, state) whose Share and Card line
  fold as it scrolls; a chat opens at its newest message, with a door's knocks at their own
  times; and a paired sender's waiting message reads "Paired · Do it or Ignore this message".
  The app reads again what tafs cloud's live updates name, instead of polling an open chat,
  and shows each door's words. A vendor sign-in reads "Signed in" only once planque says the
  connection is connected, and says it's checking meanwhile. The home list's order holds while
  it's on screen, so a refresh never moves a taf under the owner's finger. A connection its
  vendor signed out (`needs_reconnect`) reads as planque says ("Signed out by Stripe · sign in
  again") and is signed in again from its card, instead of being refused.

- **Android, Coach's screen:** a compact header (back, Coach, a menu with its conversations),
  the plan in tafs cloud's one line that folds away as the thread scrolls, and one calm live
  line from tafs cloud's stream instead of the model's thinking and a list of steps. Alerts
  that tafs cloud didn't take at first are handed over again at the next launch.

- **tafs for Android** (`android/`, Android 11 and later), with what the iPhone app has
  ([docs/android-app.md](docs/android-app.md)):
  - tafs, Needs you and the Record, with KIFF's receipts checked on the phone;
  - a taf's page, with its runs, last report, memory, Revoke and Remove;
  - the live chat, with photos, files and dictation;
  - Coach, with its conversations, given its tools and Card exactly as tafs cloud drafts them.
    A taf Coach hands over is set up by tafs cloud, and its card shows tafs cloud's answer.
    Coach's cards are answered through tafs cloud's route and show the answer the thread
    holds; after an answer, Coach's next words show without leaving the conversation;
  - Doors, Versions and trials, Halt, Usage and asking an admin for tasks;
  - People and groups, Devices, the time zone and Delete account.

  Every approval is signed with an approval key that needs a fingerprint for each
  signature. What didn't go through is said in the app's own words, with what the owner can
  do, never a server's error text, and no tool's id is shown. Dictation works in a variant of
  the phone's language (English in France dictates in British English), and a phone without a
  microphone can install the app. A new fingerprint turns it off until the next sign-in. Signing out ends the
  session at KIFF before the phone shows signed out. Buying through Google Play is built
  but off. On a tablet, the list sits beside what's open, with Overview, and a taf's page
  has its second column in landscape.
- tafs cloud can alert Android phones through Firebase Cloud Messaging. `POST /v1/push` takes
  `platform: "android"` with the phone's Firebase token; an iPhone registers as before.
  Android alerts are data messages the phone builds its notification from, with no call
  parameters; a door's alert carries its taf (`taf_id`) and opens it. They are off until `FCM_PROJECT_ID` and `FCM_SECRET_ID` are set, and an Android
  phone's registration is refused (503) until then.

### Fixed

- **Android, Google Play unreachable:** when Play can't be read just before a purchase, it says
  "Couldn't reach Google Play. Try again.", not that Play doesn't sell it.

- **Android, an ended trial:** Usage says "Your trial has ended." (with "Start Solo to keep your
  tafs running." where Solo is offered), not that its tasks are used, and shows no past period
  end and no red meter.
- **Android, New conversation:** never greyed out in Coach's menu. On an empty conversation it
  keeps it; otherwise it opens a new one.

- **Android, Solo's free trial only while Play offers it:** Play's offers are read again each
  time Usage shows and after every purchase, never kept from an earlier read, so a trial already
  used reads "€10.99 a month", not "Free for 1 week".
- **Android, a purchase's outcome where you tapped:** under Start Solo, the pack or Restore
  purchases, read out by TalkBack, with Restore purchases right there when KIFF hasn't recorded
  it yet.
- **Android, packs need Solo:** before a trial starts or after it ends, Usage offers Solo alone;
  packs already held stay listed and say "Counts once Solo starts." (KIFF's `can_add_packs`).
- **Android, "1 task":** Usage says "1 task", "0 of 1 task" and "Tasks last 1 month", not "1 tasks".
- **Android, signing out asks first:** Sign out of KIFF, Sign in again and Switch account each
  confirm before the phone signs out, so a stray tap doesn't end the session.
- **Android, an answered card in Coach's chat:** it stays after the words that asked for it,
  and Coach's words after the answer come below it.

- **Android, Coach's last words on the open screen:** with Coach open, a run that paused on a
  result goes on once tafs cloud answers it, and its closing message shows without leaving
  Coach. The dots and Stop no longer stay after it's done.
- **Versions:** version 1 ("As it was set up") shows when the taf was set up, not the moment its
  second version was made.
- **Android, an approval is "approved" only once signed:** Approve on a connection already
  connected that brings no Card no longer tells Coach "approved" with nothing signed. Coach
  hears it's already connected and there's nothing new to approve. A Card KIFF already
  confirmed says "Already approved". An answered approval's line is one line, however long
  its words.
- **Android, installing a version:** Install this version opens the Card the version brings,
  then the fingerprint. The Card sheet no longer stays invisible over Versions, taking every
  tap. Back cancels the install.
- **Android, every refusal says why:** Run now, Send again and every other refusal tafs cloud
  explains show its words, not "It changed in the meantime".
- **Android, refused calls in a total:** how much of a Card's total is from calls the tool
  refused, in KIFF's words ("It counts until KIFF can confirm nothing moved"), on a held call
  and under the Card's limits, from live, active Cards only. The app's own line is gone.
- **A taf's job line and last run:** a taf Coach sets up shows what Coach said it does, not
  the nearest fixed job's line, and a run from its chat counts as its last run.
- **Android, Play's crash view:** the release bundle carries its native libraries' symbol
  tables, so a native crash reads by function name.
- **Android, Coach at launch:** Coach opens on its newest conversation alone, with the earlier
  ones under Previous conversations, never several conversations together in one thread.
- **Android, a refused message:** a message kiff/agents refused (the period's AI use spent, a
  taf halted) says why in tafs cloud's words, not "It changed in the meantime".
- **Android, an improvement's new version:** tafs cloud makes it in the hand-over, and the
  phone shows it ("See version n"), or why it wasn't made. The phone no longer makes it.
- **Android, Coach's answered cards:** an answered approval says in one line what it was
  ("Approved · Connect Stripe"), and an answered card goes back to its own time in the thread,
  while one waiting for the owner stays at the end.
- **Android, Coach's cards under a reused id:** a card in a new conversation, or a new card in
  the same one, under an id Coach used before, no longer shows "Answered." with no button, nor
  sits in another conversation's part of the thread.
- **Android, a chat while its taf or Coach works:** the thread no longer jumps about. The dots and
  the answer replacing them are one row that never shortens the thread's end, and an answer
  growing word by word is followed without restarting an animation on every word.
- **Versions, in tafs cloud's words:** each version says who made it (`by_words`), and a switch a
  run blocks says what's in the way (`words`), so the apps show tafs cloud's words, not their own.
- **Android, connecting a tool:** a sign-in that came back with its tools listed for Coach to choose
  from showed a red "didn't connect … planque has it as listed", though it was connected. Listed
  now counts as signed in, as on iOS.
- **Chats, words written while a card waited:** they showed twice in the thread, once as the
  card's answer and once as the run's message, at the same time. A phone's list could not hold
  both (Android closed on opening Coach). tafs cloud now gives one message, marked as the answer,
  with the id its send answered.
- **Android, held calls:** a call Coach's own agent holds is titled **Coach**, with Coach's mark,
  never "A taf". Why a call is held reads KIFF's own words when sent. A count of calls without
  them reads "Each call asks you first" for a limit of 0, otherwise "20 calls a day: this would
  be the 21st", never the machine's reason.
- **Android, Coach crash:** opening Coach no longer crashes when a chat holds the same message
  twice. An owner's message is told apart by tafs cloud's id for it, and a chat shows each item
  once.
- **Coach, a scheduled taf with no numbered version:** Coach said it had "no version installed
  yet" and that nothing was working, while the home said "Working · Next run 04:00". Its
  `tafs_state` now says such a taf runs the definition it was made with, and that a schedule
  that's on is working, with its next run, though nothing runs this minute.
- **Android, switching accounts:** after signing in to another account without closing the app,
  connecting tools and starting Coach work there. The phone used to sign planque's requests with
  the last account's device id, which planque refused, and the app said it was signed out.
- **Android, starting Coach:** when the tools can't be connected because planque couldn't check
  the phone, Coach says "Connecting your tools didn't work. Tap Start to try again", not that
  you're signed out, since the session still works.
- A taf's work steps say what it did, in words, both while it works and after: KIFF's title for
  a tool through KIFF ("List your charges"), and what Coach's and the agents' own tools do
  ("Showing you a card"). A step showed the tool's name before ("show", "list_charges_ijkp").
- Sign out of KIFF ends the session at KIFF first. When KIFF doesn't confirm, the app stays
  signed in and says so, instead of showing signed out while the session still works.
- When a change to groups or sharing fails, the app says why. The reason used to be cleared by
  the list being read again.
- A Card's total over all time, or over a rolling hour or 24 hours, no longer reads as "a day".
  The home list gives each total KIFF's own window and its words ("in total", "in any hour").
- A chat that opens with your answers to Setup's questions card is named by those answers,
  as the card shows them ("Test mode · Monday, Tuesday"), not by their raw JSON.
- Coming back to the app no longer shows "Not done: Couldn't reach KIFF" (the 930 test).
  A refresh the app makes by itself tries again on its own. While KIFF can't be reached, a
  quiet line under the account's name says so, with a link to KIFF's status page.
- A held call's card, in the chat, Needs you and the taf's page, says "Nothing is sent until
  you approve." while it waits, and so does its notification. The taf's message about it no
  longer repeats that, and a chat answer no longer ends with a summary of what wasn't done.
- Setup, for a taf whose work arrives somewhere it can't connect (an email inbox), tells you
  to pass each request in the taf's chat in your own words. It used to show an example with a
  charge id to type.
- A taf's answer when KIFF holds a call is one sentence: it waits for your approval and nothing
  was sent, the one limit that holds it, and until when. It used to add what was left on the
  payment and the other limits.
- A Card's amount with no known currency (#309) says so: "30 000 left today of 30 000 ·
  no currency", and "≤ 5 000 (no currency) each". It read like money, though the number
  is the vendor's own, often in cents.
- The iPad's tafs list (the 901 test): a tap anywhere on a taf, its icon and name
  included, opens it. Only the empty end of the row did.
- Coach (the 870 test): a step whose call came back refused or failed shows as failed in
  its plan, never done, and Coach says what went wrong. Before, a hand-off the tool refused
  showed as done. Its rules say a read tool's call count as its Card will have it, where
  they said "no limit" and the Card then counted the reads.
- Coach mode (the 870 test) works like a taf's chat. The card you answered goes to the top
  and stays, with Coach's work and answer under it. The work block sits under what started
  it and folds in place, each answer lists only its own steps, and folded steps read in
  Coach's words, not "Asking KIFF to run planque".
- A taf's page (the 870 test): Share uses the system's share mark, larger, beside who uses
  the taf. A Card that acts for real no longer says "Live", which read as live money beside
  a test-mode connection; a Card scoped to another environment still names it. Someone
  with no connections no longer sees an empty Connections box.
- The tafs list sets apart the tafs shared with you, under "Shared with you" (#306).
- Overview (the 870 test): each heading sits over its column, the Card reads as on the taf's
  page ("300 US$ left today of 300 US$", where it read "0 of 30000 today"), and who uses a
  taf shows only to whoever manages it.
- Refunds: the job's instructions say to look up the payment and read its result before
  asking for a refund (the 859 and 870 tests). The taf twice sent the look-up and the
  refund together, then said it had checked the payment; kiff/agents now refuses a
  refund sent beside a read, and this keeps the taf from spending a turn on it.
- Chat (#249): a run that fails after a call went through names what went through and
  how much moved, for example "Refunds stopped part-way, after this went through: create
  refund on ch_3001, US$5.", from the run's own record and KIFF's Record. Before, it only
  said it stopped part-way, so the owner had to check the Record to learn the money had
  moved.
- Chat: a call kiff/agents refused back unmade, because the taf sent an act beside a read,
  shows as "not made · read first", not as a failed step. Made again, it's one row
  with its tries. tafs cloud's `step` event may say `not_made`, and a message's `work`
  steps may carry `not_made`. A run's work is split where kiff/agents marks a turn
  `resumed`, the first after a pause, rather than guessed from the calls it waited on.
- Launch (the 870 test): the taf used last leads the list from the first frame, and the iPad
  opens on it. The list's order is settled once KIFF's record is read, and kept with the
  remembered list, so the app no longer opens on the first taf made and then jumps.
- Chat (the 859 test): when a run ends, the view stays on the pinned message, even as tafs
  cloud's copy takes the place of the one shown while it worked. If the message is gone, the
  chat goes to the latest rather than blank. A step the stream sends again keeps its status,
  so a finished step never shows as pending. On iPad, the app opens on the first taf in the
  list, selected, until the owner picks another, and a tap on the open taf counts as a pick.
- Chat (the 859 test): a run that paused on a held call and went on once the owner answered
  shows its work in parts. The answer written before the hold keeps what was done before it,
  and the time it took, not the time spent waiting for the owner. The work after the approval
  shows on its own, and the steps from before aren't listed again as new.
- Chat (the 845 test): a sent message goes to the top at once and stays there while the taf
  thinks, works and answers. The work block sits under it, with the answer streaming below,
  and folds in place to "Worked for" when the run ends; a run's work shows once, on its
  first answer. Coming back from another app shows the chat, not a blank screen. On iPad,
  the app opens on the taf used last. The sample taf now streams its answer like a real
  one, and a UI test pins all of this on a simulator.

### Added

- Deleting your account finishes even if the app closes partway: you confirm once, and tafs
  cloud deletes the account in tafs, its connections and KIFF, trying again on its own until
  it's done. It can't be undone.
- A card that waits for your answer (confirm, questions, approval, sign in) gets it as data:
  `POST /v1/tafs/{id}/components/{component}/answer`, and the thread shows each card's
  answer. Your choice reaches the taf as you made it, never as a line of chat text.
- Settings, **Devices**: the devices signed in as you on the account, when each was last
  used, and Sign out for any but this one, behind Face ID.
- Settings, **Notifications & haptics** (the 901 test): **Sound** off makes this device's
  alerts arrive quietly, in Notification Center and Needs you, with no sound or banner;
  **Haptics** off stops the app's taps. Each is for this device only. tafs cloud's
  `POST /v1/push` takes `sound`.
- Accounts (the 870 test, kiff/tafs#294): a person who uses a shared taf is offered only what
  their role allows. No Approve or Refuse without the right to answer held calls (a line says
  who answers instead), no Delete account without that right, and no Doors tab or share line
  on a taf they don't manage. "You" marks only their own door key, and the Card says the
  account issued it. The account's name shows next to Settings, and Settings has Switch account.
- Usage: after an account moves from Solo in the App Store to Team, which KIFF bills,
  the usage screen says Solo still renews with Apple on its date, with a Cancel Solo
  button that opens Apple's own subscriptions page. The usage read's `billing` may
  carry `apple_solo_until`.
- Chat: while a taf or Coach works, the chat shows the step in progress, one line
  of what it's thinking (once it has thought for a few seconds, changing at a pace
  you can read), and the steps done under it. A step tried again shows once, with
  its number of tries. Once the answer is in, the steps fold into "Worked for
  12 s", which opens them again, and that line stays with the message. Leaving the
  chat or the app mid-answer and coming back follows the work to its end. The
  answer appears at an even pace, with formatting still being written never shown
  as symbols. A message you sent can be sent again, edited first, or copied from
  its menu. tafs cloud's message stream gains `thinking` and `step` events and
  a `call_id` on `activity`; `GET /v1/tafs/{id}/runs/{run}/stream` picks your own
  run back up; conversation messages may carry `work`. The definitions of tafs and
  Coach set `thinking: "summarized"`, which needs kiff/agents with it.
- Doors: a taf can have an email address of its own. It has none until you turn
  it on in the taf's Doors tab, where a dial picks the door and pressing the taf
  opens or closes it. A paired sender's email works like a chat message, under the
  taf's Cards, and the answer goes back in the same thread; the chat marks both.
  Anyone else knocks: you're told who wrote and what they want, and answer Do it,
  Pair or Ignore. Your own email is paired from the start, and an email counts as
  a paired sender's only when it passes the sender check. Each door takes 20
  emails a day from you and paired senders and 10 from anyone else, then one line
  says how many more were dropped. 20,000 characters an email, text only. tafs
  cloud gains `/v1/tafs/{id}/doors` and the email door's webhook; the home list
  may carry `doors_offered` and each taf's `doors`, conversation messages `door`,
  and the conversation `knocks`.
- Security reporting, contribution and agent guidance, issue and pull-request
  templates, code owners, and dependency-update configuration.
- Halt: one tap stops a taf, or every taf with Halt all,
  until you resume it with Face ID. It shows Halted only once tafs' runtime, KIFF
  and planque all confirm, and Halting… until then. Nothing from before a halt
  runs again after a resume. tafs cloud gains `POST`, `GET` and `DELETE` on
  `/v1/tafs/{id}/halt` and `/v1/halt`.
- AI fair use (#193): chats with your tafs and Setup are included up to half
  your plan's monthly price in AI use each period. Past it, your tafs ask before
  going on, and again at each further full bound; AI use is never billed. tafs
  cloud sends the bound with each chat and gains `GET /v1/usage/ai`.
- Task packs: when your tafs' tasks run out, add a pack (Pico, Vonk, Rayo, Forte
  or Maxi) in the app. A pack's tasks last 12 months; your plan's tasks are used
  first each period, then packs, oldest first. Usage shows the tasks the period
  has, what's used and what's left, the packs you hold, and one line on who bills
  your plan, with Manage when Apple does.
- Solo in the app, through the App Store: a monthly subscription that starts
  with a free week, for an account with no plan billed elsewhere. Every offer
  shows its terms. Restore purchases brings back what this Apple account bought.
- Ask an admin: someone who can't add tasks asks for a pack, with a reason, and
  sees it waiting until an admin approves (buys the pack) or declines it with a
  note; they can withdraw it. tafs cloud tells the phones of people who may add
  tasks, and only theirs.

### Removed

- The monthly ceiling row on Usage: tasks past what the plan includes come from
  packs.

### Changed

- On iPhone and iPad, a card's answer goes to tafs cloud as data, not as a message in the chat,
  and the card shows the answer as it was recorded: your choice, Approved, Declined, or why it
  didn't go through. An answer that doesn't go stays on the card, so you can answer again.
- Dictation in a language whose own form the device doesn't have uses its best form: British,
  then American English for English; a language's own country's form for the others.
- On iPhone and iPad, a taf Coach hands over is set up by tafs cloud, not by the app. Its card
  shows tafs cloud's answer: set up, with a button to open it; why it wasn't, with Coach handing
  it over again; or "Setting up…" until the answer comes. The card no longer offers Try again.
- Deleting the account the earlier way, where the app itself asks tafs cloud, planque and KIFF
  in turn, still works for installed builds. It goes one release after the build that signs one
  intent for tafs cloud to carry.
- The app's earlier way of answering a card, a message `[<type> <id>] <answer>`, still
  works while that card waits, and is taken as the card's answer. It goes one release after
  the app build that uses the answer route.
- You see only what your role lets you do: halting, versions, revoking or removing a taf,
  its doors' controls and Coach are hidden unless you may use them, instead of failing
  once tapped (or after Touch ID).
- Coming back to the app, a chat stays where you left it instead of showing blank space.
- A chat whose task stopped at the taf's cost or step limit says so, and who can raise
  the limit, instead of staying on "…".
- No one reads another person's chats: People's work, the notice that a chat can be read,
  and Settings → Who read my work are gone. Your chats with a shared taf are yours alone.
- From the device test of build 793, on an iPad:
  - A held call's card waits in the chat for the taf's words that explain it, at
    most 45 seconds, instead of showing alone under your message first. Needs you
    and the alert still show it at once.
  - Opening a taf with a call waiting lands at the latest when the call is near the
    end, instead of on blank space with the Needs you pill and the arrow.
  - People & groups' button reads "+ Group" on the iPad too.
- The app no longer crashes on iPad while a chat or a sheet updates (build 793):
  SwiftUI on iPadOS 27 builds some views on a background render thread, and the
  app's runtime main-thread checks stopped it there.
- From the device test of build 789, on an iPad:
  - The app no longer crashes a few seconds after launch: the offer to turn
    on alerts shows over the screen instead of in a sheet.
  - The tafs list keeps its order while you look at it, and settles again when a taf
    comes or goes or the app comes back.
  - A held call's card comes after the taf's words that ask for it. When KIFF won't
    take your answer, the card says why in words instead of KIFF's code: for a call you
    asked for, someone else approves it and you can still refuse it; for other reasons
    the buttons that can't work go. An expired hold says so at once, Needs you reads
    KIFF again when it expires, and its Record row reads "no longer waiting · nothing
    sent" instead of "waiting for you".
  - A message you send goes to the top at once, and coming back from another app the
    chat returns to it, or to its latest, instead of blank space.
  - People & groups reads again while it's open, so someone who joins shows; its
    button says "Group".
  - Signing in shows the approval link, with a Copy button, to open in another browser.
- From the device retest of build 755, on an iPad and an iPhone:
  - The tafs list puts the taf waiting on you first, then the one used most
    recently.
  - Alerts are offered once, right after signing in, with a short screen that
    says why, so the first held call reaches you. Needs you still offers them.
  - A sent message moves to the top as soon as you send it, not once the answer
    has rendered. A chat opens at its latest message.
  - An answered hold sits where you answered it: after the taf's "waiting for
    your approval", before what it did next.
  - Stop takes effect at once in the app, and leaves no "Stopped" line.
  - A "Not sent" kept because you wrote too many messages too fast goes once a
    later message goes through.
  - The Card shows its own total and the extra room apart, as KIFF says them
    ("300 US$ a day · +30 US$ extra room until 00:00"), not one sum. tafs
    cloud's home list may carry KIFF's `extra_room`, `base_limit` and
    `room_window_end` on a rule and on a Card's total.
  - A hold's receipt reads "held for you by the Card" while it waits, not
    "allowed by the Card".
  - Usage on the Card updates as the taf works, not only once Usage is opened.
  - Needs you reads again when a hold's alert comes in before KIFF lists it.
  - Ending a trial goes back to the taf with a cross-fade, and "This taf is
    gone" no longer flashes. Starting a trial beside the sidebar selects it there.
  - Usage, Versions and Halt open wherever their panel is tapped.
  - Forget asks first, and says it can't be undone.
  - Do it on a knock says why it wasn't sent in its own sheet.
  - The ring of a taf with an open door runs along the avatar's edge.
  - Coach makes the new version a card hands over, even when the card has the
    same id as one from an earlier conversation. Before, it showed the earlier
    version and made none.
  - Coach says when extra room ends: when that total resets.
- Coach knows holds, tasks and packs, and halting (#218): why a call is held or
  refused and what the owner can do about it, what counts as a task and what
  happens when tasks run out, and what a halt stops. It no longer says a refused
  call waits for you. They're in its instructions: when they were skills it could
  read on demand, it read none of them in the Eval.
- Dictation uses any language the device dictates in (#208), the owner's first,
  and the switch while dictating goes through those. Before, it knew Spain's
  Spanish and US English only: a device with French dictation, or Spanish from
  Colombia, said Spanish wasn't available. With none on the device, it says how
  to turn dictation on.
- Dictating goes on through pauses (#208): after a pause the next words go after
  what was said, where before they replaced it, and the recognizer ending by
  itself no longer stops dictation. The mic stays in the message bar when the
  box has text, so you can carry on dictating after typing.
- While dictating, a bar under the message box shows your voice as it comes, so
  it's plain that the mic is on, with cancel (the box goes back as it was) and
  stop (what you said stays, unsent).
- The doors no longer show "Couldn't read the doors: cancelled" when their
  screen closes before the read ends.
- A taf that didn't start reaches Coach (#183): when tafs cloud refuses to start
  the taf Coach handed over, Coach hears it once and says plainly that no taf was
  created. When the name was taken, it hands it over again with the same Card under
  a new name; for any other reason, it asks you what to do first. It no longer
  claims a rename or a start that didn't happen, or drafts a second Card. A server
  error or an expired sign-in still offers Try again.
- Tables in a chat are drawn as tables (#211): a header, a line between rows,
  amounts aligned on the right, and a wide table scrolls sideways. VoiceOver reads
  each row with its column names. Before, a table showed as text with its pipes.
- Extra room on a held call (#211): a call over a total the Card keeps across
  calls, such as a day's, which approving once can't pass, now offers **Extra
  room** with Face ID. KIFF gives the Card just the room this call needs, until
  that total resets, and the call goes on; the Card is as it was after that.
  Changing the limit for good stays in Coach mode. The hold card and the Card
  also say how many calls the vendor refused today still counted toward it.
- Every time the app shows is in your account's time zone (#211): holds'
  deadlines, chats, the Record's days, Usage, versions and a taf's next run, as
  KIFF's Card days are. Without an account (sample mode), it's the device's. When
  the account's clock isn't the device's, a hold's deadline and the Record's times
  name the zone ("14:00 CET").
- Words show once while an answer streams (#214): when the chat reloads mid-answer
  (a taf read what you wrote meanwhile, or Setup's conversation after a step), the
  words it already recorded no longer show a second time as the live answer.
- Setup says each thing once and keeps its plan with you (#214): a question's card
  isn't repeated in its message, the plan comes once the job is known and never
  restates a message, a step waiting for you stays in progress instead of failed,
  and the plan moves first once you answer, approve or sign in.
- A taf's answer only grows as it streams (#206): its words stay as written at
  each tool call, and a call KIFF holds shows its card where the call was made,
  after the words before it, and stays there once answered, in your words
  ("Refused by you · …"). tafs cloud's message stream gains a `held` event with
  KIFF's ids for the call.
- A message you send stays at the top of the chat while the answer arrives
  below it, in a taf's chat and in Coach mode (#206): the chat no longer jumps
  to the end when the answer is short.

Earlier history is available in [merged pull requests](https://github.com/kiff/tafs/pulls?q=is%3Apr+is%3Amerged)
and [releases](https://github.com/kiff/tafs/releases). This file does not
retroactively assign release dates or claim that merged work was deployed.
