package bot.tafs.app.app

import bot.tafs.app.kiff.activityPage
import bot.tafs.app.kiff.runsPage
import bot.tafs.app.kiff.setupView
import bot.tafs.app.kiff.phoneAlerts
import bot.tafs.app.kiff.timeZones

import bot.tafs.app.model.ErrorWords
import bot.tafs.app.model.Words
import bot.tafs.app.model.Unsent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import bot.tafs.app.model.ConnectWords
import bot.tafs.app.model.DoorWords
import bot.tafs.app.model.Answer
import bot.tafs.app.model.AttachmentRef
import bot.tafs.app.model.FilePrep
import bot.tafs.app.model.PendingFile
import bot.tafs.app.model.PreparedFile
import bot.tafs.app.model.RunReport
import bot.tafs.app.model.ReportResult
import bot.tafs.app.model.ExportResult
import bot.tafs.app.model.Chat
import bot.tafs.app.model.ChatItem
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.ChatEnd
import bot.tafs.app.model.LiveAnswer
import bot.tafs.app.model.LiveStep
import bot.tafs.app.kiff.StreamEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import bot.tafs.app.model.CardCheck
import bot.tafs.app.model.Card
import bot.tafs.app.model.Decision
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.ProposedAction
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafState
import bot.tafs.app.model.MemoryNote
import bot.tafs.app.model.TafDraft
import bot.tafs.app.model.TafTemplates
import bot.tafs.app.model.newId
import bot.tafs.app.model.threeMonthsOn
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.AlertTarget
import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.CloudSetup
import bot.tafs.app.kiff.PlanqueClient
import bot.tafs.app.kiff.PlanqueError
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.VendorSignIn
import bot.tafs.app.kiff.VendorSignInCancelled
import bot.tafs.app.kiff.NoVendorSignIn
import bot.tafs.app.kiff.PushStatus
import bot.tafs.app.push.NoPush
import bot.tafs.app.push.PushPlatform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.model.CoachWords
import bot.tafs.app.kiff.Door
import bot.tafs.app.kiff.DoorApp
import bot.tafs.app.kiff.DoorKey
import bot.tafs.app.kiff.Knock
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.AgentDraft
import bot.tafs.app.kiff.OpenTrial
import bot.tafs.app.model.shownDate
import bot.tafs.app.kiff.Switching
import bot.tafs.app.kiff.Version
import bot.tafs.app.model.VersionWords
import bot.tafs.app.kiff.Halt
import bot.tafs.app.kiff.Usage
import bot.tafs.app.model.SentMessages
import kotlinx.coroutines.CoroutineScope
import bot.tafs.app.kiff.ActivityCall
import bot.tafs.app.kiff.AnswerRefusals
import bot.tafs.app.model.Decisions
import bot.tafs.app.model.kiffName
import bot.tafs.app.model.localDate
import bot.tafs.app.model.shortTime
import bot.tafs.app.model.zone
import bot.tafs.app.model.AccountTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import bot.tafs.app.kiff.CardException
import bot.tafs.app.kiff.CloudError
import bot.tafs.app.billing.NoPlayStore
import bot.tafs.app.billing.PurchaseOutcome
import bot.tafs.app.billing.Purchases
import bot.tafs.app.billing.StoreProduct
import bot.tafs.app.kiff.Device
import bot.tafs.app.kiff.DeviceStart
import bot.tafs.app.kiff.Group
import bot.tafs.app.kiff.Person
import bot.tafs.app.kiff.Share
import bot.tafs.app.kiff.Home
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffError
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.kiff.SecretStore
import bot.tafs.app.kiff.TafsCloud
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import bot.tafs.app.signing.ApprovalSigning
import bot.tafs.app.signing.ApprovalKeyRenewed
import bot.tafs.app.signing.DeviceSigner
import bot.tafs.app.signing.Messages
import bot.tafs.app.signing.SignerException
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.CancellationException
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** How the app is fed: nothing yet, the sample, or the owner's KIFF account. */
enum class Mode { signedOut, sample, kiff }

private const val TRIAL_SUFFIX = "#trial"

/** Coach's chat id: the account's Setup, drawn as a taf in the chat screen. */
const val COACH_CHAT = "#coach"
/** A taf's card that hands a setting to Coach: its words, and a button that opens Coach on the taf with them written in. */
const val ASK_COACH = "ask_coach"

/**
 * A card a taf's own chat draws: an `ask_coach` card, or the loop card (a `confirm` that carries `loop`), which a run that read one
 * list page after page waits on. Any other card belongs to Coach's chat.
 */
fun isTafCard(c: bot.tafs.app.kiff.SetupComponent): Boolean = c.type == ASK_COACH || (c.type == "confirm" && c.loop != null)

/** The KIFF agent Coach acts as. */
const val COACH_AGENT = "setup"

/** The id of a taf's open trial's chat: the taf's own, and the trial's thread under it. */
fun trialChatId(tafId: String): String = tafId + TRIAL_SUFFIX

fun isTrialChat(chatId: String): Boolean = chatId.endsWith(TRIAL_SUFFIX)

/** The taf a trial's chat belongs to. */
fun tafOfTrialChat(chatId: String): String = chatId.removeSuffix(TRIAL_SUFFIX)

/** One taf's doors, for its Doors tab and the knocks in its chat, read from tafs cloud with the owner's session. */
data class TafDoors(
  val doors: List<Door> = emptyList(),
  val apps: List<DoorApp> = emptyList(),
  val knocks: List<Knock> = emptyList(),
  val loaded: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
) {
  val openKnocks: List<Knock> get() = knocks.filter { it.state == "open" }

  /** The apps that can still be turned on: one door of each per taf. */
  val addable: List<DoorApp> get() = apps.filter { app -> doors.none { it.kind == app.kind } }
}

/** The run at work in a taf's chat: its session, and the run itself when tafs cloud names it. */
data class ChatRun(val session: String, val run: String? = null)

/**
 * How a purchase or a restore went, said under what was tapped: [at] is the Play product, or [RESTORE]. [offersRestore]: KIFF
 * hasn't recorded it yet, so Restore purchases is offered right there.
 */
data class PurchaseNote(val at: String, val words: String, val offersRestore: Boolean = false) {
  companion object {
    const val RESTORE = "restore"
  }
}

/** What a person may do, as KIFF says it: a KIFF that doesn't say leaves it to KIFF to decide. */
internal fun allows(capabilities: List<String>?, capability: String): Boolean = capabilities?.contains(capability) ?: true

/** An Approve again waiting for the browser: the code the phone shows, which KIFF's page shows too, and the page's link. */
data class Relink(val userCode: String, val link: String)

/** What the screens draw. */
/** A search of the Record: what was asked, the pages read so far, and whether one is on its way or failed. */
data class RecordSearch(
  val q: String,
  val pages: List<bot.tafs.app.kiff.RecordSearchPage> = emptyList(),
  val loading: Boolean = false,
  val error: String? = null,
) {
  val nextCursor: String? get() = pages.lastOrNull()?.nextCursor
}

data class AppState(
  val mode: Mode = Mode.signedOut,
  /**
   * The store's screenshots are being taken (a debug build opened with the `screenshots` extra): the sample shows the Coach button,
   * as a signed-in account has it. A real sample session never offers a Coach it can't run.
   */
  val screenshots: Boolean = false,
  /** Where the owner left off has been looked up: until then no screen draws, so none flashes up wrongly. */
  val ready: Boolean = false,
  /** The KIFF account this sign-in is in; null outside KIFF, and until KIFF has said. */
  val kiffAccount: Account? = null,
  /** This account's list has been read, or tried: until then the list says it is loading, never "0 tafs". */
  val listLoaded: Boolean = false,
  /** The list couldn't be read and none is kept: it says so, never "No tafs yet". */
  val listUnread: Boolean = false,
  /** Agents made through KIFF's API, not run by tafs: their Cards, apart from the tafs. */
  val otherAgents: List<Taf> = emptyList(),
  /** This session holds this phone's signing key: KIFF knows it, so it checks this phone's signature on an answer. */
  val sessionHasKey: Boolean = false,
  /**
   * This phone can't approve until a new sign-in: KIFF holds an approval key for the session that isn't this phone's, or the
   * phone turned its key off when a fingerprint was added. Refusing still works.
   */
  val approvalKeyLost: Boolean = false,
  /** Approve again, waiting for the browser: the code to compare, and where to approve it. */
  val relink: Relink? = null,
  /** Why the last Approve again didn't go through. */
  val relinkProblem: String? = null,
  /** KIFF can't re-link a phone yet: where this phone can't approve, Sign in again is offered, as before. */
  val relinkUnavailable: Boolean = false,
  /** Why Sign out of KIFF, or Sign in again, didn't go through: KIFF didn't end the session. */
  val signOutError: String? = null,
  /** The held calls KIFF said this person may not answer, by call, with its reason: they show words, not buttons. */
  val unanswerable: Map<String, String> = emptyMap(),
  /**
   * The holds this person gave extra room, by KIFF's id. KIFF keeps a hold's id when the taf tries the call again: one back
   * as held, over a limit for one call, needs one more answer (Approve once), and the card says so, with KIFF's reason.
   */
  val roomGiven: Set<String> = emptySet(),
  val tafs: List<Taf> = emptyList(),
  val held: List<HeldCall> = emptyList(),
  /** Needs you, counted by tafs cloud on its home read; null when it couldn't count all of it. */
  val needsYou: bot.tafs.app.kiff.NeedsYouCount? = null,
  /** Cards asked to take another currency, as tafs cloud draws them from KIFF; counted with the held calls in Needs you. */
  val cardUnitExceptions: List<bot.tafs.app.kiff.CardUnitException> = emptyList(),
  /** The ones whose Dismiss is on its way to KIFF. */
  val dismissingCardUnits: Set<String> = emptySet(),
  /** The ones KIFF took a Dismiss for: kept out of every page and Setup read since, even one that was on its way already. */
  val dismissedCardUnits: Set<String> = emptySet(),
  /** Steps a taf handed its owner, as tafs cloud lists them on Home (docs/api.md › Your step); Needs you draws them. */
  val yourSteps: List<bot.tafs.app.kiff.YourStep> = emptyList(),
  /** The hand-offs whose answer is on its way to KIFF. */
  val answeringSteps: Set<String> = emptySet(),
  /** The hand-offs KIFF took an answer for: kept out of a Home read already on its way. */
  val answeredSteps: Set<String> = emptySet(),
  val record: List<RecordEntry> = emptyList(),
  val scriptPosition: Map<String, Int> = emptyMap(),
  val templateOf: Map<String, String> = emptyMap(),
  /** What each taf remembers, once loaded; not kept between launches. */
  val memories: Map<String, List<MemoryNote>> = emptyMap(),
  /** Why a taf's notes couldn't be read, or a note wasn't forgotten. */
  val memoryErrors: Map<String, String> = emptyMap(),
  /** Each taf's latest run and its report, by the taf's id. */
  val reports: Map<String, RunReport> = emptyMap(),
  /** Where each taf's work stands, as tafs cloud says, once read; and why it couldn't be read, or a Send again didn't go. */
  val works: Map<String, bot.tafs.app.kiff.TafWork> = emptyMap(),
  val workErrors: Map<String, String> = emptyMap(),
  /** The requests whose reply is being sent again, by run. */
  val sendingAgain: Set<String> = emptySet(),
  /** The tafs whose Run now is on its way, so a second tap starts nothing. */
  val runsInFlight: Set<String> = emptySet(),
  /** A Run now that found nothing new, by taf: tafs cloud's words for it, shown under the button until the next one. */
  val runNowQuiet: Map<String, String> = emptyMap(),
  /** Each taf's thread, oldest first; the sample's lives here, not between launches. */
  val chats: Map<String, List<ChatMessage>> = emptyMap(),
  /** The tafs a message is on its way to, so one is sent at a time. */
  val chatSending: Set<String> = emptySet(),
  val chatErrors: Map<String, String> = emptyMap(),
  /** The files picked for each chat's next message, as they go up (docs/attachments.md): by the chat's id. Sent with the message, then cleared. */
  val chatFiles: Map<String, List<PendingFile>> = emptyMap(),
  /** Why a file wasn't added, or couldn't be opened, by chat. */
  val fileNotices: Map<String, String> = emptyMap(),
  /** The run at work in each taf's chat, as tafs cloud says it. */
  val chatWorking: Map<String, ChatRun> = emptyMap(),
  /** Since when the owner has written to a taf that hasn't yet answered or been seen at work. */
  val chatAwaiting: Map<String, Instant> = emptyMap(),
  /** The answer each taf is writing now, as its run streams; and how its chat's latest run ended, when the chat says so. */
  val chatLive: Map<String, LiveAnswer> = emptyMap(),
  val chatEnded: Map<String, ChatEnd> = emptyMap(),
  /**
   * What tafs cloud holds of the owner's in each chat (docs/chat.md, the thread's `steering`): what waits for the taf to be done, and
   * what a stopped run never read. tafs cloud's to keep and send; the app only shows it.
   */
  val chatSteering: Map<String, bot.tafs.app.kiff.WireSteering> = emptyMap(),
  /** What the owner sent that tafs cloud's thread doesn't have yet, by taf: kept on screen until it does. */
  val chatSent: Map<String, List<ChatMessage>> = emptyMap(),
  /** The owner's messages that weren't sent, in each chat, kept as written until Retry or Edit (kiff/tafs#476). */
  val chatUnsent: Map<String, List<Unsent>> = emptyMap(),
  /** Each halt as last read, by the taf's id, "" for the whole account. */
  val halts: Map<String, Halt> = emptyMap(),
  /** Why the last Halt or Resume didn't go through, shown on the Halt screen itself, where it was asked. */
  val haltError: String? = null,
  /** KIFF's usage read, for what it still says beside the balance: the store, who bills the plan, and requests. And why it couldn't be read. */
  val usage: Usage? = null,
  val usageError: String? = null,
  /**
   * The balance now (docs/accounts.md#the-balance-read), in tafs cloud's words: Plan & usage, Settings' Plan card and Home's line.
   * Null until read, and from a tafs cloud from before the balance. And why it couldn't be read, in tafs cloud's words where it sends some.
   */
  val balance: bot.tafs.app.kiff.Balance? = null,
  val balanceError: String? = null,
  /** Raise the limit is on its way: one fingerprint, then KIFF. */
  val raising: Boolean = false,
  /** Why Raise the limit didn't go through, under its button. */
  val raiseError: String? = null,
  /**
   * A raise went through but the balance couldn't be read since: what's shown is from before it, so no raise is offered (a
   * second would raise the limit again), only Read it again, which signs nothing.
   */
  val balanceStale: Boolean = false,
  /** Read it again is on its way. */
  val readingBalance: Boolean = false,
  /**
   * The credits the runs used (docs/accounts.md#credits), read with Plan & usage: null until read, and from a tafs cloud from
   * before credits. [creditsPrevious] shows the period before. And why they couldn't be read, in tafs cloud's words where it sends some.
   */
  val credits: bot.tafs.app.kiff.Credits? = null,
  val creditsPrevious: Boolean = false,
  val creditsError: String? = null,
  /** One taf's runs, open from its Credits row, the pages read so far; why they couldn't be read; and More on its way. */
  val tafCredits: bot.tafs.app.kiff.TafCredits? = null,
  val tafCreditsError: String? = null,
  val readingCredits: Boolean = false,
  /** What this person may do with each KIFF agent, as KIFF says; empty until read, and then nothing is shown that needs it. */
  val access: Map<String, Access> = emptyMap(),
  /** Each taf's versions, oldest first, as tafs cloud keeps them; and why one couldn't be loaded or installed. */
  val versions: Map<String, List<Version>> = emptyMap(),
  val versionErrors: Map<String, String> = emptyMap(),
  /** The version being made live on a taf, while this phone works on it. */
  val switchingVersion: Map<String, Int> = emptyMap(),
  /** A switch under way on each taf, as tafs cloud keeps it: one that didn't finish shows here, to finish or cancel. */
  val switches: Map<String, Switching> = emptyMap(),
  /** Versions whose Card KIFF refused at confirm because a Card moved on since it was drafted: drafted again and read before any confirmation. */
  val staleVersionCards: Map<String, Int> = emptyMap(),
  /** The version whose install a run is in the way of, by taf: the screen offers "Stop it and install". */
  val blockedInstalls: Map<String, Int> = emptyMap(),
  /** A version's Card that changed between the screen and the tap: put on screen to read again; nothing was signed. */
  val changedVersionCards: Map<String, AgentDraft> = emptyMap(),
  /** Each taf's open trial: the owner chats with one version under a trial Card of its own, while the live version keeps working. */
  val openTrials: Map<String, OpenTrial> = emptyMap(),
  val trialErrors: Map<String, String> = emptyMap(),
  /** The tafs whose trial is starting, installing or ending, so one tap does one thing. */
  val trialBusy: Set<String> = emptySet(),
  /** The account's Setup (Coach) on tafs cloud, once started; and whether it has been looked for yet. */
  val coachCloudId: String? = null,
  val coachChecked: Boolean = false,
  /** The KIFF agent Coach acts as, once it is started. */
  val coachAgent: String? = null,
  /** Coach is being started on this account, and why it wasn't, when it wasn't. */
  val startingCoach: Boolean = false,
  val coachStartError: String? = null,
  /** Coach's cards whose answer is on its way to tafs cloud, and why one wasn't taken. A card's answer is the thread's, not the phone's. */
  /** Where an approval in progress is, once past opening the vendor's page: "Checking the sign-in…". */
  val connectStep: String? = null,
  val cardsAnswering: Set<String> = emptySet(),
  /**
   * What the owner wrote in each chat and hasn't sent, Coach's included, by chat: kept while they go elsewhere in the app, and
   * dropped with the account's state at sign-out or a switch of account (as iOS's setupDraft).
   */
  val drafts: Map<String, String> = emptyMap(),
  /** Cards whose answer tafs cloud took, before the thread has it: shown answered, never as open again. */
  val cardsTaken: Set<String> = emptySet(),
  val cardErrors: Map<String, String> = emptyMap(),
  /** Coach's cards in its thread, folded. */
  val coachComponents: List<ShownComponent> = emptyList(),
  /** Each drive's end in Coach's chat, at its time, as tafs cloud keeps it (kiff/tafs#568). */
  val coachDrives: List<bot.tafs.app.drive.DriveRecap> = emptyList(),
  /**
   * A taf's own cards in its chat, by taf: its `ask_coach` cards, the settings it hands to Coach (kiff/tafs#476, gap 15), and the loop
   * card a run that kept reading one list waits on ([isTafCard]).
   */
  val tafCards: Map<String, List<ShownComponent>> = emptyMap(),
  /** The Coach conversation shown (kiff/tafs#73): one the owner reopened or started here, or null for the latest ones together. */
  val coachChat: String? = null,
  /** The owner asked for a new conversation: the next message starts one. */
  val coachFresh: Boolean = false,
  /** The taf a new Coach conversation improves (kiff/tafs#107), by its id here: its first message goes to the improve route. */
  val coachImproving: String? = null,
  /** The taf the Coach conversation on screen is about, when it was opened on one; null for one about the account. */
  val coachContext: String? = null,
  /** Coach's past conversations, newest first, and the archived ones (kiff/tafs#97). */
  val coachChats: List<TafsCloud.Chat> = emptyList(),
  val coachArchived: List<TafsCloud.Chat> = emptyList(),
  /** News of the tasks Coach handed tafs, until Coach reads it (kiff/tafs#206). */
  val coachLines: List<TafsCloud.CoachLine> = emptyList(),
  /** Coach was asked to open, from a screen without it: the tab screen opens it, then says so. */
  val coachRequested: Boolean = false,
  /** Coach's tools as KIFF has them, from tafs cloud; null until read. And why giving them didn't go. */
  val coachTools: bot.tafs.app.kiff.CoachTools? = null,
  /**
   * Coach's first start under one fingerprint, as tafs cloud builds it before Coach exists (docs/coach.md); null once Coach is
   * there, or from a tafs cloud that sends none. Startable, its step is Coach's tools step, and its one button starts Coach.
   */
  val coachStart: bot.tafs.app.kiff.CoachStart? = null,
  /** The recipes a new Coach conversation opens to, from tafs cloud; null until read, or when they couldn't be. */
  val recipes: bot.tafs.app.kiff.Recipes? = null,
  /** Every job a taf can take, for + New and the strip under the tafs list; null before it's read, or from an older tafs cloud. */
  val gallery: bot.tafs.app.kiff.RecipesGallery? = null,
  val coachToolsError: String? = null,
  /** The account's planque grants: connections, and connections waiting for the owner; and whether planque answered this phone yet. */
  val grants: List<Grant> = emptyList(),
  /** The request open from the rim or Activity: its taf, and the request step by step. */
  val openRequest: Pair<String, bot.tafs.app.kiff.RequestDetail>? = null,
  /** Why a request couldn't be opened. */
  val requestError: String? = null,
  /** The channels each Slack bot connection can see, by grant id, for a Slack door. */
  val slackChannels: Map<String, List<bot.tafs.app.kiff.SlackChannel>> = emptyMap(),
  /** Each taf's dial as tafs cloud last sent it, by taf id. */
  val knobs: Map<String, bot.tafs.app.kiff.KnobWire> = emptyMap(),
  /** The tafs whose app event is being approved on the dial. */
  val approvingEvent: Set<String> = emptySet(),
  /** Why a dial change didn't go through, in tafs cloud's words when it sends them, by taf id. */
  val knobErrors: Map<String, String> = emptyMap(),
  /**
   * The version tafs cloud said a dial change made (or waits on) that couldn't be read back yet, by taf id. Until the dial
   * shows it, − and + stay off: they'd build on the choices from before.
   */
  val knobBehind: Map<String, Int> = emptyMap(),
  /** Money calls whose outcome KIFF can't prove, waiting on "Did this happen?" in Needs you. */
  val unsure: List<bot.tafs.app.kiff.UnsureCall> = emptyList(),
  /** The proposal whose answer is on its way. */
  val answeringUnsure: String? = null,
  /** Why an answer to "Did this happen?" didn't go through, by proposal. */
  val unsureErrors: Map<String, String> = emptyMap(),
  /** Connect apps: the catalog as tafs cloud lists it, for the last search; null before it's read. */
  val connectApps: bot.tafs.app.kiff.ConnectApps? = null,
  /** Why Connect apps couldn't be read, or a connection couldn't start. */
  val connectAppsError: String? = null,
  /** The app whose connection is starting, by its entry. */
  val startingApp: String? = null,
  /** The grant whose approval is open over Connect apps. */
  val listApproval: String? = null,
  /** Whether that connection takes an API key, given on the sheet instead of a vendor's sign-in. */
  val listApprovalNeedsKey: Boolean = false,
  /** The secure connection sheet's words, by grant, from tafs cloud (docs/secure-connection.md); and why one couldn't be read. */
  val secureSheets: Map<String, bot.tafs.app.kiff.SecureSheet> = emptyMap(),
  val secureSheetErrors: Map<String, String> = emptyMap(),
  /** The key went: the receipt on the secure sheet, with the pasted key's last 4, kept only while the sheet is open. */
  val keyReceipt: KeyReceipt? = null,
  /** The key sheet opened from Coach's card: that card, and its draft version, answered once the key went. */
  val keyForCard: KeyForCard? = null,
  /** "Allow this from now on", open on a held call (docs/allow-from-now-on.md). */
  val allow: AllowSheetState? = null,
  /** A connection from Connect apps that just came back signed in or connected: said at once, over the list. */
  val connectedNow: bot.tafs.app.kiff.Grant? = null,
  /**
   * KIFF's words for a grant's profile, by grant id, checked against their digests ([ProfileWords]). A grant's profile can change
   * under the same id (tools added to a connection): read them through [profileWordsFor], which holds only the current profile's.
   */
  val profileWords: Map<String, bot.tafs.app.kiff.ProfileWords> = emptyMap(),
  /** Why a grant's words couldn't be read or didn't check out, by grant id. */
  val profileWordsErrors: Map<String, String> = emptyMap(),
  val planqueReady: Boolean = false,
  /** The grant being connected now, so a second tap starts nothing. */
  val connecting: String? = null,
  /** The drafts Coach's approvals name, read from KIFF so the owner sees the Cards. */
  val setupDrafts: Map<String, AgentDraft> = emptyMap(),
  /** Why a card's step wasn't done, by card. */
  val coachNotes: Map<String, String> = emptyMap(),
  /** Clearing removed tafs' connections is on its way, and how it went, under them in Connect apps. */
  val clearingRemoved: Boolean = false,
  val clearNote: String? = null,
  /** A drive card being approved, by its key: Approve waits for the fingerprint and tafs cloud. */
  val driveStarting: String? = null,
  /** The drive that just ended, for the "What I did" card in Coach's chat, until the person closes it. */
  /**
   * The calendar (kiff/tafs#534): what it shows, kept here so a rotation or a new layout keeps it; the last read, for the selected
   * month's grid; and why it couldn't be read. A new account starts a new state, so another account's events never show.
   */
  val calendarState: bot.tafs.app.model.CalendarState? = null,
  val calendar: bot.tafs.app.kiff.CalendarRead? = null,
  val calendarError: String? = null,
  /** The account's "Show suggestions" setting as tafs cloud last said it, while it's being changed, and why a change didn't go. */
  val suggestions: bot.tafs.app.kiff.Suggestions? = null,
  val suggestionsBusy: Boolean = false,
  /**
   * The person's AI-data consent as tafs cloud last said it, with the screen's words (docs/accounts.md, "Your data and the AI").
   * [aiConsentAsked] shows the screen: after sign-in until they answer, and again when tafs cloud refuses a model turn for it.
   */
  val aiConsent: bot.tafs.app.kiff.AIConsent? = null,
  val aiConsentAsked: Boolean = false,
  val aiConsentBusy: Boolean = false,
  val aiConsentError: String? = null,
  val suggestionsError: String? = null,
  /** KIFF's words for the next approval while this phone holds an approval window ("No fingerprint for the next 4 minutes"). */
  val windowWords: String? = null,
  /** planque's live sign-in view for a browser connection, open over everything until the owner closes it. */
  val siteSignIn: bot.tafs.app.kiff.SiteSignInView? = null,
  /** The connection whose sign-in view is being asked for. */
  val openingSiteSignIn: String? = null,
  /** Alerts can work in this build (Firebase is set up), whether this phone gets them, and whether this person may answer holds, as tafs cloud last said. */
  val alertsAvailable: Boolean = false,
  val alertsReady: Boolean = false,
  /** Alerts were on for this account when this phone signed out, and didn't come back on with the sign-in: it says so once. */
  val alertsOffSinceSignOut: Boolean = false,
  /**
   * Alerts were offered on this phone for this account already (answered, or turned on): the offer on a new taf's card shows once.
   * True until the session says otherwise, so it never shows for a session not read yet.
   */
  val alertsOffered: Boolean = true,
  val canAnswerHolds: Boolean? = null,
  val turningOnAlerts: Boolean = false,
  /** Sound for alerts on this phone: off, tafs cloud sends this phone's alerts quietly. */
  val alertSound: Boolean = true,
  val phoneAlerts: bot.tafs.app.kiff.PhoneAlerts? = null,
  val phoneAlertsBusy: Boolean = false,
  val phoneAlertsError: String? = null,
  val timeZones: List<bot.tafs.app.kiff.TimeZoneChoice> = emptyList(),
  val timeZoneQuery: String = "",
  val tafSetups: Map<String, bot.tafs.app.kiff.TafSetup> = emptyMap(),
  val tafSetupErrors: Map<String, String> = emptyMap(),
  val activityPages: Map<String, SourcePages<bot.tafs.app.kiff.TafActivityPage>> = emptyMap(),
  val runPages: Map<String, SourcePages<bot.tafs.app.kiff.TafRunsPage>> = emptyMap(),
  /** The Record's search box words, as tafs cloud says them ("Search by order, invoice, amount or action"); null until read. */
  val recordHint: String? = null,
  /** The Record searched across the person's tafs; null while nothing is searched. */
  val recordSearch: RecordSearch? = null,
  /** The case open from a search: every call about the same thing, oldest first. */
  val recordCase: bot.tafs.app.kiff.RecordCase? = null,
  /** Why the case couldn't be read, in words. */
  val recordCaseError: String? = null,
  /** Where a tapped alert asked to go ("needs-you", "usage"), until the screen has gone there. */
  val requestedOpen: String? = null,
  /** The doors tafs cloud offers this account: none, and no taf shows Doors. */
  val doorsOffered: List<String> = emptyList(),
  /** Each taf's doors, once read. */
  val doors: Map<String, TafDoors> = emptyMap(),
  /** Buying through Google Play is in this build (res/values/flags.xml). */
  val playBilling: Boolean = false,
  /** Play's names and prices for what KIFF sells; the product being bought; and what happened to the last purchase, request or answer. */
  val storeProducts: Map<String, StoreProduct> = emptyMap(),
  /** Whether Play has been asked for them yet, and why its last answer failed (Purchases.buyNote says it). */
  val storeLoaded: Boolean = false,
  val storeFailed: String? = null,
  val buying: String? = null,
  val tasksMessage: String? = null,
  /** How the latest purchase or restore went, said under what was tapped (Start Solo, a pack, Restore purchases). */
  val purchaseNote: PurchaseNote? = null,
  /**
   * Restore purchases is offered: while Play knows a purchase not finished yet, or couldn't say, or after one KIFF didn't record.
   * Launch and sign-in already send what's unfinished, so with nothing there it has nothing to do.
   */
  val restoreShown: Boolean = false,
  /** A first Start just made Coach, with tools still to give: the tools step is asked, until given or put off. */
  val coachToolsAsked: Boolean = false,
  /**
   * What a refresh the app made by itself couldn't do, said quietly under the account's name ("Can't reach KIFF"), never as
   * "Not done"; null once one goes through. And whether the app is still trying again by itself.
   */
  val unreachable: String? = null,
  val retryingRefresh: Boolean = false,
  /** This device's time zone, offered once when the account's differs; and why the account's didn't change, when it didn't. */
  val timeZoneOffer: String? = null,
  val timeZoneError: String? = null,
  /** The account's groups (Everyone first) and its active people, as KIFF lists them; and why they couldn't be read or changed. */
  val groups: List<Group> = emptyList(),
  val people: List<Person> = emptyList(),
  val peopleError: String? = null,
  /** The devices signed in as this person on the account (Settings, Devices), and why they couldn't be read or one signed out. */
  val devices: List<Device> = emptyList(),
  val devicesError: String? = null,
  /** The account is being deleted, and why deleting it stopped, for its sheet; null when nothing went wrong. */
  val deletingAccount: Boolean = false,
  val deleteAccountError: String? = null,
  /** Why something wasn't done, for the "Not done" alert; null when nothing is. */
  val lastError: String? = null,
) {
  val signedIn: Boolean get() = mode != Mode.signedOut

  /**
   * Whether this person manages the taf, as KIFF says for it: its Card, versions, halting it. Without KIFF's answer for
   * it (not read yet, or failed), nothing is shown: an Editor manages only the tafs they made, which the role alone can't say.
   */
  fun manages(tafId: String): Boolean = access[tafId]?.manage ?: false

  /** The taf's connected Slack bot connections, which a Slack door listens through. */
  fun slackBots(tafId: String): List<bot.tafs.app.kiff.Grant> = grants.filter { it.connected && it.isSlackBot && it.request.agent == tafId }

  /** The taf's connected bot connections from [provider] (tafs cloud names it on the door's app), which a bot door listens through. */
  fun bots(tafId: String, provider: String): List<bot.tafs.app.kiff.Grant> = grants.filter { it.connected && it.isFrom(provider) && it.request.agent == tafId }

  /** The tafs an app can be connected for: on tafs cloud, and managed by this person. */
  val connectableTafs: List<Taf> get() = tafs.filter { it.cloudId != null && manages(it.id) }

  /**
   * Who a taf's Card reads as issued by: "You" on a taf this person manages, else the account, by its name, never a person
   * (a taf shared with them was issued by whoever manages it), as iOS's `issuer(of:)`.
   */
  fun issuer(taf: Taf): String = if (mode != Mode.kiff || manages(taf.id)) taf.card.issuedBy else accountLabel ?: Words.Card.issuedByAccount

  /**
   * Doors are tafs cloud's, and only where it offers one: a taf it runs, that this person manages: who may give it work
   * and from where is the manager's to say.
   */
  fun hasDoors(tafId: String): Boolean = mode == Mode.kiff && doorsOffered.isNotEmpty() && taf(tafId)?.cloudId != null && manages(tafId)

  /**
   * A taf's connections: the grants made for its KIFF agent that are connected, or whose sign-in planque holds while Coach chooses
   * the tools, so the owner can see and end them.
   */
  fun connections(tafId: String): List<Grant> =
    grants.filter { it.request.agent == tafId && (it.connected || it.holdsSignIn || it.needsReconnect || it.waitingSignIn) }

  /**
   * The taf's connection its vendor signed out, if one is: the taf can't use those tools until the owner signs in again, so it
   * waits on the owner in the list, in planque's words.
   */
  fun signedOut(tafId: String): Grant? = grants.firstOrNull { it.request.agent == tafId && it.needsReconnect }

  /** The Card draft a switch to [version] waits for: the one tafs cloud prepared when it began, else the version's own. */
  fun switchDraft(version: Version, tafId: String): String? {
    val switch = switches[tafId]
    return if (switch != null && switch.n == version.n) switch.draft else version.draft
  }

  /** The halt of a taf, or with none of the whole account, as last read: running until read. */
  fun haltOf(tafId: String?): Halt = halts[tafId.orEmpty()] ?: Halt.Running

  /**
   * What Needs you holds, as Home's headline and the bar's badge count it: tafs cloud's count, else (when it couldn't count all
   * of it) the app's own of what it has, held calls, currency requests and "Did this happen?".
   */
  val needsYouCount: Int get() = needsYou?.count ?: (held.size + cardUnitExceptions.size + unsure.size + yourSteps.size)

  /** Home's line for it: tafs cloud's words, else the app's for its own count. */
  val needsYouWords: String get() = needsYou?.words ?: needsYouCount.let { if (it == 0) Words.NeedsYou.nothing else Words.NeedsYou.count(count = it) }

  /** Halting is tafs cloud's: the account's, or a taf it runs. */
  fun canHalt(tafId: String?): Boolean = mode == Mode.kiff && (tafId == null || taf(tafId)?.cloudId != null)

  /**
   * Home's row for halting every taf: with tafs to halt, or whenever the account isn't running, tafs or not, so an account
   * halted with none left can still be resumed.
   */
  val showsAccountHalt: Boolean get() = canHalt(null) && (tafs.isNotEmpty() || !haltOf(null).running)

  /** "Allow this from now on" on [call]: for someone who manages Cards, on a hold a Card change can lift, from KIFF. */
  fun offersAllow(call: HeldCall): Boolean =
    mode == Mode.kiff && canManageCards && allowLive && call.kiffId != null && holder(call.tafId)?.cloudId != null && bot.tafs.app.model.HoldAllow.allowable(call)

  /**
   * A message refused because the account or its taf is halted: while that halt holds, its Not sent row offers Resume, behind the
   * owner's confirmation, in place of a Retry that can't work. Resumed, Retry is back.
   */
  fun unsentWaitsOnHalt(tafId: String, unsent: Unsent): Boolean = when (unsent.halted) {
    Unsent.HALTED_ACCOUNT -> canHalt(null) && !haltOf(null).running
    Unsent.HALTED_TAF -> canHalt(tafId) && !haltOf(tafId).running
    else -> false
  }

  /** A taf by the KIFF agent it acts as; the agent's id when the app doesn't know it. */
  fun tafName(agent: String): String = taf(agent)?.name ?: agent

  /** The Record entry of a counted task, by KIFF's call key (the usage read's `record`). */
  fun recordEntry(forCall: String): RecordEntry? = record.firstOrNull { it.entityId == forCall }

  /**
   * Whether this person may answer held calls (KIFF's holds.answer): a viewer may not, so their card says who
   * answers instead of offering buttons KIFF refuses. An account from before roles lists no capabilities, and may.
   */
  val mayAnswerHolds: Boolean get() = allows(kiffAccount?.capabilities, "holds.answer")

  /** Whether this person may give a Card extra room (KIFF's cards.manage): offered only to them. */
  val canManageCards: Boolean get() = allows(kiffAccount?.capabilities, "cards.manage")

  /**
   * "Allow this from now on" is live: KIFF's `GET /v1/me` lists `holds.allow` once its draft for it (`allow-draft`) answers. Never assumed: with no
   * list, or none, the button isn't drawn, so no one taps into a route that isn't there yet.
   */
  val allowLive: Boolean get() = kiffAccount?.capabilities?.contains("holds.allow") == true

  /**
   * Whether buying is offered here: Play Billing in this build, the balance offering Solo, packs or the First month pack to this person (`buy`), and KIFF
   * naming Play's products and the account's token. Until KIFF records Play purchases it names none, so nothing is offered.
   */
  val offersPurchases: Boolean
    get() = playBilling && mode == Mode.kiff && balance?.balance?.let { it.offers("solo") || it.offers("packs") || it.firstMonth != null } == true &&
      usage?.store?.appAccountToken != null && usage.store.googleProducts.isNotEmpty()

  /** Whether this person manages billing (KIFF's billing.manage): raises the limit, buys, and answers requests; others ask. */
  val managesBilling: Boolean get() = allows(kiffAccount?.capabilities, "billing.manage")

  /** Whether this person may make and change groups (KIFF's members.manage). */
  val canManagePeople: Boolean get() = kiffAccount?.capabilities?.contains("members.manage") ?: false

  /** This person, as KIFF lists the account's people. */
  val me: Person? get() = people.firstOrNull { it.you == true }

  fun person(subject: String): Person? = people.firstOrNull { it.subject == subject }

  /** Whether this person may change a group's people: members.manage, or its manager. */
  fun managesGroup(group: Group): Boolean = canManagePeople || group.members.any { it.subject == me?.subject && it.isManager }

  /** Whether this person may delete the account (KIFF's account.delete): Delete account shows only to them. */
  val canDeleteAccount: Boolean get() = mode == Mode.kiff && allows(kiffAccount?.capabilities, "account.delete")

  /**
   * Whether the owner can answer [call] from this phone. A held call from KIFF is answered here only when KIFF
   * checks this phone's signature on it: the session holds the phone's key, and the hold says which version of
   * it the owner saw.
   */
  fun canAnswer(call: HeldCall): Boolean = call.kiffId == null || (sessionHasKey && call.accountingVersion != null)

  /** The account this person is in, by its name, where they can always see it. Null outside KIFF. */
  val accountLabel: String? get() = if (mode == Mode.kiff) kiffAccount?.name else null

  val workingCount: Int get() = tafs.count { it.state == TafState.working }

  fun taf(id: String): Taf? = tafs.firstOrNull { it.id == id } ?: otherAgents.firstOrNull { it.id == id } ?: trialTaf(id) ?: coachTaf(id)

  /**
   * Whether this person may use Coach: KIFF's answer for the account's Setup agent, else by default an account admin
   * (the one who manages members).
   */
  /**
   * Alerts are offered on a new taf's card (docs/android-app.md): the first run's one offer, so a held call never reaches nobody.
   * Only where alerts can be on and aren't, once per account on this phone.
   */
  val offersAlerts: Boolean get() = mode == Mode.kiff && alertsAvailable && !alertsReady && !alertsOffered

  val mayUseCoach: Boolean
    get() = (mode == Mode.kiff && (access[COACH_AGENT]?.use ?: (kiffAccount?.capabilities?.contains("members.manage") ?: false))) ||
      (mode == Mode.sample && screenshots)

  /** Whether this person sees every taf's Record (oversees or manages each), which the receipts export needs; tafs cloud still decides. */
  val seesEveryTaf: Boolean
    get() = mode == Mode.kiff && tafs.any { it.cloudId != null } &&
      tafs.filter { it.cloudId != null }.all { access[it.id]?.let { a -> a.oversee || a.manage } == true }

  /** The app's taf for tafs cloud's id, when the person has it on Home. */
  fun tafByCloudId(cloudId: String): Taf? = tafs.firstOrNull { it.cloudId == cloudId }

  /** Coach's chat drawn as a taf, so the one chat screen serves both. */
  private fun coachTaf(chatId: String): Taf? {
    if (chatId != COACH_CHAT) return null
    val cloudId = coachCloudId ?: return null
    return coach(cloudId)
  }

  private fun coach(cloudId: String?) =
    Taf(id = COACH_CHAT, name = Words.Coach.title, job = "", tools = emptyList(), colorIndex = 2, card = Card(rules = emptyList(), issuedBy = ""), now = "", cloudId = cloudId)

  /**
   * Who holds a call, as the owner reads it: its taf, or Coach for a call from Coach's own KIFF agent, which no taf in the list
   * is, drawn with Coach's mark; null when neither.
   */
  fun holder(agentId: String): Taf? =
    taf(agentId) ?: coachAgent?.takeIf { it.isNotEmpty() && it == agentId }?.let { coach(coachCloudId) }

  /**
   * A trial's chat drawn as its taf, named as the lists name it: "Refunds · v2". The chat is the taf's chat view on the
   * trial's own id, so one chat screen serves both.
   */
  private fun trialTaf(chatId: String): Taf? {
    if (!isTrialChat(chatId)) return null
    val tafId = tafOfTrialChat(chatId)
    val trial = openTrials[tafId] ?: return null
    val taf = tafs.firstOrNull { it.id == tafId } ?: return null
    return taf.copy(
      id = chatId,
      name = Words.Version.Trial.chatName(taf = taf.name, n = trial.n.toString()),
      badge = trial.label ?: Words.Version.Trial.badge,
      now = listOfNotNull(trial.title ?: Words.Version.Trial.trying(n = trial.n.toString()), trial.ends?.let { Words.Version.Trial.endsIfUnused(date = shownDate(it)) }).joinToString(" · "),
    )
  }

  /**
   * The tafs as listed: one waiting on the owner first, then the one used or at work most recently (its
   * latest call, or the latest message in its chat), so the taf the owner just worked with is at the top.
   * Tafs with nothing yet keep tafs cloud's order, after the rest.
   */
  /** The tafs waiting on the owner: a call held for an answer, or a connection to sign in to again. */
  val waitingOnOwner: Set<String>
    get() = held.map { it.tafId }.toSet() + tafs.filter { signedOut(it.id) != null }.map { it.id }

  val listedTafs: List<Taf>
    get() {
      val latest = mutableMapOf<String, Instant>()
      for (e in record) latest[e.tafId] = maxOf(latest[e.tafId] ?: Instant.MIN, e.at)
      for ((id, messages) in chats) messages.lastOrNull()?.at?.let { latest[id] = maxOf(latest[id] ?: Instant.MIN, it) }
      val waiting = waitingOnOwner
      return tafs.withIndex().sortedWith(
        compareByDescending<IndexedValue<Taf>> { it.value.id in waiting }
          .thenByDescending { latest[it.value.id] ?: Instant.MIN }
          .thenBy { it.index },
      ).map { it.value }
    }

  /**
   * Whether KIFF still holds this Record entry's call for an answer: a held call with its proposal is in
   * Needs you. Before KIFF's list is read, a held entry counts as waiting.
   */
  fun isWaiting(entry: RecordEntry): Boolean {
    if (mode != Mode.kiff || !listLoaded) return true
    return held.any { call -> (call.proposalId ?: call.kiffId)?.let(KiffMapping::recordId) == entry.id }
  }

  fun entries(tafId: String): List<RecordEntry> = record.filter { it.tafId == tafId }
  fun heldCalls(tafId: String): List<HeldCall> = held.filter { it.tafId == tafId }

  /** A taf can chat in the sample, and on KIFF once tafs cloud runs it. */
  fun canChat(tafId: String): Boolean = mode == Mode.sample || (mode == Mode.kiff && taf(tafId)?.cloudId != null)

  /**
   * True while the taf is at work on its chat, or hasn't answered the owner's last message yet: it shows thinking,
   * and the field says the next message is written to it while it works.
   */
  fun thinking(tafId: String): Boolean = tafId in chatSending || (mode == Mode.kiff && (tafId in chatWorking || tafId in chatAwaiting || tafId in chatLive))

  /** Whether the chat takes photos and files: a taf (or Coach) on tafs cloud, signed in; not the sample, nor a trial's chat. */
  fun canAttach(tafId: String): Boolean =
    mode == Mode.kiff && !isTrialChat(tafId) && taf(tafId)?.cloudId != null &&
      // A new Coach conversation on a taf starts with the improve route, which takes words only.
      !(tafId == COACH_CHAT && coachFresh && coachImproving != null)

  /** The files picked for the chat's next message, as they go up. */
  fun files(tafId: String): List<PendingFile> = chatFiles[tafId].orEmpty()

  /**
   * Whether Send waits: while any file is still going up, words or not, so a message never goes without a file just
   * attached; and while files are ready but the taf (or Coach) is at work, since files go with a message of their own,
   * never into a run.
   */
  fun holdsSend(tafId: String): Boolean =
    files(tafId).any { it.state == PendingFile.State.Uploading } || (thinking(tafId) && files(tafId).any { it.ref != null })

  /**
   * Whether Retry on a message that wasn't sent waits: while another message is on its way, and, for one with files, while the
   * taf (or Coach) is at work, as Send waits for files. It stays Not sent meanwhile, words and files.
   */
  fun holdsRetry(tafId: String, unsent: Unsent): Boolean =
    tafId in chatSending || (unsent.files.isNotEmpty() && thinking(tafId))

  /**
   * A taf's chat as one timeline: its messages, the calls held for the owner where they were
   * held, and what became of earlier held calls (approved, refused) since the thread's first
   * message, each as one line.
   */
  /**
   * A chat's items, each once: the list keys its rows by id, and a repeated key crashes it, so an exact repeat (the same
   * message twice in a thread) shows once.
   */
  fun chatItems(tafId: String): List<ChatItem> = chatItemsAsRead(tafId).distinctBy { it.id }

  /** The digest of [grant]'s profile as planque sends it now, or null for a grant with none. */
  fun profileDigest(grant: bot.tafs.app.kiff.Grant): String? = grant.request.profile?.let(bot.tafs.app.kiff.ProfileWords::digest)

  /** KIFF's words for [grant]'s profile as it is now: none while the words read are an earlier profile's. */
  fun profileWordsFor(grant: bot.tafs.app.kiff.Grant): bot.tafs.app.kiff.ProfileWords? =
    profileDigest(grant)?.let { digest -> profileWords[grant.id]?.takeIf { it.profileSha256 == digest } }

  private fun chatItemsAsRead(tafId: String): List<ChatItem> {
    // A message that wasn't sent stays where it was written, until Retry or Edit.
    val messages = (chats[tafId] ?: emptyList()) + chatUnsent[tafId].orEmpty().map { it.message }
    // Coach's thread has its cards in it, and the owner's answers to them read as what they chose.
    if (tafId == COACH_CHAT) {
      // An older thread's answers, sent as text before cards were answered as data, read as what the owner chose.
      return CoachWords.items(messages.map { if (it.from == ChatMessage.From.owner) it.copy(text = CoachWords.ownerWords(it.text, coachComponents)) else it }, coachComponents, coachDrives)
    }
    val items = mutableListOf<ChatItem>()
    items += messages.map { ChatItem.Message(it) }
    items += heldCalls(tafId).map { ChatItem.Held(it) }
    // The settings it handed to Coach, each at its time (kiff/tafs#476, gap 15).
    items += tafCards[tafId].orEmpty().map { ChatItem.Card(it, it.firstAt) }
    messages.firstOrNull()?.at?.let { first ->
      // What became of a call the owner approved shows too, done or not (kiff/tafs#115).
      items += entries(tafId).filter {
        (it.kind == RecordEntry.Kind.approved || it.kind == RecordEntry.Kind.refused ||
          (it.ownerApproved == true && it.kind in setOf(RecordEntry.Kind.notDone, RecordEntry.Kind.sending, RecordEntry.Kind.unknown))) &&
          it.at >= first
      }.map { ChatItem.Outcome(it) }
    }
    // By time; what happened at the same moment keeps the order it was added in.
    return items.sortedBy { it.at }
  }
}

/**
 * The app's state and what changes it, as iOS's AppModel has it. The phone never decides: in
 * production KIFF decides every action; [CardCheck] only drives the sample.
 */
class AppModel(
  private val store: SampleStore?,
  private val signer: ApprovalSigning,
  private val kiff: KiffClient,
  /** tafs cloud, which lists the account's tafs and their Cards from KIFF's. */
  private val cloud: TafsCloud,
  /** Where the KIFF session is kept: this phone only. */
  private val secrets: SecretStore,
  private val ownerName: String = Words.Common.you,
  private val clock: () -> Instant = Instant::now,
  /** How long a sample taf takes to answer; tests set it to zero. */
  private val sampleReplyDelayMs: Long = 700,
  /** What KIFF's device list calls this phone: "tafs on Android phone". */
  private val deviceName: String = "tafs on Android",
  /** Where a chat is followed while a run is at work; the view model's own, unless a test gives one. */
  private val followScope: CoroutineScope? = null,
  /** Firebase and the notification permission, as this phone has them; none in a build without Firebase's settings. */
  private val push: PushPlatform = NoPush,
  /** planque, which keeps the vendor sign-ins: Coach's connections are approved through it. */
  private val planque: PlanqueClient = PlanqueClient(bot.tafs.app.kiff.UrlConnectionTransport()),
  /** The vendor's own sign-in page, opened in a browser. */
  private val vendorSignIn: VendorSignIn = NoVendorSignIn,
  /** Buying through Google Play; none in a build with Play Billing off (res/values/flags.xml). */
  private val purchases: Purchases = Purchases(NoPlayStore),
  private val playBilling: Boolean = false,
  /** Where files the person opens full size are copied (docs/attachments.md): removed on sign-out. */
  private val openedFiles: java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "attachments"),
) : ViewModel() {
  private val _state = MutableStateFlow(AppState())
  val state: StateFlow<AppState> = _state

  /** The account KIFF signed this session in to, for signed requests. */
  private var kiffTenant: String? = null

  /** KIFF holds this phone's approval key for the session (kiff/tafs#233): approving counts only then. */
  private var sessionHasApprovalKey = false

  /** Bumped when an account is left, so an answer for the account that was is dropped. */
  private var accountGeneration = 0
  /**
   * Moves on when what tafs cloud writes in the account's clock changes (a new time zone): a page, Setup, dial or work read
   * begun before then is in the old zone, and is dropped when it lands rather than shown.
   */
  private var presentationGeneration = 0
  /** Moves on when Coach leaves its conversation for another: reads and streams of the earlier one stop showing. */
  private var coachGeneration = 0
  private fun shownGeneration() = accountGeneration * 1_000_000 + presentationGeneration

  /** The session ([accountGeneration]) Coach's setup was last read in for a hold no taf is. */
  private var setupReadIn = -1

  /**
   * The owner's answers to KIFF's held calls, by their record id, until KIFF records the outcome
   * (kiff/tafs#138): a refresh just after an answer still reads the call as held.
   */
  private val decided = mutableMapOf<String, RecordEntry>()

  init {
    // KIFF wants the approval key this phone doesn't hold (kiff/tafs#233).
    kiff.approvalKeyRequired = { lostApprovalKey() }
    // tafs cloud refused a model turn until the person allows AI use: its screen shows there and then, in its words.
    cloud.aiConsentNeeded = { consent -> if (consent.drawable) _state.update { it.copy(aiConsent = consent, aiConsentAsked = true, aiConsentError = null) } }
  }

  // MARK: Where the owner left off

  /** Restores where the owner left off: signed in to KIFF, or the saved sample. */
  suspend fun restore() {
    val token = secrets.load(TOKEN)
    if (token != null) {
      resumeSession(token, secrets.load(TENANT), keyed = secrets.load(KEYED) == "1")
      endDriveLeftOn()
    } else {
      store?.load()?.let { startSample() }
    }
    _state.update { it.copy(ready = true) }
  }

  /** Goes on with a KIFF session kept from an earlier launch. */
  private suspend fun resumeSession(token: String, tenant: String?, keyed: Boolean) {
    kiff.token = token
    cloud.token = token
    kiffTenant = tenant
    kiff.tenant = tenant
    forgetApprovalKey()
    // Checked at launch, with no prompt: a fingerprint added since turned the key off, and the owner learns it here, not
    // after a tap that fails.
    val keyGone = keyed && !signer.approvalKeyUsable()
    // The approval key counts only while it's the one KIFF registered: a key made anew since (the
    // lock screen turned off and on) registers again, and KIFF's answer says whether it may.
    val key = signer.approvalPublicKeyDER
    if (keyed && key.isNotEmpty() && KiffClient.sameKey(secrets.load(APPROVAL_KEYED), key)) useApprovalKey()
    // Drawn at once, saying it is loading, not after the network: the owner never waits on a blank screen.
    _state.update { it.copy(mode = Mode.kiff, ready = true, sessionHasKey = keyed, alertsAvailable = push.available, alertSound = secrets.load(ALERT_SOUND) != "0", alertsOffered = alertsOfferedOn(tenant)) }
    if (keyGone) lostApprovalKey()
    refresh()
    loadAIConsent(ask = true)
    // A session from before the approval key registers it once (kiff/tafs#233).
    registerApprovalKey()
    // This phone's alerts, where its owner turned them on.
    syncPush()
    startPurchases()
  }

  // MARK: Sign in

  /**
   * Starts signing in with this phone's signing key, so KIFF accepts its answers only with that key's
   * signature. A connection dropped while the app was away is tried again before it counts as a failure.
   */
  suspend fun startSignIn(fresh: Boolean = false): DeviceStart {
    signInNudged = false
    // A sign-in under way goes on with its code: the screen, or the whole app, may have been recreated while the person
    // approved it in the browser, and a new code would never be approved.
    if (!fresh) pendingSignIn()?.let { return it.start }
    endSignIn()
    var tries = 0
    while (true) {
      try {
        val key = signer.publicKeyDER
        val approvalKey = signer.approvalPublicKeyDER
        return kiff.startDeviceSignIn(
          publicKey = key.takeIf { it.isNotEmpty() },
          approvalPublicKey = approvalKey.takeIf { key.isNotEmpty() && it.isNotEmpty() },
          deviceName = deviceName,
          // KIFF's device list names the platform; it takes one only with a key.
          platform = if (key.isEmpty()) null else PLATFORM,
        ).also { keepSignIn(it) }
      } catch (e: Exception) {
        if (e is CancellationException || !transient(e) || tries >= 2) throw e
        tries += 1
        delay(500)
      }
    }
  }

  /** How long a sign-in poll waits before asking KIFF again, at least; and how often it looks whether the app came back. */
  var signInSliceMs = 500L

  /** The least time between two polls, in seconds, whatever KIFF advertises. */
  var signInMinimumIntervalSeconds = 2

  private var signInNudged = false

  /**
   * The app came back to the foreground: a sign-in, or an Approve again, asks KIFF again at once, so an approval made in the
   * browser counts without a wait.
   */
  fun nudgeSignIn() {
    signInNudged = true
  }

  /**
   * Polls until the person approves the code in KIFF, then loads their account. The code is kept until it's approved, refused
   * or expired; a poll stopped because the screen went away keeps it, so the sign-in goes on with the same code.
   */
  suspend fun finishSignIn(start: DeviceStart) {
    val deadline = pendingSignIn()?.takeIf { it.start.deviceCode == start.deviceCode }?.until ?: clock().plusSeconds(start.expiresIn.toLong())
    val token = try {
      untilApproved(start, deadline) { kiff.pollDeviceToken(start.deviceCode) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      endSignIn()
      throw e
    }
    endSignIn()
    // The account loads to the end whatever the screen does: the sign-in screen leaves as soon as the app is signed in, and its
    // coroutine with it, which used to cancel the first refresh and leave Home on "Loading your tafs…" until a pull.
    withContext(NonCancellable) { useSession(token.accessToken, token.tenant) }
  }

  /** A sign-in's code and when it expires, kept with the phone's secrets until it's done. */
  @Serializable
  private data class PendingSignIn(val start: DeviceStart, @Serializable(with = bot.tafs.app.model.InstantAsString::class) val until: Instant)

  private var pendingSignIn: PendingSignIn? = null

  private fun keepSignIn(start: DeviceStart) {
    val pending = PendingSignIn(start, clock().plusSeconds(start.expiresIn.toLong()))
    pendingSignIn = pending
    secrets.save(KiffClient.json.encodeToString(PendingSignIn.serializer(), pending), PENDING_SIGN_IN)
  }

  /** The sign-in under way, while its code hasn't expired: from memory, or from the secrets after the app was restarted. */
  private fun pendingSignIn(): PendingSignIn? {
    val pending = pendingSignIn
      ?: secrets.load(PENDING_SIGN_IN)?.let { runCatching { KiffClient.json.decodeFromString(PendingSignIn.serializer(), it) }.getOrNull() }
    if (pending == null || clock() >= pending.until) {
      if (pending != null) endSignIn()
      return null
    }
    pendingSignIn = pending
    return pending
  }

  /** The sign-in is over, done, refused, expired or cancelled: its code is forgotten. */
  fun endSignIn() {
    pendingSignIn = null
    secrets.delete(PENDING_SIGN_IN)
  }

  /**
   * Asks KIFF with [poll] until the person approves [start]'s code in their browser. It keeps asking through a dropped
   * connection or a KIFF error until the code expires.
   */
  private suspend fun <T> untilApproved(start: DeviceStart, deadline: Instant = clock().plusSeconds(start.expiresIn.toLong()), poll: suspend () -> T): T {
    var intervalMs = maxOf(start.interval, signInMinimumIntervalSeconds) * 1000L
    while (clock() < deadline) {
      var waited = 0L
      while (waited < intervalMs && !signInNudged) {
        val slice = minOf(signInSliceMs, intervalMs - waited)
        delay(slice)
        waited += signInSliceMs
      }
      signInNudged = false
      try {
        return poll()
      } catch (_: KiffError.AuthorizationPending) {
        continue
      } catch (_: KiffError.SlowDown) {
        intervalMs += 5_000
      } catch (e: Exception) {
        if (e is CancellationException || !transient(e)) throw e
      }
    }
    throw KiffError.Expired
  }

  /** A failure worth trying again: the network, or KIFF briefly not answering. */
  private fun transient(e: Throwable): Boolean = e is IOException || (e is KiffError.Server && (e.status >= 500 || e.status == 429))

  /**
   * Starts using a KIFF session: keeps it on this phone, clears what belonged to any earlier one, and
   * loads this one's account. A session from the device-flow sign-in names its account ([tenant]) and
   * holds this phone's signing key.
   */
  suspend fun useSession(token: String, tenant: String? = null) {
    secrets.save(token, TOKEN)
    if (tenant != null) {
      secrets.save(tenant, TENANT)
      secrets.save("1", KEYED)
    } else {
      secrets.delete(TENANT)
      secrets.delete(KEYED)
    }
    secrets.delete(APPROVAL_KEYED)
    forgetApprovalKey()
    kiffTenant = tenant
    kiff.token = token
    cloud.token = token
    kiff.tenant = tenant
    accountGeneration += 1
    // An approval window is the last session's: a new sign-in opens its own.
    kiff.window = null
    windowClosing?.cancel()
    _state.update { it.copy(windowWords = null) }
    eventReceipts.clear()
    forgetPlanqueDevice()
    relinkJob?.cancel()
    decided.clear()
    followers.values.forEach { it.cancel() }
    followers.clear()
    // Nothing of an earlier session or of the sample shows in this one.
    _state.value = AppState(mode = Mode.kiff, ready = true, sessionHasKey = tenant != null, alertsAvailable = push.available, alertSound = secrets.load(ALERT_SOUND) != "0", alertsOffered = alertsOfferedOn(tenant))
    // This sign-in, so a later one that overtook it while it read gets nothing of it: no alert registration, no notice.
    val generation = accountGeneration
    refresh()
    loadAIConsent(ask = true)
    registerApprovalKey()
    if (generation != accountGeneration) return
    // Alerts were on for this account when this phone signed out of it: they come back on with no new confirmation while the
    // account's alerts still reach tafs cloud. Otherwise the phone says they're off, and offers to turn them on.
    val restoring = tenant != null && push.available && !alertsWanted() && secrets.load(ALERTS_WERE_ON) == tenant
    if (restoring) secrets.save("1", ALERTS_ON)
    syncPush()
    if (generation != accountGeneration) return
    if (restoring && !_state.value.alertsReady) {
      secrets.delete(ALERTS_ON)
      _state.update { it.copy(alertsOffSinceSignOut = true) }
    }
    startPurchases()
  }

  /** The "alerts are off" note was read, or acted on. */
  fun dismissAlertsOff() = _state.update { it.copy(alertsOffSinceSignOut = false) }

  /** Whether alerts were offered on this phone for [tenant]'s account already; an account not known yet has had no offer. */
  private fun alertsOfferedOn(tenant: String?): Boolean = tenant != null && secrets.load(ALERTS_OFFERED_PREFIX + tenant) != null

  /** The offer on a new taf's card was answered: it doesn't show again for this account on this phone. Settings keeps the switch. */
  fun notNowAlertsOffer() {
    kiffTenant?.let { secrets.save("1", ALERTS_OFFERED_PREFIX + it) }
    _state.update { it.copy(alertsOffered = true) }
  }

  /** Turn on alerts, from the offer on a new taf's card: as from Settings, and the offer is answered either way. */
  suspend fun turnOnAlertsFromOffer(): Boolean {
    val on = turnOnAlerts()
    notNowAlertsOffer()
    return on
  }

  /** What the four reads of a refresh found. */
  private class Fetched(val home: Home, val exceptions: List<CardException>, val activity: List<ActivityCall>?, val access: Map<String, Access>?)

  /** Runs [block], and answers null if it fails: for what a screen can do without. A cancellation still cancels. */
  private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }

  /**
   * Reads the account again: one list per refresh (#91), made by tafs cloud from its tafs and KIFF's
   * Cards and held calls. The held calls themselves are read from KIFF here: the owner answers what KIFF
   * holds, as KIFF sent it. What the tafs did comes from KIFF's activity; a KIFF that doesn't let the
   * phone read it leaves the Record as it is.
   */
  /**
   * Reads the account again: the list, its holds, its access, then planque, the plan and the rest. It runs to the end whoever
   * asked, so a screen that goes away meanwhile (the sign-in screen once signed in, a screen left as the app comes back) never
   * cancels it: a cancelled refresh used to leave Home on "Loading your tafs…" until a pull. A sign-out or another sign-in on
   * the way is told by the account's generation, as before.
   */
  suspend fun refresh(retry: Boolean = false) = withContext(NonCancellable) { refreshNow(retry) }

  private suspend fun refreshNow(retry: Boolean) {
    if (_state.value.mode != Mode.kiff) return
    // Any refresh but the app's own try again (back in the app, a pull) starts the tries over.
    if (!retry) retries = 0
    val generation = accountGeneration
    var cancelled = false
    try {
      // Which account this is, once a sign-in: it doesn't change under it. Read on its own, so its name
      // shows even when the list can't be read.
      if (_state.value.kiffAccount == null) {
        attempt { kiff.me() }?.let { account ->
          if (generation == accountGeneration && _state.value.kiffAccount == null) showAccount(account)
        }
      }
      // None cancels another, so the order they are awaited in decides which failure is reported: KIFF's
      // first. An ended session then always reads as signed out, never as a list that wouldn't load.
      val read = supervisorScope {
        val home = async { cloud.home() }
        val calls = async { kiff.heldCalls() }
        val activity = async { attempt { kiff.activity() } }
        val access = async { attempt { kiff.myAccess() } }
        val exceptions = calls.await()
        Fetched(home.await(), exceptions, activity.await(), access.await())
      }
      // The account read began under this generation: a sign-out or another sign-in meanwhile moves it
      // on, and the old account's data is dropped here.
      if (generation != accountGeneration) return
      // A held install is Coach's only from Coach's own agent, and a hold from an agent no taf is may be Coach's: read which
      // agent is Coach's when either is held. For a hold no taf is, once a session: an account with no Coach has none to find.
      val unnamed = setupReadIn != accountGeneration && read.exceptions.any { _state.value.holder(it.subject) == null }
      if (_state.value.coachAgent == null && (unnamed || read.exceptions.any { it.action == "version_install" })) {
        setupReadIn = accountGeneration
        attempt { cloud.setup() }?.let { setup ->
          if (generation == accountGeneration) _state.update { it.copy(coachAgent = setup.kiffAgent, coachCloudId = it.coachCloudId ?: setup.id) }
        }
      }
      // KIFF and tafs cloud answered: nothing to say, and no try again waiting.
      retries = 0
      retrying?.cancel()
      retrying = null
      _state.update { it.copy(unreachable = null, retryingRefresh = false) }
      show(read)
      // planque's grants too, after the list: a taf whose connection its vendor signed out waits on the owner there.
      refreshConnections()
      // The plan, for Settings' Plan card and Home's line: tafs cloud's balance, quietly.
      refreshPlan(generation)
      // Did this happen?: the money calls KIFF can't prove, for whoever answers held calls, read quietly.
      if (_state.value.mayAnswerHolds) attempt { kiff.didThisHappen() }?.let { calls ->
        if (generation == accountGeneration) _state.update { it.copy(unsure = calls) }
      }
      // Removals tafs cloud began and didn't finish: archived tafs aren't listed, so the app finishes them.
      if (read.home.removing.isNotEmpty()) finishRemovals(read.home.removing, generation)
      nameRecordLines(generation)
      // An account with no taf: Home lists the recipes, when tafs cloud says so (docs/android-app.md). The list carries no
      // account halt then, so it's read on its own: a halt that outlived every taf still shows on Home, to resume.
      if (_state.value.tafs.isEmpty()) {
        loadRecipes()
        refreshHalt(null)
      }
      // The jobs a taf can take, for + New and the strip under the list, read with each refresh.
      loadGallery()
    } catch (e: CancellationException) {
      cancelled = true
      throw e
    } catch (_: KiffError.Unauthorized) {
      // KIFF ended this session: signed out, with the words to sign in again.
      if (generation == accountGeneration) {
        signOut(tellKiff = false)
        fail(KiffError.Unauthorized.message ?: Words.Error.signedOut)
      }
      return
    } catch (_: CloudError) {
      if (generation != accountGeneration) return
      // A refresh is the app's own doing, so it never shows as "Not done" (kiff/tafs#318).
      refreshFailed(Words.Home.couldntLoadTafs, generation)
      _state.update { if (it.listLoaded) it else it.copy(listUnread = true) }
    } catch (e: Exception) {
      if (generation != accountGeneration) return
      refreshFailed(refreshWords(e), generation)
      _state.update { if (it.listLoaded) it else it.copy(listUnread = true) }
    } finally {
      // Read or not, this account's list is no longer loading (KIFF's error shows).
      if (!cancelled && generation == accountGeneration && _state.value.mode == Mode.kiff) _state.update { it.copy(listLoaded = true) }
      // What Coach waits on after a pull to refresh, while it drives (docs/drive.md).
      if (!cancelled) bot.tafs.app.drive.DriveSignals.emit("refreshed")
    }
  }

  private var retrying: Job? = null
  private var retries = 0

  /** Words true to what failed: the network, or KIFF answering with an error. */
  private fun refreshWords(e: Exception): String = if (e is java.io.IOException) Words.Home.cantReachKiff else Words.Home.couldntLoadFromKiff

  /**
   * A refresh the app made by itself failed (kiff/tafs#318): said quietly under the account's name, never as an alert, and
   * tried again a moment later, then less often, then not until the person comes back or pulls.
   */
  private fun refreshFailed(words: String, generation: Int) {
    if (generation != accountGeneration) return
    retrying?.cancel()
    val wait = REFRESH_RETRY_SECONDS.getOrNull(retries)
    _state.update { it.copy(unreachable = words, retryingRefresh = wait != null) }
    if (wait == null) return
    retries += 1
    retrying = scope.launch {
      delay(wait * 1_000)
      if (accountGeneration == generation && _state.value.unreachable != null) refresh(retry = true)
    }
  }

  /** Shows tafs cloud's list as it is, and KIFF's held calls and activity. */
  private fun show(read: Fetched) {
    val tafs = read.home.tafs.map { KiffMapping.taf(it, ownerName) }
    val others = read.home.others.map { KiffMapping.taf(it, ownerName) }
    val holders = (tafs + others).map { it.id }.toSet()
    // A held install is Coach's only from Coach's own agent, which KIFF holds only under Coach's install Card.
    val coach = _state.value.coachAgent?.takeIf { it.isNotEmpty() }
    // A Card asked to take another currency isn't a held call: tafs cloud draws it once, with its own actions (card_unit_exceptions).
    val held = read.exceptions.filter { it.kind != "card_unit" }.map { KiffMapping.held(it, coach) }
    _state.update { s ->
      s.copy(
        tafs = tafs,
        otherAgents = others,
        held = held,
        cardUnitExceptions = read.home.cardUnitExceptions.orEmpty().filterNot { it.id in s.dismissedCardUnits },
        yourSteps = read.home.yourSteps.orEmpty().filterNot { it.handoff in s.answeredSteps },
        needsYou = read.home.needsYou,
        // What the tafs did, as KIFF records it (kiff/tafs#102), not only the answers given on this device.
        record = read.activity?.let { withDecisions(KiffMapping.record(it, holders)) } ?: s.record,
        // What this person may do with each agent, as KIFF says: a read that failed leaves what was known.
        access = read.access ?: s.access,
        doorsOffered = read.home.doorsOffered.orEmpty(),
        // A refusal for a call that is no longer held is no longer news.
        unanswerable = s.unanswerable.filterKeys { id -> held.any { it.id == id } },
        listLoaded = true,
        listUnread = false,
      )
    }
  }

  /** KIFF's record with the owner's answers KIFF hasn't recorded an outcome for yet, and those it now has settled. */
  private fun withDecisions(kiff: List<RecordEntry>): List<RecordEntry> {
    val (record, settled) = Decisions.merge(kiff, decided)
    settled.forEach { decided.remove(it) }
    return record
  }

  /**
   * Makes sure KIFF holds this phone's approval key for the session (kiff/tafs#233): sent at sign-in,
   * or, for a session from before, registered once, signed by the current key. It counts only once KIFF
   * says so: registered now, or the session has one already (409). A KIFF without the route yet (404),
   * or a failure, leaves the current key signing everything, and the next launch tries again.
   */
  suspend fun registerApprovalKey() {
    val tenant = kiffTenant ?: return
    // Only for a session KIFF knows this phone's signing key for: the request is signed by that key.
    if (_state.value.mode != Mode.kiff || !_state.value.sessionHasKey || sessionHasApprovalKey || _state.value.approvalKeyLost) return
    val key = signer.approvalPublicKeyDER
    if (key.isEmpty()) return
    val generation = accountGeneration
    kiff.tenant = tenant
    val held = try {
      kiff.registerApprovalKey(key) { signer.signConfirmed(it) }
    } catch (e: KiffError.ApprovalKeyExists) {
      e.fingerprint // the key sent at sign-in, or another
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      return
    }
    if (generation != accountGeneration) return
    // KIFF's fingerprint says which key it holds: only ours lets this phone approve.
    if (!KiffClient.sameKey(held, key)) {
      secrets.delete(APPROVAL_KEYED)
      _state.update { it.copy(approvalKeyLost = true) }
      return
    }
    secrets.save(KiffClient.fingerprint(key), APPROVAL_KEYED)
    useApprovalKey()
  }

  /** From now on, what grants authority is signed by the approval key, each request behind its own fingerprint. */
  private fun useApprovalKey() {
    sessionHasApprovalKey = true
    _state.update { it.copy(approvalKeyLost = false) }
    val signer = signer
    kiff.approvalSigner = { built -> signer.signApproval(approvalReason, built) }
  }

  /** What the fingerprint prompt says for a request that grants authority: the step's own words. */
  private var approvalReason = Words.Approval.Prompt.approveOnKiff

  /**
   * Before a step that grants authority, in [reason]'s words. With the approval key in KIFF, each of the step's requests
   * asks for its own fingerprint, so nothing is asked here. A session KIFF holds no approval key for asks once here,
   * with the approval key all the same: what it signs is the owner's presence, never a record (kiff/tafs#233).
   */
  private suspend fun confirmGrant(presence: String, reason: String) {
    approvalReason = reason
    if (!sessionHasApprovalKey) signer.signApproval(presence.toByteArray(), reason)
  }

  /**
   * What a signature that didn't happen says: the phone's own reason where the owner can act on it (no fingerprint,
   * the key turned off), else [otherwise]. A key the phone turned off ends approving until a new sign-in.
   */
  private fun notSigned(e: SignerException, otherwise: String): String = when {
    e is ApprovalKeyRenewed -> {
      lostApprovalKey()
      e.message!!
    }
    e is bot.tafs.app.signing.ConfirmationTimedOut -> e.message!!
    e.message == DeviceSigner.NO_FINGERPRINT -> e.message!!
    e.message == DeviceSigner.NOT_RECOGNIZED -> e.message!!
    else -> otherwise
  }

  private fun forgetApprovalKey() {
    sessionHasApprovalKey = false
    // A phone with no approval key (no fingerprint enrolled) grants nothing with its screen lock: what grants authority
    // stops at the signer, which says to add a fingerprint. One with a key KIFF doesn't hold yet signs as before.
    val signer = signer
    kiff.approvalSigner = if (signer.approvalPublicKeyDER.isEmpty()) { built -> signer.signApproval(approvalReason, built) } else null
  }

  /** KIFF answered that an act needs the approval key this phone doesn't hold: approving waits for a new sign-in, and says so. */
  private fun lostApprovalKey() {
    sessionHasApprovalKey = false
    _state.update { it.copy(approvalKeyLost = true) }
    kiff.approvalSigner = null
    secrets.delete(APPROVAL_KEYED)
  }

  /**
   * The session changed: this phone's id in KIFF is another account's now, or none. planque reads it again before its next
   * signature; kept, it would name the last account's device, and KIFF would answer planque that the device is unknown.
   */
  private fun forgetPlanqueDevice() {
    planque.deviceId = null
    planque.tenant = null
    planque.signer = null
  }

  // MARK: Approve again

  private var relinkJob: Job? = null

  /**
   * Approve again in your browser, where this phone can't approve: its approval key (made anew when the old one was turned off)
   * takes the place of the one KIFF holds once the person approves it in their browser. No sign-out: the session, its alerts and
   * its list stay. Run by the model, as [startApproval]; a tap while one is under way does nothing.
   */
  fun startRelink() {
    if (relinkJob?.isActive == true) return
    relinkJob = scope.launch { relink() }
  }

  /** Stops waiting for the browser. KIFF lets the code expire, and a new Approve again cancels it. */
  fun cancelRelink() {
    relinkJob?.cancel()
    relinkJob = null
    _state.update { it.copy(relink = null, relinkProblem = null) }
  }

  suspend fun relink() {
    if (_state.value.mode != Mode.kiff || !_state.value.approvalKeyLost) return
    val generation = accountGeneration
    _state.update { it.copy(relink = null, relinkProblem = null) }
    try {
      val key = signer.approvalPublicKeyDER
      // Never the screen lock alone: without a fingerprint there's no key to approve with, and no browser opens.
      if (key.isEmpty()) throw SignerException(DeviceSigner.NO_FINGERPRINT)
      val start = try {
        kiff.startRelink(key, signer.publicKeyDER, { built -> signer.signApproval(RELINK_REASON, built) }) { signer.signConfirmed(it) }
      } catch (e: KiffError.Server) {
        // A KIFF without re-linking: Sign in again, as before.
        if (e.status != 404) throw e
        if (generation == accountGeneration) _state.update { it.copy(relinkUnavailable = true) }
        return
      }
      if (generation != accountGeneration) return
      _state.update { it.copy(relink = Relink(start.userCode, start.verificationUriComplete)) }
      val held = untilApproved(start) { kiff.pollRelink(start.deviceCode) }
      if (generation != accountGeneration) return
      // KIFF's fingerprint says which key it holds now; a fingerprint added while the browser waited turned this one off.
      if (!KiffClient.sameKey(held, key) || !signer.approvalKeyUsable()) {
        _state.update { it.copy(relink = null, relinkProblem = RELINK_DIDNT_TAKE) }
        return
      }
      secrets.save(KiffClient.fingerprint(key), APPROVAL_KEYED)
      useApprovalKey()
      _state.update { it.copy(relink = null, relinkProblem = null) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(relink = null, relinkProblem = relinkFailure(e)) }
    }
  }

  /** Why Approve again didn't go through, in words the owner can act on. */
  private fun relinkFailure(e: Exception): String = when {
    e is KiffError.Expired -> Words.SignIn.Relink.codeExpired
    e is KiffError.Server && e.message == "access_denied" -> Words.SignIn.Relink.notApprovedInBrowser
    e is KiffError.Server && e.message == "relink_in_progress" -> Words.SignIn.Relink.finishingLast
    e is ApprovalKeyRenewed -> Words.SignIn.Relink.fingerprintsChanged
    e is SignerException -> e.message ?: RELINK_FAILED
    e is IOException -> Words.SignIn.Relink.noConnection
    else -> RELINK_FAILED
  }

  /**
   * Sign out of KIFF, and Sign in again after this phone lost its approval key: the session ends at KIFF first, and its
   * keys with it, so a sign-in that should be over never stays listed in Devices, able to approve. Only then is the phone
   * signed out; a new sign-in, approved in the browser, registers the phone's keys as a new device.
   *
   * False when KIFF didn't end the session: the phone stays signed in, never showing signed out while KIFF still accepts
   * the session, says so, and keeps trying ([SIGN_OUT_RETRY_SECONDS]) until KIFF ends it.
   */
  suspend fun signOutAtKiff(): Boolean {
    if (_state.value.mode != Mode.kiff) {
      // The sample has nothing at KIFF: it's cleared.
      signOut()
      return true
    }
    signOutRetry?.cancel()
    signOutRetry = null
    _state.update { it.copy(signOutError = null) }
    if (endSessionAtKiff()) {
      signOut(tellKiff = false)
      return true
    }
    _state.update { it.copy(signOutError = NOT_SIGNED_OUT) }
    val generation = accountGeneration
    signOutRetry = (followScope ?: viewModelScope).launch {
      var attempt = 0
      while (generation == accountGeneration) {
        delay(SIGN_OUT_RETRY_SECONDS[minOf(attempt++, SIGN_OUT_RETRY_SECONDS.lastIndex)] * 1000)
        if (generation != accountGeneration) return@launch
        if (endSessionAtKiff()) {
          signOutRetry = null
          signOut(tellKiff = false)
          return@launch
        }
      }
    }
    return false
  }

  /** The sign-out still trying to reach KIFF, if one is. */
  private var signOutRetry: Job? = null

  /**
   * Asks KIFF to end this session. True once it has, or had already; false when it couldn't be told. This phone's alerts
   * stop with it: tafs cloud is told while the session can still say so, just before, and told again at once if KIFF
   * didn't end it, so a phone still signed in never goes without its alerts.
   */
  private suspend fun endSessionAtKiff(): Boolean {
    val token = pushToken?.takeIf { alertsWanted() }
    val unregistered = token != null && runCatching { cloud.unregisterPush(token) }.isSuccess
    val ended = try {
      kiff.signOut()
      true
    } catch (e: CancellationException) {
      throw e
    } catch (_: KiffError.Unauthorized) {
      // KIFF ended it already.
      true
    } catch (_: Exception) {
      false
    }
    if (!ended && unregistered) syncPush()
    return ended
  }

  /** Signs out of KIFF, or clears the sample: nothing of either stays on the phone. */
  suspend fun signOut(tellKiff: Boolean = true) {
    // Signing out ends a drive at once (docs/drive.md, "When a drive ends").
    drive.stop("signed_out")
    // This phone stops getting the account's alerts, while the session can still say so.
    if (tellKiff && _state.value.mode == Mode.kiff) pushToken?.let { token -> if (alertsWanted()) runCatching { cloud.unregisterPush(token) } }
    if (tellKiff && _state.value.mode == Mode.kiff) runCatching { kiff.signOut() }
    // Remembered for this account only: signing in to it again turns this phone's alerts back on, as they were.
    kiffTenant?.let { tenant ->
      if (alertsWanted()) secrets.save(tenant, ALERTS_WERE_ON) else if (secrets.load(ALERTS_WERE_ON) == tenant) secrets.delete(ALERTS_WERE_ON)
    }
    secrets.delete(ALERTS_ON)
    secrets.delete(HANDOFF)
    secrets.delete(TOKEN)
    secrets.delete(TENANT)
    secrets.delete(KEYED)
    secrets.delete(APPROVAL_KEYED)
    forgetApprovalKey()
    kiff.token = null
    // KIFF ends this session's approval window at sign-out.
    kiff.window = null
    windowClosing?.cancel()
    cloud.token = null
    kiff.tenant = null
    kiffTenant = null
    accountGeneration += 1
    eventReceipts.clear()
    forgetPlanqueDevice()
    relinkJob?.cancel()
    decided.clear()
    followers.values.forEach { it.cancel() }
    followers.clear()
    resumedRuns.clear()
    retrying?.cancel()
    retrying = null
    retries = 0
    // A purchase Play reports from now on waits for the next sign-in, and goes to that account.
    purchasesListening?.cancel()
    purchasesListening = null
    purchases.record = null
    purchases.recorded = null
    store?.clear()
    AccountTime.use(null)
    _state.value = AppState(mode = Mode.signedOut, ready = true)
    // Nothing the person attached and opened stays on the phone after them, nor their export of their chats (beside it).
    openedFiles.deleteRecursively()
    openedFiles.parentFile?.let { java.io.File(it, "exports").deleteRecursively() }
  }

  // MARK: Sample mode

  /** The saved sample, else five tafs and a morning of work. Pulling to refresh plays the next round. */
  fun startSample() {
    val saved = store?.load()
    if (saved != null) {
      _state.value = AppState(mode = Mode.sample, ready = true, tafs = saved.tafs, held = saved.held, record = saved.record,
        scriptPosition = saved.scriptPosition, templateOf = saved.templateOf)
      return
    }
    _state.update { it.copy(mode = Mode.sample, ready = true) }
    loadSample()
    save()
  }

  private fun loadSample() {
    if (_state.value.tafs.isNotEmpty()) return
    for (key in listOf("chargebacks", "deploys", "access", "billpay", "refunds")) {
      TafTemplates.template(key)?.let { t -> create(TafDraft(t.name, t.job, t.tools, t.rules, t.key)) }
    }
    // Two rounds: a few actions within the Cards, and two held for the owner.
    playNextDemoActions()
    playNextDemoActions()
    _state.value.let { s -> s.tafs.firstOrNull { s.templateOf[it.id] == "refunds" } }?.let { refunds ->
      val question = Words.Sample.Chat.question
      say(refunds.id, ChatMessage.From.taf, Chat.hello(refunds))
      say(refunds.id, ChatMessage.From.owner, question)
      say(refunds.id, ChatMessage.From.taf, sampleReply(question, refunds.id))
    }
  }

  /** The owner's name on a Card they issue. A new taf starts in Coach, signed in; the sample's tafs are its own. */
  val issuer: String get() = ownerName

  private fun create(draft: TafDraft): Taf {
    val s = _state.value
    val taf = Taf(
      name = draft.name, job = draft.job, tools = draft.tools, colorIndex = s.tafs.size,
      card = Card(rules = draft.rules, issuedBy = ownerName, expires = threeMonthsOn(clock())),
      state = TafState.working, now = Words.Taf.Now.starting,
    )
    _state.update {
      it.copy(
        tafs = listOf(taf) + it.tafs,
        templateOf = if (draft.templateKey != null) it.templateOf + (taf.id to draft.templateKey) else it.templateOf,
        record = listOf(entry(taf.id, RecordEntry.Kind.started, Words.Record.started(taf = taf.name), Words.Record.cardIssuedBy(owner = ownerName))) + it.record,
      )
    }
    save()
    return taf
  }

  // MARK: Pausing and stopping

  /** Pauses a working sample taf, or sets a paused one back to work. */
  fun togglePause(tafId: String) {
    val taf = _state.value.taf(tafId) ?: return
    if (taf.state == TafState.revoked) return
    val paused = taf.state != TafState.paused
    update(taf.copy(state = if (paused) TafState.paused else TafState.working, now = if (paused) Words.Taf.Now.pausedByYou else Words.Taf.Now.backToWork))
    save()
  }

  /**
   * Revokes a taf's Card: no new action goes through, and what waited on the owner is refused. Signed in, KIFF revokes
   * each of its Cards first; the taf shows stopped only once KIFF has.
   */
  fun revoke(tafId: String) {
    if (_state.value.mode == Mode.kiff) viewModelScope.launch { revokeCards(tafId) } else revoked(tafId)
  }

  /** Revokes the taf's Cards in KIFF. False when KIFF didn't, and it says so; nothing is shown stopped then. */
  suspend fun revokeCards(tafId: String): Boolean {
    val taf = _state.value.taf(tafId) ?: return false
    val generation = accountGeneration
    try {
      taf.kiffCardIds.forEach { kiff.revoke(it) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) fail(Words.Card.revokeFailed(why = ErrorWords.of(e)))
      return false
    }
    if (generation != accountGeneration) return false
    revoked(tafId)
    return true
  }

  private fun revoked(tafId: String) {
    val taf = _state.value.taf(tafId) ?: return
    val dropped = _state.value.heldCalls(tafId)
    update(taf.copy(card = taf.card.copy(revoked = true), state = TafState.revoked, now = Words.Taf.Now.cardRevoked))
    _state.update { s -> s.copy(held = s.held.filterNot { it.tafId == tafId }) }
    val detail = if (dropped.isEmpty()) Words.Record.Revoked.noNewAction else Words.Record.Revoked.refusedWaiting(count = dropped.size)
    log(tafId, RecordEntry.Kind.revoked, Words.Record.Revoked.title(taf = taf.name), detail)
    save()
  }

  // MARK: Coach's conversations (kiff/tafs#73, #82, #97, #107)

  /** A new Coach conversation: an empty chat, and the next message starts it. */
  fun newCoachChat() {
    leaveCoachChat()
    _state.update { s ->
      s.copy(
        coachChat = null, coachFresh = true, coachImproving = null, coachContext = null, coachComponents = emptyList(), coachDrives = emptyList(), coachLines = emptyList(),
        chats = s.chats - COACH_CHAT, chatSent = s.chatSent - COACH_CHAT, chatUnsent = s.chatUnsent - COACH_CHAT, chatAwaiting = s.chatAwaiting - COACH_CHAT,
        chatEnded = s.chatEnded - COACH_CHAT, chatLive = s.chatLive - COACH_CHAT, chatWorking = s.chatWorking - COACH_CHAT, chatSteering = s.chatSteering - COACH_CHAT,
        chatErrors = s.chatErrors - COACH_CHAT,
      )
    }
  }

  /**
   * A recipe handed to Coach from Home: a new conversation with the recipe's words in the message box, for the person to edit
   * and send, as a recipe tap in Coach does. Nothing is sent.
   */
  fun handRecipeToCoach(recipe: bot.tafs.app.kiff.Recipe) {
    if (_state.value.mode != Mode.kiff || !_state.value.mayUseCoach) return
    newCoachChat()
    setDraft(COACH_CHAT, recipe.say)
    _state.update { it.copy(coachRequested = true) }
  }

  /** "Say it in your own words": a new Coach conversation, empty, to build a taf with the person. */
  fun openCoachEmpty() {
    if (_state.value.mode != Mode.kiff || !_state.value.mayUseCoach) return
    newCoachChat()
    _state.update { it.copy(coachRequested = true) }
  }

  /** The conversation on screen is left: this phone stops following it, and work it accepted goes on at tafs cloud. */
  private fun leaveCoachChat() {
    coachGeneration++
    followers.remove(COACH_CHAT)?.cancel()
    // A send to the conversation left may still be on its way: the new one can be written in at once.
    _state.update { it.copy(chatSending = it.chatSending - COACH_CHAT) }
  }

  /** A new Coach conversation on one taf, to improve it: its first message goes to the improve route. */
  fun improveTaf(tafId: String) {
    newCoachChat()
    _state.update { it.copy(coachContext = tafId) }
    if (_state.value.taf(tafId)?.cloudId != null) _state.update { it.copy(coachImproving = tafId) }
  }

  /**
   * Opens Coach from the bar. On a taf's page it's that taf's conversation: the one already open about it, else a new one.
   * Elsewhere, the conversation open as it was.
   */
  fun openCoachOn(tafId: String?) {
    if (tafId != null && _state.value.coachContext != tafId) improveTaf(tafId)
  }

  /** Improves a taf from wherever its hold is ("Change the limit in Coach mode"): Coach opens on a new conversation about it. */
  fun improveInCoach(tafId: String) {
    improveTaf(tafId)
    _state.update { it.copy(coachRequested = true) }
  }

  /**
   * A delivery still not made, asked about in Coach: a new conversation about the taf, with tafs cloud's words for it in the
   * message box, for the owner to edit and send. Nothing is sent.
   */
  fun askCoachAbout(tafId: String, words: String) {
    improveInCoach(tafId)
    setDraft(COACH_CHAT, words)
  }

  /** The tab screen opened Coach. */
  fun coachOpened() = _state.update { it.copy(coachRequested = false) }

  /** Reopens one of Coach's conversations: it's shown alone, and messages go on in it. */
  suspend fun openCoachChat(session: String) {
    leaveCoachChat()
    _state.update { s ->
      s.copy(
        coachChat = session, coachFresh = false, coachImproving = null, coachContext = null,
        chatSent = s.chatSent - COACH_CHAT, chatUnsent = s.chatUnsent - COACH_CHAT, chatEnded = s.chatEnded - COACH_CHAT, chatErrors = s.chatErrors - COACH_CHAT,
      )
    }
    readThread(COACH_CHAT)
  }

  /** The conversation a message went into is the one shown from then on, once a new one is started. */
  private fun settleCoachChat(session: String) {
    _state.update { if (it.coachFresh) it.copy(coachChat = session, coachFresh = false, coachImproving = null) else it }
  }

  /** Reads Coach's past conversations, for the list to reopen. */
  suspend fun loadCoachChats() {
    val cloudId = _state.value.coachCloudId ?: return
    val generation = accountGeneration
    val chats = attempt { cloud.chats(cloudId) } ?: return
    if (generation == accountGeneration) _state.update { it.copy(coachChats = chats) }
  }

  /** Reads the conversations the owner archived, under Archived. */
  suspend fun loadArchivedCoachChats() {
    val cloudId = _state.value.coachCloudId ?: return
    val generation = accountGeneration
    val chats = attempt { cloud.archivedChats(cloudId) } ?: return
    if (generation == accountGeneration) _state.update { it.copy(coachArchived = chats) }
  }

  /**
   * Archives one of Coach's conversations: it leaves the list and the thread, on every device. The one shown gives way to a
   * new conversation. False, saying why, when tafs cloud didn't take it.
   */
  suspend fun archiveCoachChat(session: String): Boolean {
    val cloudId = _state.value.coachCloudId ?: return false
    val generation = accountGeneration
    try {
      cloud.archiveChat(cloudId, session)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) fail(Words.Taf.notArchived(why = ErrorWords.of(e)))
      return false
    }
    if (generation != accountGeneration) return false
    val shown = _state.value.coachChat == session
    _state.update { s -> s.copy(coachChats = s.coachChats.filterNot { it.session == session }) }
    if (shown) newCoachChat()
    return true
  }

  /** Puts an archived conversation back in the list. False, saying why, when tafs cloud didn't take it. */
  suspend fun unarchiveCoachChat(session: String): Boolean {
    val cloudId = _state.value.coachCloudId ?: return false
    val generation = accountGeneration
    try {
      cloud.unarchiveChat(cloudId, session)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) fail(Words.Taf.notPutBack(why = ErrorWords.of(e)))
      return false
    }
    if (generation != accountGeneration) return false
    _state.update { s -> s.copy(coachArchived = s.coachArchived.filterNot { it.session == session }) }
    loadCoachChats()
    return true
  }

  // MARK: Runs, pausing and removing a taf on tafs cloud

  /**
   * Runs a taf now, outside its schedule. The run's session; null when it didn't start, when a Run now for it is on its way,
   * or when its check found nothing new, which is no failure: no run, nothing said wrong.
   */
  suspend fun runNow(tafId: String): String? {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return null
    if (tafId in _state.value.runsInFlight) return null
    _state.update { it.copy(runsInFlight = it.runsInFlight + tafId, runNowQuiet = it.runNowQuiet - tafId) }
    val generation = accountGeneration
    try {
      val run = cloud.runNow(cloudId)
      if (generation != accountGeneration) return run.session
      run.words?.let { words -> _state.update { it.copy(runNowQuiet = it.runNowQuiet + (tafId to words)) } }
      loadReport(tafId)
      return run.session
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) fail(creditsRefusal(e) ?: Words.Taf.runNotStarted(why = ErrorWords.of(e)))
      return null
    } finally {
      _state.update { it.copy(runsInFlight = it.runsInFlight - tafId) }
    }
  }

  // MARK: A Card asked to take another currency (KIFF's card_unit exception)

  /**
   * Talks it over in Coach: a new conversation on the taf, with tafs cloud's proposal written in, not sent. It grants nothing;
   * only a changed Card, made and signed as any other, lets those calls through.
   */
  fun discussCardUnit(ask: bot.tafs.app.kiff.CardUnitException) {
    val taf = _state.value.tafs.firstOrNull { it.cloudId == ask.taf } ?: return
    if (_state.value.mode != Mode.kiff || !_state.value.mayUseCoach || !_state.value.manages(taf.id)) return
    improveTaf(taf.id)
    setDraft(COACH_CHAT, ask.coachText)
    _state.update { it.copy(coachRequested = true) }
  }

  /**
   * Dismisses it: KIFF's exception is read again, and only while it's still this taf's open currency request is its ordinary
   * refusal signed, against the version it has now.
   */
  suspend fun dismissCardUnit(ask: bot.tafs.app.kiff.CardUnitException) {
    val tenant = kiffTenant
    val taf = _state.value.tafs.firstOrNull { it.cloudId == ask.taf }
    if (_state.value.mode != Mode.kiff || tenant == null || taf == null || ask.id in _state.value.dismissingCardUnits) return
    val generation = accountGeneration
    _state.update { it.copy(dismissingCardUnits = it.dismissingCardUnits + ask.id) }
    try {
      val current = kiff.cardException(ask.id)
      if (generation != accountGeneration) return
      val version = current.accountingVersion
      if (current.kind != "card_unit" || current.status != "held" || _state.value.holder(current.subject)?.id != taf.id || version == null) {
        fail(Words.NeedsYou.CardUnit.changed)
        return
      }
      var signedAt = ""
      val signature = signer.sign(Words.NeedsYou.CardUnit.dismissPrompt(title = ask.title)) {
        signedAt = DateTimeFormatter.ISO_INSTANT.format(clock().truncatedTo(ChronoUnit.SECONDS))
        KiffClient.answerMessage(tenant, current.id, Answer.Refuse.kiffName, null, null, version, signedAt)
      }
      if (generation != accountGeneration) return
      kiff.reject(current.id, null, KiffClient.AnswerSignature(signedAt, signature))
      if (generation != accountGeneration) return
      // Gone at once wherever it shows, Activity's cached pages included, and kept out of any read already on its way.
      _state.update { st ->
        st.copy(
          dismissedCardUnits = st.dismissedCardUnits + current.id,
          cardUnitExceptions = st.cardUnitExceptions.filterNot { it.id == current.id },
          activityPages = st.activityPages.mapValues { (_, c) -> c.copy(pages = c.pages.map { p -> p.copy(cardUnitExceptions = p.cardUnitExceptions.filterNot { it.id == current.id }) }) },
          tafSetups = st.tafSetups.mapValues { (_, v) -> v.copy(cardUnitExceptions = v.cardUnitExceptions.filterNot { it.id == current.id }) },
        )
      }
      refresh()
      loadTafSetup(taf.id)
    } catch (e: SignerException) {
      if (generation == accountGeneration) fail(notSigned(e, Words.NeedsYou.CardUnit.notDismissedUnconfirmed))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) fail(Words.NeedsYou.CardUnit.notDismissed(why = AnswerRefusals.answerRefusedWords(e)))
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(dismissingCardUnits = it.dismissingCardUnits - ask.id) }
    }
  }

  /**
   * Answers a step a taf handed its owner: [done] says the owner did it, else they skip it. The phone reads the hand-off from KIFF
   * for its version, signs KIFF's `kiff.handoff.v1` with its signing key behind the screen lock, and sends it to KIFF, which tells
   * the taf's run. Answering grants nothing: the step stays one the taf may never take. The app decides nothing about the step.
   */
  suspend fun answerYourStep(step: bot.tafs.app.kiff.YourStep, done: Boolean) {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || tenant == null || step.handoff in _state.value.answeringSteps) return
    val generation = accountGeneration
    val answer = if (done) "done" else "skipped"
    _state.update { it.copy(answeringSteps = it.answeringSteps + step.handoff) }
    try {
      val current = kiff.handoff(step.handoff)
      if (generation != accountGeneration) return
      if (current.status.isNotEmpty() && current.status != "waiting") {
        fail(Words.NeedsYou.YourStep.ended)
        drop(step.handoff)
        return
      }
      var signedAt = ""
      // The prompt is tafs cloud's words for the answer and the step: "Done · Approve the transfer to Acme in Qonto".
      val prompt = listOf(if (done) step.words.done else step.words.skip, step.what).filter { it.isNotBlank() }.joinToString(" · ")
      val signature = signer.sign(prompt) {
        signedAt = DateTimeFormatter.ISO_INSTANT.format(clock().truncatedTo(ChronoUnit.SECONDS))
        KiffClient.handoffMessage(tenant, current.id, answer, current.version, signedAt)
      }
      if (generation != accountGeneration) return
      kiff.answerHandoff(current.id, answer, KiffClient.AnswerSignature(signedAt, signature))
      if (generation != accountGeneration) return
      drop(current.id)
      refresh()
    } catch (e: SignerException) {
      if (generation == accountGeneration) fail(notSigned(e, Words.NeedsYou.YourStep.notAnsweredUnconfirmed))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) fail(Words.NeedsYou.YourStep.notAnswered(why = AnswerRefusals.answerRefusedWords(e)))
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(answeringSteps = it.answeringSteps - step.handoff) }
    }
  }

  /** Takes an answered or ended step off Needs you at once, and out of a Home read already on its way. */
  private fun drop(handoff: String) {
    _state.update { it.copy(answeredSteps = it.answeredSteps + handoff, yourSteps = it.yourSteps.filterNot { s -> s.handoff == handoff }) }
  }

  /** Reads the taf's latest run and, once it has stopped, its report. */
  /** Keeps what the owner wrote in a chat and hasn't sent; an empty one is dropped. */
  fun setDraft(tafId: String, text: String) {
    _state.update { it.copy(drafts = if (text.isEmpty()) it.drafts - tafId else it.drafts + (tafId to text)) }
  }

  /** Reads where the taf's work stands, from tafs cloud (docs/reliability.md): its requests and skipped firings, in its words. */
  suspend fun loadWork(tafId: String) {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    if (_state.value.mode != Mode.kiff) return
    val generation = shownGeneration()
    val work = try {
      cloud.work(cloudId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == shownGeneration()) _state.update { it.copy(workErrors = it.workErrors + (tafId to Words.Work.notRead(why = ErrorWords.of(e)))) }
      return
    }
    if (generation == shownGeneration()) _state.update { it.copy(works = it.works + (tafId to work), workErrors = it.workErrors - tafId) }
  }

  /**
   * Send again: a delivery given up on goes once more, by its place (`POST …/deliveries/{place}/send`): the door reply, or a place
   * of the taf's own by its key, which tafs cloud retries as the taf's task. It signs nothing. The work is read again after.
   */
  suspend fun sendAgain(tafId: String, run: String, place: String = "door_reply") {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    if (run in _state.value.sendingAgain) return
    _state.update { it.copy(sendingAgain = it.sendingAgain + run, workErrors = it.workErrors - tafId) }
    val generation = accountGeneration
    val failed = try {
      cloud.sendAgain(cloudId, run, place)
      null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Words.Work.notSentAgain(why = ErrorWords.of(e))
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(sendingAgain = it.sendingAgain - run) }
    }
    if (generation != accountGeneration) return
    loadWork(tafId)
    // Said after the work is read again, so the read doesn't clear it.
    failed?.let { why -> _state.update { it.copy(workErrors = it.workErrors + (tafId to why)) } }
  }

  suspend fun loadReport(tafId: String) {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    val generation = accountGeneration
    val report = try {
      val runs = cloud.runs(cloudId)
      val latest = runs.firstOrNull { it.session != null }
      val session = latest?.session
      if (session == null) {
        RunReport(at = runs.firstOrNull()?.at, error = runs.firstOrNull()?.error)
      } else {
        val r = cloud.report(cloudId, session)
        RunReport(session = session, at = latest.at, status = r.status, text = r.text)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      (_state.value.reports[tafId] ?: RunReport()).copy(error = Words.Report.runsNotLoaded(why = ErrorWords.of(e)))
    }
    if (generation == accountGeneration) _state.update { it.copy(reports = it.reports + (tafId to report)) }
  }

  /** Pauses or resumes its schedule on tafs cloud: no run starts while it's paused. Its Cards aren't touched; revoke one to stop its authority. */
  suspend fun setPaused(tafId: String, paused: Boolean) {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    val generation = accountGeneration
    val schedule = try {
      cloud.setPaused(cloudId, paused)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // A refusal tafs cloud words itself (a taf that works from its chat has no schedule to pause) is said as it is; a 502 is a
      // pause that reached some of its starts, not all.
      val cloud = e as? bot.tafs.app.kiff.CloudError
      val said = cloud?.takeIf { it.status == 409 && it.words == null }?.message
        ?: cloud?.takeIf { it.status == 502 && paused }?.let { bot.tafs.app.model.SetupWords.NOT_EVERY_PAUSED }
      if (generation == accountGeneration) fail(said ?: if (paused) Words.Taf.Schedule.notPaused(why = ErrorWords.of(e)) else Words.Taf.Schedule.notResumed(why = ErrorWords.of(e)))
      return
    }
    if (generation != accountGeneration) return
    val taf = _state.value.taf(tafId) ?: return
    // The schedule's answer carries no words for the new time: the app's own format stands in until Home is read again.
    var updated = taf.copy(paused = schedule.paused, nextRun = schedule.nextRun, nextRunWords = null)
    if (taf.state != TafState.waiting && taf.state != TafState.revoked && taf.state != TafState.halted) {
      updated = updated.copy(
        state = if (schedule.paused) TafState.paused else TafState.working,
        now = if (schedule.paused) Words.Taf.Now.pausedByYou else schedule.nextRun?.let { Words.Taf.Now.nextRun(time = shortTime(it)) } ?: Words.Taf.Now.backToWork,
      )
    }
    update(updated)
  }

  /**
   * Removes a taf for good, after one confirmation: KIFF revokes its Cards first, so it can't act whatever happens next;
   * then tafs cloud stops it, deletes its runs, chats, memory and key, and frees its name. What to tell the owner; null
   * when nothing was done.
   */
  /**
   * tafs cloud's words for what removing [tafId] takes with it ("Qonto goes with it. Stripe stays: Chargebacks uses it."), for
   * the confirmation before the fingerprint. Null when it sends none, or a tafs cloud from before the route: then the
   * confirmation says only that the taf goes.
   */
  suspend fun removalWords(tafId: String): String? {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return null
    if (_state.value.mode != Mode.kiff) return null
    return try {
      cloud.removal(cloudId).words?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }

  suspend fun removeTaf(tafId: String): String? {
    val s = _state.value
    val taf = s.taf(tafId)
    val cloudId = taf?.cloudId
    if (s.mode != Mode.kiff || taf == null || cloudId == null) return null
    val tenant = kiffTenant ?: return null
    val generation = accountGeneration
    // The fingerprint first, every time, before anything is undone: the approval key signs the DELETE itself, and tafs cloud asks
    // KIFF whether this phone signed it. No fingerprint, no removal, even on a debug phone with no screen lock.
    val signature = try {
      cloud.removalSignature(cloudId, tenant) { built -> signer.signCritical(Words.Taf.Remove.prompt(taf = taf.name)) { built() } }
    } catch (e: SignerException) {
      // A prompt closed or a finger not recognized does nothing; anything else (no fingerprint set up) says why.
      return e.message?.takeIf { it != bot.tafs.app.signing.DeviceSigner.NOT_RECOGNIZED }
    }
    if (generation != accountGeneration) return null
    var cardsLeft = 0
    if (!taf.card.revoked) {
      for (cardId in taf.kiffCardIds) {
        if (generation != accountGeneration) return null
        try {
          kiff.revoke(cardId)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          cardsLeft += 1
        }
      }
    }
    if (generation != accountGeneration) return null
    // Its connections go with it, ended by tafs cloud as part of the removal (every status, and one another taf uses stays):
    // the phone no longer disconnects them itself, which signed one more request each and missed waiting and failed ones.
    val cards = if (cardsLeft == 0) Words.Taf.Remove.cardsRevoked else Words.Taf.Remove.cardsLeft(count = cardsLeft)
    val removal = try {
      cloud.remove(cloudId, signature)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation != accountGeneration) return null
      return "$cards ${ErrorWords.of(e)} ${Words.Taf.Remove.finishesOnRefresh}"
    }
    if (generation != accountGeneration) return null
    if (cardsLeft == 0) _state.value.taf(tafId)?.let { update(it.copy(card = it.card.copy(revoked = true), state = TafState.revoked, now = Words.Taf.Now.removed)) }
    // What tafs cloud did with its connections: ended (said once), or not (named among the steps left).
    val apps = if ("connections" in removal.removed) " " + Words.Taf.Remove.connectionsDisconnected else ""
    if (!removal.done) {
      return "$cards$apps " + Words.Taf.Remove.notFinished(steps = removal.failed.joinToString(", ") { removalStep(it) })
    }
    log(tafId, RecordEntry.Kind.revoked, Words.Record.Removed.title(taf = taf.name), Words.Record.Removed.detail)
    refresh()
    refreshConnections()
    return "$cards$apps " + Words.Taf.Remove.done(taf = taf.name)
  }

  /**
   * What each Record line acted on (kiff/tafs#167): the call references of each taf with a line KIFF hashed a resource
   * for, read from tafs cloud. Best effort: a taf whose calls can't be read keeps its lines without the payment.
   */
  private suspend fun nameRecordLines(generation: Int) {
    val tafIds = _state.value.record.filter { it.onHash != null && it.onValue == null }.map { it.tafId }.toSet()
    val cloudIds = tafIds.mapNotNull { _state.value.taf(it)?.cloudId }
    if (cloudIds.isEmpty()) return
    val refs = mutableMapOf<String, String>()
    for (id in cloudIds) {
      val list = attempt { cloud.calls(id) } ?: continue
      if (generation != accountGeneration) return
      list.forEach { refs[it.invocation] = it.value }
    }
    if (generation != accountGeneration || refs.isEmpty()) return
    _state.update { it.copy(record = KiffMapping.naming(it.record, refs)) }
  }

  /** KIFF's receipt for a Record line, as KIFF wrote it, for the phone to check. */
  suspend fun receipt(traceId: String): ByteArray = kiff.receipt(traceId)

  /**
   * Finishes removals tafs cloud lists as begun. They were confirmed, signed, when they began; finishing only deletes what's left of a
   * taf already stopped, which tafs cloud takes unsigned only for a taf it lists as `removing`.
   */
  private suspend fun finishRemovals(removing: List<bot.tafs.app.kiff.PendingRemoval>, generation: Int) {
    for (r in removing) {
      if (generation != accountGeneration) return
      attempt { cloud.remove(r.id, signature = null) }
    }
  }

  private fun removalStep(step: String): String = when (step) {
    "history" -> Words.Taf.Remove.stepHistory
    "connections" -> Words.Taf.Remove.stepConnections
    else -> step
  }

  // MARK: Chat

  /**
   * Opens a taf's thread: in the sample it starts with the taf's hello; signed in, it is read from tafs cloud, and
   * followed while a run is at work in it.
   */
  suspend fun loadChat(tafId: String) {
    val taf = _state.value.taf(tafId) ?: return
    if (_state.value.mode == Mode.kiff) {
      // A run at work is followed on its stream (readThread picks it up), and what changes otherwise comes as a live update:
      // only a trial's chat, which has no stream, is read again on a timer.
      readThread(tafId)
      if (isTrialChat(tafId)) followChat(tafId)
      return
    }
    if (_state.value.chats.containsKey(tafId)) return
    say(tafId, ChatMessage.From.taf, Chat.hello(taf))
  }

  /** The threads being followed, by taf: one at a time each. */
  private val followers = mutableMapOf<String, kotlinx.coroutines.Job>()

  private val scope: CoroutineScope get() = followScope ?: viewModelScope

  /**
   * Reads the thread again every few seconds while a run is at work in it, or the taf has been written to and
   * hasn't answered: its words and steps arrive as it works. It stops once the taf is idle.
   */
  fun followChat(tafId: String, handOver: String? = null) {
    if (followers[tafId]?.isActive == true) return
    followers[tafId] = scope.launch {
      var polls = 0
      while (polls < CHAT_POLL_BUDGET && (busy(tafId) || (handOver != null && handingOver(handOver)))) {
        delay(CHAT_POLL_MS)
        polls += 1
        readThread(tafId)
      }
      // The run stopped: done, or waiting on a call KIFF held. KIFF is read again, so a hold's card shows in the chat and in
      // Needs you, and the Card's amounts are today's. (Until the chat follows its run's stream, as iOS's does.)
      if (polls > 0) refresh()
    }
  }

  private fun busy(tafId: String): Boolean = _state.value.let { tafId in it.chatWorking || tafId in it.chatAwaiting }

  /** Coach's run paused on a result tafs cloud hasn't answered yet: it goes on once tafs cloud has made the taf or version. */
  private fun handingOver(run: String): Boolean =
    _state.value.coachComponents.any { it.run == run && it.component.type == "result" && it.answer == null }

  /** The keys of the `not_sent` messages Retry or Edit took (their hashes, never their words), kept across launches. */
  private var notSentTaken: MutableSet<String>? = null

  private fun settledNotSent(): Set<String> =
    notSentTaken ?: (secrets.load(NOT_SENT_TAKEN)?.split('\n')?.filter { it.isNotBlank() }.orEmpty().toMutableSet()).also { notSentTaken = it }

  private fun rememberNotSent(keys: List<String>) {
    val shown = (settledNotSent() as MutableSet<String>).apply { addAll(keys) }
    // The latest ones only: an older run is no longer a chat's latest, so its `not_sent` doesn't come back.
    secrets.save(shown.toList().takeLast(NOT_SENT_KEPT).joinToString("\n"), NOT_SENT_TAKEN)
  }

  /** One `not_sent` message, by its run, time and words, hashed. */
  private fun notSentKey(run: String?, words: bot.tafs.app.kiff.SteerWords): String =
    java.security.MessageDigest.getInstance("SHA-256").digest("${run.orEmpty()}|${words.at}|${words.text}".toByteArray())
      .joinToString("") { "%02x".format(it) }

  /** Reads a taf's thread from tafs cloud and shows it; what the owner sent that it doesn't have yet stays, dimmed. */
  /**
   * [alongside] changes the state in the same update that brings the thread: the live answer leaving as its words land in the thread,
   * so the chat's end never grows by a row and shrinks again, which moved the thread about while Coach wrote.
   */
  private suspend fun readThread(tafId: String, alongside: ((AppState) -> AppState)? = null) {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    // A new Coach conversation has nothing to read until its first message is sent.
    if (tafId == COACH_CHAT && _state.value.coachFresh) return
    val generation = accountGeneration
    // A Coach conversation left for another (another taf, a new one) since: what it read is no longer the one on screen.
    val coach = coachGeneration
    fun current() = generation == accountGeneration && (tafId != COACH_CHAT || coach == coachGeneration)
    // No conversation chosen yet (at launch): Coach opens on its newest alone, the earlier ones under Previous conversations,
    // never several together. Without the list, or with none yet, the thread reads as before.
    if (tafId == COACH_CHAT && _state.value.coachChat == null) {
      val chats = attempt { cloud.chats(cloudId) }
      if (!current()) return
      chats?.let { list -> _state.update { it.copy(coachChats = list) } }
      chats?.maxByOrNull { it.updated }?.let { newest -> _state.update { if (it.coachChat == null && !it.coachFresh) it.copy(coachChat = newest.session) else it } }
    }
    val thread = try {
      if (isTrialChat(tafId)) cloud.trialConversation(cloudId)
      else cloud.conversation(cloudId, session = if (tafId == COACH_CHAT) _state.value.coachChat else null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (current()) _state.update { it.copy(chatErrors = it.chatErrors + (tafId to Words.Chat.threadNotLoaded(why = ErrorWords.of(e)))) }
      return
    }
    if (!current() || _state.value.mode != Mode.kiff) return
    val now = clock()
    // What a stopped run never read (docs/chat.md): each becomes its own Not sent row once, in tafs cloud's words. It's remembered when
    // Retry or Edit takes it, never before, so a restart brings it back until then, and a second Retry finds nothing, as on iOS.
    val notSent = thread.steering?.let { st -> st.notSent.map { it to notSentKey(st.run, it) } }.orEmpty().filter { (_, key) -> key !in settledNotSent() }
    _state.update { read ->
      val s = alongside?.invoke(read) ?: read
      // What the owner sent stays on screen until the thread has it (kiff/tafs#138, 48).
      val (messages, waiting) = SentMessages.merge(thread.messages, s.chatSent[tafId].orEmpty(), now)
      // The wait ends once tafs cloud says the taf is at work, once it answered after the owner wrote, or after
      // two minutes with no sign.
      val since = s.chatAwaiting[tafId]
      val answered = since != null && thread.messages.any { it.from == ChatMessage.From.taf && it.at >= since }
      val settled = since != null && (thread.working != null || answered || java.time.Duration.between(since, now) > java.time.Duration.ofSeconds(120))
      // Coach handed the taf over and said its last words: its run is done, whatever the stream still holds open. Stop goes
      // (kiff/tafs#476); tafs cloud's `working` still says if a run is at work.
      val handedOver = tafId == COACH_CHAT && thread.working == null && waiting.isEmpty() && tafId !in s.chatSending &&
        CoachWords.handedOver(thread.messages, thread.components, s.chatLive[tafId]?.session)
      s.copy(
        chatLive = if (handedOver) s.chatLive - tafId else s.chatLive,
        chats = s.chats + (tafId to messages),
        chatSent = if (waiting.isEmpty()) s.chatSent - tafId else s.chatSent + (tafId to waiting),
        chatErrors = s.chatErrors - tafId,
        chatWorking = thread.working?.let { s.chatWorking + (tafId to ChatRun(it, thread.workingRun)) } ?: (s.chatWorking - tafId),
        chatAwaiting = if (settled || handedOver) s.chatAwaiting - tafId else s.chatAwaiting,
        chatSteering = thread.steering?.takeIf { it.held != null }?.let { s.chatSteering + (tafId to it) } ?: (s.chatSteering - tafId),
        chatUnsent = s.chatUnsent[tafId].orEmpty().let { kept ->
          // One already on screen isn't added again.
          val fresh = notSent.filter { (_, key) -> kept.none { it.notSentKey == key } }
          if (fresh.isEmpty()) s.chatUnsent else s.chatUnsent + (tafId to kept + fresh.map { (words, key) ->
            val at = runCatching { java.time.Instant.parse(words.at) }.getOrNull() ?: now
            Unsent(ChatMessage(at, ChatMessage.From.owner, words.text, Unsent.SESSION), words.why.orEmpty(), notSentKey = key)
          })
        },
        coachComponents = if (tafId == COACH_CHAT) thread.components else s.coachComponents,
        coachDrives = if (tafId == COACH_CHAT) thread.drives else s.coachDrives,
        tafCards = if (tafId == COACH_CHAT) s.tafCards else s.tafCards + (tafId to thread.components.filter { isTafCard(it.component) }),
        // A taken card is the thread's once it holds its answer.
        cardsTaken = s.cardsTaken - thread.components.filter { it.answer != null }.map { it.ref }.toSet(),
        coachLines = if (tafId == COACH_CHAT) thread.coachLines else s.coachLines,
      )
    }
    if (tafId == COACH_CHAT) {
      readApprovals()
      followHandOvers()
    }
    // A run at work that this phone isn't following: its stream is picked back up, once.
    thread.workingRun?.let { run -> resumeChat(tafId, cloudId, run) }
  }

  // MARK: Alerts

  /**
   * A KIFF alert target whose secret tafs cloud hasn't confirmed yet. Registering a target replaces the account's one before,
   * and KIFF shows the new secret only once: until tafs cloud has it, KIFF's alerts fail its check. So the secret is kept here,
   * on the phone, and handed over again until tafs cloud takes it.
   */
  @Serializable
  private data class AlertHandoff(val tenant: String, val target: AlertTarget)

  /** This phone's push token, once Firebase gave it. */
  private var pushToken: String? = null

  private fun pendingHandoff(): AlertHandoff? {
    val json = secrets.load(HANDOFF) ?: return null
    val handoff = runCatching { KiffClient.json.decodeFromString<AlertHandoff>(json) }.getOrNull() ?: return null
    return handoff.takeIf { it.tenant == kiffTenant }
  }

  private fun keepHandoff(handoff: AlertHandoff?) {
    if (handoff == null) secrets.delete(HANDOFF) else secrets.save(KiffClient.json.encodeToString(AlertHandoff.serializer(), handoff), HANDOFF)
  }

  /** Alerts were turned on on this phone: only then is it registered at launch, so a phone that never asked is never alerted. */
  private fun alertsWanted(): Boolean = secrets.load(ALERTS_ON) == "1"

  private fun applyPush(status: PushStatus) {
    _state.update { it.copy(canAnswerHolds = status.answers, alertsReady = status.alerts && pendingHandoff() == null) }
  }

  /** Firebase gave this phone a push token, at launch or when it changes: tafs cloud is told it again. */
  fun tokenArrived(token: String) {
    pushToken = token
    viewModelScope.launch { syncPush() }
  }

  /**
   * tafs cloud said something changed on the account (docs/approvals.md, "Live updates"): what it names is read again, with this
   * phone's own session, and nothing is taken from the push itself. `taf`: the list, and that taf's thread where it's been read;
   * `halted` and `resumed`: the list; `knock`: that taf's doors and thread. [cloudId] is the taf's id in tafs cloud.
   */
  fun changed(change: String, cloudId: String?) {
    viewModelScope.launch { readChange(change, cloudId) }
  }

  /**
   * Signs in again to a connection its vendor signed out, from the taf's page: the same approval as Coach's card, behind one
   * fingerprint, then the vendor's own page. Signed in only once planque answers connected.
   */
  suspend fun reconnect(grant: Grant) {
    // A provider's connection is disconnected and connected again instead: planque would refuse, so nothing is asked.
    if (grant.signsInThroughProvider) return note(grant.id, bot.tafs.app.model.ConnectionWords.throughProvider(grant))
    // KIFF's words for its profile, which the approval signs: read now, as the approval card reads them, wherever this starts.
    loadProfileWords(grant.id)
    _state.value.profileWordsErrors[grant.id]?.let { return note(grant.id, it) }
    val result = connect(grant.id, grant.id, grant.digest, again = true)
    if (result is Connected.Failed) note(grant.id, result.why)
  }

  /** Reads again what a live update names (see [changed]). */
  suspend fun readChange(change: String, cloudId: String?) {
    if (_state.value.mode != Mode.kiff) return
    val tafId = cloudId?.let { id -> _state.value.tafs.firstOrNull { it.cloudId == id }?.id }
    when (change) {
      "taf", "halted", "resumed" -> refresh()
      "knock" -> {
        tafId?.let { loadDoors(it) }
        // Every push reads planque's grants too (a refresh does): tafs cloud never sees them, so a connection its vendor signed
        // out shows after the next push, whatever it named.
        refreshConnections()
      }
    }
    if ((change == "taf" || change == "knock") && tafId != null && tafId in _state.value.chats) readThread(tafId)
    // Its work too, where its page has read it.
    if (change == "taf" && tafId != null && tafId in _state.value.works) loadWork(tafId)
  }

  /** Where a tapped alert goes: the screen takes it once. */
  fun requestOpen(destination: String?) {
    if (destination != null) _state.update { it.copy(requestedOpen = destination) }
  }

  fun openRequestTaken() = _state.update { it.copy(requestedOpen = null) }

  private fun savedAlertKinds(): Map<String, Boolean>? = secrets.load("$ALERT_KINDS|$kiffTenant")?.let { text ->
    runCatching { KiffClient.json.decodeFromString<Map<String, Boolean>>(text) }.getOrNull()
  }

  suspend fun loadPhoneAlerts() {
    if (_state.value.mode != Mode.kiff) return
    val gen = accountGeneration
    try {
      val token = pushToken ?: (if (push.available) push.token() else null).orEmpty()
      val preferences = cloud.phoneAlerts(token)
      if (gen == accountGeneration) _state.update { it.copy(phoneAlerts = preferences, alertsReady = preferences.enabled, phoneAlertsError = null) }
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) { if (gen == accountGeneration) _state.update { it.copy(phoneAlertsError = ErrorWords.of(e)) } }
  }

  suspend fun turnOffAlerts() {
    if (_state.value.mode != Mode.kiff || _state.value.phoneAlertsBusy) return
    val gen = accountGeneration
    _state.update { it.copy(phoneAlertsBusy = true, phoneAlertsError = null) }
    try {
      val token = pushToken ?: push.token()
      if (token != null) cloud.unregisterPhone(token)
      if (gen != accountGeneration) return
      secrets.save("0", ALERTS_ON)
      _state.update { it.copy(alertsReady = false, phoneAlerts = it.phoneAlerts?.copy(enabled = false)) }
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) { if (gen == accountGeneration) _state.update { it.copy(phoneAlertsError = ErrorWords.of(e)) }
    } finally { if (gen == accountGeneration) _state.update { it.copy(phoneAlertsBusy = false) } }
  }

  suspend fun setAlertKind(id: String, on: Boolean) {
    val choices = _state.value.phoneAlerts?.takeIf { it.enabled }?.kinds ?: return
    if (choices.none { it.id == id } || _state.value.phoneAlertsBusy) return
    val gen = accountGeneration
    _state.update { it.copy(phoneAlertsBusy = true, phoneAlertsError = null) }
    try {
      val token = pushToken ?: push.token() ?: return
      val kinds = choices.associate { it.id to (if (it.id == id) on else it.enabled) }
      cloud.registerPush(token, _state.value.alertSound, kinds = kinds)
      if (gen != accountGeneration) return
      secrets.save(KiffClient.json.encodeToString(kotlinx.serialization.serializer<Map<String, Boolean>>(), kinds), "$ALERT_KINDS|$kiffTenant")
      loadPhoneAlerts()
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) { if (gen == accountGeneration) _state.update { it.copy(phoneAlertsError = ErrorWords.of(e)) }
    } finally { if (gen == accountGeneration) _state.update { it.copy(phoneAlertsBusy = false) } }
  }

  suspend fun loadTimeZones(query: String) {
    val gen = accountGeneration
    _state.update { it.copy(timeZoneQuery = query) }
    try { val zones = cloud.timeZones(query); if (gen == accountGeneration && _state.value.timeZoneQuery == query) _state.update { it.copy(timeZones = zones) }
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) { if (gen == accountGeneration) _state.update { it.copy(timeZoneError = ErrorWords.of(e)) } }
  }

  suspend fun loadTafSetup(tafId: String) {
    val id = _state.value.taf(tafId)?.cloudId ?: return
    val gen = shownGeneration()
    try {
      planque.tenant = kiffTenant; planque.deviceLoader = { kiff.thisDeviceId() }
      val proof = runCatching { planque.grantsReadProof { signer.signConfirmed(it) } }.getOrNull()
      val setup = cloud.setupView(id, proof).let { v -> v.copy(cardUnitExceptions = v.cardUnitExceptions.filterNot { it.id in _state.value.dismissedCardUnits }) }
      if (gen == shownGeneration()) _state.update { it.copy(tafSetups = it.tafSetups + (tafId to setup), tafSetupErrors = it.tafSetupErrors - tafId) }
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) { if (gen == shownGeneration()) _state.update { it.copy(tafSetupErrors = it.tafSetupErrors + (tafId to ErrorWords.of(e))) } }
  }

  /** Activity's cached pages as written, with any request KIFF took a Dismiss for left out: a 304, a failure or a cancel included. */
  private fun SourcePages<bot.tafs.app.kiff.TafActivityPage>.without(dismissed: Set<String>) =
    if (dismissed.isEmpty()) this else copy(pages = pages.map { p -> p.copy(cardUnitExceptions = p.cardUnitExceptions.filterNot { it.id in dismissed }) })

  suspend fun loadActivityPage(tafId: String, filter: String, more: Boolean = false, selection: String = "") {
    val id = _state.value.taf(tafId)?.cloudId ?: return
    val key = "$tafId:$filter:$selection"
    val old = _state.value.activityPages[key] ?: SourcePages()
    if (old.loading || (more && old.pages.lastOrNull()?.nextCursor == null)) return
    val gen = shownGeneration()
    _state.update { it.copy(activityPages = it.activityPages + (key to old.copy(loading = true, error = null).without(it.dismissedCardUnits))) }
    try {
      val result = cloud.activityPage(id, filter, if (more) old.pages.lastOrNull()?.nextCursor else null, if (more) null else old.firstEtag, selection)
      if (gen != shownGeneration()) return
      val page = result.value?.let { p -> p.copy(cardUnitExceptions = p.cardUnitExceptions.filterNot { it.id in _state.value.dismissedCardUnits }) }
      val next = if (page == null) old else SourcePages(if (more) old.pages + page else listOf(page), if (more) old.firstEtag else result.etag)
      _state.update { it.copy(activityPages = it.activityPages + (key to next.copy(loading = false).without(it.dismissedCardUnits))) }
    } catch (e: CancellationException) {
      if (gen == shownGeneration()) _state.update { it.copy(activityPages = it.activityPages + (key to old.copy(loading = false).without(it.dismissedCardUnits))) }
      throw e
    } catch (e: Exception) {
      if (gen != shownGeneration()) return
      if (e is CloudError && e.status == 409 && more) {
        _state.update { it.copy(activityPages = it.activityPages - key) }; loadActivityPage(tafId, filter, false, selection)
      } else _state.update { it.copy(activityPages = it.activityPages + (key to old.copy(error = ErrorWords.of(e)).without(it.dismissedCardUnits))) }
    }
  }

  suspend fun loadRunPage(tafId: String, more: Boolean = false, all: Boolean = false) {
    val id = _state.value.taf(tafId)?.cloudId ?: return
    val key = "$tafId:$all"; val old = _state.value.runPages[key] ?: SourcePages()
    if (old.loading || (more && old.pages.lastOrNull()?.nextCursor == null)) return
    val gen = shownGeneration()
    _state.update { it.copy(runPages = it.runPages + (key to old.copy(loading = true, error = null))) }
    try {
      val result = cloud.runsPage(id, if (more) old.pages.lastOrNull()?.nextCursor else null, if (more) null else old.firstEtag, if (all) 20 else 3)
      if (gen != shownGeneration()) return
      val next = if (result.value == null) old else SourcePages(if (more) old.pages + result.value else listOf(result.value), if (more) old.firstEtag else result.etag)
      _state.update { it.copy(runPages = it.runPages + (key to next.copy(loading = false))) }
    } catch (e: CancellationException) {
      if (gen == shownGeneration()) _state.update { it.copy(runPages = it.runPages + (key to old.copy(loading = false))) }
      throw e
    } catch (e: Exception) { if (gen == shownGeneration()) _state.update { it.copy(runPages = it.runPages + (key to old.copy(error = ErrorWords.of(e)))) } }
  }

  /** Sound for alerts on this phone. */
  fun setAlertSound(on: Boolean) {
    secrets.save(if (on) "1" else "0", ALERT_SOUND)
    _state.update { it.copy(alertSound = on) }
    viewModelScope.launch { syncPush() }
  }

  /**
   * Tells tafs cloud this phone's token again (tokens change), with a secret it hasn't confirmed yet if there is one, and learns
   * whether the account's alerts are set up. Only for a phone whose owner turned alerts on.
   */
  suspend fun syncPush() {
    // A hand-off tafs cloud didn't take is the owner's yes already given: it's handed over again here, with no new confirmation.
    if (_state.value.mode != Mode.kiff || !push.available || !(alertsWanted() || pendingHandoff() != null)) return
    val token = pushToken ?: push.token()?.also { pushToken = it } ?: return
    val generation = accountGeneration
    val handoff = pendingHandoff()
    if (secrets.load(ALERTS_ON) == "0" && handoff != null) {
      attempt { cloud.keepAlerts(handoff.target) } ?: return
      if (generation == accountGeneration) keepHandoff(null)
      return
    }
    val status = attempt { cloud.registerPush(token, _state.value.alertSound, handoff?.target, kinds = savedAlertKinds()) } ?: return
    if (generation != accountGeneration) return
    if (handoff != null) {
      keepHandoff(null)
      secrets.save("1", ALERTS_ON)
    }
    applyPush(status)
  }

  /**
   * Turns on alerts for the account's held calls, so they reach this phone with tafs closed: the owner allows notifications and
   * confirms; the phone points the account's KIFF alert target at tafs cloud (a signed setup call) and hands tafs cloud the
   * target's signing secret with its push token. A secret tafs cloud didn't take last time is handed over again first, without
   * registering a new one. True once done.
   */
  suspend fun turnOnAlerts(): Boolean {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || !_state.value.sessionHasKey || tenant == null) {
      fail(Words.Alert.signInAgain)
      return false
    }
    if (!push.available) {
      fail(Words.Alert.notInBuild)
      return false
    }
    if (_state.value.turningOnAlerts) return false
    _state.update { it.copy(turningOnAlerts = true) }
    try {
      val generation = accountGeneration
      if (!push.allowed()) {
        fail(Words.Alert.allowNotifications)
        return false
      }
      val token = pushToken ?: push.token()?.also { pushToken = it }
      if (token == null) {
        fail(Words.Alert.noPushTokenDevice(bot.tafs.app.model.ThisDevice.word))
        return false
      }
      var handoff = pendingHandoff()
      if (handoff == null) {
        try {
          signer.sign("tafs.alerts.v1|$tenant".toByteArray(), Words.Alert.prompt)
        } catch (_: SignerException) {
          return false
        }
        if (generation != accountGeneration) return false
        kiff.tenant = tenant
        val signer = signer
        kiff.setupSigner = { signer.signConfirmed(it) }
        try {
          val target = kiff.registerAlertTarget(cloud.alertsUrl)
          if (generation != accountGeneration) return false
          // Kept before anything else can fail: KIFF already signs with this secret.
          handoff = AlertHandoff(tenant, target)
          keepHandoff(handoff)
        } catch (e: CancellationException) {
          throw e
        } catch (e: KiffError.Server) {
          if (e.status == 403) {
            fail(
              if ((e.message ?: "").contains("phone_alert_url_forbidden") || (e.message ?: "").contains("app backend")) Words.Alert.kiffDoesntSend
              else Words.Alert.roleCant,
            )
          } else {
            fail(Words.Alert.notOn(why = ErrorWords.of(e)))
          }
          return false
        } catch (e: Exception) {
          fail(Words.Alert.notOn(why = ErrorWords.of(e)))
          return false
        } finally {
          kiff.setupSigner = null
        }
      }
      return try {
        val status = cloud.registerPush(token, _state.value.alertSound, handoff.target, kinds = savedAlertKinds())
        if (generation != accountGeneration) return false
        keepHandoff(null)
        secrets.save("1", ALERTS_ON)
        applyPush(status)
        status.alerts
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == accountGeneration) {
          fail(Words.Alert.notOnForPhone(why = ErrorWords.of(e)))
          _state.update { it.copy(alertsReady = false) }
        }
        false
      }
    } finally {
      _state.update { it.copy(turningOnAlerts = false) }
    }
  }

  // MARK: Coach

  /** Looks for the account's Setup on tafs cloud: null until it is started (from an iPhone, for now). */
  suspend fun loadCoach() {
    if (_state.value.mode != Mode.kiff) return
    val generation = accountGeneration
    val setup = try {
      cloud.setupRead()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(coachChecked = true, chatErrors = it.chatErrors + (COACH_CHAT to Words.Coach.notReached(why = ErrorWords.of(e)))) }
      return
    }
    if (generation == accountGeneration) {
      val coach = setup.setup
      // No Coach yet: tafs cloud's first start, when it sends one the phone may sign, is drawn as Coach's tools step, whose one
      // button starts Coach and gives it its tools under one fingerprint.
      val start = setup.start?.takeIf { coach == null && it.startable }
      // tafs cloud set a new account's zone from this phone's: times show in it, and the question isn't asked.
      setup.timeZone?.let { zone ->
        AccountTime.use(zone)
        _state.update { s -> s.copy(kiffAccount = s.kiffAccount?.copy(timeZone = zone), timeZoneOffer = s.timeZoneOffer?.takeIf { it != zone }) }
      }
      _state.update {
        it.copy(
          coachCloudId = coach?.id, coachAgent = coach?.kiffAgent, coachChecked = true, coachStart = start,
          coachTools = if (coach == null) start?.tools else setup.tools, chatErrors = it.chatErrors - COACH_CHAT,
        )
      }
    }
    loadRecipes()
  }

  /** Reads the recipes a new Coach conversation opens to. Not read, the conversation opens without them; Coach still takes any job. */
  suspend fun loadRecipes() {
    if (_state.value.mode != Mode.kiff) return
    val generation = accountGeneration
    val recipes = try {
      cloud.recipes()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return
    }
    if (generation == accountGeneration) _state.update { it.copy(recipes = recipes) }
  }

  /** Reads the gallery: every job, and the strip's. A tafs cloud without it, or a read that fails, leaves what was read. */
  suspend fun loadGallery() {
    if (_state.value.mode != Mode.kiff) return
    val generation = accountGeneration
    val gallery = try {
      cloud.gallery()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return
    }
    if (generation == accountGeneration) _state.update { it.copy(gallery = gallery) }
  }

  /** The calendar shows another view or date: kept in the state, so a rotation keeps it. */
  fun showCalendar(calendar: bot.tafs.app.model.CalendarState) {
    _state.update { it.copy(calendarState = calendar) }
  }

  /** The taf behind tafs cloud's id, as the calendar names it, when this person has it listed. */
  fun tafIdForCloud(cloudId: String): String? = _state.value.tafs.firstOrNull { it.cloudId == cloudId }?.id

  /** Reads the calendar for the selected month's grid ([bot.tafs.app.model.CalendarMath.range]): one read draws its day, week and month. */
  suspend fun loadCalendar(calendar: bot.tafs.app.model.CalendarState) {
    if (_state.value.mode != Mode.kiff) return
    val (from, to) = bot.tafs.app.model.CalendarMath.range(calendar.selected)
    val generation = accountGeneration
    _state.update { it.copy(calendarError = null) }
    try {
      val read = cloud.calendar(from, to)
      if (generation == accountGeneration) _state.update { it.copy(calendar = read, calendarError = null) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(calendarError = Words.Home.calendarNotRead(why = ErrorWords.of(e))) }
    }
  }

  /**
   * Reads the person's AI-data consent. After sign-in and at launch ([ask]), its screen shows while it isn't given, before anything
   * they write is sent; read for Account, it never brings the screen back after Not now. Unread (tafs cloud not reached), or with
   * no words to draw, nothing shows: tafs cloud still refuses every model turn until it's given.
   */
  suspend fun loadAIConsent(ask: Boolean = false) {
    if (_state.value.mode != Mode.kiff) return
    val generation = accountGeneration
    val read = attempt { cloud.aiConsent() }?.takeIf { it.drawable } ?: return
    if (generation == accountGeneration) _state.update { it.copy(aiConsent = read, aiConsentAsked = if (ask) !read.given else it.aiConsentAsked && !read.given) }
  }

  /** The person allows AI use, for the version of the words on their screen. Words changed since come back to read again. */
  suspend fun allowAI() = answerAIConsent { cloud.giveAIConsent(it.version) }

  /** The person withdraws it, from Account. */
  suspend fun withdrawAI() = answerAIConsent { cloud.withdrawAIConsent() }

  /** "Not now": the screen goes; nothing is sent, and it shows again when they write to a taf or Coach. */
  fun notNowAI() = _state.update { it.copy(aiConsentAsked = false, aiConsentError = null) }

  private suspend fun answerAIConsent(call: suspend (bot.tafs.app.kiff.AIConsent) -> bot.tafs.app.kiff.AIConsent) {
    val shown = _state.value.aiConsent ?: return
    if (_state.value.mode != Mode.kiff || _state.value.aiConsentBusy) return
    val generation = accountGeneration
    _state.update { it.copy(aiConsentBusy = true, aiConsentError = null) }
    try {
      val now = call(shown)
      if (generation == accountGeneration) _state.update { it.copy(aiConsent = now, aiConsentAsked = false) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Words changed since they read them (409): the new ones are shown, to read and allow again.
      val cloudError = e as? bot.tafs.app.kiff.CloudError
      if (generation == accountGeneration) _state.update { it.copy(aiConsentError = cloudError?.message ?: ErrorWords.of(e)) }
      if (cloudError?.status == 409) loadAIConsent()
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(aiConsentBusy = false) }
    }
  }

  /** Reads the account's "Show suggestions" setting, for Account. */
  suspend fun loadSuggestions() {
    if (_state.value.mode != Mode.kiff) return
    val generation = accountGeneration
    val read = attempt { cloud.suggestions() } ?: return
    if (generation == accountGeneration) _state.update { it.copy(suggestions = read) }
  }

  /**
   * Turns the account's suggestions off for good, or on again, in tafs cloud: it decides what a new Coach conversation shows, and
   * the recipes are read again. A refusal is said in tafs cloud's words.
   */
  suspend fun setSuggestions(show: Boolean) {
    if (_state.value.mode != Mode.kiff || _state.value.suggestionsBusy) return
    val generation = accountGeneration
    _state.update { it.copy(suggestionsBusy = true, suggestionsError = null) }
    try {
      val now = cloud.setSuggestions(show)
      if (generation == accountGeneration) _state.update { it.copy(suggestions = now) }
      loadRecipes()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(suggestionsError = Words.Settings.suggestionsNotChanged(why = (e as? bot.tafs.app.kiff.CloudError)?.message ?: ErrorWords.of(e))) }
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(suggestionsBusy = false) }
    }
  }

  /**
   * Starts Coach on this account, once, behind one confirmation: the phone gives Coach its tools, enrols with planque (planque gets
   * the phone's key and a connector key that connects tools and does nothing else), and Coach is started from a draft with no
   * tools, whose key tafs cloud claims, so it never reaches the phone. Done before the first message, so every grant Coach asks
   * for can be read and shown. Returns whether Coach is started; why not is in [AppState.coachStartError].
   */
  suspend fun startCoach(): Boolean {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || !_state.value.sessionHasKey || tenant == null) {
      startFailed(Words.SignIn.againToSignDevice(bot.tafs.app.model.ThisDevice.word))
      return false
    }
    if (_state.value.approvalKeyLost) {
      startFailed(KiffClient.APPROVAL_KEY_LOST)
      return false
    }
    if (_state.value.planqueReady && _state.value.coachCloudId != null) return true
    if (_state.value.startingCoach) return false
    // tafs cloud's first start: Start's screen already shows what Coach is given, the Cards in KIFF's words among it, so the
    // first Send (or Start) starts Coach and gives its tools under one fingerprint, with no second screen.
    _state.value.coachStart?.takeIf { _state.value.coachCloudId == null && it.startable }?.let { return startCoachTogether(it) }
    _state.update { it.copy(startingCoach = true, coachStartError = null) }
    val generation = accountGeneration
    val signer = signer
    val sign: (ByteArray) -> String = { signer.signConfirmed(it) }
    try {
      kiff.tenant = tenant
      kiff.setupSigner = sign
      planque.tenant = tenant
      planque.deviceLoader = { kiff.thisDeviceId() }
      planque.signer = sign
      var setup = _state.value.coachCloudId?.let { id -> CloudSetup(id, kiffAgent = _state.value.coachAgent) }
      // The draft this Start made, and its claim: set only when Coach wasn't there yet.
      var claim: Pair<String, String>? = null
      try {
        // Whether Coach is there yet: a read, which makes nothing.
        if (setup == null) setup = cloud.setup()
        if (generation != accountGeneration) return false
        // One yes, before anything is made (Coach's connector key, its drafts). A first Start whose draft is signed by the
        // approval key in KIFF asks for that fingerprint and no other, so "once" stays true; otherwise the owner says yes here.
        val draftAsks = setup == null && sessionHasApprovalKey
        if (!draftAsks) {
          try {
            signer.sign("tafs.setup.v1|$tenant".toByteArray(), Words.Coach.Prompt.connectTools)
          } catch (e: SignerException) {
            notSigned(e, "").takeIf { it.isNotEmpty() }?.let { startFailed(it) }
            return false
          }
          if (generation != accountGeneration) return false
        }
        if (setup == null) {
          approvalReason = Words.Coach.Prompt.start
          val draft = kiff.submitNoToolsDraft(COACH_AGENT, "Coach")
          val secret = draft.claimSecret
          if (generation != accountGeneration) return false
          if (secret == null) {
            startFailed(Words.Coach.didntStart(why = ErrorWords.OURS))
            return false
          }
          claim = draft.id to secret
        }
        if (!enrolForCoach(::startFailed)) return false
        if (generation != accountGeneration) return false
        claim?.let { (draft, secret) -> setup = cloud.startSetup(draft, secret) }
      } catch (e: SignerException) {
        if (generation == accountGeneration) notSigned(e, "").takeIf { it.isNotEmpty() }?.let { startFailed(it) }
        return false
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == accountGeneration) startFailed(Words.Coach.couldntStart(why = ErrorWords.of(e)))
        return false
      }
      val started = setup ?: return false
      if (generation != accountGeneration) return false
      _state.update { it.copy(coachCloudId = started.id, coachAgent = started.kiffAgent, coachChecked = true) }
      // Coach's tools are given in one place only, their own step, in tafs cloud's words, behind their own fingerprint: never
      // inside another confirmation. Still to give, the step is asked: at the end of a first Start, or on a phone that just
      // enrolled for a Coach already there.
      loadCoachTools()
      if (generation == accountGeneration && _state.value.coachTools?.toGive == true) _state.update { it.copy(coachToolsAsked = true) }
    } finally {
      planque.signer = null
      kiff.setupSigner = null
      if (generation == accountGeneration) _state.update { it.copy(startingCoach = false) }
    }
    refreshConnections()
    readThread(COACH_CHAT)
    return true
  }

  /**
   * Enrols this phone with planque for Coach, once: planque gets the phone's key and a connector key minted with a signed setup
   * call, never kept here. False, with why said through [failed], when it didn't go. [kiff]'s and [planque]'s signers are the
   * caller's, under the confirmation it took.
   */
  private suspend fun enrolForCoach(failed: (String) -> Unit): Boolean {
    if (_state.value.planqueReady) return true
    try {
      planque.enrol(signer.publicKeyDER, kiff.mintConnectorKey())
    } catch (e: CancellationException) {
      throw e
    } catch (e: KiffError.Server) {
      failed(if (e.status == 403) Words.Coach.connectAdminOnly else Words.Coach.connectingNotSetUp(why = ErrorWords.of(e)))
      return false
    } catch (e: PlanqueError.Server) {
      failed(
        when (e.status) {
          409 -> Words.Coach.connectOtherDevice
          // planque couldn't check this phone with KIFF. KIFF's session works, so it isn't a sign-out.
          401 -> CoachWords.CONNECTING_FAILED
          else -> Words.Coach.connectingNotSetUp(why = ErrorWords.of(e))
        },
      )
      return false
    }
    return true
  }

  /**
   * Coach's first start under one fingerprint, from tafs cloud's [start] (docs/coach.md): the batch for KIFF's gateway and Coach's
   * draft with its Cards, each body as tafs cloud wrote it, signed once together by the approval key (`kiff.requests.v1`, one
   * `signed_at`). Right after the fingerprint this phone enrols with planque, before anything goes to KIFF, so a refused
   * enrolment leaves no tools and no confirmed draft nobody claims. Then the batch, the draft once it answered, and tafs cloud
   * claims the draft's key. With every tool connected, [start] carries the draft alone, signed on its own. A draft refused after the batch
   * went in comes back on the next read as the draft alone. True once Coach is started; why not is in `coachToolsError`.
   */
  private suspend fun startCoachTogether(start: bot.tafs.app.kiff.CoachStart): Boolean {
    val tenant = kiffTenant
    val failed: (String) -> Unit = { words -> _state.update { it.copy(coachToolsError = words) } }
    if (!_state.value.sessionHasKey || tenant == null) {
      failed(Words.SignIn.againToSignDevice(bot.tafs.app.model.ThisDevice.word))
      return false
    }
    if (_state.value.approvalKeyLost) {
      failed(KiffClient.APPROVAL_KEY_LOST)
      return false
    }
    val draftRequest = start.draft ?: return false
    if (_state.value.startingCoach) return false
    _state.update { it.copy(startingCoach = true, coachToolsError = null, coachStartError = null) }
    val generation = accountGeneration
    val signer = signer
    val sign: (ByteArray) -> String = { signer.signConfirmed(it) }
    val started = try {
      kiff.tenant = tenant
      kiff.setupSigner = sign
      planque.tenant = tenant
      planque.deviceLoader = { kiff.thisDeviceId() }
      planque.signer = sign
      val draft = if (start.together) {
        val connectRequest = start.connect ?: return false
        var batch: KiffClient.Prepared? = null
        var cards: KiffClient.Prepared? = null
        // One fingerprint for both, the time taken once the person approved: inside the prompt's success.
        val signature = signer.signApproval(Words.Coach.Prompt.start) {
          val signedAt = kiff.nextSignedAt()
          val b = kiff.prepare(connectRequest.method, connectRequest.path, connectRequest.body, gateway = true, signedAt = signedAt)
          val c = kiff.prepare(draftRequest.method, draftRequest.path, draftRequest.body, gateway = false, signedAt = signedAt)
          batch = b
          cards = c
          KiffClient.requestsMessage(tenant, signedAt, b.hash, c.hash)
        }
        val b = batch ?: return false
        val c = cards ?: return false
        // Enrolled before anything goes to KIFF, under the same confirmation.
        if (!enrolForCoach(failed)) return false
        if (generation != accountGeneration) return false
        // The batch first: KIFF refuses a Card naming an action no tool declares yet.
        kiff.sendTogether(b, signature, with = c.hash)
        if (generation != accountGeneration) return false
        KiffClient.json.decodeFromString(bot.tafs.app.kiff.AgentDraft.serializer(), String(kiff.sendTogether(c, signature, with = b.hash)).ifEmpty { "{}" })
      } else {
        // Every tool connected already: the draft alone, behind its own fingerprint, taken first; then the enrolment; then the draft.
        var alone: KiffClient.Prepared? = null
        val signature = signer.signApproval(Words.Coach.Prompt.start) {
          kiff.prepare(draftRequest.method, draftRequest.path, draftRequest.body, gateway = false, signedAt = kiff.nextSignedAt()).also { alone = it }.message
        }
        val d = alone ?: return false
        if (!enrolForCoach(failed)) return false
        if (generation != accountGeneration) return false
        KiffClient.json.decodeFromString(bot.tafs.app.kiff.AgentDraft.serializer(), String(kiff.sendSigned(d, signature)).ifEmpty { "{}" })
      }
      if (generation != accountGeneration) return false
      val secret = draft.claimSecret
      if (secret == null) {
        failed(Words.Coach.didntStart(why = ErrorWords.OURS))
        return false
      }
      cloud.startSetup(draft.id, secret)
    } catch (e: SignerException) {
      if (generation == accountGeneration) notSigned(e, "").takeIf { it.isNotEmpty() }?.let(failed)
      return false
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) failed(Words.Coach.couldntStart(why = ErrorWords.of(e)))
      // A draft refused after the batch went in: the next read carries the draft alone.
      if (generation == accountGeneration) loadCoach()
      return false
    } finally {
      planque.signer = null
      kiff.setupSigner = null
      if (generation == accountGeneration) _state.update { it.copy(startingCoach = false) }
    }
    if (generation != accountGeneration) return false
    _state.update { it.copy(coachCloudId = started.id, coachAgent = started.kiffAgent, coachChecked = true, coachStart = null, coachToolsAsked = false) }
    loadCoachTools()
    refreshConnections()
    readThread(COACH_CHAT)
    return true
  }

  /** The tools step put off, or done: the row above the conversation opens it again. */
  fun notNowCoachTools() = _state.update { it.copy(coachToolsAsked = false) }

  /** A logo's image from tafs cloud, for [bot.tafs.app.design.LogoStore]: only its own `/v1/logos/` paths. */
  suspend fun logo(path: String): ByteArray? = cloud.logo(path)

  private fun startFailed(words: String) = _state.update { it.copy(coachStartError = words) }

  /** Reads Coach's tools as KIFF has them, from tafs cloud: what's still to give, if anything. */
  suspend fun loadCoachTools() {
    if (_state.value.mode != Mode.kiff || _state.value.coachCloudId == null) return
    val generation = accountGeneration
    val tools = attempt { cloud.coachTools() } ?: return
    if (generation == accountGeneration) _state.update { it.copy(coachTools = tools) }
  }

  /**
   * Gives Coach its tools, as tafs cloud says they're still to give (`GET /v1/setup`'s `tools`, docs/coach.md): the batch of
   * connections, sent as it comes and signed; then Coach's Cards' draft, sent as it comes, behind its own fingerprint. The phone
   * builds neither: tafs cloud reads KIFF each time, so every phone of the account agrees. A name a vendor's tool holds stops it
   * before anything is sent, and says which. True once nothing's left to give.
   */
  suspend fun giveCoachTools(): Boolean {
    // Before Coach exists, its tools are given by starting it, under one fingerprint.
    if (_state.value.mode == Mode.kiff && _state.value.coachCloudId == null) {
      return _state.value.coachStart?.takeIf { it.startable }?.let { startCoachTogether(it) } ?: false
    }
    val tenant = kiffTenant ?: return false
    if (_state.value.mode != Mode.kiff || _state.value.coachCloudId == null) return false
    val generation = accountGeneration
    val tools = try {
      cloud.coachTools()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(coachToolsError = Words.Coach.toolsNotRead(why = ErrorWords.of(e))) }
      return false
    } ?: return false
    if (generation != accountGeneration) return false
    _state.update { it.copy(coachTools = tools, coachToolsError = null) }
    when (tools.state) {
      "given" -> return true
      "taken" -> {
        _state.update { it.copy(coachToolsError = CoachWords.taken(tools.taken)) }
        return false
      }
      "unknown" -> {
        _state.update { it.copy(coachToolsError = CoachWords.TOOLS_UNKNOWN) }
        return false
      }
    }
    val signer = signer
    kiff.tenant = tenant
    kiff.setupSigner = { signer.signConfirmed(it) }
    try {
      tools.connect?.let { kiff.connectBatch(it.body) }
      if (generation != accountGeneration) return false
      // tafs cloud drafts the Card over tools KIFF holds: one sent without it comes once the batch is in, so it's read again
      // here and given in the same flow, and the owner taps once.
      val card = tools.card ?: if (tools.connect != null) cloud.coachTools()?.card else null
      if (generation != accountGeneration) return false
      card?.let {
        // Its own fingerprint, in its own words: it gives Coach its Cards.
        approvalReason = Words.Coach.Prompt.giveCard
        kiff.submitDraft(it.body)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: SignerException) {
      if (generation == accountGeneration) notSigned(e, "").takeIf { it.isNotEmpty() }?.let { words -> _state.update { it.copy(coachToolsError = words) } }
      return false
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(coachToolsError = Words.Coach.toolsNotGiven(why = ErrorWords.of(e))) }
      return false
    } finally {
      kiff.setupSigner = null
    }
    if (generation != accountGeneration) return false
    loadCoachTools()
    return true
  }

  // MARK: Coach's approvals (planque and KIFF)

  /** What a connection's approval came to. */
  sealed interface Connected {
    /** Connected, or its Cards confirmed. */
    data class Done(val grant: Grant) : Connected

    /** Nothing was done and nothing is to be told: the owner didn't confirm, the terms changed on the screen, or another approval is going through. */
    data object Stopped : Connected

    /** Approved before, with its own signature: connected, and its Card confirmed in KIFF. Nothing was signed this time. */
    data class AlreadyApproved(val grant: Grant) : Connected

    /**
     * It didn't go through: [why] for the owner, in the app's words; [told] for Coach, the card's answer, which keeps the server's own
     * reason so Coach can say what went wrong and ask again.
     */
    data class Failed(val why: String, val told: String = why) : Connected
  }

  private fun note(cardId: String, words: String?) {
    words?.let(bot.tafs.app.drive.DriveDiagnostics::error)
    _state.update { it.copy(coachNotes = if (words == null) it.coachNotes - cardId else it.coachNotes + (cardId to words)) }
  }

  /**
   * Reads the grants. A read asks for no confirmation: it is signed with the phone's plain key so planque shows them only to a
   * device KIFF knows. It never touches the signer a confirmed step set: it runs whenever the app comes back to the front, which
   * is also when the owner returns from the vendor's sign-in, mid-way through a step that still needs its signer to confirm it.
   */
  suspend fun refreshConnections() {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || !_state.value.sessionHasKey || tenant == null) return
    val generation = accountGeneration
    planque.tenant = tenant
    planque.deviceLoader = { kiff.thisDeviceId() }
    val signer = signer
    val list = try {
      planque.grants { signer.signConfirmed(it) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: PlanqueError.Server) {
      // planque has no account for this one, or doesn't know this device: nothing to approve from here.
      if (generation == accountGeneration && e.status in setOf(401, 403, 404)) _state.update { it.copy(planqueReady = false) }
      return
    } catch (_: Exception) {
      return
    }
    if (generation == accountGeneration) _state.update { it.copy(grants = list, planqueReady = true) }
  }

  /** What an approval needs to be shown: planque's grant and KIFF's draft, read when Coach's thread is. */
  private suspend fun readApprovals() {
    val generation = accountGeneration
    val approvals = _state.value.coachComponents.map { it.component }.filter { it.type == "approval" || it.type == "sign_in" }
    if (approvals.isEmpty()) return
    refreshConnections()
    for (c in approvals) {
      val id = c.draft ?: continue
      if (_state.value.setupDrafts[id]?.confirmed == true) continue
      val draft = attempt { kiff.draft(id) } ?: continue
      if (generation == accountGeneration) _state.update { it.copy(setupDrafts = it.setupDrafts + (id to draft)) }
    }
  }

  /**
   * An approval in Coach's chat: one confirmation each. For a grant not yet connected, it approves planque's grant, then the
   * owner signs in on the vendor's own page and the phone confirms the sign-in with the code it brought back. For a connected
   * grant, it confirms the taf's draft: KIFF takes a Card only for a tool that is connected, so a draft comes only after its
   * grant. [shownDigest] is the grant's digest as the owner's screen showed it, and [shownDraft] the draft's version: the
   * confirmation signs exactly that. Terms that changed since are put on screen and not signed; nothing shown, nothing signed.
   */
  /** Connect apps: tafs cloud's list, the catalog first, for [q] when given. The sample has none. */
  suspend fun loadConnectApps(q: String? = null) {
    if (_state.value.mode != Mode.kiff) return
    connectAppsQuery = q
    // The account's connections too, so a connected app lists each one, with its taf and Disconnect.
    if (q == null) refreshConnections()
    // Only the newest search's answer is shown: an earlier one that comes back late (the first, unfiltered read) is dropped.
    val asked = ++connectAppsAsked
    val generation = accountGeneration
    try {
      // This phone's signed grants read, as Setup sends it: tafs cloud then says which removed taf's connections Clear may end.
      planque.tenant = kiffTenant; planque.deviceLoader = { kiff.thisDeviceId() }
      val proof = runCatching { planque.grantsReadProof { signer.signConfirmed(it) } }.getOrNull()
      val apps = cloud.connectApps(q, proof = proof)
      if (generation == accountGeneration && asked == connectAppsAsked) _state.update { it.copy(connectApps = apps, connectAppsError = null) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(connectAppsError = Words.Connect.appsNotRead(why = ErrorWords.of(e))) }
    }
  }

  /** The search the shown list is for, sent again with the next page's cursor. */
  private var connectAppsQuery: String? = null

  /** How many searches were asked, so only the newest one's answer is shown. */
  private var connectAppsAsked = 0

  /** Show more apps: the next page of the managed provider's catalog, added under the ones shown. */
  suspend fun loadMoreConnectApps() {
    val now = _state.value.connectApps ?: return
    val cursor = now.nextCursor ?: return
    val generation = accountGeneration
    try {
      val page = cloud.connectApps(connectAppsQuery, now.provider, cursor)
      if (generation == accountGeneration) _state.update { s ->
        val shown = s.connectApps ?: return@update s
        // The removed tafs stay as the first page read them, with its proof: a further page only adds apps.
        s.copy(connectApps = page.copy(apps = shown.apps + page.apps.filter { app -> shown.apps.none { it.entry == app.entry } }, removedTafs = shown.removedTafs), connectAppsError = null)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(connectAppsError = Words.Connect.appsNotRead(why = ErrorWords.of(e))) }
    }
  }

  /**
   * The owner's answer to "Did this happen?" for [call]: one fingerprint, the approval key signs it whichever the answer, and KIFF
   * keeps the first. **No** gives the Card its room back. Answered, the call leaves the list.
   */
  suspend fun answerDidThisHappen(call: bot.tafs.app.kiff.UnsureCall, happened: Boolean): Boolean {
    if (_state.value.mode != Mode.kiff || _state.value.answeringUnsure != null) return false
    val tenant = kiffTenant
    val key = call.proposal
    if (!_state.value.sessionHasKey || tenant == null) {
      _state.update { it.copy(unsureErrors = it.unsureErrors + (key to Words.SignIn.againToSignDevice(bot.tafs.app.model.ThisDevice.word))) }
      return false
    }
    if (_state.value.approvalKeyLost) {
      _state.update { it.copy(unsureErrors = it.unsureErrors + (key to KiffClient.APPROVAL_KEY_LOST)) }
      return false
    }
    val generation = accountGeneration
    _state.update { it.copy(answeringUnsure = key, unsureErrors = it.unsureErrors - key) }
    try {
      confirmGrant("tafs.did-this-happen.v1|$tenant|$key|$happened", bot.tafs.app.model.DidThisHappenWords.reason(happened))
      if (generation != accountGeneration) return false
      kiff.tenant = tenant
      val signer = signer
      kiff.setupSigner = { signer.signConfirmed(it) }
      kiff.answerDidThisHappen(key, happened)
    } catch (e: SignerException) {
      if (generation == accountGeneration) _state.update { it.copy(unsureErrors = it.unsureErrors + (key to notSigned(e, Words.Common.notSentUnconfirmed))) }
      return false
    } catch (_: KiffError.ApprovalKeyRequired) {
      if (generation == accountGeneration) _state.update { it.copy(unsureErrors = it.unsureErrors + (key to KiffClient.APPROVAL_KEY_LOST)) }
      return false
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(unsureErrors = it.unsureErrors + (key to Words.NeedsYou.Unsure.answerNotTaken(why = ErrorWords.of(e)))) }
      return false
    } finally {
      kiff.setupSigner = null
      if (generation == accountGeneration) _state.update { it.copy(answeringUnsure = null) }
    }
    if (generation == accountGeneration) _state.update { s -> s.copy(unsure = s.unsure.filterNot { it.proposal == key }) }
    // The Card's room may be back: read its numbers again.
    refresh()
    return true
  }

  /**
   * "Not sure, help me check" on [call]: KIFF takes it signed by the device key (it decides nothing), then tafs cloud opens a new
   * conversation with Coach on that call, which Coach opens on. The card stays until the owner answers it.
   */
  suspend fun checkDidThisHappen(call: bot.tafs.app.kiff.UnsureCall): Boolean {
    if (_state.value.mode != Mode.kiff || _state.value.answeringUnsure != null) return false
    val tenant = kiffTenant
    val key = call.proposal
    if (!_state.value.sessionHasKey || tenant == null) {
      _state.update { it.copy(unsureErrors = it.unsureErrors + (key to Words.SignIn.againToSignDevice(bot.tafs.app.model.ThisDevice.word))) }
      return false
    }
    val generation = accountGeneration
    _state.update { it.copy(answeringUnsure = key, unsureErrors = it.unsureErrors - key) }
    val sent = try {
      kiff.tenant = tenant
      val signer = signer
      kiff.checkDidThisHappen(key) { signer.signConfirmed(it) }
      cloud.checkCall(key)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(unsureErrors = it.unsureErrors + (key to Words.NeedsYou.Unsure.coachNotOpened(why = ErrorWords.of(e)))) }
      return false
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(answeringUnsure = null) }
    }
    if (generation != accountGeneration) return false
    openCoachChat(sent.session)
    _state.update { it.copy(coachRequested = true) }
    return true
  }

  /** Opens [run] of [tafId], step by step (K5). Someone else's request is only for whoever manages the taf: tafs cloud says no. */
  suspend fun openRequest(tafId: String, run: String) {
    if (_state.value.mode != Mode.kiff) return
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    val generation = accountGeneration
    _state.update { it.copy(requestError = null) }
    try {
      val detail = cloud.request(cloudId, run)
      if (generation == accountGeneration) _state.update { it.copy(openRequest = tafId to detail) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(requestError = Words.NeedsYou.requestNotRead(why = ErrorWords.of(e))) }
    }
  }

  /** From the dial's rim: the request it points at, the first not done, else the newest. Reads the taf's work first. */
  suspend fun openRimRequest(tafId: String) {
    loadWork(tafId)
    val requests = _state.value.works[tafId]?.requests.orEmpty()
    val request = requests.firstOrNull { it.state != "done" } ?: requests.firstOrNull() ?: return
    openRequest(tafId, request.id)
  }

  fun closeRequest() = _state.update { it.copy(openRequest = null, requestError = null) }

  /**
   * A browser connection's sign-in to a site (kiff/planque#86): planque's live view, asked for with this device's signature and
   * opened as it comes. A view that isn't on planque's own address is never opened. A refusal is said on the connection.
   */
  suspend fun openSiteSignIn(grantId: String) {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || tenant == null || _state.value.openingSiteSignIn != null || _state.value.siteSignIn != null) return
    val generation = accountGeneration
    _state.update { it.copy(openingSiteSignIn = grantId, coachNotes = it.coachNotes - grantId) }
    planque.tenant = tenant
    planque.deviceLoader = { kiff.thisDeviceId() }
    val signer = signer
    try {
      val view = planque.siteSignIn(grantId) { signer.signConfirmed(it) }
      if (generation != accountGeneration) return
      if (!onPlanque(view.view)) {
        note(grantId, Words.Connect.SignInView.wrongAddress)
        return
      }
      _state.update { it.copy(siteSignIn = view) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // planque's own words say why (a view already open, the connection not connected); anything else, in the app's.
      if (generation == accountGeneration) note(grantId, Words.Connect.SignInView.notOpened(why = (e as? PlanqueError.Server)?.message ?: ErrorWords.of(e)))
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(openingSiteSignIn = null) }
    }
  }

  fun closeSiteSignIn() = _state.update { it.copy(siteSignIn = null) }

  /** Whether [url] is on planque's own https origin (scheme, host and port): the only place a sign-in view may come from. */
  private fun onPlanque(url: String): Boolean = bot.tafs.app.views.sameHttpsOrigin(url, planque.baseUrl)

  /** The channels a Slack bot connection can see, from planque: a signed read, for picking a Slack door's channel. */
  suspend fun loadSlackChannels(grantId: String) {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || !_state.value.sessionHasKey || tenant == null) return
    val generation = accountGeneration
    planque.tenant = tenant
    planque.deviceLoader = { kiff.thisDeviceId() }
    val signer = signer
    val channels = try {
      planque.slackChannels(grantId) { signer.signConfirmed(it) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return
    }
    if (generation == accountGeneration) _state.update { it.copy(slackChannels = it.slackChannels + (grantId to channels)) }
  }

  /**
   * A Slack door: planque first, then tafs cloud. One confirmation, then this device signs planque's subscription of the Slack bot
   * to new messages in that channel, and only then is the door added in tafs cloud (`{app: "slack", grant, channel}`). If the door
   * can't be added, the subscription is removed again, so nothing listens for a door that isn't there.
   */
  suspend fun addSlackDoor(tafId: String, app: DoorApp, grant: bot.tafs.app.kiff.Grant, channel: bot.tafs.app.kiff.SlackChannel): Door? {
    val tenant = kiffTenant
    val taf = _state.value.taf(tafId)
    val cloudId = taf?.cloudId
    val account = grant.account
    if (_state.value.mode != Mode.kiff || tenant == null || cloudId == null) return null
    if (account == null) {
      updateDoors(tafId) { it.copy(error = DoorWords.SLACK_NO_ACCOUNT) }
      return null
    }
    val generation = accountGeneration
    updateDoors(tafId) { it.copy(busy = true, error = null) }
    planque.tenant = tenant
    planque.deviceLoader = { kiff.thisDeviceId() }
    val signer = signer
    try {
      try {
        signer.sign("tafs.slack-door.v1|$tenant|${grant.id}|${channel.id}".toByteArray(), DoorWords.slackReason(taf.name, channel.name.ifBlank { channel.id }))
      } catch (e: SignerException) {
        updateDoors(tafId) { it.copy(error = notSigned(e, Words.Door.notAddedUnconfirmed)) }
        return null
      }
      if (generation != accountGeneration) return null
      planque.signer = { signer.signConfirmed(it) }
      val trigger = try {
        planque.subscribe(grant.id, account, DoorWords.SLACK_CHANNEL_MESSAGES, listOf(channel.id))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == accountGeneration) updateDoors(tafId) { it.copy(error = Words.Door.slackCantListen(why = ErrorWords.of(e))) }
        return null
      }
      if (generation != accountGeneration) return null
      val door = try {
        // planque's subscription id goes with the door, so removing the door can remove it.
        cloud.addDoor(cloudId, app.id, mapOf("grant" to grant.id, "channel" to channel.id) + (trigger.key?.let { mapOf("trigger" to it) } ?: emptyMap()))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // No door: the subscription goes again, so nothing listens for it.
        trigger.key?.let { key -> runCatching { planque.unsubscribe(grant.id, key, account) } }
        if (generation == accountGeneration) updateDoors(tafId) { it.copy(error = Words.Door.slackNotAdded(why = ErrorWords.of(e))) }
        return null
      }
      if (generation == accountGeneration) loadDoors(tafId)
      return door
    } finally {
      planque.signer = null
      if (generation == accountGeneration) updateDoors(tafId) { it.copy(busy = false) }
    }
  }

  /**
   * A bot door, such as Telegram's: the owner's bot connection, as tafs cloud's door (`{app, grant, bot?}`). planque listens to the bot
   * from its connection on, so there's nothing to subscribe and nothing to sign; who may give work is paired after, from their knock.
   */
  suspend fun addBotDoor(tafId: String, app: DoorApp, grant: bot.tafs.app.kiff.Grant): Door? {
    if (_state.value.mode != Mode.kiff) return null
    return doorWork(tafId) { cloud.addDoor(it, app.id, mapOf("grant" to grant.id) + (grant.botName?.let { bot -> mapOf("bot" to bot) } ?: emptyMap())) }
  }

  /** A subscription planque made for an approved event that tafs cloud hasn't recorded yet: kept only to record it again. */
  private data class EventReceipt(val version: Int, val approval: bot.tafs.app.kiff.KnobWire.Approval, val subscription: String)

  /** Those receipts, by taf and trigger, so recording again neither asks the owner nor subscribes twice. */
  private val eventReceipts = mutableMapOf<Pair<String, String>, EventReceipt>()

  /**
   * Approves an app's event on the dial (`ok: approve`): the live dial is read again first, so only the filters it still shows
   * are approved; the owner confirms with the approval key, over the taf, version, trigger, connection, event and the filters'
   * hash; planque subscribes exactly those filters, signed by this device; then tafs cloud records planque's id for that
   * version. Whether it's approved is tafs cloud's answer alone. A subscription made but not recorded is kept, to record again.
   */
  suspend fun approveKnobEvent(tafId: String, triggerId: String): Boolean {
    val tenant = kiffTenant
    val cloudId = _state.value.taf(tafId)?.cloudId
    if (_state.value.mode != Mode.kiff || !_state.value.manages(tafId) || tenant == null || cloudId == null) return false
    if (!_state.value.sessionHasKey || !_state.value.planqueReady) return knobError(tafId, Words.Setup.Knob.eventsNotSetUp)
    val shown = _state.value.knobs[tafId] ?: return false
    val item = shown.item(triggerId)?.takeIf { it.kind == "app_event" && it.ok == "approve" } ?: return false
    val approval = item.approval?.takeIf { it.needed } ?: return false
    val version = shown.version ?: return false
    val generation = accountGeneration
    if (!approvingEvents.add(tafId)) return false
    _state.update { it.copy(knobErrors = it.knobErrors - tafId, approvingEvent = it.approvingEvent + tafId) }
    try {
      // Read the live dial before asking: a draft or an older dial must not subscribe other filters.
      val live = cloud.knob(cloudId)
      if (generation != accountGeneration) return false
      val current = live.item(triggerId)?.approval
      if (live.version != version || current == null || current.grant != approval.grant || current.event != approval.event ||
        bot.tafs.app.kiff.Jcs.canonical(current.config) != bot.tafs.app.kiff.Jcs.canonical(approval.config)
      ) {
        loadKnob(tafId)
        return knobError(tafId, bot.tafs.app.model.KnobWords.EVENT_CHANGED)
      }
      if (current.state == "approved") {
        eventReceipts.remove(tafId to triggerId)
        loadKnob(tafId)
        return true
      }
      val key = tafId to triggerId
      val receipt = eventReceipts[key]?.takeIf { it.version == version && it.approval == approval } ?: run {
        refreshConnections()
        if (generation != accountGeneration) return false
        val grant = _state.value.grants.firstOrNull { it.id == approval.grant && it.connected && it.request.agent == tafId }
        val account = grant?.account?.takeIf { it.isNotBlank() } ?: return knobError(tafId, bot.tafs.app.model.KnobWords.CONNECT_FIRST)
        val filters = PlanqueClient.sha256Hex(bot.tafs.app.kiff.Jcs.canonical(approval.config).toByteArray())
        val signer = signer
        try {
          signer.sign("tafs.event.v1|$tenant|$cloudId|$version|$triggerId|${approval.grant}|${approval.event}|$filters".toByteArray(), item.words)
        } catch (e: SignerException) {
          return knobError(tafId, notSigned(e, Words.Setup.Knob.notApprovedUnconfirmed))
        }
        if (generation != accountGeneration) return false
        planque.tenant = tenant
        planque.deviceLoader = { kiff.thisDeviceId() }
        planque.signer = { signer.signConfirmed(it) }
        val made = try {
          planque.subscribeEvent(approval.grant, account, approval.event, approval.config)
        } finally {
          planque.signer = null
        }
        if (generation != accountGeneration) return false
        val id = made.key ?: return knobError(tafId, Words.Setup.Knob.noSubscription)
        EventReceipt(version, approval, id).also { eventReceipts[key] = it }
      }
      val updated = cloud.recordKnobSubscription(cloudId, triggerId, receipt.subscription, receipt.version)
      if (generation != accountGeneration) return false
      _state.update { it.copy(knobs = it.knobs + (tafId to updated)) }
      val recorded = updated.item(triggerId)?.approval
      if (recorded?.state != "approved" || recorded.subscription != receipt.subscription) return knobError(tafId, bot.tafs.app.model.KnobWords.NOT_RECORDED)
      eventReceipts.remove(key)
      // Shown as tafs cloud has it now, the waiting version included.
      loadKnob(tafId)
      return true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation != accountGeneration) return false
      val said = (e as? PlanqueError.Server)?.message ?: (e as? bot.tafs.app.kiff.CloudError)?.takeIf { it.status == 409 && it.words == null }?.message
      return knobError(tafId, Words.Setup.Knob.notApproved(why = said ?: ErrorWords.of(e)))
    } finally {
      approvingEvents.remove(tafId)
      if (generation == accountGeneration) _state.update { it.copy(approvingEvent = it.approvingEvent - tafId) }
    }
  }

  private val approvingEvents = mutableSetOf<String>()

  private fun knobError(tafId: String, words: String): Boolean {
    _state.update { it.copy(knobErrors = it.knobErrors + (tafId to words)) }
    return false
  }

  /** The Record's search box words, read once from tafs cloud; the app's own stand in until they come. */
  suspend fun loadRecordHint() {
    if (_state.value.mode != Mode.kiff || _state.value.recordHint != null) return
    val hint = try { cloud.searchRecord("", limit = 1).hint } catch (e: CancellationException) { throw e } catch (_: Exception) { return }
    if (hint.isNotBlank()) _state.update { it.copy(recordHint = hint) }
  }

  /**
   * Searches the Record across the person's tafs (`GET /v1/record?q=`). A blank [q] clears the search and shows the Record as before.
   * [more] reads the next page of the same search; a cursor tafs cloud refuses (409, a call made since) searches again from the top.
   */
  suspend fun searchRecord(q: String, more: Boolean = false) {
    if (_state.value.mode != Mode.kiff) return
    val asked = q.trim()
    if (asked.isEmpty()) { _state.update { it.copy(recordSearch = null) }; return }
    val current = _state.value.recordSearch?.takeIf { it.q == asked }
    if (more && (current == null || current.loading || current.nextCursor == null)) return
    val cursor = if (more) current?.nextCursor else null
    _state.update { it.copy(recordSearch = (if (more) current!! else RecordSearch(asked)).copy(loading = true, error = null)) }
    val generation = accountGeneration
    val page = try {
      cloud.searchRecord(asked, cursor)
    } catch (e: CancellationException) {
      throw e
    } catch (e: CloudError) {
      if (generation != accountGeneration) return
      if (more && e.status == 409) { searchRecord(asked); return }
      _state.update { s -> s.copy(recordSearch = s.recordSearch?.takeIf { it.q == asked }?.copy(loading = false, error = ErrorWords.of(e))) }
      return
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { s -> s.copy(recordSearch = s.recordSearch?.takeIf { it.q == asked }?.copy(loading = false, error = ErrorWords.of(e))) }
      return
    }
    if (generation != accountGeneration) return
    _state.update { s ->
      val now = s.recordSearch?.takeIf { it.q == asked } ?: return@update s
      s.copy(recordSearch = now.copy(pages = (if (more) now.pages else emptyList()) + page, loading = false, error = null), recordHint = page.hint.ifBlank { s.recordHint ?: "" }.ifBlank { null })
    }
  }

  /** Opens one case from the Record: every call about [about], oldest first, and the step that didn't go through. */
  suspend fun openRecordCase(about: String) {
    if (_state.value.mode != Mode.kiff || about.isBlank()) return
    val generation = accountGeneration
    _state.update { it.copy(recordCase = bot.tafs.app.kiff.RecordCase(about), recordCaseError = null) }
    val case = try {
      cloud.recordCase(about)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(recordCaseError = ErrorWords.of(e)) }
      return
    }
    if (generation == accountGeneration) _state.update { s -> if (s.recordCase?.about == about) s.copy(recordCase = case) else s }
  }

  fun closeRecordCase() = _state.update { it.copy(recordCase = null, recordCaseError = null) }

  /** The receipts of [from] to [to] for an accountant, as tafs cloud exports them; why not, in words, when it can't. */
  suspend fun exportReceipts(from: String, to: String): ReceiptsResult {
    if (_state.value.mode != Mode.kiff) return ReceiptsResult.NotExported(Words.Error.signInFirst)
    return try {
      ReceiptsResult.Ready(cloud.exportReceipts(from, to))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      ReceiptsResult.NotExported(ErrorWords.of(e))
    }
  }

  /**
   * How long a case of the taf may wait before its phones are told (`PUT …/promise`), for whoever manages it; then its work is read
   * again, which says the promise as tafs cloud keeps it. A refusal says why on the work's panel.
   */
  suspend fun setPromise(tafId: String, hours: Int?) {
    if (_state.value.mode != Mode.kiff || !_state.value.manages(tafId)) return
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    val generation = accountGeneration
    try {
      cloud.setPromise(cloudId, hours)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(workErrors = it.workErrors + (tafId to Words.Work.answerWithinNotSet(why = ErrorWords.of(e)))) }
      return
    }
    if (generation != accountGeneration) return
    loadWork(tafId)
  }

  /**
   * Where the taf's results go, of tafs' own places (`PUT …/results`), for whoever manages it; then the dial is read again, which
   * says which places are on. tafs cloud keeps at least one on.
   */
  suspend fun setResults(tafId: String, places: List<String>) {
    if (_state.value.mode != Mode.kiff || !_state.value.manages(tafId)) return
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    val generation = accountGeneration
    val kept = try {
      cloud.setResults(cloudId, places)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(knobErrors = it.knobErrors + (tafId to Words.Setup.Knob.notChanged(why = ErrorWords.of(e)))) }
      return
    }
    if (generation != accountGeneration) return
    // The live places, as tafs cloud now keeps them: what the switches show.
    _state.update { s -> s.copy(knobErrors = s.knobErrors - tafId, tafs = s.tafs.map { if (it.id == tafId) it.copy(results = kept ?: places) else it }) }
    loadKnob(tafId)
  }

  /** Removes a follow-up the taf set itself, from the dial's face, then reads the dial again. */
  suspend fun removeFollowUp(tafId: String, followUp: String) {
    if (_state.value.mode != Mode.kiff || !_state.value.manages(tafId)) return
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    val generation = accountGeneration
    try {
      cloud.removeFollowUp(cloudId, followUp)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(knobErrors = it.knobErrors + (tafId to Words.Setup.Knob.notRemoved(why = ErrorWords.of(e)))) }
      return
    }
    if (generation == accountGeneration) _state.update { it.copy(knobErrors = it.knobErrors - tafId) }
    loadKnob(tafId)
  }

  /**
   * A taf's dial (`GET /v1/tafs/{id}/knob`), read quietly: with none, its Doors tab keeps the doors' own dial. While a change
   * waits to go live (tafs cloud's `pending`), the dial shows that version (`?version=N`), so the next − or + builds on it and a
   * door or ⏻ doesn't lose it from view. [version] reads that one directly.
   */
  suspend fun loadKnob(tafId: String, version: Int? = null) {
    if (_state.value.mode != Mode.kiff) return
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    val generation = shownGeneration()
    val first = attempt { cloud.knob(cloudId, version) } ?: run {
      // A version tafs cloud made that couldn't be read: the dial stays behind it.
      if (version != null && generation == shownGeneration()) _state.update { it.copy(knobBehind = it.knobBehind + (tafId to version)) }
      return
    }
    val waiting = first.pending?.version
    val want = version ?: waiting
    val knob = if (version == null && waiting != null && first.shown != waiting) {
      try {
        cloud.knob(cloudId, waiting)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        first
      }
    } else first
    val behind = want != null && knob.shown != want
    if (generation == shownGeneration()) _state.update {
      it.copy(knobs = it.knobs + (tafId to knob), knobBehind = if (behind) it.knobBehind + (tafId to want) else it.knobBehind - tafId)
    }
  }

  /**
   * − or + on the dial: the stop's next choice, sent as its whole `setting` to `PUT …/knob`, which makes the taf's next version.
   * That version is then read (`?version=N`), so the next − or + builds on it. Nothing runs from it until the owner makes the
   * changes live ([confirmKnobChange]).
   */
  suspend fun changeKnobSize(tafId: String, setting: kotlinx.serialization.json.JsonObject): Boolean {
    if (_state.value.mode != Mode.kiff || !_state.value.manages(tafId)) return false
    // A change not read back yet: a choice from the dial before it would undo it.
    if (tafId in _state.value.knobBehind) return false
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return false
    val generation = accountGeneration
    _state.update { it.copy(knobErrors = it.knobErrors - tafId) }
    return try {
      val change = cloud.changeKnob(cloudId, setting)
      if (generation != accountGeneration) return false
      _state.update { it.copy(knobBehind = it.knobBehind + (tafId to change.version)) }
      loadKnob(tafId, change.version)
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // A form the scheduler can't keep exactly: tafs cloud's words name the nearest one.
      if (generation == accountGeneration) _state.update { it.copy(knobErrors = it.knobErrors + (tafId to Words.Setup.Knob.notChanged(why = ErrorWords.of(e)))) }
      false
    }
  }

  /**
   * Makes the dial's change live: the version tafs cloud says waits (`pending`, from − and + or Coach's draft), switched as any
   * version is (with the fingerprint where its Card changes).
   */
  suspend fun confirmKnobChange(tafId: String): Boolean {
    val pending = _state.value.knobs[tafId]?.pending ?: return false
    loadVersions(tafId)
    val version = _state.value.versions[tafId]?.firstOrNull { it.n == pending.version } ?: run {
      _state.update { it.copy(knobErrors = it.knobErrors + (tafId to Words.Setup.Knob.versionNotRead(n = pending.version.toString()))) }
      return false
    }
    if (!useVersion(version, tafId)) return false
    loadKnob(tafId)
    return true
  }

  /**
   * Connect from Connect apps: starts the app's grant (or, for one to sign in again, takes the grant it has) and opens its
   * approval over the list. A vendor that takes an API key (`needs_key`) takes it on the same sheet ([startGiveKey]).
   */
  suspend fun startConnectApp(app: bot.tafs.app.kiff.ConnectApps.App, tafId: String? = null) {
    if (_state.value.mode != Mode.kiff || _state.value.startingApp != null) return
    if (app.needsReconnect && app.grant != null) {
      refreshConnections()
      _state.update { it.copy(listApproval = app.grant, listApprovalNeedsKey = false) }
      return
    }
    // The taf it's for, by its tafs cloud id: the one chosen, or the only one this person manages.
    val taf = tafId?.let { id -> _state.value.taf(id) } ?: _state.value.connectableTafs.singleOrNull()
    val cloudId = taf?.cloudId ?: return
    val generation = accountGeneration
    _state.update { it.copy(startingApp = app.entry, connectAppsError = null) }
    try {
      val started = cloud.startConnect(app.entry, cloudId)
      if (generation != accountGeneration) return
      refreshConnections()
      if (generation == accountGeneration) _state.update { it.copy(listApproval = started.grant, listApprovalNeedsKey = started.needsKey) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(connectAppsError = Words.Connect.appNotStarted(app = app.title, why = ErrorWords.of(e))) }
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(startingApp = null) }
    }
  }

  /** Approve on the sheet over Connect apps: the same as Coach's card (KIFF's words, the signature, the vendor's sign-in), then the list again. */
  fun startListApproval(grantId: String, shownDigest: String) {
    scope.launch { approveFromList(grantId, shownDigest) }
  }

  /** The approval from Connect apps, run to its end: then what came of it is said at once, and the list read again. */
  suspend fun approveFromList(grantId: String, shownDigest: String) {
    run {
      when (val result = connect(ConnectWords.cardKey(grantId), grantId, shownDigest)) {
        is Connected.Done, is Connected.AlreadyApproved -> {
          note(ConnectWords.cardKey(grantId), null)
          // Said at once: signed in, and what comes next, as planque says the grant is now.
          val done = (result as? Connected.Done)?.grant
          _state.update { it.copy(listApproval = null, connectedNow = done?.takeIf { g -> g.listed || g.connected }) }
          loadConnectApps()
        }
        is Connected.Failed -> note(ConnectWords.cardKey(grantId), result.why)
        Connected.Stopped -> Unit
      }
    }
  }

  fun dismissConnectedNow() = _state.update { it.copy(connectedNow = null) }

  fun closeListApproval() = _state.update { it.copy(listApproval = null, listApprovalNeedsKey = false, keyForCard = null, keyReceipt = null) }

  /** The secure sheet's words for [grantId], read from tafs cloud when the key sheet opens, once per grant. */
  suspend fun loadSecureSheet(grantId: String) {
    if (_state.value.mode != Mode.kiff || _state.value.secureSheets.containsKey(grantId)) return
    _state.update { it.copy(secureSheetErrors = it.secureSheetErrors - grantId) }
    val generation = accountGeneration
    try {
      // This phone's signed grants read: tafs cloud reads the grant from planque only as this phone.
      planque.tenant = kiffTenant; planque.deviceLoader = { kiff.thisDeviceId() }
      val proof = runCatching { planque.grantsReadProof { signer.signConfirmed(it) } }.getOrNull()
      val sheet = cloud.connectSheet(grantId, proof)
      if (generation == accountGeneration) _state.update { it.copy(secureSheets = it.secureSheets + (grantId to sheet)) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) {
        _state.update { it.copy(secureSheetErrors = it.secureSheetErrors + (grantId to Words.Connect.approveNotRead(why = ErrorWords.of(e)))) }
      }
    }
  }

  /** Disconnect from the receipt: the connection goes, as from Connect apps, and the sheet closes. */
  fun disconnectFromReceipt(grantId: String) {
    closeListApproval()
    scope.launch { if (disconnect(grantId)) loadConnectApps() }
  }

  /** "Allow this from now on" tapped on a held call: the sheet opens, and tafs cloud makes the offer now, never before. */
  fun openAllow(call: HeldCall) {
    scope.launch { openAllowNow(call) }
  }

  /** The sheet open on [call], and tafs cloud's offer read: true when it's what was offered and can be signed. */
  suspend fun openAllowNow(call: HeldCall): Boolean {
    _state.update { it.copy(allow = AllowSheetState(call, busy = true)) }
    return loadAllow(round = false)
  }

  /** Round up to the figure the offer named: tafs cloud makes the rounded draft, shown and checked the same way. */
  fun roundAllow() {
    if (_state.value.allow?.busy != false) return
    _state.update { s -> s.copy(allow = s.allow?.copy(busy = true, error = null)) }
    scope.launch { loadAllow(round = true) }
  }

  fun setAllowSendHeld(on: Boolean) = _state.update { s -> s.copy(allow = s.allow?.copy(sendHeld = on)) }

  fun closeAllow() = _state.update { it.copy(allow = null) }

  fun startConfirmAllow() {
    scope.launch { confirmAllow() }
  }

  /**
   * tafs cloud's offer for the open sheet's held call, then KIFF's draft, read by this phone: the sheet shows KIFF's own lines
   * under tafs cloud's words, and signs only when the draft is what was offered ([bot.tafs.app.model.HoldAllow.matches]).
   */
  suspend fun loadAllow(round: Boolean): Boolean {
    val open = _state.value.allow ?: return false
    val call = open.call
    val cloudId = _state.value.holder(call.tafId)?.cloudId
    val exception = call.kiffId
    if (cloudId == null || exception == null) {
      _state.update { s -> s.copy(allow = s.allow?.copy(busy = false, error = Words.NeedsYou.answerNotTaken)) }
      return false
    }
    val generation = accountGeneration
    try {
      val answer = cloud.allowOffer(cloudId, exception, round)
      val offer = answer.offer
      val draft = offer?.draft?.takeIf { it.isNotEmpty() }?.let { kiff.draft(it) }
      if (generation != accountGeneration) return false
      val matches = offer != null && draft != null && bot.tafs.app.model.HoldAllow.matches(draft, offer, call.tafId, call.kiffId, call.proposed.action)
      // A late answer for a call whose sheet is no longer open changes nothing. Rounding up keeps the person's switch.
      _state.update { s ->
        val now = s.allow
        if (now == null || now.call.id != call.id) s
        else s.copy(allow = now.copy(
          answer = answer, draft = draft, matches = matches, busy = false, error = null,
          sendHeld = if (round) now.sendHeld else offer?.sendHeld ?: true, rounded = round,
        ))
      }
      return matches
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) {
        _state.update { s ->
          val now = s.allow
          if (now == null || now.call.id != call.id) s
          else s.copy(allow = now.copy(busy = false, error = Words.Version.cardNotRead(why = ErrorWords.of(e))))
        }
      }
      return false
    }
  }

  /**
   * One signature: KIFF's draft as this phone read it, confirmed with the approval key, with the held call when the switch is on,
   * so KIFF lets it go under the same signature. With it off, the Card changes and the call still waits for its own answer.
   */
  suspend fun confirmAllow(): Boolean {
    val open = _state.value.allow ?: return false
    val draft = open.draft ?: return false
    if (!open.matches || open.busy || draft.confirmed) return false
    val tenant = kiffTenant ?: return false
    if (_state.value.approvalKeyLost) {
      _state.update { s -> s.copy(allow = s.allow?.copy(error = KiffClient.APPROVAL_KEY_LOST)) }
      return false
    }
    val hold = if (open.sendHeld) holdAnswered(open.call) else null
    val generation = accountGeneration
    _state.update { s -> s.copy(allow = s.allow?.copy(busy = true, error = null)) }
    try {
      confirmGrant("tafs.cards.v1|$tenant|${draft.id}|${draft.version}", Words.NeedsYou.allowTitle)
      if (generation != accountGeneration) return false
      val signer = signer
      kiff.setupSigner = { signer.signConfirmed(it) }
      if (hold == null) kiff.confirmDraft(draft.id, draft.version) else kiff.confirmDraft(draft.id, draft.version, hold)
    } catch (e: SignerException) {
      if (generation == accountGeneration) {
        _state.update { s -> s.copy(allow = s.allow?.copy(busy = false, error = notSigned(e, Words.Common.notDoneUnconfirmed))) }
      }
      return false
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) {
        _state.update { s -> s.copy(allow = s.allow?.copy(busy = false, error = Words.Version.cardNotRead(why = ErrorWords.of(e)))) }
      }
      return false
    } finally {
      kiff.setupSigner = null
    }
    if (generation != accountGeneration) return false
    closeAllow()
    // The Card changed, and with the switch on the call went: Needs you and the taf read again.
    refresh()
    return true
  }

  /**
   * Coach's card for an app connected with a key: its key screen, the Connect sheet's own, full screen and outside the chat, so
   * a secret is never typed into Coach's conversation. Given there, the card is answered ([startGiveKey]).
   */
  fun openCardKeySheet(c: SetupComponent, shownDraft: String?) {
    val grant = c.grant ?: return
    _state.update { it.copy(listApproval = grant, listApprovalNeedsKey = true, keyForCard = KeyForCard(c, shownDraft)) }
  }

  /**
   * Replace a key-based connection's key (gap 13): the key sheet over Connect apps, for this grant. planque checks the signed
   * key as for a first one; a grant it won't take a key for now says why there.
   */
  fun openReplaceKey(grant: bot.tafs.app.kiff.Grant) {
    if (grant.takesKey) _state.update { it.copy(listApproval = grant.id, listApprovalNeedsKey = true) }
  }

  /** Give the key on the sheet over Connect apps, run by the model as an approval is; then the list again. */
  fun startGiveKey(grantId: String, shownDigest: String, key: String) {
    scope.launch { giveKeyOnSheet(grantId, shownDigest, key) }
  }

  /**
   * The key given on the key sheet. Opened from Coach's card ([openCardKeySheet]): the key, then that card's answer, as an
   * approval. Else the key alone, then Connect apps' list again. The sheet closes once it went.
   */
  suspend fun giveKeyOnSheet(grantId: String, shownDigest: String, key: String): Boolean {
    val card = _state.value.keyForCard?.takeIf { it.card.grant == grantId }
    val given = if (card != null) approveCoachWithKey(card.card, shownDigest, card.draft, key) else giveKey(grantId, shownDigest, key)
    if (!given) return false
    // With tafs cloud's secure sheet open, its receipt shows there until the person closes it; else the sheet closes now.
    if (_state.value.listApproval == grantId && _state.value.secureSheets.containsKey(grantId)) {
      _state.update { it.copy(keyReceipt = KeyReceipt(grantId, bot.tafs.app.model.SecureSheetWords.last4(key))) }
    } else {
      closeListApproval()
    }
    if (card == null) loadConnectApps()
    return true
  }

  /**
   * Gives an API-key connection its key: the same checks as approving one (the digest the owner was shown, KIFF's words for its
   * profile), confirmed with the approval key, then sent to planque signed by this device. The key goes to planque once and is
   * kept nowhere here. What went wrong is said on the sheet.
   */
  suspend fun giveKey(grantId: String, shownDigest: String, key: String): Boolean {
    val noteKey = ConnectWords.cardKey(grantId)
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || !_state.value.sessionHasKey || tenant == null || !_state.value.planqueReady) {
      note(noteKey, Words.Connect.notSetUpHere)
      return false
    }
    if (_state.value.approvalKeyLost) return false.also { note(noteKey, KiffClient.APPROVAL_KEY_LOST) }
    if (key.isBlank() || _state.value.connecting != null) return false
    _state.update { it.copy(connecting = grantId, coachNotes = it.coachNotes - noteKey) }
    val generation = accountGeneration
    planque.tenant = tenant
    planque.deviceLoader = { kiff.thisDeviceId() }
    val signer = signer
    planque.signer = { signer.signConfirmed(it) }
    try {
      val grant = try {
        planque.grants().firstOrNull { it.id == grantId }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return false.also { note(noteKey, Words.Connect.approveNotRead(why = ErrorWords.of(e))) }
      } ?: return false.also { note(noteKey, Words.Connect.noSuchConnection) }
      if (!grant.digestMatches) return false.also { note(noteKey, PlanqueError.DigestMismatch.message!!) }
      if (grant.digest != shownDigest) {
        _state.update { s -> s.copy(grants = s.grants.map { if (it.id == grant.id) grant else it }) }
        return false.also { note(noteKey, VersionWords.OUT_OF_DATE_CARD) }
      }
      val words = grant.request.profile?.let {
        _state.value.profileWordsFor(grant) ?: return false.also { note(noteKey, Words.Connect.profileWordsUnread) }
      }
      try {
        signer.sign("tafs.connect.v1|$tenant|${grant.id}|${grant.digest}".toByteArray(), bot.tafs.app.model.ConnectionWords.giveKeyPrompt(grant, _state.value.connectApps) { _state.value.taf(it)?.name })
      } catch (_: SignerException) {
        return false.also { note(noteKey, Words.Common.notDoneUnconfirmed) }
      }
      if (generation != accountGeneration) return false
      try {
        planque.giveKey(grant, key, words)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return false.also { note(noteKey, Words.Connect.keyNotGiven(why = ErrorWords.of(e))) }
      }
      refreshConnections()
      return true
    } finally {
      planque.signer = null
      if (generation == accountGeneration) _state.update { it.copy(connecting = null) }
    }
  }

  /**
   * KIFF's words for [grantId]'s profile (KIFF's profile contract, `kiff.words.v1`), read with this person's session and checked against their digests, so
   * the card shows them before any approval. Nothing is read for a grant with no profile.
   */
  suspend fun loadProfileWords(grantId: String) {
    if (_state.value.mode != Mode.kiff) return
    val grant = _state.value.grants.firstOrNull { it.id == grantId } ?: return
    val profile = grant.request.profile ?: return
    // Read once per profile, not per connection: tools added to it change its profile under the same id.
    if (_state.value.profileWordsFor(grant) != null) return
    // An earlier profile's refusal isn't this one's.
    _state.update { it.copy(profileWordsErrors = it.profileWordsErrors - grantId) }
    val generation = accountGeneration
    val result = try {
      Result.success(kiff.profileWords(profile))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }
    if (generation != accountGeneration) return
    result.onSuccess { w -> _state.update { it.copy(profileWords = it.profileWords + (grantId to w), profileWordsErrors = it.profileWordsErrors - grantId) } }
      .onFailure { e ->
        val words = if (e is bot.tafs.app.kiff.ProfileWords.Mismatch) e.message!! else Words.Connect.profileWordsNotRead(why = ErrorWords.of(e))
        _state.update { it.copy(profileWordsErrors = it.profileWordsErrors + (grantId to words)) }
      }
  }

  suspend fun connect(
    cardId: String, grantId: String, shownDigest: String?, draftId: String? = null, shownDraft: String? = null,
    /** Sign in again (gap 13): a connected grant goes through its vendor's sign-in again, kept apart until this phone confirms it. */
    again: Boolean = false,
  ): Connected {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || !_state.value.sessionHasKey || tenant == null || !_state.value.planqueReady) {
      return Connected.Failed(Words.Connect.notSetUpHereIphone, told = "Connecting tools isn't set up for this account from this phone. Start from tafs on an iPhone.")
    }
    if (_state.value.approvalKeyLost) return Connected.Failed(KiffClient.APPROVAL_KEY_LOST)
    if (_state.value.connecting != null) {
      note(cardId, Words.Connect.anotherApproval)
      return Connected.Stopped
    }
    _state.update { it.copy(connecting = grantId, coachNotes = it.coachNotes - cardId) }
    val generation = accountGeneration
    planque.tenant = tenant
    planque.deviceLoader = { kiff.thisDeviceId() }
    val signer = signer
    val sign: (ByteArray) -> String = { signer.signConfirmed(it) }
    planque.signer = sign
    kiff.tenant = tenant
    try {
      // What the owner approves, read as it is now.
      val grant: Grant
      var draft: AgentDraft? = null
      try {
        grant = planque.grants().firstOrNull { it.id == grantId } ?: return Connected.Failed(Words.Connect.noSuchConnection, told = "There's no connection with that id.")
        if (draftId != null && grant.connected) draft = kiff.draft(draftId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return Connected.Failed(Words.Connect.approveNotRead(why = ErrorWords.of(e)), "couldn't read what to approve: ${e.message}")
      }
      if (grant.connected && !again) {
        // The second approval: the vendor is connected, and the taf's Cards over its tools wait as a draft. One confirmation confirms them.
        // Done only after a signature made here: a connection already connected with no Cards left to confirm signs nothing, so it
        // isn't approved. Coach hears that, and asks for what it needs as a new grant.
        val d = draft
        if (d == null) return Connected.Failed(CoachWords.nothingToApprove(grant.vendor), "${grant.vendor} is already connected; nothing new to approve")
        // Its Card confirmed already: a real approval, signed earlier. Said as that, never as done here, nor as failed.
        if (d.confirmed) return Connected.AlreadyApproved(grant)
        if (d.version != shownDraft) {
          _state.update { it.copy(setupDrafts = it.setupDrafts + (d.id to d)) }
          note(cardId, VersionWords.OUT_OF_DATE_CARD)
          return Connected.Stopped
        }
        try {
          confirmGrant("tafs.cards.v1|$tenant|${d.id}|${d.version}", Words.Connect.Prompt.giveCard(agent = d.agentId))
          if (generation != accountGeneration) return Connected.Stopped
          kiff.setupSigner = sign
          kiff.confirmDraft(d.id, d.version)
        } catch (e: SignerException) {
          note(cardId, notSigned(e, Words.Common.notDoneUnconfirmed))
          return Connected.Stopped
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return Connected.Failed(Words.Connect.cardsNotIssued(why = ErrorWords.of(e)), "KIFF didn't issue the taf's Cards: ${e.message}")
        }
        return Connected.Done(grant)
      }
      // A grant its vendor signed out (needs_reconnect) is approved and signed in again, as a new one is.
      // A connected one signs in again in place (gap 13): planque keeps the sign-in in use until this phone confirms the new one.
      if (grant.status !in setOf("pending", "approved", "signed_in", "needs_reconnect") && !(again && grant.connected)) {
        return Connected.Failed(
          grant.status.replace('_', ' ').let { status -> grant.reason?.let { Words.Connect.connectionIsBecause(status = status, reason = it) } ?: Words.Connect.connectionIs(status = status) },
          told = "This connection is ${grant.status.replace('_', ' ')}" + (grant.reason?.let { ": $it" } ?: "."),
        )
      }
      if (!grant.digestMatches) return Connected.Failed(PlanqueError.DigestMismatch.message!!)
      if (grant.digest != shownDigest) {
        _state.update { s -> s.copy(grants = if (s.grants.any { it.id == grant.id }) s.grants.map { if (it.id == grant.id) grant else it } else s.grants + grant) }
        note(cardId, VersionWords.OUT_OF_DATE_CARD)
        return Connected.Stopped
      }
      // A connection with a profile is approved only with KIFF's words for exactly that profile, as the owner read them.
      val words = grant.request.profile?.let {
        _state.value.profileWordsFor(grant) ?: return Connected.Failed(Words.Connect.profileWordsUnread, told = "KIFF's words for this connection aren't read yet. Nothing was approved.")
      }
      try {
        signer.sign("tafs.connect.v1|$tenant|${grant.id}|${grant.digest}".toByteArray(), bot.tafs.app.model.ConnectionWords.connectPrompt(grant, _state.value.connectApps) { _state.value.taf(it)?.name })
      } catch (_: SignerException) {
        note(cardId, Words.Common.notDoneUnconfirmed)
        return Connected.Stopped
      }
      if (generation != accountGeneration) return Connected.Stopped
      try {
        val approved = planque.approve(grant, words)
        val url = approved.signInUrl
        if (url == null) {
          // planque already holds the sign-in: approving the tools Setup chose from the vendor's list connected them, with no second sign-in.
          if (approved.status != "connected" && approved.status != "listed") {
            refreshConnections()
            return Connected.Failed(Words.Connect.noSignInLink(status = approved.status), told = "The connection came back ${approved.status} with no sign-in link. Nothing was connected.")
          }
          refreshConnections()
          return Connected.Done(_state.value.grants.firstOrNull { it.id == grant.id && it.connected } ?: grant.marked(true))
        }
        val back = vendorSignIn.signIn(url)
        val signedIn = PlanqueClient.signedIn(back)
        if (signedIn == null || signedIn.grant != grant.id || signedIn.tenant != tenant) {
          return Connected.Failed(Words.Connect.noConfirmationCode(vendor = grant.vendor), told = "The sign-in at ${grant.vendor} came back without its confirmation code. Nothing was connected.")
        }
        if (generation != accountGeneration) return Connected.Stopped
        // Back from the vendor: signed in only once planque says the grant is connected, never because the page closed.
        _state.update { it.copy(connectStep = CoachWords.CHECKING_SIGN_IN) }
        val connected = planque.confirm(grant.id, signedIn.code)
        refreshConnections()
        // Listed is signed in too: planque holds the sign-in and listed the vendor's tools for Setup to choose from.
        if (!connected.connected && !connected.listed) return Connected.Failed(CoachWords.notConnected(grant.vendor, connected.status), "the sign-in at ${grant.vendor} came back, but planque says the connection is ${connected.status}")
        return Connected.Done(connected)
      } catch (e: VendorSignInCancelled) {
        refreshConnections()
        return Connected.Failed(Words.Connect.signInClosedNothingConnected, told = "The sign-in was closed. Nothing was connected.")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        refreshConnections()
        return Connected.Failed(Words.Connect.notConnected(why = ErrorWords.of(e)), "Not connected: ${e.message}")
      }
    } finally {
      planque.signer = null
      kiff.setupSigner = null
      if (generation == accountGeneration) _state.update { it.copy(connecting = null, connectStep = null) }
    }
  }

  /**
   * The owner approves a card Coach asked about: the connection, or the Cards over it. What it came to goes back to Coach as the
   * card's answer: "approved", or "failed: why" so Coach can say what went wrong and ask again. Nothing is told for a step the
   * owner didn't confirm or whose terms changed on the screen.
   */
  /**
   * The card's approval, run by the model, not the screen: opening the vendor's page can tear the screen down, and the flow
   * (planque's confirm, the answer to Coach, reading the thread, following the run) goes on whatever happens to it.
   */
  fun startApproval(c: SetupComponent, shownDigest: String?, shownDraft: String?) {
    scope.launch { approveCoach(c, shownDigest, shownDraft) }
  }

  /**
   * Coach's approval card for an app connected with a key (planque's `api_key`), not connected yet, given on the key sheet the card
   * opened ([openCardKeySheet]): planque approves such a grant only with its key. So the key goes first, as on the Connect sheet ([giveKey]: the shown digest checked, confirmed with
   * the approval key, sent signed by this device, kept nowhere here). Then the card is answered as any approval is: tafs cloud
   * finds it connected and Coach goes on, or asks for its Cards. A key that didn't go answers nothing, and says why on the card.
   */
  suspend fun approveCoachWithKey(c: SetupComponent, shownDigest: String, shownDraft: String?, key: String): Boolean {
    val grantId = c.grant ?: return false
    // One fingerprint for the key and its Card (docs/secure-connection.md): asked for only by a phone with an approval key, on
    // a card with a Card's draft. tafs cloud offers it (`key_and_cards`) or answers the two steps, and then nothing was signed.
    if (c.draft != null && sessionHasApprovalKey && !_state.value.approvalKeyLost) {
      if (approveCoach(c, shownDigest, shownDraft, together = key)) {
        return _state.value.grants.firstOrNull { it.id == grantId }?.status == "connected"
      }
    }
    if (!giveKey(grantId, shownDigest, key)) return false
    val digest = _state.value.grants.firstOrNull { it.id == grantId }?.digest ?: shownDigest
    approveCoach(c, digest, shownDraft)
    return true
  }

  /** The sign-in card's Sign in again, run by the model for the same reason as [startApproval]. */
  fun startSignInAgain(c: SetupComponent) {
    scope.launch { signInAgain(c) }
  }

  /** A taf page's Sign in again (a connection its vendor signed out), run by the model for the same reason. */
  /** Re-read the signed grant before opening its existing device approval flow. */
  fun startReconnectId(id: String) {
    viewModelScope.launch {
      val gen = accountGeneration
      refreshConnections()
      if (gen == accountGeneration) _state.value.grants.firstOrNull { it.id == id }?.let { startReconnect(it) }
    }
  }

  fun startReconnect(grant: Grant) {
    scope.launch { reconnect(grant) }
  }

  /**
   * Approve on a Coach card (kiff/tafs#413): tafs cloud decides. The phone sends that Approve was tapped, with what the card
   * showed; tafs cloud reads planque's grant (as this phone) and KIFF's draft and says what needs signing, if anything. With
   * nothing to sign it answers Coach itself (already approved, or failed with why), and the card shows its note. Otherwise the
   * phone signs exactly the message it's given, under the fingerprint, makes the one call it names as given (planque's approve
   * and the vendor's sign-in, or KIFF's draft confirm), and reports what came of it, signed as a setup call. tafs cloud checks
   * the report with KIFF and the service before Coach hears "approved". The phone never builds an approval's answer.
   */
  suspend fun approveCoach(c: SetupComponent, shownDigest: String?, shownDraft: String?, together: String? = null): Boolean {
    val grantId = c.grant ?: return true
    val tenant = kiffTenant
    val cloudId = _state.value.coachCloudId
    if (_state.value.mode != Mode.kiff || !_state.value.sessionHasKey || tenant == null || cloudId == null || !_state.value.planqueReady) {
      note(c.id, CoachWords.NOT_SET_UP_HERE)
      return true
    }
    if (_state.value.approvalKeyLost) { note(c.id, KiffClient.APPROVAL_KEY_LOST); return true }
    if (_state.value.connecting != null) { note(c.id, CoachWords.ANOTHER_APPROVAL); return true }
    _state.update { it.copy(connecting = grantId, coachNotes = it.coachNotes - c.id) }
    val generation = accountGeneration
    planque.tenant = tenant
    planque.deviceLoader = { kiff.thisDeviceId() }
    val signer = signer
    val sign: (ByteArray) -> String = { signer.signConfirmed(it) }
    kiff.tenant = tenant
    val shown = _state.value.coachComponents.lastOrNull { it.id == c.id && it.answer == null } ?: _state.value.coachComponents.lastOrNull { it.id == c.id }
    val session = shown?.session?.ifEmpty { null } ?: _state.value.coachChat
    val key = shown?.ref ?: CoachWords.cardKey(session, c.id)
    try {
      // The read proof: planque shows the grant to tafs cloud only as this phone; it signs and grants nothing there.
      val proof = runCatching { planque.grantsReadProof(sign) }.getOrNull()
      // The words for the profile as it is now: an earlier profile's would name another digest, and tafs cloud refuses it.
      val words = _state.value.grants.firstOrNull { it.id == grantId }?.let { _state.value.profileWordsFor(it) }
      val ask = kotlinx.serialization.json.buildJsonObject {
        put("grant", grantId)
        shownDigest?.let { put("shown_digest", it) }
        c.draft?.let { put("draft", it) }
        shownDraft?.let { put("shown_draft", it) }
        words?.let { put("words", it.approval()) }
        session?.let { put("session", it) }
        // This build signs the key and its Card under one fingerprint: tafs cloud offers that step only when it's asked for.
        if (together != null) put("can", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("key_and_cards")) })
      }
      val decision = cloud.approval(cloudId, c.id, ask, proof)
      if (generation != accountGeneration) return true
      when (decision.outcome) {
        "answered" -> { note(c.id, decision.note); coachAnswered(cloudId, key, decision.session, decision.run) }
        "read_again" -> { note(c.id, decision.note ?: VersionWords.OUT_OF_DATE_CARD); readApprovals() }
        "sign" -> {
          val asked = decision.ask ?: return true
          // Asked for one signature and answered the two steps: nothing is signed here, and the key goes the two-step way.
          if (together != null && asked.step != "key_and_cards") return false
          // The phone is the last check, not a signing service: it signs only the message this card implies, rebuilt from
          // what it holds (its own account, the card's grant or draft, the ask's digest or version), and refuses any other.
          if (asked.message != expectedApprovalMessage(asked, c, tenant, shownDigest, shownDraft)) { note(c.id, CoachWords.ASK_NOT_THIS_CARDS); return true }
          val came: Came
          if (asked.step == "key_and_cards") {
            // The key and its Card under one signature, built here from the two requests: never without the pasted key.
            val key = together ?: run { note(c.id, CoachWords.ASK_NOT_THIS_CARDS); return true }
            came = keyAndCards(asked, c.id, grantId, shownDigest, key, tenant) ?: return true
          } else {
            // tafs cloud's hint that the call takes an approval window (kiff/tafs#494): one fingerprint opens it, unless this
            // phone holds one, and the call goes as a tap. The service decides; a KIFF without windows leaves the step as it was.
            kiff.setupSigner = sign
            var windowed = false
            try {
              windowed = asked.key == "window" && holdWindow(asked.reason)
              // The Cards step grants authority: the approval key signs it where the session has one; a connection, the device's key.
              if (!windowed) {
                if (asked.step == "cards") confirmGrant(asked.message, asked.reason) else signer.sign(asked.message.toByteArray(), asked.reason)
              }
            } catch (e: SignerException) {
              note(c.id, notSigned(e, Words.Common.notDoneUnconfirmed))
              return true
            }
            if (generation != accountGeneration) return true
            planque.signer = sign
            if (windowed) planque.window = kiff.window?.id
            var made = makeAskedCall(asked)
            // planque says the window ended before anything was done: a new one, behind the fingerprint, and the call once more.
            if (windowed && made == Came.WindowEnded) {
              kiff.window = null
              try {
                holdWindow(asked.reason)
              } catch (e: SignerException) {
                note(c.id, notSigned(e, Words.Common.notDoneUnconfirmed))
                return true
              }
              if (generation != accountGeneration) return true
              planque.window = kiff.window?.id
              made = makeAskedCall(asked)
            }
            came = made
          }
          val report = kotlinx.serialization.json.buildJsonObject {
            when (val c2 = came) {
              is Came.Of -> { put("result", c2.result); c2.status?.let { put("status", it) }; c2.why?.let { put("why", it) } }
              Came.WindowEnded -> put("result", "failed")
            }
            session?.let { put("session", it) }
          }
          if (generation != accountGeneration) return true
          refreshConnections()
          // A fresh read proof for the report: the first was spent by the approval's read, and planque takes each signed
          // request once, within minutes (a vendor sign-in can take longer).
          val reportProof = runCatching { planque.grantsReadProof(sign) }.getOrNull()
          val outcome = cloud.approvalReport(cloudId, c.id, asked.id, report, reportProof, tenant, sign)
          if (generation != accountGeneration) return true
          note(c.id, outcome.note)
          coachAnswered(cloudId, key, outcome.session, outcome.run)
        }
        else -> note(c.id, Words.Coach.unknownAnswer)
      }
      return true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation != accountGeneration) return true
      note(c.id, Words.Coach.answerNotReached(why = ErrorWords.of(e)))
      readThread(COACH_CHAT)
      return true
    } finally {
      planque.signer = null
      planque.window = null
      kiff.setupSigner = null
      if (generation == accountGeneration) _state.update { it.copy(connecting = null, connectStep = null) }
      if (generation == accountGeneration) readWindowWords()
    }
  }

  /**
   * The key and its Card under one signature (`kiff.requests.v1`, docs/secure-connection.md). The phone builds both requests
   * itself (planque's key request, as [PlanqueClient.giveKey] sends it, and KIFF's draft confirm) with one `signed_at`, hashes
   * each message, and signs both hashes once with the approval key, behind the fingerprint. Each request goes with that one
   * signature and the other's hash; each service rebuilds its own and checks it. Null when nothing was signed (said on the card).
   */
  private suspend fun keyAndCards(asked: bot.tafs.app.kiff.ApprovalAsk, noteKey: String, grantId: String, shownDigest: String?, key: String, tenant: String): Came? {
    val k = asked.kiff ?: return Came.Of("failed", why = "tafs cloud named no draft to confirm")
    val grant = _state.value.grants.firstOrNull { it.id == grantId } ?: return Came.Of("failed", why = "the connection isn't on this phone")
    // What the person read: the grant as shown, and KIFF's words for its profile when it has one.
    if (!grant.digestMatches || grant.digest != shownDigest) { note(noteKey, VersionWords.OUT_OF_DATE_CARD); return null }
    val words = grant.request.profile?.let { _state.value.profileWordsFor(grant) ?: run { note(noteKey, Words.Connect.profileWordsUnread); return null } }
    planque.tenant = tenant
    planque.deviceLoader = { kiff.thisDeviceId() }
    kiff.tenant = tenant
    var signedAt = ""
    var keyHash = ""
    var confirmHash = ""
    approvalReason = asked.reason
    val signature = try {
      // The time is taken once the person approved, as for any signed call: inside the prompt's success.
      signer.signApproval(asked.reason) {
        signedAt = kiff.nextSignedAt()
        keyHash = KiffClient.messageHash(planque.keyMessage(grant, key, words, signedAt))
        confirmHash = KiffClient.messageHash(kiff.confirmMessage(k.draft, k.version, signedAt))
        KiffClient.requestsMessage(tenant, signedAt, keyHash, confirmHash)
      }
    } catch (e: SignerException) {
      note(noteKey, notSigned(e, Words.Common.notDoneUnconfirmed))
      return null
    }
    val given = try {
      planque.giveKeyTogether(grant, key, words, signedAt, signature, with = confirmHash)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return Came.Of("failed", why = e.message ?: "the key didn't go in")
    }
    // The key first, then the Card: a Card is never confirmed for a key that didn't connect.
    if (given.status != "connected") return Came.Of("not_connected", status = given.status)
    return try {
      kiff.confirmDraftTogether(k.draft, k.version, signedAt, signature, with = keyHash)
      Came.Of("confirmed")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // The key went in and the Card didn't: tafs cloud says so, and Coach's next card asks for the Card alone.
      Came.Of("failed", why = e.message ?: "the Card wasn't confirmed")
    }
  }

  /** What came of the call tafs cloud asked for, as the report says it: never an answer, only what happened. */
  private sealed interface Came {
    data class Of(val result: String, val status: String? = null, val why: String? = null) : Came

    /** planque refused it: signed in an approval window that had ended, so nothing was done. */
    data object WindowEnded : Came
  }

  /**
   * An approval window held at KIFF (kiff/tafs#494): true at once while this phone holds one, else one fingerprint (the approval
   * key, with [reason]'s words) opens one for five minutes. False where it can't: no approval key in KIFF, or a KIFF without
   * windows; the step then signs as it did. Whether the window is open is KIFF's to say, at each call.
   */
  private suspend fun holdWindow(reason: String): Boolean {
    // While Coach drives, the approval window is suspended: every confirming signature asks for the fingerprint (docs/drive.md).
    if (drive.on) return false
    if (!sessionHasApprovalKey || kiff.approvalSigner == null) return false
    if (kiff.window != null) return true
    approvalReason = reason
    val expires = DateTimeFormatter.ISO_INSTANT.format(clock().plusSeconds(WINDOW_SECONDS).truncatedTo(ChronoUnit.SECONDS))
    val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    return try {
      val opened = kiff.openWindow(expires, nonce)
      _state.update { it.copy(windowWords = opened.words) }
      true
    } catch (e: KiffError.Server) {
      // A KIFF from before the window has no such route: the step goes on as before.
      if (e.status == 404 || e.status == 405) false else throw e
    }
  }

  /** KIFF's words for the next approval, while a window is held; none once it's gone. */
  private suspend fun readWindowWords() {
    if (kiff.window == null) {
      _state.update { it.copy(windowWords = null) }
      return
    }
    val read = runCatching { kiff.readWindow() }.getOrNull() ?: return
    if (read.window?.open != true) kiff.window = null
    _state.update { it.copy(windowWords = read.window?.takeIf { w -> w.open }?.words) }
  }

  private var windowClosing: Job? = null

  /**
   * The app went to the background: after 30 seconds there, the window this phone holds is closed at KIFF (kiff/tafs#494).
   * Back sooner, it stays.
   */
  fun wentToBackground() {
    if (kiff.window == null) return
    windowClosing?.cancel()
    windowClosing = scope.launch {
      delay(WINDOW_BACKGROUND_MS)
      closeWindow()
    }
  }

  /** Back on screen: a close waiting on the background is called off, and KIFF's words read again. */
  fun cameToForeground() {
    windowClosing?.cancel()
    windowClosing = null
    if (kiff.window != null) scope.launch { readWindowWords() }
  }

  /** The screen turned off: the window this phone holds is closed at once. */
  fun screenLocked() {
    windowClosing?.cancel()
    if (kiff.window != null) windowClosing = scope.launch { closeWindow() }
  }

  private suspend fun closeWindow() {
    val signer = signer
    // Forgotten here whether or not KIFF heard: a window it still holds ends at its own time, and the next approval asks again.
    runCatching { kiff.closeWindow { signer.signConfirmed(it) } }
    _state.update { it.copy(windowWords = null) }
  }

  /** Makes the one call tafs cloud asked for, as given, and says what came of it. */
  private suspend fun makeAskedCall(asked: bot.tafs.app.kiff.ApprovalAsk): Came = try {
    when (asked.step) {
      "cards" -> {
        val k = asked.kiff ?: return Came.Of("failed", why = "tafs cloud named no draft to confirm")
        kiff.confirmDraft(k.draft, k.version)
        Came.Of("confirmed")
      }
      else -> {
        val p = asked.planque ?: return Came.Of("failed", why = "tafs cloud named no connection to approve")
        val approved = planque.approveAsAsked(p.grant, p.body)
        val url = approved.signInUrl
        if (url == null) {
          // planque already holds the sign-in: approving connected it, with no second sign-in.
          if (approved.status == "connected" || approved.status == "listed") Came.Of(approved.status) else Came.Of("not_connected", status = approved.status)
        } else {
          val back = vendorSignIn.signIn(url)
          val signedIn = PlanqueClient.signedIn(back)
          if (signedIn == null || signedIn.grant != p.grant || signedIn.tenant != planque.tenant) Came.Of("no_code")
          else {
            // Back from the vendor: connected only once planque says so, never because the page closed.
            _state.update { it.copy(connectStep = CoachWords.CHECKING_SIGN_IN) }
            val connected = planque.confirm(p.grant, signedIn.code)
            if (connected.connected || connected.listed) Came.Of("connected") else Came.Of("failed", why = "the sign-in came back, but the connection is ${connected.status}")
          }
        }
      }
    }
  } catch (e: VendorSignInCancelled) {
    Came.Of("sign_in_closed")
  } catch (e: bot.tafs.app.kiff.PlanqueError.WindowRequired) {
    Came.WindowEnded
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Came.Of("failed", why = e.message ?: "it didn't go through")
  }

  /**
   * Coach heard the card's answer from tafs cloud: the card shows it's answered until the thread's words for it come, and
   * the run that paused on it is followed to its end, which reads the thread again.
   */
  private suspend fun coachAnswered(cloudId: String, key: String, session: String?, run: String?) {
    _state.update { it.copy(cardsTaken = it.cardsTaken + key) }
    session?.let { if (_state.value.coachChat == null) settleCoachChat(it) }
    _state.update { it.copy(chatAwaiting = it.chatAwaiting + (COACH_CHAT to clock()), chatEnded = it.chatEnded - COACH_CHAT) }
    readThread(COACH_CHAT)
    run?.let { resumeChat(COACH_CHAT, cloudId, it, again = true) } ?: followChat(COACH_CHAT)
  }

  /**
   * Sign in again, for a grant whose sign-in was left: a new approval, one confirmation. What it came to goes back to Coach as the
   * card's answer: "signed in", or "failed: why".
   */
  suspend fun signInAgain(c: SetupComponent) {
    val grantId = c.grant ?: return
    // The sign-in card shows no terms: it approves the grant as the app last showed it.
    note(c.id, null)
    when (val result = connect(c.id, grantId, _state.value.grants.firstOrNull { it.id == grantId }?.digest)) {
      is Connected.Done -> answerCard("sign_in", c.id, "signed in")
      // Only an approval with a Card to confirm comes back already approved; a sign-in has none.
      is Connected.AlreadyApproved -> Unit
      is Connected.Failed -> {
        note(c.id, result.why)
        answerCard("sign_in", c.id, "failed: " + result.told)
      }
      Connected.Stopped -> Unit
    }
  }

  // MARK: Coach's hand-overs

  /** The tafs tafs cloud said it set up that the list was read again for, so a taf not listed yet is read for once. */
  private val setUpTafs = mutableSetOf<String>()

  /** The versions tafs cloud said it made from an improvement, by taf and number, that the taf's Versions were read again for. */
  private val versionsRead = mutableSetOf<String>()

  /**
   * Coach's results, each tafs cloud's to hand over: it sets up a new taf, or makes an improved taf's next version, and answers
   * the card itself; the phone only shows the answer. It reads the list again once for a taf it set up, and a taf's Versions
   * once for a version it made, so it's there to try.
   */
  private suspend fun followHandOvers() {
    if (_state.value.mode != Mode.kiff) return
    var readList = false
    for (shown in _state.value.coachComponents.filter { it.component.type == "result" }) {
      val c = shown.component
      val taf = c.taf ?: continue
      if (taf.improves != null) {
        val n = shown.answer?.outcome?.version ?: continue
        val improved = improvedTaf(c) ?: continue
        if (versionsRead.add("${improved.id}#$n")) loadVersions(improved.id)
        continue
      }
      val id = shown.answer?.outcome?.tafId ?: continue
      if (_state.value.tafs.none { it.cloudId == id || it.id == taf.agent } && setUpTafs.add(id)) readList = true
    }
    if (readList) refresh()
  }

  /** The taf an improvement result improves, as this phone lists it: by its tafs id, else by its agent's. */
  private fun improvedTaf(c: SetupComponent): Taf? = c.taf?.let { t -> _state.value.tafs.firstOrNull { it.cloudId == t.improves || it.id == t.improves || it.id == t.agent } }

  /**
   * Clears the connections of tafs removed since, from Connect apps (the owner's ask): one confirmation, one fingerprint, then the
   * device key signs each disconnect, as removing a taf does. Only the ones tafs cloud lists, and one a live taf's Card still uses
   * (tafs cloud's `used_by`) is kept, with the line naming that taf.
   */
  suspend fun clearRemovedConnections(grantIds: List<String>): Boolean {
    val tenant = kiffTenant
    // Only a connection tafs cloud lists as a removed taf's (`removed_tafs[].grants`): never one whose taf the phone merely can't
    // see. Which to keep is tafs cloud's word too: one a live taf's Card still uses (`used_by`) is kept, and the line names it.
    val listed = _state.value.connectApps?.removedTafs.orEmpty().flatMap { it.grants.orEmpty() }.associateBy { it.grant }
    val grants = _state.value.grants.filter { it.id in grantIds && it.id in listed }
    if (_state.value.mode != Mode.kiff || !_state.value.sessionHasKey || tenant == null || grants.isEmpty() || !_state.value.planqueReady) return false
    if (_state.value.clearingRemoved) return false
    val keptBy = grants.mapNotNull { g -> listed[g.id]?.usedBy?.takeIf { it.isNotEmpty() } }
    val ending = grants.filter { listed[it.id]?.usedBy.isNullOrEmpty() }
    if (ending.isEmpty()) {
      _state.update { it.copy(clearNote = bot.tafs.app.model.ConnectionWords.cleared(0, keptBy.size, keptBy.flatten().map { u -> u.name }, 0)) }
      return true
    }
    val generation = accountGeneration
    _state.update { it.copy(clearingRemoved = true, clearNote = null) }
    try {
      try {
        signer.sign("tafs.disconnect.v1|$tenant|${ending.joinToString(",") { it.id }}".toByteArray(), bot.tafs.app.model.ConnectionWords.clearTitle(ending.size))
      } catch (e: SignerException) {
        if (generation == accountGeneration) _state.update { it.copy(clearNote = notSigned(e, Words.Common.notDoneUnconfirmed)) }
        return false
      }
      if (generation != accountGeneration) return false
      var left = 0
      run {
        planque.tenant = tenant
        planque.deviceLoader = { kiff.thisDeviceId() }
        val signer = signer
        planque.signer = { signer.signConfirmed(it) }
        try {
          for (g in ending) {
            if (generation != accountGeneration) return false
            try {
              planque.disconnect(g.id)
            } catch (e: CancellationException) {
              throw e
            } catch (_: Exception) {
              left += 1
            }
          }
        } finally {
          planque.signer = null
        }
      }
      refreshConnections()
      if (generation == accountGeneration) _state.update { it.copy(clearNote = bot.tafs.app.model.ConnectionWords.cleared(ending.size - left, keptBy.size, keptBy.flatten().map { u -> u.name }, left)) }
      return left == 0
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(clearingRemoved = false) }
    }
  }

  /** Disconnects a vendor: planque removes its tools from KIFF and revokes the sign-in at the vendor. Asks for the owner's confirmation. */
  suspend fun disconnect(grantId: String): Boolean {
    val tenant = kiffTenant
    val grant = _state.value.grants.firstOrNull { it.id == grantId }
    if (_state.value.mode != Mode.kiff || !_state.value.sessionHasKey || tenant == null || grant == null || !_state.value.planqueReady) return false
    val generation = accountGeneration
    try {
      // The fingerprint prompt asks what the confirmation asked: this taf's connection, by name.
      val taf = _state.value.tafs.firstOrNull { it.id == grant.request.agent && it.state != TafState.revoked }?.name
      val alsoStops = _state.value.tafsUsing(grant, except = grant.request.agent).map { it.name }
      signer.sign("tafs.disconnect.v1|$tenant|$grantId".toByteArray(), bot.tafs.app.model.ConnectionWords.disconnectTitle(grant, taf, alsoStops))
    } catch (_: SignerException) {
      return false
    }
    if (generation != accountGeneration) return false
    planque.tenant = tenant
    planque.deviceLoader = { kiff.thisDeviceId() }
    val signer = signer
    planque.signer = { signer.signConfirmed(it) }
    try {
      planque.disconnect(grantId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      fail(Words.Connect.notDisconnected(why = ErrorWords.of(e)))
      return false
    } finally {
      planque.signer = null
    }
    // Connect apps' list is tafs cloud's, and says Connected: read again with the grants, so it shows what's left at once.
    if (_state.value.connectApps != null) loadConnectApps(connectAppsQuery) else refreshConnections()
    return true
  }

  /**
   * Reports an answer Coach or a taf wrote (docs/chat.md): by tafs cloud's id for it, in the chat it's in. tafs cloud keeps the
   * answer as kiff/agents holds it and a person at KIFF reads the report. Nothing on the phone changes.
   */
  suspend fun reportAnswer(tafId: String, message: ChatMessage, reason: String, note: String?): ReportResult {
    val cloudId = _state.value.taf(tafId)?.cloudId
    val id = message.serverId
    if (_state.value.mode != Mode.kiff || cloudId == null || id == null || message.from != ChatMessage.From.taf) {
      return ReportResult.NotSent(ErrorWords.CHANGED)
    }
    val session = message.session.takeIf { it.isNotEmpty() && it != "pending" && it != "sample" }
    return try {
      ReportResult.Sent(first = cloud.report(cloudId, id, reason, note?.trim()?.takeIf { it.isNotEmpty() }, session).new)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      ReportResult.NotSent(ErrorWords.of(e))
    }
  }

  /** The person's own chats, for their own copy (docs/data.md): tafs cloud's export, as the two files Settings saves. */
  suspend fun exportChats(): ExportResult {
    if (_state.value.mode != Mode.kiff) return ExportResult.NotExported(ErrorWords.CHANGED)
    return try {
      ExportResult.Ready(cloud.exportChats())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      ExportResult.NotExported(ErrorWords.of(e))
    }
  }

  /** The store's screenshots are being taken: the sample shows the Coach button ([AppState.screenshots]). Debug builds only. */
  fun showForScreenshots() {
    _state.update { it.copy(screenshots = true) }
  }

  /** The owner's answer to one of Coach's cards. */
  fun answerCoach(type: String, id: String, answer: String) {
    viewModelScope.launch { answerCard(type, id, answer) }
  }

  // MARK: Coach drives the app (docs/drive.md)

  /** The activity on screen gives its windows to the drive; with none, a step is refused. */
  val driveHost = bot.tafs.app.drive.DriveHostProxy()

  /** The person's drive on this phone: one at a time, never outliving the app. */
  val drive: bot.tafs.app.drive.DriveController by lazy { bot.tafs.app.drive.DriveController(cloud, driveHost, scope).also { watchDrive(it) } }

  private fun watchDrive(d: bot.tafs.app.drive.DriveController) {
    d.onResumed = { turn -> scope.launch { coachGoesOn(turn) } }
    scope.launch {
      d.state.collect { s ->
        when (s) {
          is bot.tafs.app.drive.DriveState.On -> bot.tafs.app.drive.DriveInput.turn(true)
          is bot.tafs.app.drive.DriveState.Over -> {
            bot.tafs.app.drive.DriveInput.turn(false)
            // The chat opens again with what was done, in the thread at the time the drive ended (kiff/tafs#568): tafs cloud
            // writes it from its record of the steps, not Coach's memory of them, once the end has reached it.
            d.settled()
            runCatching { readThread(COACH_CHAT) }
            // Coach may still be at work after the drive's last step: the chat follows it to its end, as after Continue.
            followChat(COACH_CHAT)
          }
          bot.tafs.app.drive.DriveState.Off -> bot.tafs.app.drive.DriveInput.turn(false)
        }
      }
    }
  }

  /**
   * Approve on a drive card: the approval key, behind the fingerprint, signs the Start of this one request (docs/drive.md). tafs
   * cloud starts it only for that key, outside any window; an approval window this phone holds is closed first, as the drive
   * suspends it. A refusal is said on the card in tafs cloud's words, and nothing starts.
   */
  fun approveDrive(c: SetupComponent) {
    viewModelScope.launch { startDrive(c) }
  }

  suspend fun startDrive(c: SetupComponent) {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || tenant == null) return
    val shown = _state.value.coachComponents.lastOrNull { it.id == c.id && it.answer == null } ?: _state.value.coachComponents.lastOrNull { it.id == c.id }
    val session = shown?.session?.ifEmpty { null } ?: _state.value.coachChat ?: return
    val key = shown?.ref ?: CoachWords.cardKey(session, c.id)
    if (_state.value.driveStarting != null) return
    if (_state.value.approvalKeyLost) { note(c.id, KiffClient.APPROVAL_KEY_LOST); return }
    val task = (c.text ?: c.title).orEmpty().trim().take(2_000)
    _state.update { it.copy(driveStarting = key, coachNotes = it.coachNotes - c.id) }
    val generation = accountGeneration
    try {
      if (kiff.window != null) closeWindow()
      val info = cloud.startDrive(session, task, c.id, tenant) { built -> signer.signApproval(bot.tafs.app.drive.DriveWords.approval(task)) { built() } }
      if (generation != accountGeneration) return
      _state.update { it.copy(cardsTaken = it.cardsTaken + key) }
      drive.begin(info)
    } catch (e: CancellationException) {
      throw e
    } catch (e: SignerException) {
      note(c.id, notSigned(e, Words.Coach.Drive.notStartedUnconfirmed))
    } catch (e: Exception) {
      note(c.id, Words.Coach.Drive.cant(why = ErrorWords.of(e)))
      bot.tafs.app.drive.DriveDiagnostics.error("Coach can't drive: ${ErrorWords.of(e)}")
    } finally {
      _state.update { it.copy(driveStarting = null) }
    }
  }

  /** The person ended the drive: their back, their typing, a third tap, Stop, the Coach button, the app leaving the screen, the lock. */
  fun stopDrive(why: String) {
    drive.stop(why)
  }

  /** The person tapped Continue on a paused drive: the keyboard goes away, and Coach goes on with a look. */
  fun resumeDrive() {
    drive.resume()
  }

  /**
   * Continue went through, and Coach's turn goes on: the run that was parked, or the turn Continue started, as tafs cloud names
   * it. While a run is parked the chat may have stopped following it, so it's followed again here, to its end, which reads the
   * thread, as an answered card's run is. Without a name, the thread is read, and a run at work in it is picked up.
   */
  private suspend fun coachGoesOn(turn: bot.tafs.app.drive.DriveCoachTurn?) {
    val cloudId = _state.value.taf(COACH_CHAT)?.cloudId ?: return
    if (turn == null) { readThread(COACH_CHAT); return }
    // Another Coach conversation is on screen now: that one's run isn't followed into it.
    val shown = _state.value.coachChat
    if (turn.session != null && shown != null && turn.session != shown) return
    resumeChat(COACH_CHAT, cloudId, turn.run, again = true)
  }

  /** A drive still on after the app restarted outlived it: it ends now, and never resumes (docs/drive.md). */
  private fun endDriveLeftOn() {
    scope.launch {
      val left = runCatching { cloud.readDrive() }.getOrNull()?.drive?.takeIf { it.ended == null } ?: return@launch
      if (!drive.on) runCatching { cloud.stopDrive(left.id, "left_app") }
    }
  }

  /**
   * Answers one of Coach's cards as data (docs/chat.md): tafs cloud hands it to the run that waits on the card, which goes on.
   * The card shows the answer once the thread has it, in kiff/agents' words; until then it says the answer is on its way. The
   * run that goes on is followed like any run at work, so Coach's next words show without leaving it. A questions card's
   * answer is its JSON. A card that no longer waits (409) is read again as it is. Only signed in: the sample has no Coach.
   */
  suspend fun answerCard(type: String, id: String, answer: String, chat: String = COACH_CHAT): Boolean {
    if (_state.value.mode != Mode.kiff) return false
    val coach = chat == COACH_CHAT
    val cloudId = (if (coach) _state.value.coachCloudId else _state.value.taf(chat)?.cloudId) ?: return false
    // The card answered: the one waiting under that id, since Coach can use an id again.
    val cards = if (coach) _state.value.coachComponents else _state.value.tafCards[chat].orEmpty()
    val shown = cards.lastOrNull { it.id == id && it.answer == null } ?: cards.lastOrNull { it.id == id }
    val session = shown?.session?.ifEmpty { null } ?: if (coach) _state.value.coachChat else null
    val key = shown?.ref ?: CoachWords.cardKey(session, id)
    if (key in _state.value.cardsAnswering) return false
    val value: kotlinx.serialization.json.JsonElement =
      if (type == "questions") runCatching { KiffClient.json.parseToJsonElement(answer) }.getOrElse { kotlinx.serialization.json.JsonPrimitive(answer) }
      else kotlinx.serialization.json.JsonPrimitive(answer)
    _state.update { it.copy(cardsAnswering = it.cardsAnswering + key, cardErrors = it.cardErrors - key) }
    val generation = accountGeneration
    val answered = try {
      cloud.answerCard(cloudId, id, value, session)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation != accountGeneration) return false
      val gone = e is CloudError && e.status == 409
      // Refused until AI use is allowed: its screen is up and says why, so the card keeps its answer with no line of its own.
      val needsConsent = e is CloudError && e.code == bot.tafs.app.kiff.AI_CONSENT_REQUIRED
      if (!gone && !needsConsent) _state.update { it.copy(cardErrors = it.cardErrors + (key to Words.Coach.cardAnswerNotSent(why = ErrorWords.of(e)))) }
      _state.update { it.copy(cardsAnswering = it.cardsAnswering - key) }
      readThread(chat)
      return false
    } finally {
      // Whatever comes after (the thread read, following the run), the answer is no longer on its way once tafs cloud has
      // answered: a screen torn down meanwhile (the vendor's page opening) never leaves the card on "Sending your answer…".
      _state.update { it.copy(cardsAnswering = it.cardsAnswering - key) }
    }
    if (generation != accountGeneration) return false
    // Taken: the card shows it's answered at once, until the thread's words for it come.
    _state.update { it.copy(cardsTaken = it.cardsTaken + key) }
    if (coach) answered.session?.let { if (_state.value.coachChat == null) settleCoachChat(it) }
    // Coach or the taf is at work again: it reads as thinking until its run is followed, and the run's end reads the thread again.
    _state.update { it.copy(chatAwaiting = it.chatAwaiting + (chat to clock()), chatEnded = it.chatEnded - chat) }
    readThread(chat)
    // The run paused on the card and goes on now: its stream is followed to its end, which reads the thread again.
    answered.run?.let { resumeChat(chat, cloudId, it, again = true) } ?: followChat(chat)
    return true
  }

  /** The owner's answer to a card in a taf's own chat (the loop card's Read on or Stop here), answered as Coach's are. */
  fun answerTafCard(tafId: String, type: String, id: String, answer: String) {
    viewModelScope.launch { answerCard(type, id, answer, chat = tafId) }
  }

  /**
   * Sends what the owner wrote, from a screen: it shows at once and the taf answers after it. [withFiles] takes the files
   * ready in the chat's tray with it; Send again and Retry send the words only. [onNotSent] gets the words back when they
   * didn't go, so the screen can keep them.
   */
  fun sendChat(text: String, tafId: String, withFiles: Boolean = false, onNotSent: (String) -> Unit) {
    scope.launch {
      // Coach not started on this phone yet: the first message starts it, behind its one fingerprint, then goes. Not started
      // (the fingerprint cancelled, or Start refused), nothing is sent and the words go back to the box; why shows above it.
      if (tafId == COACH_CHAT && _state.value.coachCloudId == null && !startCoach()) {
        onNotSent(text)
        return@launch
      }
      // One that was tried and refused stays in the thread, marked not sent: it never comes back to the box as well.
      if (!deliverChat(text, tafId, withFiles) && _state.value.chatUnsent[tafId].orEmpty().none { it.message.text == text.trim() }) onNotSent(text)
    }
  }

  /**
   * Sends a message that wasn't sent, once: it leaves the thread's "Not sent" as it goes, so a second tap finds nothing to
   * send. Refused again, it's back, with the new reason.
   */
  fun retryUnsent(tafId: String, message: ChatMessage) {
    viewModelScope.launch { resendUnsent(tafId, message) }
  }

  /** [retryUnsent], waited for: whether it went. A second tap finds nothing left to send; refused again, deliverChat keeps it. */
  suspend fun resendUnsent(tafId: String, message: ChatMessage): Boolean {
    val s = _state.value
    val kept = s.chatUnsent[tafId]?.firstOrNull { it.message == message } ?: return false
    // Nothing is tried while it can't go: it stays Not sent, words and files, for a later Retry. Checked before it leaves
    // the thread, since a send refused before it starts keeps nothing.
    if (s.holdsRetry(tafId, kept)) return false
    if (s.mode == Mode.kiff && s.taf(tafId)?.cloudId == null) {
      _state.update { it.copy(chatErrors = it.chatErrors + (tafId to NOT_ON_CLOUD)) }
      return false
    }
    // Remembered only once it went: tried and not sent, it's still owed. Meanwhile it's held in memory, so the thread read the send
    // makes, while tafs cloud still lists it, doesn't bring it back.
    val unsent = takeUnsent(tafId, message, remember = false) ?: return false
    unsent.notSentKey?.let { (settledNotSent() as MutableSet<String>).add(it) }
    // With its own files, never the tray's.
    val sent = if (_state.value.mode == Mode.kiff && unsent.files.isNotEmpty()) deliverToTafsCloud(message.text, tafId, again = unsent.files)
    else deliverChat(message.text, tafId)
    unsent.notSentKey?.let { key -> if (sent) rememberNotSent(listOf(key)) else (settledNotSent() as MutableSet<String>).remove(key) }
    if (!sent) keepUnsent(tafId, unsent)
    return sent
  }

  /**
   * A Retry that didn't send: the Not sent row its failure kept carries [unsent]'s `not_sent` key on, as iOS's does, so a restart still
   * brings it back. With no row kept (refused before it was tried), [unsent] is back as it was.
   */
  private fun keepUnsent(tafId: String, unsent: Unsent) = _state.update { s ->
    val kept = s.chatUnsent[tafId].orEmpty()
    val again = kept.indexOfLast { it.message.text == unsent.message.text && it.notSentKey == null }
    val next = if (again >= 0) kept.toMutableList().also { it[again] = it[again].copy(notSentKey = unsent.notSentKey) } else kept + unsent
    s.copy(chatUnsent = s.chatUnsent + (tafId to next))
  }

  /** Deletes a message that wasn't sent, for good: it leaves the thread, and one of tafs cloud's `not_sent` never comes back. */
  fun deleteUnsent(tafId: String, message: ChatMessage) {
    takeUnsent(tafId, message)
  }

  /** Takes a message that wasn't sent out of the thread, to change it in the box: its words, or null when it's gone already. */
  fun editUnsent(tafId: String, message: ChatMessage): String? {
    val unsent = takeUnsent(tafId, message) ?: return null
    // Its files go back in the tray, with its words in the box.
    putBackFiles(unsent.files, tafId)
    return message.text
  }

  private fun takeUnsent(tafId: String, message: ChatMessage, remember: Boolean = true): Unsent? {
    var taken: Unsent? = null
    _state.update { s ->
      val kept = s.chatUnsent[tafId].orEmpty()
      taken = kept.firstOrNull { it.message == message }
      val left = kept.filterNot { it.message == message }
      s.copy(chatUnsent = if (left.isEmpty()) s.chatUnsent - tafId else s.chatUnsent + (tafId to left))
    }
    // One of tafs cloud's `not_sent`, taken by Edit or Delete: remembered now, so it never comes back.
    if (remember) taken?.notSentKey?.let { rememberNotSent(listOf(it)) }
    return taken
  }

  /**
   * Sends the owner's message to the taf and waits for its answer. The sample answers from its
   * own record; signed in, the thread is tafs cloud's. A message may be words, files, or both.
   */
  suspend fun deliverChat(text: String, tafId: String, withFiles: Boolean = false): Boolean {
    val words = text.trim()
    val files = withFiles && _state.value.mode == Mode.kiff && _state.value.files(tafId).any { it.ref != null }
    if (words.isEmpty() && !files) return false
    if (words.length > Chat.LIMIT) {
      _state.update { it.copy(chatErrors = it.chatErrors + (tafId to Words.Chat.tooLong(count = Chat.LIMIT))) }
      return false
    }
    if (_state.value.mode == Mode.kiff) return deliverToTafsCloud(words, tafId, withFiles)
    if (tafId in _state.value.chatSending) return false
    _state.update { it.copy(chatSending = it.chatSending + tafId, chatErrors = it.chatErrors - tafId) }
    try {
      val pending = ChatMessage(nextStamp(tafId), ChatMessage.From.owner, words, "pending")
      append(tafId, pending)
      // It answers from what it did, as a taf does: the reply is worked out now, shown after a beat.
      val reply = sampleReply(words, tafId)
      if (sampleReplyDelayMs > 0) delay(sampleReplyDelayMs)
      // The message shown while it worked gives way to the thread's stored copy, under another id.
      _state.update { s ->
        s.copy(chats = s.chats + (tafId to (s.chats[tafId] ?: emptyList()).map { if (it == pending) it.copy(session = "sample") else it }))
      }
      say(tafId, ChatMessage.From.taf, reply)
      return true
    } finally {
      _state.update { it.copy(chatSending = it.chatSending - tafId) }
    }
  }

  /**
   * Sends the owner's message to a taf that runs on tafs cloud: into the run at work, which reads it at its next
   * step, or, when none is at work (or it just ended), as a new message that starts one. It shows at once, dimmed,
   * until the thread has it; then the thread is read and followed as the taf answers.
   */
  private suspend fun deliverToTafsCloud(words: String, tafId: String, withFiles: Boolean = false, again: List<PendingFile> = emptyList()): Boolean {
    val taf = _state.value.taf(tafId)
    val cloudId = taf?.cloudId
    if (cloudId == null) {
      _state.update { it.copy(chatErrors = it.chatErrors + (tafId to NOT_ON_CLOUD)) }
      return false
    }
    if (tafId in _state.value.chatSending) return false
    // Files go with a message of their own, never into a run at work, and never while one is still going up.
    if (withFiles && _state.value.holdsSend(tafId)) return false
    // A message sent again (Retry) goes with its own files; a new one takes the tray's.
    val files = again.ifEmpty { if (withFiles) takeFiles(tafId) else emptyList() }
    val refs = files.mapNotNull { it.ref }
    _state.update { it.copy(chatSending = it.chatSending + tafId, chatErrors = it.chatErrors - tafId) }
    val generation = accountGeneration
    // A send to a Coach conversation left while it was on its way (another taf's, a new one) goes on at tafs cloud, but nothing
    // of it lands on the conversation now shown.
    val coachToken = coachGeneration
    fun current() = generation == accountGeneration && (tafId != COACH_CHAT || coachToken == coachGeneration)
    val pending = ChatMessage(nextStamp(tafId), ChatMessage.From.owner, words, "pending", attachments = refs)
    append(tafId, pending)
    _state.update { it.copy(chatSent = it.chatSent + (tafId to (it.chatSent[tafId].orEmpty() + pending)), chatEnded = it.chatEnded - tafId) }
    var streamed: kotlinx.coroutines.flow.Flow<StreamEvent>? = null
    try {
      if (isTrialChat(tafId)) {
        // A trial's turn runs in the background, and its chat is read again as it goes.
        cloud.trialMessage(cloudId, words)
      } else {
        // Coach's conversations (kiff/tafs#73): the one shown, or a new one the owner asked for. A new one on a taf starts
        // with the improve route, which puts the taf's job, Card and latest calls in front of the owner's words.
        val coach = tafId == COACH_CHAT
        val fresh = coach && _state.value.coachFresh
        val improving = if (fresh) _state.value.coachImproving?.let { _state.value.taf(it)?.cloudId } else null
        val session = if (coach && !fresh) _state.value.coachChat else null
        if (improving != null) {
          val begun = cloud.improve(improving, words).session
          if (current()) settleCoachChat(begun)
        } else {
          val run = if (fresh) null else _state.value.chatLive[tafId]?.run ?: _state.value.chatWorking[tafId]?.run
          var steered = false
          if (run != null && refs.isEmpty()) {
            try {
              val steer = cloud.steer(cloudId, run, words)
              steered = true
              // Sent once (docs/chat.md): what the run didn't take is tafs cloud's, held in this chat until the taf is done, or sent
              // at once as the next message. The thread shows which, a held one as what waits, so its pending copy goes now.
              if (steer.sent != null || steer.held != null || steer.status.isEmpty()) {
                if (current() && coach) steer.sent?.let { settleCoachChat(it.session) }
                _state.update { s ->
                  val left = s.chatSent[tafId].orEmpty().filterNot { it == pending }
                  s.copy(
                    chats = s.chats + (tafId to s.chats[tafId].orEmpty().filterNot { it == pending }),
                    chatSent = if (left.isEmpty()) s.chatSent - tafId else s.chatSent + (tafId to left),
                  )
                }
              }
            } catch (e: CloudError) {
              // An older tafs cloud, which holds nothing: a run that has ended throws 409 with no code, and the message goes as a new one.
              if (e.status != 409 || e.code != null) throw e
            }
          }
          // Streamed, so the taf's words show as it writes them; sent plainly when the stream can't open.
          if (!steered) streamed = openStream(cloudId, words, refs, session, fresh)
          if (!steered && streamed == null) cloud.sendMessage(cloudId, words, refs, session, fresh).also { sent ->
            if (current()) {
              if (coach) settleCoachChat(sent.session)
              sent.message?.let { markSent(tafId, pending, it.id) }
            }
          }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val waitsForCredits = creditsRefusal(e)
      val needsConsent = e is CloudError && e.code == bot.tafs.app.kiff.AI_CONSENT_REQUIRED
      if (current() && (waitsForCredits != null || needsConsent || e is CloudError && e.code == HELD_FULL)) {
        // As much waits as one message can be, the tafs wait on credits, or the person hasn't allowed AI use yet (its screen shows):
        // tafs cloud's words, as they are, and the owner's go back in the box to send later. No Not sent row: Retry would change
        // nothing until then (docs/accounts.md#in-the-apps).
        _state.update { s ->
          val left = s.chatSent[tafId].orEmpty().filterNot { it == pending }
          s.copy(
            chats = s.chats + (tafId to s.chats[tafId].orEmpty().filterNot { it == pending }),
            chatSent = if (left.isEmpty()) s.chatSent - tafId else s.chatSent + (tafId to left),
            // Refused until AI use is allowed: its screen is up and says why, so no line under the box as well.
            chatErrors = if (needsConsent) s.chatErrors - tafId else s.chatErrors + (tafId to (waitsForCredits ?: e.message ?: "")),
          )
        }
        if (waitsForCredits != null || needsConsent) putBackFiles(files, tafId)
        return false
      }
      if (current()) {
        _state.update { s ->
          val left = s.chatSent[tafId].orEmpty().filterNot { it == pending }
          // Its words stay where they were written, marked not sent with why, for Retry or Edit (kiff/tafs#476).
          val unsent = Unsent(pending.copy(session = Unsent.SESSION), sendFailure(e, taf.name), files, halted = (e as? CloudError)?.takeIf { it.code == "halted" }?.halted)
          s.copy(
            chats = s.chats + (tafId to s.chats[tafId].orEmpty().filterNot { it == pending }),
            chatSent = if (left.isEmpty()) s.chatSent - tafId else s.chatSent + (tafId to left),
            chatUnsent = if (words.isBlank()) s.chatUnsent else s.chatUnsent + (tafId to (s.chatUnsent[tafId].orEmpty() + unsent)),
            chatErrors = if (words.isBlank()) s.chatErrors + (tafId to sendFailure(e, taf.name)) else s.chatErrors,
          )
        }
        // Its files didn't go. With its words kept as not sent, they stay with it for Retry; files alone go back in the tray.
        if (words.isBlank()) putBackFiles(files, tafId)
        // Refused for a halt: where it stands is read, so the row offers Resume while it holds.
        when ((e as? CloudError)?.takeIf { it.code == "halted" }?.halted) {
          Unsent.HALTED_ACCOUNT -> refreshHalt(null)
          Unsent.HALTED_TAF -> refreshHalt(tafId)
        }
      }
      return false
    } finally {
      if (current()) _state.update { it.copy(chatSending = it.chatSending - tafId) }
    }
    if (!current()) return false
    // The taf is answering now: until it has, or is at work, it reads as thinking.
    _state.update { it.copy(chatAwaiting = it.chatAwaiting + (tafId to clock())) }
    val events = streamed
    if (events != null) {
      _state.update { it.copy(chatLive = it.chatLive + (tafId to LiveAnswer(started = clock()))) }
      readThread(tafId)
      if (!current()) return false
      followStream(tafId, events, retry = words, sent = pending, coach = coachToken, account = generation)
      return true
    }
    readThread(tafId)
    followChat(tafId)
    return true
  }

  // MARK: Photos and files on a message (docs/attachments.md)

  /**
   * Where a chat's files go on tafs cloud: the taf, or Coach. Null where nothing can be attached: the sample, a trial's
   * chat, or a taf that doesn't run on tafs cloud.
   */
  fun filesTarget(tafId: String): String? = _state.value.takeIf { it.canAttach(tafId) }?.taf(tafId)?.cloudId

  /**
   * Adds a file to the chat's next message and uploads it: declared to tafs cloud, which checks its type and size, then its
   * bytes straight to storage. One that can't go says why in the tray, and is left out of the message.
   */
  suspend fun attach(file: PreparedFile, tafId: String) {
    val target = filesTarget(tafId) ?: return
    if (_state.value.files(tafId).size >= FilePrep.PER_MESSAGE) {
      refuseFile(Words.Chat.File.tooMany(count = FilePrep.PER_MESSAGE), tafId)
      return
    }
    val pending = PendingFile(name = file.name, mediaType = file.mediaType, thumbnail = file.thumbnail)
    _state.update { it.copy(chatFiles = it.chatFiles + (tafId to it.files(tafId) + pending), fileNotices = it.fileNotices - tafId) }
    val generation = accountGeneration
    val state = try {
      val (ref, upload) = cloud.attach(target, file.mediaType, file.name, file.data.size.toLong(), FilePrep.sha256(file.data))
      cloud.upload(file.data, upload)
      PendingFile.State.Ready(ref)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      PendingFile.State.Failed(
        // tafs cloud refused the file itself: the owner can pick another.
        if (e is CloudError && e.status in 400..499 && e.status !in listOf(401, 403, 409, 429)) FilePrep.NOT_TAKEN
        else if (e is bot.tafs.app.model.OwnWords) ErrorWords.of(e)
        else Words.Chat.File.notUploaded(why = ErrorWords.of(e)),
      )
    }
    if (generation != accountGeneration) return
    _state.update { s -> s.copy(chatFiles = s.chatFiles + (tafId to s.files(tafId).map { if (it.id == pending.id) it.copy(state = state) else it })) }
  }

  /** A file picked that can't be attached: said in the tray, never uploaded. */
  fun refuseFile(why: String, tafId: String) {
    _state.update { it.copy(fileNotices = it.fileNotices + (tafId to why)) }
  }

  fun removeFile(id: String, tafId: String) {
    _state.update { s ->
      val left = s.files(tafId).filterNot { it.id == id }
      s.copy(chatFiles = if (left.isEmpty()) s.chatFiles - tafId else s.chatFiles + (tafId to left), fileNotices = s.fileNotices - tafId)
    }
  }

  /** The chat's ready files, taken for the message being sent. Ones that failed stay in the tray. */
  private fun takeFiles(tafId: String): List<PendingFile> {
    val ready = _state.value.files(tafId).filter { it.ref != null }
    if (ready.isEmpty()) return ready
    _state.update { s ->
      val left = s.files(tafId).filter { it.ref == null }
      s.copy(chatFiles = if (left.isEmpty()) s.chatFiles - tafId else s.chatFiles + (tafId to left))
    }
    return ready
  }

  /** Puts back files a message didn't take, ahead of any picked since. */
  private fun putBackFiles(taken: List<PendingFile>, tafId: String) {
    if (taken.isEmpty()) return
    _state.update { s -> s.copy(chatFiles = s.chatFiles + (tafId to taken + s.files(tafId))) }
  }

  /**
   * A file the person attached, downloaded to open full size: a copy under its own name in the app's own folder, removed on
   * sign-out. Null when it couldn't be read; the notice says why.
   */
  suspend fun openFile(ref: AttachmentRef, tafId: String): java.io.File? {
    val target = filesTarget(tafId) ?: return null
    return try {
      val data = cloud.attachment(target, ref.id)
      val dir = java.io.File(openedFiles, ref.id).apply { mkdirs() }
      java.io.File(dir, java.io.File(ref.name).name.ifBlank { "file" }).apply { writeBytes(data) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      refuseFile(Words.Chat.File.notOpened(file = ref.name, why = ErrorWords.of(e)), tafId)
      null
    }
  }

  /**
   * Opens the streamed send. Null when the stream couldn't open and the plain send should take the message (no network to it,
   * tafs cloud without the route, or failing); a refusal (the flood guard, the role) throws, as the plain send's would.
   */
  private suspend fun openStream(
    cloudId: String,
    text: String,
    files: List<AttachmentRef> = emptyList(),
    session: String? = null,
    newChat: Boolean = false,
  ): kotlinx.coroutines.flow.Flow<StreamEvent>? = try {
    cloud.streamMessage(cloudId, text, files, session, newChat)
  } catch (e: CancellationException) {
    throw e
  } catch (e: CloudError) {
    if (e.status in 400..499 && e.status != 404 && e.status != 408) throw e
    null
  } catch (_: Exception) {
    null
  }

  /**
   * Follows a run's stream into the live answer (docs/chat.md): words as they're written, what it's thinking, each step in
   * words, a held call's card at once (KIFF is read when the stream says so, as iOS does), and how it ended. When it's over the
   * thread is read and the stored answer takes the live one's place. A stream that drops without an end leaves the thread to
   * show the rest, read as before.
   */
  /**
   * The message on its way takes tafs cloud's id for it, from its send or its stream: the thread's copy is then found by that
   * id alone (SentMessages.merge), never by its words.
   */
  private fun markSent(tafId: String, sent: ChatMessage, serverId: String) {
    val marked = sent.copy(serverId = serverId)
    _state.update { s ->
      s.copy(
        chats = s.chats + (tafId to s.chats[tafId].orEmpty().map { if (it == sent) marked else it }),
        chatSent = s.chatSent + (tafId to s.chatSent[tafId].orEmpty().map { if (it == sent) marked else it }),
      )
    }
  }

  private fun followStream(
    tafId: String, events: kotlinx.coroutines.flow.Flow<StreamEvent>, retry: String?, sent: ChatMessage? = null,
    // The account and Coach conversation this stream is of, taken before it was opened: once either has changed, nothing of it
    // shows.
    coach: Int = coachGeneration,
    account: Int = accountGeneration,
  ) {
    val generation = account
    fun current() = generation == accountGeneration && (tafId != COACH_CHAT || coach == coachGeneration)
    followers[tafId]?.cancel()
    followers[tafId] = scope.launch {
      var ended: StreamEvent.End? = null
      fun live(change: (LiveAnswer) -> LiveAnswer) = _state.update { s ->
        s.chatLive[tafId]?.let { s.copy(chatLive = s.chatLive + (tafId to change(it))) } ?: s
      }
      try {
        events.collect { e ->
          if (!current()) throw CancellationException("another account or conversation")
          when (e) {
            is StreamEvent.Session -> {
              live { it.copy(session = e.session, run = e.run ?: it.run) }
              if (sent != null && e.messageId != null) markSent(tafId, sent, e.messageId)
              // A new Coach conversation is the one shown once it has begun.
              if (tafId == COACH_CHAT) settleCoachChat(e.session)
              // At work in that chat, for steering and Stop.
              _state.update { it.copy(chatWorking = it.chatWorking + (tafId to ChatRun(e.session, e.run))) }
            }
            is StreamEvent.Text -> live { it.copy(text = it.text + e.text, activity = null, thinking = "") }
            is StreamEvent.Thinking -> live { it.copy(thinking = it.thinking + e.text) }
            is StreamEvent.State -> live { it.copy(state = bot.tafs.app.model.LiveState(e.state, e.words)) }
            is StreamEvent.Activity -> live { a ->
              // The words written so far stay, and the answer goes on below them.
              val kept = if (a.text.isNotEmpty() && !a.text.endsWith("\n\n")) a.copy(text = a.text + "\n\n") else a
              // Only tafs cloud's words for a step: a call it gives none for (show, whose card shows itself) has no line.
              kept.began(e.tool, e.step.orEmpty(), e.call)
            }
            is StreamEvent.Step -> live { it.ended(e.call, LiveStep.Status.of(e.status)) }
            is StreamEvent.Held -> {
              live { it.ended(null, LiveStep.Status.held).copy(activity = null) }
              // KIFF is read for the card, where the call was made, and for Needs you.
              scope.launch { refresh() }
            }
            StreamEvent.Taken, StreamEvent.Said, StreamEvent.Component -> readThread(tafId)
            StreamEvent.Part -> {
              // The part's words leave the live row in the update that brings them into the thread, never a frame before.
              readThread(tafId) { s -> s.chatLive[tafId]?.let { a -> s.copy(chatLive = s.chatLive + (tafId to a.newPart())) } ?: s }
            }
            is StreamEvent.End -> {
              ended = e
              live { it.copy(activity = null) }
            }
            StreamEvent.Broken -> Unit
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // The stream dropped: the thread has the rest.
      }
      if (!current()) return@launch
      val end = ended
      val run = _state.value.chatLive[tafId]?.run
      _state.update { s ->
        val how = when {
          end == null -> null
          end.status == "ERROR" && retry != null -> ChatEnd.Failed(retry, end.done, end.words)
          end.status == "CANCELLED" -> ChatEnd.Stopped
          end.stop in CAP_STOPS -> ChatEnd.Capped(end.stop)
          else -> null
        }
        s.copy(chatEnded = if (how != null) s.chatEnded + (tafId to how) else s.chatEnded - tafId)
      }
      // Over, or paused on a held call: KIFF is read at once, so the hold shows and the Card's amounts are today's.
      if (end != null) refresh()
      // The live answer goes in the update that brings the answer into the thread: one row takes the other's place.
      val settle: (AppState) -> AppState = { s -> s.copy(chatLive = s.chatLive - tafId, chatAwaiting = if (end != null) s.chatAwaiting - tafId else s.chatAwaiting) }
      readThread(tafId, settle)
      if (!current()) return@launch
      // Where the thread couldn't be read, the live answer goes all the same.
      _state.update(settle)
      // This stream no longer holds the chat. What goes on after it is read on a timer: a stream that dropped with no end, or a
      // run that paused and went on with no stream open, as Coach's does once tafs cloud answers its result. Its end must show.
      if (followers[tafId] === coroutineContext[kotlinx.coroutines.Job]) followers.remove(tafId)
      val handOver = if (end != null && tafId == COACH_CHAT) run else null
      if (busy(tafId) || (handOver != null && handingOver(handOver))) followChat(tafId, handOver)
    }
  }

  /** Stops what the taf is doing in its chat: its latest run is cancelled, and the chat says so. */
  suspend fun stopChat(tafId: String) {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    val session = _state.value.chatLive[tafId]?.session ?: _state.value.chatWorking[tafId]?.session ?: return
    val generation = accountGeneration
    try {
      cloud.stop(cloudId, session)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) fail(Words.Chat.notStopped(why = ErrorWords.of(e)))
      return
    }
    if (generation != accountGeneration) return
    followers.remove(tafId)?.cancel()
    _state.update {
      it.copy(chatLive = it.chatLive - tafId, chatAwaiting = it.chatAwaiting - tafId, chatWorking = it.chatWorking - tafId, chatEnded = it.chatEnded + (tafId to ChatEnd.Stopped))
    }
    readThread(tafId)
  }

  /** Runs this phone picked back up, so each is picked up once. */
  private val resumedRuns = mutableSetOf<String>()

  /**
   * Back in a chat whose run is at work: its stream is picked back up once, its steps so far, then the rest, and the thread is
   * read on a timer when the stream can't open. [again]: a run the owner just set going again (a card answered) is followed in
   * place of whatever followed the chat.
   */
  private suspend fun resumeChat(tafId: String, cloudId: String, run: String, again: Boolean = false) {
    if (isTrialChat(tafId)) return
    if (!again && (followers[tafId]?.isActive == true || run in resumedRuns)) return
    resumedRuns += run
    // The account and Coach conversation it resumes for: a resume that lands after either changed writes nothing and follows nothing.
    val generation = accountGeneration
    val coach = coachGeneration
    fun current() = generation == accountGeneration && (tafId != COACH_CHAT || coach == coachGeneration)
    val events = try {
      cloud.resumeRun(cloudId, run)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Its stream can't open: the thread is read on a timer while the run works, as before the stream.
      if (current()) followChat(tafId)
      return
    }
    if (!current()) return
    val working = _state.value.chatWorking[tafId]
    _state.update { it.copy(chatLive = it.chatLive + (tafId to LiveAnswer(started = clock(), session = working?.session, run = run))) }
    followStream(tafId, events, retry = null, coach = coach, account = generation)
  }

  /**
   * Why a message wasn't sent, in words: tafs cloud's own where it says why (a run kiff/agents refused: the credits used, the taf
   * halted), else the app's for the status.
   */
  private fun sendFailure(e: Exception, name: String): String =
    if (e is CloudError && e.status == 429 && e.words == null) Words.Chat.tooMany(taf = name)
    else Words.Chat.notSent(why = ErrorWords.of(e))

  /**
   * A run refused because the tafs wait on credits (docs/accounts.md#the-balance-read): 409 with `credits_used_up`, `limit_reached`
   * or `no_plan`, and tafs cloud's words, which say what lets them go on. Shown as they are; null for any other refusal.
   */
  private fun creditsRefusal(e: Exception): String? =
    (e as? CloudError)?.takeIf { it.status == 409 && it.code in CREDITS_WAIT }?.words

  private fun nextStamp(tafId: String): Instant = Chat.stamp(_state.value.chats[tafId]?.maxOfOrNull { it.at }, clock())

  private fun append(tafId: String, message: ChatMessage) =
    _state.update { s -> s.copy(chats = s.chats + (tafId to ((s.chats[tafId] ?: emptyList()) + message))) }

  private fun say(tafId: String, from: ChatMessage.From, text: String) =
    append(tafId, ChatMessage(nextStamp(tafId), from, text, "sample"))

  /** A sample taf answers from its own record: what it did, what waits, or a pause. */
  private fun sampleReply(text: String, tafId: String): String {
    val lower = text.lowercase()
    val taf = _state.value.taf(tafId) ?: return Words.Sample.Reply.gone
    if (listOf("stop", "pause", "hold off").any { lower.contains(it) }) {
      if (taf.state != TafState.paused) togglePause(tafId)
      return Words.Sample.Reply.paused
    }
    if (listOf("go", "carry on", "resume", "continue").any { lower == it || lower.startsWith("$it ") }) {
      if (taf.state == TafState.paused) togglePause(tafId)
      return Words.Sample.Reply.backToWork
    }
    if (listOf("more", "raise", "limit", "bigger", "increase").any { lower.contains(it) }) {
      return Words.Sample.Reply.moreRoom
    }
    val done = _state.value.entries(tafId).take(3).map { "• " + it.title }
    val waiting = _state.value.heldCalls(tafId).size
    val lines = mutableListOf<String>()
    lines += if (done.isEmpty()) Words.Sample.Reply.nothingDone else Words.Sample.Reply.lately(done = done.joinToString("\n"))
    if (waiting > 0) lines += Words.Sample.Reply.waiting(count = waiting)
    lines += Words.Sample.Reply.note
    return lines.joinToString("\n\n")
  }

  // MARK: What a taf remembers

  /** The taf's notes: signed in, read from tafs cloud; in the sample, the kind of thing a real one writes down as it learns. */
  fun loadAllMemory(tafId: String) {
    val id = _state.value.taf(tafId)?.cloudId ?: return
    val gen = accountGeneration
    viewModelScope.launch {
      try { val notes = cloud.memory(id, all = true); if (gen == accountGeneration) _state.update { it.copy(memories = it.memories + (tafId to notes), memoryErrors = it.memoryErrors - tafId) }
      } catch (e: CancellationException) { throw e
      } catch (e: Exception) { if (gen == accountGeneration) _state.update { it.copy(memoryErrors = it.memoryErrors + (tafId to ErrorWords.of(e))) } }
    }
  }

  fun loadMemory(tafId: String) {
    if (_state.value.mode == Mode.kiff) {
      viewModelScope.launch { readMemory(tafId) }
      return
    }
    if (_state.value.memories.containsKey(tafId)) return
    _state.update { it.copy(memories = it.memories + (tafId to sampleNotes(tafId, clock()))) }
  }

  /** Reads what a taf on tafs cloud remembers; one that can't be read says so in its panel. */
  suspend fun readMemory(tafId: String) {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    val generation = accountGeneration
    val notes = try {
      cloud.memory(cloudId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(memoryErrors = it.memoryErrors + (tafId to Words.Taf.Memory.notLoaded(why = ErrorWords.of(e)))) }
      return
    }
    if (generation == accountGeneration) _state.update { it.copy(memories = it.memories + (tafId to notes), memoryErrors = it.memoryErrors - tafId) }
  }

  /** Forgets one note. It goes from the list at once; signed in, it comes back if tafs cloud refuses. */
  fun forget(note: MemoryNote, tafId: String) {
    _state.update { s -> s.copy(memories = s.memories + (tafId to (s.memories[tafId] ?: emptyList()).filterNot { it.id == note.id }), memoryErrors = s.memoryErrors - tafId) }
    if (_state.value.mode == Mode.kiff) viewModelScope.launch { forgetAtCloud(note, tafId) }
  }

  /** Tells tafs cloud to forget the note. Only that note comes back if it refuses: another Forget may have finished meanwhile. */
  suspend fun forgetAtCloud(note: MemoryNote, tafId: String): Boolean {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return false
    val generation = accountGeneration
    return try {
      cloud.forget(cloudId, note.id)
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: CloudError) {
      // Already gone.
      if (e.status == 404) return true
      putBack(note, tafId, generation, e)
      false
    } catch (e: Exception) {
      putBack(note, tafId, generation, e)
      false
    }
  }

  private fun putBack(note: MemoryNote, tafId: String, generation: Int, e: Exception) {
    if (generation != accountGeneration) return
    _state.update { s ->
      val notes = (s.memories[tafId].orEmpty().filterNot { it.id == note.id } + note).sortedByDescending { it.updatedAt }
      s.copy(memories = s.memories + (tafId to notes), memoryErrors = s.memoryErrors + (tafId to Words.Taf.Memory.notForgotten(why = ErrorWords.of(e))))
    }
  }

  private fun sampleNotes(tafId: String, now: Instant): List<MemoryNote> = listOf(
    Triple("/how-you-like-it.md", Words.Sample.Memory.explain, 3_600L),
    Triple("/customers/regulars.md", Words.Sample.Memory.regulars, 86_400L),
    Triple("/from-the-owner.md", Words.Sample.Memory.askFirst, 3 * 86_400L),
  ).mapIndexed { i, (path, content, ago) -> MemoryNote("sample-$tafId-$i", path, content, now.minusSeconds(ago)) }

  /** Plays the next scripted action of every working taf, so the sample shows the Card at work. */
  fun playNextDemoActions() {
    if (_state.value.mode != Mode.sample) return
    for (taf in _state.value.tafs.filter { it.state == TafState.working }) {
      val script = TafTemplates.template(_state.value.templateOf[taf.id])?.script ?: continue
      val position = _state.value.scriptPosition[taf.id] ?: 0
      if (position >= script.size) continue
      _state.update { it.copy(scriptPosition = it.scriptPosition + (taf.id to position + 1)) }
      propose(script[position], taf.id)
    }
    save()
  }

  /** Runs [proposed] through the taf's Card: allowed actions happen, others wait or are refused. */
  fun propose(proposed: ProposedAction, tafId: String) {
    val taf = _state.value.taf(tafId) ?: return
    when (val decision = CardCheck.decide(proposed, taf)) {
      Decision.Allowed -> {
        update(CardCheck.draw(proposed, taf).copy(state = TafState.working, now = proposed.summary))
        log(tafId, RecordEntry.Kind.done, proposed.summary, amountLine(proposed, { Words.Record.withinCard(amount = it) }, Words.Record.withinCardAlone), proposed)
      }
      is Decision.Held -> {
        val now = clock()
        val rule = taf.card.rules.firstOrNull { it.action == proposed.action }
        val call = HeldCall(tafId = tafId, proposed = proposed, reason = decision.reason, heldAt = now,
          expiresAt = now.plus(Duration.ofMinutes(10)), nextSteps = rule?.nextSteps ?: emptyList())
        _state.update { it.copy(held = listOf(call) + it.held) }
        update(taf.copy(state = TafState.waiting, now = Words.Taf.Now.waitingOn(summary = proposed.summary)))
        log(tafId, RecordEntry.Kind.held, Words.Record.held(summary = proposed.summary), decision.reason, proposed)
      }
      is Decision.Refused -> {
        update(taf.copy(now = Words.Taf.Now.refused(summary = proposed.summary)))
        log(tafId, RecordEntry.Kind.refused, Words.Record.refused(summary = proposed.summary), decision.reason, proposed)
      }
    }
  }

  // MARK: The owner answers

  /**
   * The owner's answer to a held call. It is confirmed on the phone first, then signed with a key
   * that never leaves it; the signature is kept with the answer. Returns whether it was answered:
   * not when the owner didn't confirm, and then the call still waits.
   *
   * [confirmed]: the owner confirmed this step already (installing the version Coach asked for), so the answer is signed
   * without asking again. [installed]: the version Coach's held call names is live already. [approvedWithCard]: KIFF approved
   * the call with the Card's confirm, under its one fingerprint, so nothing is signed or sent. [presigned]: the answer was signed
   * before the version went live, under the install's one fingerprint.
   */
  suspend fun answer(
    call: HeldCall,
    answer: Answer,
    confirmed: Boolean = false,
    installed: Boolean = false,
    approvedWithCard: Boolean = false,
    presigned: SignedAnswer? = null,
  ): Boolean {
    val before = _state.value
    if (before.held.none { it.id == call.id }) return false
    // Approving Coach's install is installing it (kiff/tafs#206): the switch and its Card come first, then this answer, so
    // tafs never hears of a half-done install.
    if (call.install != null && answer is Answer.ApproveOnce && !confirmed && !installed) {
      fail(Words.NeedsYou.installFromCard)
      return false
    }
    val kiffId = call.kiffId
    if (approvedWithCard && kiffId != null) {
      // KIFF holds the answer already: the Record says so, and the paused run goes on.
      applyAnswer(call, answer, null)
      goOn(call.tafId, kiffId)
      refresh()
      return true
    }
    // A call KIFF holds is answered here only when KIFF checks this phone's signature on it (kiff/tafs#138).
    if (kiffId != null && !before.canAnswer(call)) {
      fail(
        if (!before.sessionHasKey) Words.NeedsYou.signInAgainToAnswer
        else Words.NeedsYou.answerInKiff,
      )
      return false
    }
    if (kiffId == null && clock() > call.expiresAt) {
      resolve(call)
      log(call.tafId, RecordEntry.Kind.refused, Words.Record.expired(summary = call.proposed.summary), Words.Record.expiredDetail)
      save()
      return true
    }
    // Approving, or raising a Card, needs the approval key KIFF holds: without it, only a new sign-in lets this
    // phone approve (kiff/tafs#233).
    if (before.approvalKeyLost && kiffId != null && (answer is Answer.ApproveOnce || answer is Answer.ExtraRoom)) {
      fail(KiffClient.APPROVAL_KEY_LOST)
      return false
    }
    val generation = accountGeneration
    val answered = try {
      presigned ?: signAnswer(call, answer, Words.NeedsYou.Prompt.answer(summary = call.proposed.summary), confirmed)
    } catch (e: SignerException) {
      // Nothing was sent, so the hold stays where it was; say so after why (requirement: a clear outcome, the request kept).
      fail("${notSigned(e, Words.Common.notSentUnconfirmed)} ${bot.tafs.app.model.ApprovalWords.KEPT}")
      return false
    }
    val signature = answered.value
    val signed = answered.kiff
    // The session ended, or another began, while the prompt was up: nothing of this answer goes to the new one.
    if (generation != accountGeneration) return false

    if (kiffId != null) {
      try {
        when (answer) {
          Answer.ApproveOnce -> kiff.approveOnce(kiffId, signed)
          Answer.Refuse -> kiff.reject(kiffId, null, signed)
          is Answer.NextStep -> kiff.reject(kiffId, answer.step, signed)
          Answer.ExtraRoom -> kiff.changeCard(kiffId, signed)
        }
      } catch (_: KiffError.ApprovalKeyRequired) {
        fail(KiffClient.APPROVAL_KEY_LOST)
        return false
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation != accountGeneration) return false
        fail(AnswerRefusals.answerRefusedWords(e))
        AnswerRefusals.lastingRefusal(e)?.let { code -> _state.update { it.copy(unanswerable = it.unanswerable + (call.id to code)) } }
        return false
      }
    }
    // Answered meanwhile (the call went from the screen while the prompt was up): nothing to do.
    if (_state.value.held.none { it.id == call.id }) return false
    applyAnswer(call, answer, signature)
    if (kiffId != null) {
      goOn(call.tafId, kiffId)
      // KIFF is read again for the Card's use and the call's outcome.
      refresh()
    }
    return true
  }

  /** An answer to a held call, signed: [value] for the Record, and [kiff] what KIFF checks, for a call KIFF holds. */
  class SignedAnswer(val value: String, val kiff: KiffClient.AnswerSignature?)

  /**
   * Signs [answer] to [call], behind the prompt with [reason]'s words: KIFF's message for a call KIFF holds, else the sample's.
   * Throws [SignerException] when the owner didn't confirm.
   */
  private suspend fun signAnswer(call: HeldCall, answer: Answer, reason: String, confirmed: Boolean): SignedAnswer {
    val kiffId = call.kiffId
    val tenant = kiffTenant
    val version = call.accountingVersion
    if (kiffId == null || tenant == null || version == null) return SignedAnswer(signer.sign(Messages.answer(call.id, answer), reason), null)
    // KIFF's message: this hold, this answer, the hold's version, and the time, taken once the owner confirmed.
    val step = (answer as? Answer.NextStep)?.step
    var signedAt = ""
    val message = {
      signedAt = DateTimeFormatter.ISO_INSTANT.format(clock().truncatedTo(ChronoUnit.SECONDS))
      KiffClient.answerMessage(tenant, kiffId, answer.kiffName, step, null, version, signedAt)
    }
    // Approving, or raising a Card, grants authority: the approval key signs it, behind its own fingerprint, which
    // the key checks itself (kiff/tafs#233). An approval key KIFF doesn't hold yet registers first. A phone with no
    // approval key doesn't approve with the screen lock: it says to add a fingerprint. Refusing is the signing key's.
    val approving = answer is Answer.ApproveOnce || answer is Answer.ExtraRoom
    if (approving && !sessionHasApprovalKey && signer.approvalPublicKeyDER.isNotEmpty()) registerApprovalKey()
    val value = when {
      approving && (sessionHasApprovalKey || signer.approvalPublicKeyDER.isEmpty()) -> signer.signApproval(reason, message)
      confirmed -> signer.signConfirmed(message())
      else -> signer.sign(reason, message)
    }
    return SignedAnswer(value, KiffClient.AnswerSignature(signedAt, value))
  }

  /**
   * Coach's held call as a draft's confirm names it, so KIFF approves it under the confirm's fingerprint; null where this phone
   * can't answer it (no signing key in KIFF, no approval key, a call KIFF doesn't hold).
   */
  private fun holdAnswered(call: HeldCall): KiffClient.HoldAnswered? {
    val id = call.kiffId ?: return null
    val version = call.accountingVersion ?: return null
    val s = _state.value
    if (!s.canAnswer(call) || s.approvalKeyLost) return null
    return KiffClient.HoldAnswered(id, version)
  }

  /**
   * The run paused on the answered call goes on with the answer (kiff/tafs#102): the chat shows the taf working
   * and then its reply, as after a message. It is asked to go on now, not when KIFF's alert reaches it
   * (kiff/tafs#206), best effort: the alert and the hold's expiry still continue it. Only a taf's call has a run.
   */
  private fun goOn(tafId: String, kiffId: String) {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    val generation = accountGeneration
    _state.update { it.copy(chatAwaiting = it.chatAwaiting + (tafId to clock())) }
    scope.launch {
      try {
        cloud.continueHeld(cloudId, kiffId)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
      }
      if (generation != accountGeneration) return@launch
      readThread(tafId)
      followChat(tafId)
    }
  }

  private fun applyAnswer(call: HeldCall, answer: Answer, signature: String?) {
    resolve(call)
    val taf = _state.value.taf(call.tafId)
    val summary = call.proposed.summary
    // The owner's decision stays in the Record where the call was held, at once and in its history
    // (kiff/tafs#138): "Refused by you · 22:41 · Refund €150.00".
    val at = shortTime(clock())
    // A call KIFF holds counts against the Card in KIFF; only the sample draws on the Card here.
    val sample = call.kiffId == null
    when (answer) {
      Answer.ApproveOnce -> {
        taf?.let { update((if (sample) CardCheck.draw(call.proposed, it) else it).copy(now = summary)) }
        decide(call, RecordEntry.Kind.approved, Words.Record.approvedOnce(at = at, summary = summary), amountLine(call.proposed, { Words.Record.approvedOnceBy(amount = it, owner = ownerName) }, Words.Record.approvedOnceByAlone(owner = ownerName)), signature)
      }
      is Answer.NextStep -> {
        taf?.let { update(it.copy(now = answer.step)) }
        decide(call, RecordEntry.Kind.refused, Words.Record.refusedNextStep(at = at, summary = summary), Words.Record.refusedNextStepDetail(step = answer.step), signature)
      }
      Answer.ExtraRoom -> {
        taf?.let { update(it.copy(now = summary)) }
        decide(call, RecordEntry.Kind.approved, Words.Record.extraRoom(at = at, summary = summary), Words.Record.extraRoomDetail, signature)
        call.kiffId?.let { id -> _state.update { it.copy(roomGiven = it.roomGiven + id) } }
      }
      Answer.Refuse -> {
        taf?.let { update(it.copy(now = Words.Taf.Now.refused(summary = summary))) }
        decide(call, RecordEntry.Kind.refused, Words.Record.refusedByYou(at = at, summary = summary), call.reason, signature)
      }
    }
    _state.value.taf(call.tafId)?.let { t ->
      if (t.state == TafState.waiting && _state.value.held.none { it.tafId == call.tafId }) update(t.copy(state = TafState.working))
    }
    save()
  }

  // MARK: Helpers

  // MARK: Versions

  private fun versionError(tafId: String, words: String?) =
    _state.update { it.copy(versionErrors = if (words == null) it.versionErrors - tafId else it.versionErrors + (tafId to words)) }

  /** Reloads a taf's versions from tafs cloud, which settles a switch that stopped half way first. */
  suspend fun loadVersions(tafId: String) {
    val cloudId = _state.value.taf(tafId)?.cloudId
    if (_state.value.mode != Mode.kiff || cloudId == null) return
    val generation = accountGeneration
    val read = try {
      cloud.versionState(cloudId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) versionError(tafId, Words.Version.notLoaded(why = ErrorWords.of(e)))
      return
    }
    if (generation != accountGeneration || _state.value.mode != Mode.kiff) return
    _state.update {
      it.copy(
        versions = it.versions + (tafId to read.versions),
        switches = read.switching?.let { sw -> it.switches + (tafId to sw) } ?: (it.switches - tafId),
        versionErrors = it.versionErrors - tafId,
      )
    }
  }

  /**
   * The Card a version brings, as the owner reads it before confirming: its draft, each revised Card with its
   * terms now. Null, with the reason in [AppState.versionErrors], when it can't be read.
   */
  suspend fun versionCard(version: Version, tafId: String): AgentDraft? {
    val tenant = kiffTenant
    val id = _state.value.switchDraft(version, tafId)
    if (_state.value.mode != Mode.kiff || tenant == null || id == null) return null
    val generation = accountGeneration
    kiff.tenant = tenant
    return try {
      kiff.draft(id).takeIf { generation == accountGeneration }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) versionError(tafId, Words.Version.cardNotRead(why = ErrorWords.of(e)))
      null
    }
  }

  /**
   * Begins the switch to a version: tafs cloud prepares the Card draft it waits for, which the owner then reads, and
   * from here nothing of the taf runs. With [redraft], its Cards are drafted again. Null, with the reason in
   * [AppState.versionErrors], when it can't begin; a run in the way is named, with the one step that clears it.
   */
  suspend fun prepareSwitch(version: Version, tafId: String, redraft: Boolean = false, stopBlocking: Boolean = false): Switching? {
    val cloudId = _state.value.taf(tafId)?.cloudId
    if (_state.value.mode != Mode.kiff || cloudId == null || version.live) return null
    val generation = accountGeneration
    versionError(tafId, null)
    _state.update { it.copy(blockedInstalls = it.blockedInstalls - tafId) }
    return try {
      cloud.beginSwitch(cloudId, version.n, redraft, stopBlocking).also { sw ->
        if (generation == accountGeneration) _state.update { it.copy(switches = it.switches + (tafId to sw), staleVersionCards = it.staleVersionCards - tafId) }
      }.takeIf { generation == accountGeneration }
    } catch (e: CancellationException) {
      throw e
    } catch (e: CloudError) {
      if (generation != accountGeneration) return null
      if (e.status == 409 && e.blocking.isNotEmpty()) {
        _state.update { it.copy(blockedInstalls = it.blockedInstalls + (tafId to version.n)) }
        versionError(tafId, e.words ?: Words.Version.cantGoLive(n = version.n.toString(), why = ErrorWords.of(e)))
      } else {
        versionError(tafId, Words.Version.cantGoLive(n = version.n.toString(), why = ErrorWords.of(e)))
      }
      null
    } catch (e: Exception) {
      if (generation == accountGeneration) versionError(tafId, Words.Version.cantGoLive(n = version.n.toString(), why = ErrorWords.of(e)))
      null
    }
  }

  /**
   * Makes a version live, forward or back, behind one confirmation. When the version brings a Card revision, the
   * switch begins first, so nothing of the taf runs from then until it ends; then the owner's confirmation signs the
   * draft's confirmation, which KIFF verifies; then the version goes live. A switch that stops half way stays begun
   * (the taf doesn't run), and tafs cloud ends it once KIFF has the Card, or this phone finishes or cancels it.
   * Otherwise it only asks for the confirmation: nobody checks that signature, so it is the owner's presence, never a
   * record. Such a switch changes no authority; KIFF decides every call under the live Cards.
   *
   * [shownDraft] is the version of the draft the owner's screen showed: the confirmation signs exactly that, and a
   * version that brings a Card with nothing shown signs nothing.
   *
   * [approving]: the held version_install of Coach's this install answers (kiff/tafs#206): approved once the version is
   * live, under the same one fingerprint. With a Card, the Card's confirm names it, and KIFF approves it with the Card; a
   * KIFF that doesn't, or a call it didn't take, is answered as before, with a fingerprint of its own. Without a Card, the
   * answer is signed before the version goes live and sent after, so its fingerprint is the confirmation. What's in the
   * way of the switch is stopped first.
   */
  suspend fun useVersion(version: Version, tafId: String, shownDraft: String? = null, approving: HeldCall? = null): Boolean {
    val s = _state.value
    val tenant = kiffTenant
    val taf = s.taf(tafId)
    val cloudId = taf?.cloudId
    if (s.mode != Mode.kiff || tenant == null || taf == null || cloudId == null) return false
    if (version.live || tafId in s.switchingVersion) return false
    _state.update { it.copy(switchingVersion = it.switchingVersion + (tafId to version.n), changedVersionCards = it.changedVersionCards - tafId) }
    val held = approving?.let { HeldInstall(it) }
    try {
      if (!install(version, tafId, taf.name, cloudId, tenant, shownDraft, stopBlocking = approving != null, held = held)) return false
    } finally {
      kiff.setupSigner = null
      _state.update { it.copy(switchingVersion = it.switchingVersion - tafId) }
    }
    // Live: now Coach's held call is approved, with the confirmation just given.
    if (held != null) answer(held.call, Answer.ApproveOnce, confirmed = true, approvedWithCard = held.approvedWithCard, presigned = held.signed)
    return true
  }

  /** Coach's held install as [install] goes: approved by KIFF with the Card's confirm, or its answer signed before going live. */
  private class HeldInstall(val call: HeldCall) {
    var approvedWithCard = false
    var signed: SignedAnswer? = null
  }

  /** Coach's held install, ready for the owner: the taf, the version, and whether it brings a Card to read before the confirmation. */
  data class CoachInstall(val call: HeldCall, val tafId: String, val version: Version, val hasCard: Boolean)

  /**
   * The taf and version Coach's held install names (kiff/tafs#206), with its switch begun (what's in the way stopped first) and
   * the Card draft it waits for: the owner reads that Card before the one confirmation. A version live already is only told to
   * Coach, approving the held call. Null, with why, when it can't begin.
   */
  suspend fun beginCoachInstall(call: HeldCall): CoachInstall? {
    val ask = call.install ?: return null
    val tafs = _state.value.tafs
    val taf = tafs.firstOrNull { it.cloudId == ask.taf } ?: tafs.firstOrNull { it.name.equals(ask.taf, ignoreCase = true) }
    if (taf == null) {
      fail(Words.Version.coachUnknownTaf)
      return null
    }
    loadVersions(taf.id)
    val version = _state.value.versions[taf.id]?.firstOrNull { it.n == ask.n }
    if (version == null) {
      fail(Words.Version.noSuch(taf = taf.name, n = ask.n.toString()))
      return null
    }
    if (version.live) {
      // Live already: approving only tells Coach so.
      answer(call, Answer.ApproveOnce, installed = true)
      return null
    }
    val sw = prepareSwitch(version, taf.id, stopBlocking = true) ?: return null
    return CoachInstall(call, taf.id, version, hasCard = sw.draft != null)
  }

  private suspend fun install(
    version: Version,
    tafId: String,
    name: String,
    cloudId: String,
    tenant: String,
    shownDraft: String?,
    stopBlocking: Boolean = false,
    held: HeldInstall? = null,
  ): Boolean {
    val generation = accountGeneration
    versionError(tafId, null)
    kiff.tenant = tenant

    // Begun before any Card changes, with the draft tafs cloud prepared: from here nothing of the taf runs. Again for
    // a switch under way is the same switch.
    val sw = try {
      cloud.beginSwitch(cloudId, version.n, stopBlocking = stopBlocking)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) versionError(tafId, Words.Version.cantGoLive(n = version.n.toString(), why = ErrorWords.of(e)))
      return false
    }
    if (generation != accountGeneration) return false
    _state.update { it.copy(switches = it.switches + (tafId to sw)) }

    var draft: AgentDraft? = null
    if (sw.draft != null) {
      // Nothing shown, nothing signed.
      if (shownDraft == null) return false
      if (!_state.value.sessionHasKey) return dropSwitch(tafId, Words.SignIn.againToSignDevice(bot.tafs.app.model.ThisDevice.word))
      if (_state.value.approvalKeyLost) return dropSwitch(tafId, KiffClient.APPROVAL_KEY_LOST)
      draft = try {
        kiff.draft(sw.draft)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == accountGeneration) versionError(tafId, Words.Version.cardNotRead(why = ErrorWords.of(e)))
        return false
      }
    }
    if (generation != accountGeneration) return false

    // The Card changed since it was shown: the new one goes on screen, the switch is dropped, and the owner reads it
    // again before any confirmation.
    if (draft != null && draft.version != shownDraft && !draft.confirmed && draft.status != "claimed") {
      _state.update { it.copy(changedVersionCards = it.changedVersionCards + (tafId to draft)) }
      cancelSwitch(tafId)
      versionError(tafId, VersionWords.OUT_OF_DATE_CARD)
      return false
    }
    // A claimed draft is later than confirmed, its Cards issued all the same.
    if (draft != null && !draft.confirmed && draft.status != "claimed") {
      // Coach's held install goes with the Card's confirm, so the one fingerprint approves both, and the prompt says so.
      val hold = held?.let { holdAnswered(it.call) }
      try {
        confirmGrant("tafs.cards.v1|$tenant|${draft.id}|${draft.version}", if (hold != null) Words.Version.Prompt.useWithCardCoach(n = version.n.toString(), taf = name) else Words.Version.Prompt.useWithCard(n = version.n.toString(), taf = name))
        if (generation != accountGeneration) return false
        val signer = signer
        kiff.setupSigner = { signer.signConfirmed(it) }
        if (hold == null) {
          kiff.confirmDraft(draft.id, draft.version)
        } else {
          held.approvedWithCard = kiff.confirmDraft(draft.id, draft.version, hold).approvedHold(hold.id)
        }
      } catch (e: SignerException) {
        // The owner backed out before the Card changed: the taf runs again as it was.
        if (generation == accountGeneration) {
          cancelSwitch(tafId)
          notSigned(e, "").takeIf { it.isNotEmpty() }?.let { versionError(tafId, it) }
        }
        return false
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation != accountGeneration) return false
        if (e is KiffError.Server && e.status == 409 && e.reason == "card_revised") {
          // A Card moved on since it was drafted (KIFF's reason): drafted again, it is read again.
          _state.update { it.copy(staleVersionCards = it.staleVersionCards + (tafId to version.n)) }
          versionError(tafId, Words.Version.cardOutOfDate(n = version.n.toString()))
        } else {
          versionError(tafId, Words.Version.installUnconfirmed(n = version.n.toString(), why = ErrorWords.of(e)))
        }
        return false
      }
      // Coach's tools are never given inside another confirmation: still to give, their own step is asked.
      if (_state.value.coachTools?.toGive == true) _state.update { it.copy(coachToolsAsked = true) }
    } else if (held != null && holdAnswered(held.call) != null) {
      // Coach's install with no Card: approving Coach's held call is the confirmation, signed now, before the version goes
      // live, and sent once it is. One fingerprint, and nothing goes live unless the owner gave it.
      try {
        held.signed = signAnswer(held.call, Answer.ApproveOnce, Words.Version.Prompt.useCoach(n = version.n.toString(), taf = name), confirmed = false)
      } catch (e: SignerException) {
        if (generation == accountGeneration) {
          cancelSwitch(tafId)
          notSigned(e, "").takeIf { it.isNotEmpty() }?.let { versionError(tafId, it) }
        }
        return false
      }
    } else {
      try {
        signer.sign("tafs.version.v1|$tenant|$cloudId|${version.n}".toByteArray(), Words.Version.Prompt.use(n = version.n.toString(), taf = name))
      } catch (_: SignerException) {
        // Backed out: the switch it began is dropped, and the taf runs again as it was.
        if (generation == accountGeneration) cancelSwitch(tafId)
        return false
      }
    }
    if (generation != accountGeneration) return false
    try {
      cloud.makeLive(cloudId, version.n)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) {
        versionError(
          tafId,
          if (sw.draft == null) Words.Version.notLive(n = version.n.toString(), why = ErrorWords.of(e))
          else Words.Version.installUnfinished(n = version.n.toString(), why = ErrorWords.of(e)),
        )
      }
      return false
    }
    loadVersions(tafId)
    // A version with a Card of its own issued it: the taf's page reads its Cards again, or it shows the old limits.
    if (sw.draft != null && generation == accountGeneration) refresh()
    return generation == accountGeneration
  }

  /** A switch that can't go on from this phone is dropped, so the taf runs again as it was; says why. */
  private suspend fun dropSwitch(tafId: String, words: String): Boolean {
    cancelSwitch(tafId)
    versionError(tafId, words)
    return false
  }

  /**
   * Drops a taf's switch under way, while its Card isn't confirmed: the live version stays and the taf runs again.
   * Once its Card is confirmed, tafs cloud ends the switch instead. Either way the versions are read again.
   */
  suspend fun cancelSwitch(tafId: String) {
    val cloudId = _state.value.taf(tafId)?.cloudId
    if (_state.value.mode != Mode.kiff || cloudId == null) return
    val generation = accountGeneration
    try {
      cloud.cancelSwitch(cloudId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation != accountGeneration) return
      versionError(tafId, Words.Version.switchNotCancelled(why = ErrorWords.of(e)))
    }
    loadVersions(tafId)
  }

  // MARK: Doors (docs/doors.md)

  private fun doorsOf(tafId: String): TafDoors = _state.value.doors[tafId] ?: TafDoors()

  private fun updateDoors(tafId: String, change: (TafDoors) -> TafDoors) =
    _state.update { it.copy(doors = it.doors + (tafId to change(it.doors[tafId] ?: TafDoors()))) }

  /**
   * Reads a taf's doors and the knocks waiting, trying again quietly when a read fails (a device's first request after it
   * wakes often times out); only a read that keeps failing says so, in plain words.
   */
  suspend fun loadDoors(tafId: String) {
    val cloudId = _state.value.taf(tafId)?.cloudId
    if (_state.value.mode != Mode.kiff || cloudId == null) return
    val generation = accountGeneration
    for (attempt in 0..DOORS_RETRY_MS.size) {
      try {
        val read = cloud.doors(cloudId)
        if (generation != accountGeneration) return
        updateDoors(tafId) { it.copy(doors = read.doors, apps = read.apps, knocks = read.knocks, loaded = true, error = null) }
        return
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        if (attempt >= DOORS_RETRY_MS.size) break
        delay(DOORS_RETRY_MS[attempt])
        if (generation != accountGeneration) return
      }
    }
    if (generation == accountGeneration) updateDoors(tafId) { it.copy(error = Words.Door.notRead) }
  }

  /** A change on tafs cloud that answers with the door: it replaces the one shown. */
  private suspend fun doorWork(tafId: String, change: suspend (cloudId: String) -> Door?): Door? {
    val cloudId = _state.value.taf(tafId)?.cloudId
    if (_state.value.mode != Mode.kiff || cloudId == null) return null
    val generation = accountGeneration
    updateDoors(tafId) { it.copy(busy = true) }
    return try {
      val door = change(cloudId)
      if (generation == accountGeneration && door != null) {
        updateDoors(tafId) { d -> d.copy(doors = if (d.doors.any { it.id == door.id }) d.doors.map { if (it.id == door.id) door else it } else d.doors + door, error = null) }
      }
      door
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) updateDoors(tafId) { it.copy(error = ErrorWords.of(e)) }
      null
    } finally {
      if (generation == accountGeneration) updateDoors(tafId) { it.copy(busy = false) }
    }
  }

  /** Turns a door on: email at once. WhatsApp's signup runs in a browser the app doesn't open here yet. */
  suspend fun addDoor(tafId: String, app: DoorApp): Door? {
    if (app.kind == "whatsapp") {
      updateDoors(tafId) { it.copy(error = Words.Door.whatsappAndroid) }
      return null
    }
    return doorWork(tafId) { cloud.addDoor(it, app.id) }
  }

  suspend fun setDoorOpen(tafId: String, door: Door, open: Boolean) {
    doorWork(tafId) { cloud.setDoorOpen(it, door.id, open) }
  }

  suspend fun removeDoor(tafId: String, door: Door) {
    // A door with planque's subscription (a Slack channel's, or a bot's): it goes first (a signed delete), then the door, so nothing listens for a door that's gone.
    if (door.grant != null && door.trigger != null) {
      val tenant = kiffTenant ?: return
      val account = _state.value.grants.firstOrNull { it.id == door.grant }?.account
      if (account != null) {
        planque.tenant = tenant
        planque.deviceLoader = { kiff.thisDeviceId() }
        val signer = signer
        try {
          planque.signer = { signer.signConfirmed(it) }
          planque.unsubscribe(door.grant, door.trigger, account)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          updateDoors(tafId) { it.copy(error = if (door.kind == "slack") Words.Door.slackStillListens(why = ErrorWords.of(e)) else Words.Door.botStillListens(why = ErrorWords.of(e))) }
          return
        } finally {
          planque.signer = null
        }
      }
    }
    doorWork(tafId) { cloudId ->
      cloud.removeDoor(cloudId, door.id)
      updateDoors(tafId) { d -> d.copy(doors = d.doors.filterNot { it.id == door.id }) }
      null
    }
  }

  /** Pairs a sender with a door: their messages give work from now on, within the Card. */
  suspend fun pairDoor(tafId: String, door: Door, sender: String, name: String) {
    val who = sender.trim()
    if (who.isEmpty()) return
    doorWork(tafId) { cloud.pair(it, door.id, who, name.trim()) }
  }

  suspend fun unpairDoor(tafId: String, door: Door, key: DoorKey) {
    doorWork(tafId) { cloud.unpair(it, door.id, key.id) }
  }

  /** The owner's answer to a knock. "do" starts the taf's run from their words. */
  suspend fun answerKnock(tafId: String, knock: Knock, action: String, say: String? = null) {
    val cloudId = _state.value.taf(tafId)?.cloudId
    if (_state.value.mode != Mode.kiff || cloudId == null) return
    val generation = accountGeneration
    updateDoors(tafId) { it.copy(busy = true) }
    try {
      cloud.answerKnock(cloudId, knock.id, action, say)
      if (generation != accountGeneration) return
      // Pairing keeps the message waiting, with Do it and Ignore.
      if (action == "pair") loadDoors(tafId) else updateDoors(tafId) { d -> d.copy(knocks = d.knocks.filterNot { it.id == knock.id }) }
      updateDoors(tafId) { it.copy(error = null) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation != accountGeneration) return
      // In the app's words, as the chat says it, not tafs cloud's.
      val words = if (e is CloudError && e.status == 429) Words.Door.Knock.tooMany else Words.Door.Knock.notSent(why = ErrorWords.of(e))
      updateDoors(tafId) { it.copy(error = words) }
    } finally {
      if (generation == accountGeneration) updateDoors(tafId) { it.copy(busy = false) }
    }
  }

  /** The doors error was read. */
  fun dismissDoorsError(tafId: String) = updateDoors(tafId) { it.copy(error = null) }

  // MARK: Trying a version (kiff/tafs#173)

  private fun trialError(tafId: String, words: String?) =
    _state.update { it.copy(trialErrors = if (words == null) it.trialErrors - tafId else it.trialErrors + (tafId to words)) }

  /** Marks a taf's trial as being worked on, so one tap does one thing; false when it already is. */
  private fun beginTrialWork(tafId: String): Boolean {
    var began = false
    _state.update { s -> if (tafId in s.trialBusy) s else { began = true; s.copy(trialBusy = s.trialBusy + tafId) } }
    return began
  }

  private fun endTrialWork(tafId: String) = _state.update { it.copy(trialBusy = it.trialBusy - tafId) }

  /** Shows a taf's open trial, or none: a trial that ended takes its chat with it. */
  private fun setTrial(open: OpenTrial?, tafId: String) {
    val chat = trialChatId(tafId)
    if (open == null) {
      followers.remove(chat)?.cancel()
      _state.update {
        it.copy(
          openTrials = it.openTrials - tafId,
          chats = it.chats - chat,
          chatSent = it.chatSent - chat,
          chatUnsent = it.chatUnsent - chat,
          chatErrors = it.chatErrors - chat,
          chatWorking = it.chatWorking - chat,
          chatAwaiting = it.chatAwaiting - chat,
        )
      }
    } else {
      _state.update { it.copy(openTrials = it.openTrials + (tafId to open)) }
    }
  }

  /** Reads a taf's open trial. A trial that ended elsewhere (Install, Go back, a week unused) leaves here too. */
  suspend fun loadTrial(tafId: String) {
    val cloudId = _state.value.taf(tafId)?.cloudId
    if (_state.value.mode != Mode.kiff || cloudId == null) return
    val generation = accountGeneration
    val open = try {
      cloud.openTrial(cloudId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) trialError(tafId, Words.Version.Trial.notRead(why = ErrorWords.of(e)))
      return
    }
    if (generation == accountGeneration) setTrial(open, tafId)
  }

  /**
   * The Card a trial of version n runs under, as the owner reads it before confirming: the draft tafs cloud prepares
   * for it. Null, with the reason in [AppState.trialErrors], when it can't be read.
   */
  suspend fun trialCard(version: Version, tafId: String): AgentDraft? {
    val cloudId = _state.value.taf(tafId)?.cloudId
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || tenant == null || cloudId == null) return null
    val generation = accountGeneration
    kiff.tenant = tenant
    return try {
      kiff.draft(cloud.trialDraft(cloudId, version.n)).takeIf { generation == accountGeneration }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) trialError(tafId, Words.Version.Trial.cardNotRead(n = version.n.toString(), why = ErrorWords.of(e)))
      null
    }
  }

  /**
   * Starts trying version n: tafs cloud gives the draft of its trial Card, the owner read it ([shownDraft], its version),
   * one confirmation confirms it in KIFF as a trial, and the trial opens. Returns its chat's id.
   */
  suspend fun startTrying(version: Version, tafId: String, shownDraft: String): String? {
    val s = _state.value
    val tenant = kiffTenant
    val taf = s.taf(tafId)
    val cloudId = taf?.cloudId
    if (s.mode != Mode.kiff || tenant == null || taf == null || cloudId == null || version.live) return null
    if (!beginTrialWork(tafId)) return null
    try {
      trialError(tafId, null)
      if (!_state.value.sessionHasKey) {
        trialError(tafId, Words.SignIn.againToSignDevice(bot.tafs.app.model.ThisDevice.word))
        return null
      }
      if (_state.value.approvalKeyLost) {
        trialError(tafId, KiffClient.APPROVAL_KEY_LOST)
        return null
      }
      val generation = accountGeneration
      kiff.tenant = tenant
      val draft = try {
        kiff.draft(cloud.trialDraft(cloudId, version.n))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == accountGeneration) trialError(tafId, Words.Version.Trial.cantTry(n = version.n.toString(), why = ErrorWords.of(e)))
        return null
      }
      if (generation != accountGeneration) return null
      // What the owner read is what the confirmation signs: changed since, it is read again.
      if (draft.version != shownDraft) {
        _state.update { it.copy(changedVersionCards = it.changedVersionCards + (tafId to draft)) }
        trialError(tafId, Words.Version.Trial.changed)
        return null
      }
      try {
        confirmGrant("tafs.trial.v1|$tenant|${draft.id}|${draft.version}", Words.Version.Trial.prompt(n = version.n.toString(), taf = taf.name))
        if (generation != accountGeneration) return null
        val signer = signer
        kiff.setupSigner = { signer.signConfirmed(it) }
        val confirmed = kiff.confirmDraftAsTrial(draft.id, draft.version)
        // Only a KIFF that runs trials answers "trying"; anything else is never taken for a trial.
        if (confirmed.status != "trying") {
          if (generation == accountGeneration) {
            trialError(tafId, VersionWords.notATrial(confirmed.status))
            loadVersions(tafId)
          }
          return null
        }
        val open = cloud.openTrial(cloudId, version.n, draft.id)
        if (generation != accountGeneration) return null
        setTrial(open, tafId)
        return trialChatId(tafId)
      } catch (e: SignerException) {
        if (generation == accountGeneration) notSigned(e, "").takeIf { it.isNotEmpty() }?.let { trialError(tafId, it) }
        return null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == accountGeneration) trialError(tafId, Words.Version.Trial.notStarted(n = version.n.toString(), why = ErrorWords.of(e)))
        return null
      }
    } finally {
      kiff.setupSigner = null
      endTrialWork(tafId)
    }
  }

  /**
   * Install: one confirmation confirms the trial's Card live in KIFF, then the version goes live and the trial ends, its
   * chat deleted.
   */
  suspend fun installTrial(tafId: String): Boolean {
    val s = _state.value
    val tenant = kiffTenant
    val taf = s.taf(tafId)
    val cloudId = taf?.cloudId
    val open = s.openTrials[tafId]
    if (s.mode != Mode.kiff || tenant == null || taf == null || cloudId == null || open == null) return false
    if (!beginTrialWork(tafId)) return false
    try {
      trialError(tafId, null)
      val generation = accountGeneration
      kiff.tenant = tenant
      try {
        val draft = kiff.draft(open.draft)
        if (generation != accountGeneration) return false
        if (!draft.confirmed) {
          if (!_state.value.sessionHasKey) {
            trialError(tafId, Words.SignIn.againToSignDevice(bot.tafs.app.model.ThisDevice.word))
            return false
          }
          confirmGrant("tafs.cards.v1|$tenant|${draft.id}|${draft.version}", Words.Version.Prompt.install(n = open.n.toString(), taf = taf.name))
          if (generation != accountGeneration) return false
          val signer = signer
          kiff.setupSigner = { signer.signConfirmed(it) }
          kiff.confirmDraft(draft.id, draft.version)
        }
        cloud.installTrial(cloudId)
      } catch (e: SignerException) {
        if (generation == accountGeneration) notSigned(e, "").takeIf { it.isNotEmpty() }?.let { trialError(tafId, it) }
        return false
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == accountGeneration) trialError(tafId, Words.Version.Trial.installUnfinished(n = open.n.toString(), why = ErrorWords.of(e)))
        return false
      }
      if (generation != accountGeneration) return false
      setTrial(null, tafId)
      loadVersions(tafId)
      return true
    } finally {
      kiff.setupSigner = null
      endTrialWork(tafId)
    }
  }

  /** Go back: KIFF ends the trial's Cards, the trial's chat is deleted, and the live version stays as it was. It takes authority away, so no confirmation. */
  suspend fun goBackFromTrial(tafId: String): Boolean {
    val s = _state.value
    val tenant = kiffTenant
    val cloudId = s.taf(tafId)?.cloudId
    val open = s.openTrials[tafId]
    if (s.mode != Mode.kiff || tenant == null || cloudId == null || open == null) return false
    if (!beginTrialWork(tafId)) return false
    try {
      trialError(tafId, null)
      val generation = accountGeneration
      kiff.tenant = tenant
      val signer = signer
      kiff.setupSigner = { signer.signConfirmed(it) }
      try {
        kiff.endTrial(open.draft)
        cloud.endTrial(cloudId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == accountGeneration) trialError(tafId, Words.Version.Trial.notEnded(why = ErrorWords.of(e)))
        return false
      }
      if (generation != accountGeneration) return false
      setTrial(null, tafId)
      loadVersions(tafId)
      return true
    } finally {
      kiff.setupSigner = null
      endTrialWork(tafId)
    }
  }

  // MARK: Halt

  /** tafs cloud's id for a taf, "" for the account; null for a taf tafs cloud doesn't run. */
  private fun haltKey(tafId: String?): String? = if (tafId == null) "" else _state.value.taf(tafId)?.cloudId

  /**
   * Halts a taf, or with none every taf: one tap, no confirmation, since it only takes authority away. tafs
   * cloud halts it in kiff/agents and KIFF at once; it reads halted only once kiff/agents, KIFF's gateway and
   * planque all hold it, and halting until then.
   */
  suspend fun halt(tafId: String?) {
    val key = haltKey(tafId)
    if (_state.value.mode != Mode.kiff || key == null) return
    val generation = accountGeneration
    _state.update { it.copy(haltError = null) }
    val halt = try {
      cloud.halt(key.ifEmpty { null })
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) haltFailed(if (tafId == null) Words.Taf.Halt.notHaltedAll(why = ErrorWords.of(e)) else Words.Taf.Halt.notHalted(why = ErrorWords.of(e)))
      return
    }
    if (generation != accountGeneration) return
    _state.update { it.copy(halts = it.halts + ((tafId.orEmpty()) to halt)) }
    // The list reads it halted at once, not at its next read; the halt sheet doesn't wait for the whole list to show its own.
    scope.launch { refresh() }
  }

  /** Reads where a halt stands. */
  suspend fun refreshHalt(tafId: String?) {
    val key = haltKey(tafId)
    if (_state.value.mode != Mode.kiff || key == null) return
    val generation = accountGeneration
    val halt = attempt { cloud.halted(key.ifEmpty { null }) } ?: return
    if (generation == accountGeneration) _state.update { it.copy(halts = it.halts + (tafId.orEmpty() to halt), haltError = null) }
  }

  /** A Halt or Resume that didn't go through says why where it was asked, the Halt screen, not behind it. */
  private fun haltFailed(words: String) = _state.update { it.copy(haltError = words) }

  /**
   * Resumes a halted taf, or with none every taf, behind the owner's confirmation: KIFF first, signed by this
   * phone, since it gives authority back; then tafs cloud, which lets kiff/agents run it only once KIFF says it is
   * resumed. What it was doing before the halt doesn't come back.
   */
  // MARK: The account's time zone

  /**
   * The account as KIFF says it: every time the app shows is in its time zone (docs/accounts.md). When this device's zone
   * differs, it is offered once: "Not now" is remembered for that zone and account, and a new zone asks again.
   */
  private fun showAccount(account: Account) {
    AccountTime.use(account.timeZone ?: "UTC")
    val device = java.time.ZoneId.systemDefault().id
    val offer = device.takeIf { it != (account.timeZone ?: "UTC") && secrets.load(declinedZoneKey()) != it }
    _state.update { it.copy(kiffAccount = account, timeZoneOffer = offer) }
  }

  private fun declinedZoneKey(): String = "tafs.timeZone.declined|${kiffTenant.orEmpty()}"

  /** The owner's answer to the time zone offer: yes sets it (one confirmation), no is remembered for that zone. */
  suspend fun answerTimeZoneOffer(use: Boolean) {
    val zone = _state.value.timeZoneOffer ?: return
    _state.update { it.copy(timeZoneOffer = null) }
    if (use) useThisDevicesTimeZone() else secrets.save(zone, declinedZoneKey())
  }

  /** Sets the account's time zone to this device's, so a Card's day ends at the owner's midnight: a signed call, which KIFF takes from the owner or an admin. */
  suspend fun useThisDevicesTimeZone() = setAccountTimeZone(java.time.ZoneId.systemDefault().id)

  suspend fun setAccountTimeZone(zone: String) {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || tenant == null) return
    val generation = accountGeneration
    _state.update { it.copy(timeZoneError = null) }
    try {
      signer.sign("tafs.time-zone.v1|$tenant|$zone".toByteArray(), Words.Settings.TimeZone.prompt(zone = zone))
    } catch (_: SignerException) {
      return
    }
    if (generation != accountGeneration) return
    val signer = signer
    kiff.tenant = tenant
    kiff.setupSigner = { signer.signConfirmed(it) }
    try {
      kiff.setTimeZone(zone)
      if (generation != accountGeneration) return
      attempt { kiff.me() }?.let { account -> if (generation == accountGeneration) showAccount(account) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(timeZoneError = Words.Settings.TimeZone.notChanged(why = ErrorWords.of(e))) }
      return
    } finally {
      kiff.setupSigner = null
    }
    applyAccountTimeZone()
  }

  /**
   * KIFF has the new zone: tafs cloud moves the schedules to it, then every time on screen is read again in it. Pages and
   * Setups already read are dropped, since their times were written in the old zone.
   */
  suspend fun applyAccountTimeZone() {
    val generation = accountGeneration
    try {
      cloud.applyAccountTimeZone()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(timeZoneError = ErrorWords.of(e)) }
    }
    if (generation != accountGeneration) return
    val knobs = _state.value.knobs.keys
    presentationGeneration++
    _state.update { it.copy(activityPages = emptyMap(), runPages = emptyMap(), tafSetups = emptyMap(), works = emptyMap()) }
    refresh()
    knobs.forEach { attempt { loadKnob(it) } }
  }

  // MARK: People, groups and sharing

  /** Reads the account's groups, its people and this person's access to each taf. */
  suspend fun refreshPeople() {
    if (_state.value.mode != Mode.kiff) {
      _state.update { it.copy(groups = emptyList(), people = emptyList()) }
      return
    }
    val generation = accountGeneration
    try {
      val (groups, people, access) = coroutineScope {
        val g = async { kiff.groups() }
        val p = async { kiff.people() }
        val a = async { kiff.myAccess() }
        Triple(g.await(), p.await(), a.await())
      }
      if (generation != accountGeneration) return
      _state.update {
        it.copy(
          groups = groups.sortedWith(compareBy({ g -> if (g.everyone == true) 0 else 1 }, { g -> g.name.lowercase() })),
          people = people.filter { p -> p.active }.sortedBy { p -> p.label.lowercase() },
          access = access,
          peopleError = null,
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(peopleError = Words.People.notRead(why = ErrorWords.of(e))) }
    }
  }

  /**
   * Makes one change to groups or sharing, behind one confirmation as KIFF asks of a phone, then reads them again. [what] is
   * said at the prompt. A change that [grants] use of a taf asks for a fingerprint for each share it adds instead, as the
   * approval key signs each. False when it didn't go through.
   */
  suspend fun changePeople(what: String, grants: Boolean = false, change: suspend (KiffClient) -> Unit): Boolean {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || tenant == null) return false
    val generation = accountGeneration
    _state.update { it.copy(peopleError = null) }
    try {
      if (grants) confirmGrant("tafs.people.v1|$tenant|$what", what) else signer.sign("tafs.people.v1|$tenant|$what".toByteArray(), what)
    } catch (e: SignerException) {
      notSigned(e, "").takeIf { it.isNotEmpty() }?.let { words -> _state.update { it.copy(peopleError = words) } }
      return false
    }
    if (generation != accountGeneration) return false
    val signer = signer
    kiff.tenant = tenant
    kiff.setupSigner = { signer.signConfirmed(it) }
    try {
      change(kiff)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation != accountGeneration) return false
      // Read again first: a read that works clears the error, and this one must stay on screen.
      refreshPeople()
      val words = if (e is SignerException) notSigned(e, Words.Common.didntGoThroughUnconfirmed(what = what)) else Words.Common.didntGoThrough(what = what, why = ErrorWords.of(e))
      if (generation == accountGeneration) _state.update { it.copy(peopleError = words) }
      return false
    } finally {
      kiff.setupSigner = null
    }
    refreshPeople()
    return true
  }

  /** Who a taf's KIFF agent is shared with; null when KIFF couldn't say. */
  suspend fun shares(agent: String): List<Share>? {
    if (_state.value.mode != Mode.kiff) return null
    kiff.tenant = kiffTenant
    return try {
      kiff.shares(agent)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }

  /** Shares a taf with [add] and stops sharing it with [remove], behind one confirmation for all of it. */
  suspend fun setShares(agent: String, name: String, add: Set<Share>, remove: Set<Share>): Boolean =
    changePeople(Words.People.Share.title(taf = name), grants = add.isNotEmpty()) { kiff ->
      add.forEach { kiff.share(agent, it) }
      remove.forEach { kiff.unshare(agent, it) }
    }

  // MARK: Devices

  /** Reads the devices signed in as this person on the account. */
  suspend fun refreshDevices() {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || tenant == null) {
      _state.update { it.copy(devices = emptyList()) }
      return
    }
    val generation = accountGeneration
    kiff.tenant = tenant
    try {
      val list = kiff.devices()
      if (generation == accountGeneration) _state.update { it.copy(devices = list, devicesError = null) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(devicesError = Words.Settings.Devices.notLoaded(why = ErrorWords.of(e))) }
    }
  }

  /** Signs another device out of the account, behind one confirmation. This device signs out with Sign out instead. False when it didn't go through. */
  suspend fun removeDevice(device: Device): Boolean {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || tenant == null || device.thisDevice) return false
    val generation = accountGeneration
    val what = Words.Settings.Devices.signOutPrompt(device = device.name ?: Words.Settings.Devices.thisDevice)
    try {
      signer.sign("tafs.devices.v1|$tenant|${device.id}".toByteArray(), what)
    } catch (_: SignerException) {
      return false
    }
    if (generation != accountGeneration) return false
    kiff.tenant = tenant
    try {
      kiff.removeDevice(device.id)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(devicesError = Words.Common.didntGoThrough(what = what, why = ErrorWords.of(e))) }
      return false
    }
    refreshDevices()
    return true
  }

  // MARK: Deleting the account

  /** Clears why deleting the account stopped, as its sheet opens. */
  fun clearDeleteAccountError() {
    _state.update { it.copy(deleteAccountError = null) }
  }

  /**
   * Deletes the account everywhere, behind one confirmation (kiff/tafs#185): tafs cloud erases every taf and what it and
   * kiff/agents keep; then planque forgets the account's connections; then KIFF, last, since it ends this phone's session.
   * Then the app signs out. Every step can be asked again, so when one fails the owner tries again and it goes on from
   * there. True once the account is deleted.
   */
  suspend fun deleteAccount(): Boolean {
    _state.update { it.copy(deleteAccountError = null) }
    if (_state.value.mode != Mode.kiff || _state.value.deletingAccount) return false
    val tenant = kiffTenant
    if (!_state.value.sessionHasKey || tenant == null) {
      _state.update { it.copy(deleteAccountError = Words.SignIn.againToSignDevice(bot.tafs.app.model.ThisDevice.word)) }
      return false
    }
    val generation = accountGeneration
    try {
      signer.sign("tafs.delete-account.v1|$tenant".toByteArray(), Words.Settings.DeleteAccount.prompt)
    } catch (_: SignerException) {
      return false
    }
    if (generation != accountGeneration) return false
    fun stopped(why: String): Boolean {
      if (generation == accountGeneration) _state.update { it.copy(deleteAccountError = why) }
      return false
    }
    _state.update { it.copy(deletingAccount = true) }
    try {
      try {
        if (!cloud.deleteAccount().done) return stopped(Words.Settings.DeleteAccount.notErasedYet)
      } catch (e: CancellationException) {
        throw e
      } catch (e: CloudError) {
        return stopped(if (e.status == 403) Words.Settings.DeleteAccount.ownerOnly else Words.Settings.DeleteAccount.notErased(why = ErrorWords.of(e)))
      } catch (e: Exception) {
        return stopped(Words.Settings.DeleteAccount.notErased(why = ErrorWords.of(e)))
      }
      if (generation != accountGeneration) return false
      val signer = signer
      planque.tenant = tenant
      planque.deviceLoader = { kiff.thisDeviceId() }
      planque.signer = { signer.signConfirmed(it) }
      kiff.tenant = tenant
      kiff.setupSigner = { signer.signConfirmed(it) }
      try {
        try {
          planque.eraseAccount()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return stopped(Words.Settings.DeleteAccount.connectionsLeft(why = ErrorWords.of(e)))
        }
        try {
          kiff.deleteAccount()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return stopped(Words.Settings.DeleteAccount.kiffNotDeleted(why = ErrorWords.of(e)))
        }
      } finally {
        kiff.setupSigner = null
        planque.signer = null
      }
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(deletingAccount = false) }
    }
    // KIFF ended this session with the account: nothing is left to tell it.
    signOut(tellKiff = false)
    return true
  }

  suspend fun resumeHalt(tafId: String?): Boolean {
    val key = haltKey(tafId)
    if (_state.value.mode != Mode.kiff || key == null) return false
    val tenant = kiffTenant
    if (!_state.value.sessionHasKey || tenant == null) {
      haltFailed(Words.SignIn.againToSignDevice(bot.tafs.app.model.ThisDevice.word))
      return false
    }
    if (_state.value.approvalKeyLost) {
      haltFailed(KiffClient.APPROVAL_KEY_LOST)
      return false
    }
    val name = tafId?.let { _state.value.taf(it)?.name }
    val generation = accountGeneration
    _state.update { it.copy(haltError = null) }
    try {
      // Resuming gives authority back: the approval key signs KIFF's request, behind its own fingerprint. A confirmation still
      // unanswered after 10 seconds is cancelled, the prompt with it, so a late answer sends nothing (#464 item 18).
      kotlinx.coroutines.withTimeoutOrNull(RESUME_CONFIRM_MS) {
        confirmGrant("tafs.resume.v1|$tenant|${tafId ?: "*"}", name?.let { Words.Taf.Halt.resumePrompt(taf = it) } ?: Words.Taf.Halt.resumeAllPrompt)
      } ?: throw bot.tafs.app.signing.ConfirmationTimedOut()
      if (generation != accountGeneration) return false
      kiff.tenant = tenant
      val signer = signer
      kiff.setupSigner = { signer.signConfirmed(it) }
      // With the approval key, its fingerprint is asked as KIFF's request is signed: the same 10 seconds bound it.
      val confirm = kiff.approvalSigner
      val bounded: (suspend (() -> ByteArray) -> String)? = confirm?.let { c ->
        { built -> kotlinx.coroutines.withTimeoutOrNull(RESUME_CONFIRM_MS) { c(built) } ?: throw bot.tafs.app.signing.ConfirmationTimedOut() }
      }
      kiff.approvalSigner = bounded
      try {
        kiff.resume(tafId)
      } finally {
        // Put back only if nothing changed it meanwhile (a sign-out clears it).
        if (kiff.approvalSigner === bounded) kiff.approvalSigner = confirm
      }
    } catch (e: SignerException) {
      if (generation == accountGeneration) haltFailed(notSigned(e, Words.Taf.Halt.notResumedUnconfirmed))
      return false
    } catch (_: KiffError.ApprovalKeyRequired) {
      haltFailed(KiffClient.APPROVAL_KEY_LOST)
      return false
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) haltFailed(name?.let { Words.Taf.Halt.kiffNotResumed(taf = it, why = ErrorWords.of(e)) } ?: Words.Taf.Halt.kiffNotResumedAll(why = ErrorWords.of(e)))
      return false
    } finally {
      kiff.setupSigner = null
    }
    if (generation != accountGeneration) return false
    val halt = try {
      cloud.releaseHalt(key.ifEmpty { null })
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) haltFailed(name?.let { Words.Taf.Halt.cloudNotResumed(taf = it, why = ErrorWords.of(e)) } ?: Words.Taf.Halt.cloudNotResumedAll(why = ErrorWords.of(e)))
      return false
    }
    if (generation != accountGeneration) return false
    _state.update { it.copy(halts = it.halts + (tafId.orEmpty() to halt)) }
    refresh()
    return true
  }

  // MARK: Usage

  /** Reads the period's usage: from KIFF signed in, a made-up month in the sample. */
  // MARK: Buying through Google Play

  private var purchasesListening: Job? = null

  /**
   * Once signed in, with Play Billing in this build: each purchase goes to KIFF for this account, and is finished only after
   * KIFF has recorded it. What Play reports while the app runs is sent as it comes, and what wasn't finished is sent again now.
   */
  private suspend fun startPurchases() {
    if (!playBilling || _state.value.mode != Mode.kiff) return
    _state.update { it.copy(playBilling = true) }
    purchases.record = { product, token, request -> kiff.recordGooglePurchase(product, token, request) }
    purchases.recorded = { refreshUsage(); refresh() }
    if (purchasesListening?.isActive != true) {
      purchasesListening = scope.launch { purchases.updates.collect { purchases.deliver(it, null) } }
    }
    attempt { purchases.sendUnfinished() }
  }

  /** Reads Play's names and prices for what KIFF sells, when buying is offered here. */
  suspend fun loadStore() {
    val store = _state.value.usage?.store ?: return
    if (!_state.value.offersPurchases) return
    purchases.load(store.googleProducts)
    val unfinished = purchases.anyUnfinished()
    _state.update {
      it.copy(
        storeProducts = purchases.products.value, storeLoaded = purchases.loaded.value, storeFailed = purchases.loadFailed.value,
        restoreShown = unfinished != false || it.purchaseNote?.offersRestore == true,
      )
    }
  }

  // MARK: Asking an admin for tasks

  /** Asks the account's admins for a pack. False, saying why, when KIFF didn't take it. */
  suspend fun askForPack(pack: String?, reason: String): Boolean {
    _state.update { it.copy(tasksMessage = null) }
    if (_state.value.mode != Mode.kiff) return false
    return try {
      kiff.askForPack(pack, reason.trim())
      refreshUsage()
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _state.update { it.copy(tasksMessage = Words.Billing.Request.notSent(why = ErrorWords.of(e))) }
      false
    }
  }

  /** Takes back this person's request. */
  suspend fun withdrawRequest(id: String) {
    _state.update { it.copy(tasksMessage = null) }
    if (_state.value.mode != Mode.kiff) return
    try {
      kiff.withdrawRequest(id)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _state.update { it.copy(tasksMessage = Words.Billing.Request.notWithdrawn(why = ErrorWords.of(e))) }
    }
    refreshUsage()
  }

  /** Declines a member's request, with a note they'll read: the admin's answer, behind one confirmation, signed. */
  suspend fun declineRequest(id: String, note: String): Boolean {
    _state.update { it.copy(tasksMessage = null) }
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || tenant == null) return false
    if (!_state.value.sessionHasKey) {
      _state.update { it.copy(tasksMessage = Words.SignIn.againToSignDevice(bot.tafs.app.model.ThisDevice.word)) }
      return false
    }
    val generation = accountGeneration
    try {
      signer.sign("tafs.decline-request.v1|$tenant|$id".toByteArray(), Words.Billing.Request.declinePrompt)
    } catch (_: SignerException) {
      return false
    }
    if (generation != accountGeneration) return false
    val signer = signer
    kiff.tenant = tenant
    kiff.setupSigner = { signer.signConfirmed(it) }
    try {
      kiff.declineRequest(id, note.trim())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation != accountGeneration) return false
      _state.update { it.copy(tasksMessage = Words.Billing.Request.notDeclined(why = ErrorWords.of(e))) }
      refreshUsage()
      return false
    } finally {
      kiff.setupSigner = null
    }
    refreshUsage()
    return true
  }

  /**
   * Buys Solo or a pack for the account, answering a member's request when there is one. How it went is said under what was
   * tapped; a request's, on the request. Play's offers are read again after, whatever the outcome.
   */
  suspend fun buy(product: String, requestId: String? = null) {
    _state.update { it.copy(tasksMessage = null, purchaseNote = null) }
    val token = _state.value.usage?.store?.appAccountToken
    if (!_state.value.offersPurchases || token == null) {
      _state.update { it.copy(tasksMessage = Words.Billing.adminAddsTasks) }
      return
    }
    _state.update { it.copy(buying = product) }
    val outcome = purchases.buy(product, token, requestId)
    val words = buyWords(outcome)
    _state.update {
      if (requestId != null) it.copy(buying = null, tasksMessage = words ?: it.tasksMessage)
      else it.copy(buying = null, purchaseNote = words?.let { w -> PurchaseNote(product, w, offersRestore = outcome is PurchaseOutcome.NotRecorded) })
    }
    loadStore()
  }

  /** Brings back this Google account's purchases not finished yet, and sends each to KIFF. */
  suspend fun restorePurchases() {
    _state.update { it.copy(tasksMessage = null, purchaseNote = null) }
    if (!_state.value.offersPurchases) return
    _state.update { it.copy(buying = PurchaseNote.RESTORE) }
    val outcome = purchases.restore()
    val words = when (val o = outcome) {
      PurchaseOutcome.Done -> Words.Billing.restored
      // Never "restored" when nothing came back.
      PurchaseOutcome.NothingToRestore -> Words.Billing.nothingToRestore
      is PurchaseOutcome.NotRecorded -> Words.Billing.restoredNotRecorded
      is PurchaseOutcome.NotAdded -> o.why
      is PurchaseOutcome.NeedsSupport -> o.why
      is PurchaseOutcome.Failed -> Words.Billing.notRestored(why = o.why)
      else -> null
    }
    _state.update { it.copy(buying = null, purchaseNote = words?.let { PurchaseNote(PurchaseNote.RESTORE, it, offersRestore = outcome is PurchaseOutcome.NotRecorded) }) }
    loadStore()
  }

  private fun buyWords(o: PurchaseOutcome): String? = when (o) {
    PurchaseOutcome.Done, PurchaseOutcome.Cancelled, PurchaseOutcome.NothingToRestore -> null
    PurchaseOutcome.Pending -> Words.Billing.pendingCredits
    is PurchaseOutcome.NotRecorded -> Words.Billing.boughtNotRecorded
    is PurchaseOutcome.NotAdded -> o.why
    is PurchaseOutcome.NeedsSupport -> o.why
    // Play unreachable is said on its own, with what to do: nothing was bought.
    is PurchaseOutcome.Failed -> if (o.why == bot.tafs.app.billing.Purchases.PLAY_UNREACHABLE) o.why else Words.Billing.notBought(why = o.why)
  }

  /**
   * Raise the limit (docs/accounts.md#the-balance): one step more on the spend limit, for the person the balance offers it to
   * (`raise`). KIFF's `PUT /v1/me/usage/limit`, signed by the device key once the person confirms, as KIFF takes it (a phone-signed
   * route, not the approval key's); KIFF refuses a replayed signature, so one tap is one step. Then the balance is read again.
   */
  suspend fun raiseLimit(): Boolean {
    if (_state.value.mode != Mode.kiff || _state.value.raising || _state.value.balanceStale) return false
    // The raise names the limit it raises from: none read, none offered.
    val from = _state.value.balance?.balance?.raise?.fromCents ?: return false
    val tenant = kiffTenant
    if (!_state.value.sessionHasKey || tenant == null) {
      _state.update { it.copy(raiseError = Words.SignIn.againToSignDevice(bot.tafs.app.model.ThisDevice.word)) }
      return false
    }
    val generation = accountGeneration
    _state.update { it.copy(raising = true, raiseError = null) }
    // Busy until the balance is read after KIFF took it: never a second tap against the limit from before.
    var sent = false
    try {
      signer.sign("tafs.raise-limit.v1|$tenant|$from".toByteArray(), Words.Usage.RaiseLimit.prompt)
      if (generation != accountGeneration) return false
      kiff.tenant = tenant
      val signer = signer
      kiff.setupSigner = { signer.signConfirmed(it) }
      kiff.raiseLimit(from)
      sent = true
    } catch (e: SignerException) {
      if (generation == accountGeneration) _state.update { it.copy(raiseError = notSigned(e, Words.Common.notDoneUnconfirmed)) }
      return false
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation != accountGeneration) return false
      val server = e as? KiffError.Server
      if (server?.status == 409 && server.refused("limit_changed")) {
        // Someone else raised it, or the screen was from before: read it again, and the raise is offered from the new limit.
        readBalanceNow(generation)
        // The app's own words, as iPhone's: KIFF's say to read it again, which the app has just done.
        _state.update { it.copy(raiseError = bot.tafs.app.model.UsageWords.LIMIT_CHANGED) }
      } else if (server?.status == 409 && server.refused("limit_ceiling")) {
        // KIFF's words say to contact it; the app's, the same as iPhone's, where it sends none.
        _state.update { it.copy(raiseError = server.words ?: bot.tafs.app.model.UsageWords.LIMIT_CEILING) }
      } else {
        _state.update { it.copy(raiseError = server?.words ?: Words.Usage.RaiseLimit.notRaised(why = ErrorWords.of(e))) }
      }
      return false
    } finally {
      kiff.setupSigner = null
      if (!sent && generation == accountGeneration) _state.update { it.copy(raising = false) }
    }
    if (generation != accountGeneration) return false
    // KIFF has it: read again, so the balance says the new limit. A read that fails marks what's shown stale.
    val read = try {
      attempt { cloud.balance() }
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(raising = false) }
    }
    if (generation == accountGeneration) _state.update { if (read != null) it.copy(balance = read, balanceStale = false, balanceError = null) else it.copy(balanceStale = true) }
    return true
  }

  /** Read it again, after a raise whose read failed: reads the balance and nothing else. Signs nothing. */
  suspend fun readBalance() {
    if (_state.value.mode != Mode.kiff || _state.value.readingBalance) return
    val generation = accountGeneration
    _state.update { it.copy(readingBalance = true) }
    val read = try { attempt { cloud.balance() } } finally { if (generation == accountGeneration) _state.update { it.copy(readingBalance = false) } }
    if (generation == accountGeneration && read != null) _state.update { it.copy(balance = read, balanceStale = false, balanceError = null) }
  }

  suspend fun refreshUsage() {
    when (_state.value.mode) {
      Mode.signedOut -> _state.update {
        it.copy(usage = null, balance = null, balanceError = null, balanceStale = false, credits = null, creditsError = null, tafCredits = null)
      }
      // The sample is the demo: a balance and credits, as tafs cloud words them.
      Mode.sample -> _state.update { it.copy(usage = null, usageError = null, balance = bot.tafs.app.model.SampleCredits.balance, credits = bot.tafs.app.model.SampleCredits.credits(it.creditsPrevious)) }
      Mode.kiff -> {
        val generation = accountGeneration
        // The balance first: what Plan & usage opens on.
        readBalanceNow(generation)
        // KIFF's usage read, for the store, who bills the plan and requests; the balance doesn't wait on it.
        try {
          val read = kiff.usage()
          if (generation != accountGeneration) return
          _state.update { it.copy(usage = read, usageError = null) }
          loadStore()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          if (generation == accountGeneration) _state.update { it.copy(usageError = Words.Usage.notRead(why = ErrorWords.of(e))) }
        }
        readCredits(_state.value.creditsPrevious)
      }
    }
  }

  /**
   * The balance as a refresh reads it: a 404 (a tafs cloud from before it) shows none, and a failure keeps what was read and says
   * why, in tafs cloud's words where it sends some. After a raise whose read failed, a read that works clears the stale mark.
   */
  private suspend fun readBalanceNow(generation: Int) {
    try {
      val read = cloud.balance()
      if (generation == accountGeneration) _state.update { it.copy(balance = read, balanceError = null, balanceStale = if (read != null) false else it.balanceStale) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(balanceError = (e as? CloudError)?.words ?: Words.Usage.balanceNotRead(why = ErrorWords.of(e))) }
    }
  }

  /** Shows this period's credits, or the period before's with [previous]. */
  suspend fun showCreditsPeriod(previous: Boolean) {
    when (_state.value.mode) {
      Mode.sample -> _state.update { it.copy(credits = bot.tafs.app.model.SampleCredits.credits(previous), creditsPrevious = previous) }
      Mode.kiff -> readCredits(previous)
      Mode.signedOut -> {}
    }
  }

  private suspend fun readCredits(previous: Boolean) {
    val generation = accountGeneration
    _state.update { it.copy(readingCredits = true) }
    try {
      val read = cloud.credits(previous)
      if (generation == accountGeneration) _state.update { it.copy(credits = read, creditsPrevious = previous, creditsError = null) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // tafs cloud's words when it can't read KIFF (503); what was shown stays, said as not read now.
      if (generation == accountGeneration) _state.update { it.copy(creditsError = (e as? CloudError)?.words ?: Words.Usage.creditsNotRead(why = ErrorWords.of(e))) }
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(readingCredits = false) }
    }
  }

  /**
   * Opens one taf's runs in the period shown, from its Credits row or its page's Usage row ([taf] is tafs cloud's id for it). With
   * no sheet open yet, a read that fails says why in the "Not done" alert; one from before credits (404) opens nothing.
   */
  suspend fun openTafCredits(taf: String) {
    if (_state.value.mode != Mode.kiff) return
    val generation = accountGeneration
    _state.update { it.copy(tafCreditsError = null, requestError = null) }
    try {
      val read = cloud.tafCredits(taf, _state.value.creditsPrevious) ?: return
      if (generation == accountGeneration) _state.update { it.copy(tafCredits = read) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation != accountGeneration) return
      val words = (e as? CloudError)?.words ?: Words.Usage.tafRunsNotRead(why = ErrorWords.of(e))
      if (_state.value.tafCredits == null) fail(words) else _state.update { it.copy(tafCreditsError = words) }
    }
  }

  /** The next page of the open taf's runs, after the last one read. */
  suspend fun moreTafCredits() {
    val open = _state.value.tafCredits ?: return
    val after = open.next ?: return
    if (_state.value.readingCredits) return
    val generation = accountGeneration
    _state.update { it.copy(readingCredits = true, tafCreditsError = null) }
    try {
      val page = cloud.tafCredits(open.taf, !open.period.current, after) ?: return
      if (generation == accountGeneration) _state.update {
        // Only onto the list it follows: one closed or opened again since keeps its own.
        if (it.tafCredits?.taf != open.taf || it.tafCredits.next != after) it
        else it.copy(tafCredits = page.copy(items = it.tafCredits.items + page.items.filter { r -> it.tafCredits.items.none { o -> o.run == r.run } }))
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(tafCreditsError = (e as? CloudError)?.words ?: Words.Usage.moreRunsNotRead(why = ErrorWords.of(e))) }
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(readingCredits = false) }
    }
  }

  fun closeTafCredits() = _state.update { it.copy(tafCredits = null, tafCreditsError = null) }

  /** Opens a run's request from its Credits row, on the taf tafs cloud names by its own id. */
  suspend fun openCreditsRun(taf: String, run: String) {
    val tafId = _state.value.tafs.firstOrNull { it.cloudId == taf }?.id
    if (tafId == null) {
      _state.update { it.copy(requestError = Words.NeedsYou.requestTafMissing) }
      return
    }
    openRequest(tafId, run)
  }

  /** The plan, for Settings' Plan card and Home's line: tafs cloud's balance, as each refresh reads it. */
  private suspend fun refreshPlan(generation: Int) = readBalanceNow(generation)

  private fun fail(words: String) = _state.update { it.copy(lastError = words) }

  /** The "Not done" alert was read. */
  fun dismissError() = _state.update { it.copy(lastError = null) }

  private fun resolve(call: HeldCall) = _state.update { s -> s.copy(held = s.held.filterNot { it.id == call.id }) }

  private fun update(taf: Taf) = _state.update { s -> s.copy(tafs = s.tafs.map { if (it.id == taf.id) taf else it }) }

  private fun amountLine(p: ProposedAction, withAmount: (String) -> String, alone: String): String =
    p.amount?.let { withAmount(it.formatted) } ?: alone

  private fun entry(tafId: String, kind: RecordEntry.Kind, title: String, detail: String, proposed: ProposedAction? = null, signature: String? = null) =
    RecordEntry(tafId = tafId, kind = kind, title = title, detail = detail, at = clock(), signature = signature,
      ownerApproved = if (kind == RecordEntry.Kind.approved) true else null, amountText = proposed?.amount?.formatted)

  private fun log(tafId: String, kind: RecordEntry.Kind, title: String, detail: String, proposed: ProposedAction? = null) =
    _state.update { it.copy(record = listOf(entry(tafId, kind, title, detail, proposed)) + it.record) }

  /**
   * Records the owner's answer where the call was held, under the id KIFF's record gives it (its proposal's), so it
   * folds the card in place, a refresh doesn't lose it, and once KIFF records the outcome the two are one line
   * (kiff/tafs#138, item 51).
   */
  private fun decide(call: HeldCall, kind: RecordEntry.Kind, title: String, detail: String, signature: String?) {
    val key = call.proposalId ?: call.kiffId
    val id = key?.let(KiffMapping::recordId) ?: newId()
    val line = entry(call.tafId, kind, title, detail, call.proposed, signature).copy(id = id, at = call.heldAt, byYou = true)
    _state.update { s -> s.copy(record = listOf(line) + s.record.filterNot { it.id == id }) }
    if (call.kiffId != null) decided[id] = line
  }

  private fun save() {
    val s = _state.value
    // Only the sample is kept on the phone; a KIFF account's data is KIFF's.
    if (s.mode != Mode.sample) return
    store?.save(SampleStore.Snapshot(s.tafs, s.held, s.record, s.scriptPosition, s.templateOf))
  }

  /** The held call's page in KIFF, where it can be answered today; null for the sample's. */
  fun answerPage(call: HeldCall): String? = call.kiffId?.let { kiff.answerPage(it) }

  class Factory(
    private val store: SampleStore?,
    private val signer: ApprovalSigning,
    private val kiff: KiffClient,
    private val cloud: TafsCloud,
    private val secrets: SecretStore,
    private val deviceName: String,
    private val push: PushPlatform = NoPush,
    private val planque: PlanqueClient = PlanqueClient(bot.tafs.app.kiff.UrlConnectionTransport()),
    private val vendorSignIn: VendorSignIn = NoVendorSignIn,
    private val purchases: Purchases = Purchases(NoPlayStore),
    private val playBilling: Boolean = false,
    private val openedFiles: java.io.File? = null,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      AppModel(
        store, signer, kiff, cloud, secrets, deviceName = deviceName, push = push, planque = planque, vendorSignIn = vendorSignIn,
        purchases = purchases, playBilling = playBilling, openedFiles = openedFiles ?: java.io.File(System.getProperty("java.io.tmpdir"), "attachments"),
      ) as T
  }

  companion object {
    /** tafs cloud's codes for a run that waits on credits. */
    val CREDITS_WAIT = setOf("credits_used_up", "limit_reached", "no_plan")
    /** Where the keys of the `not_sent` messages Retry or Edit took are kept, and how many: iOS keeps the same 500. */
    const val NOT_SENT_TAKEN = "tafs.chat.not-sent-taken"
    const val NOT_SENT_KEPT = 500

    /** tafs cloud's refusal of a message when as much as one message can be already waits for the taf (docs/chat.md). */
    const val HELD_FULL = "held_full"

    /** Why a message can't go to a taf that tafs cloud doesn't run. */
    val NOT_ON_CLOUD get() = Words.Chat.notOnCloud

    /**
     * The message this card's approval may sign, rebuilt from what the phone holds: its own account, the card's grant and the
     * digest the person was shown for the connect step; the card's draft and the version the person was shown for the Cards
     * step. Null for an ask that names another grant, draft, digest or version, or a step this app doesn't know, and when
     * nothing was shown: the phone signs nothing it can't rebuild itself from what the person read.
     */
    fun expectedApprovalMessage(asked: bot.tafs.app.kiff.ApprovalAsk, c: SetupComponent, tenant: String, shownDigest: String?, shownDraft: String?): String? {
      return when (asked.step) {
        "connect" -> {
          val p = asked.planque ?: return null
          val digest = runCatching { p.body.jsonObject["digest"]?.jsonPrimitive?.content }.getOrNull() ?: return null
          // The digest is what the person saw, never the ask's: terms they never read are never signed.
          if (p.grant != c.grant || shownDigest == null || digest != shownDigest) null else "tafs.connect.v1|$tenant|${c.grant}|$digest"
        }
        "cards" -> {
          val k = asked.kiff ?: return null
          val draft = c.draft ?: return null
          if (k.draft != draft || shownDraft == null || k.version != shownDraft) null else "tafs.cards.v1|$tenant|$draft|${k.version}"
        }
        // The key and its Card under one signature the phone builds itself (`kiff.requests.v1`): no message from tafs cloud,
        // and the card's grant at the digest shown, and its draft at the version shown, as for each step alone.
        "key_and_cards" -> {
          val p = asked.planque ?: return null
          val k = asked.kiff ?: return null
          val digest = runCatching { p.body.jsonObject["digest"]?.jsonPrimitive?.content }.getOrNull() ?: return null
          val draft = c.draft ?: return null
          when {
            asked.scheme != "kiff.requests.v1" -> null
            p.grant != c.grant || shownDigest == null || digest != shownDigest -> null
            k.draft != draft || shownDraft == null || k.version != shownDraft -> null
            else -> ""
          }
        }
        else -> null
      }
    }

    /** How often a thread is read again while a run is at work in it, and how many times at most. */
    const val CHAT_POLL_MS = 2_500L
    const val CHAT_POLL_BUDGET = 150

    /** The stops of the run's own: it ends with no words for it. */
    val CAP_STOPS = setOf("cost_cap", "turn_cap", "max_token", "refused")

    /** What the chat says when its latest run stopped at a cap, never "…". */
    fun capWords(stop: String, taf: String): String? = when (stop) {
      "cost_cap" -> Words.Chat.Cap.cost(taf = taf)
      "turn_cap" -> Words.Chat.Cap.turns(taf = taf)
      // A reply cut off at its length limit, and the AI declining to go on (docs/chat.md).
      "max_token" -> Words.Chat.Stopped.outOfRoom(taf = taf)
      "refused" -> Words.Chat.Stopped.declined(taf = taf)
      else -> null
    }

    /** A run that failed part-way, with what went through before it stopped, from the run's own record. */
    fun failedWords(taf: String, done: List<bot.tafs.app.kiff.DoneCall>, words: String? = null): String {
      val calls = done.joinToString("; ") { c -> KiffMapping.toolWords(c.tool).let { tool -> c.resource?.let { Words.Chat.callOn(tool = tool, resource = it) } ?: tool } }
      // tafs cloud's words say why, as they are; what went through still follows, from the run's own record.
      if (words != null) return if (done.isEmpty()) words else Words.Chat.Failed.withWords(words = words.trim(), calls = calls)
      if (done.isEmpty()) return Words.Chat.Failed.nothing(taf = taf)
      return Words.Chat.Failed.after(taf = taf, calls = calls)
    }

    /** Waits between tries of a doors read that failed. */
    val DOORS_RETRY_MS = listOf(1_000L, 3_000L)

    /** The platform KIFF records for this device's key. */
    const val PLATFORM = "android"

    // What the session is kept under, as iOS's Keychain accounts.
    const val TOKEN = "kiff.session"
    /** A sign-in's code while the person approves it in the browser. */
    const val PENDING_SIGN_IN = "kiff.sign-in-pending"
    const val TENANT = "kiff.tenant"
    const val KEYED = "kiff.session-keyed"
    const val APPROVAL_KEYED = "kiff.session-approval-keyed"

    /** What the fingerprint prompt says when Approve again proves this phone holds its new key. */
    val RELINK_REASON get() = Words.SignIn.Relink.prompt

    /** How long an approval window is asked for: KIFF takes five minutes at most, and restarts it with each approval in it. */
    const val WINDOW_SECONDS = 300L

    /** How long the app may be in the background before the window it holds is closed (kiff/tafs#494). */
    const val WINDOW_BACKGROUND_MS = 30_000L

    /** KIFF answered, but holds another key, or this one was turned off while the browser waited. */
    val RELINK_DIDNT_TAKE get() = Words.SignIn.Relink.didntTake

    val RELINK_FAILED get() = Words.SignIn.Relink.failed

    // What alerts keep on this phone: a target's secret not yet handed to tafs cloud, whether alerts were turned on, and the sound.
    const val HANDOFF = "kiff.alert-handoff"
    const val ALERTS_ON = "kiff.alerts-on"
    /** Alerts were offered on a new taf's card for an account (the key ends with the account's id): shown once. */
    const val ALERTS_OFFERED_PREFIX = "kiff.alerts-offered#"
    /** How long Resume waits for its confirmation before cancelling it. */
    const val RESUME_CONFIRM_MS = 10_000L
    /** The account whose alerts were on when this phone last signed out of it, so signing in to it again brings them back. */
    const val ALERTS_WERE_ON = "kiff.alerts-were-on"
    const val ALERT_SOUND = "kiff.alert-sound"
    const val ALERT_KINDS = "kiff.alert-kinds"

    /** The waits between a failed refresh's tries, then none until the person comes back or pulls (kiff/tafs#318). */
    val REFRESH_RETRY_SECONDS = listOf(2L, 10L, 30L, 60L, 120L)

    /** How long a sign-out waits before asking KIFF again to end the session, the last one again and again until it has. */
    val SIGN_OUT_RETRY_SECONDS = listOf(2L, 10L, 30L, 60L, 120L)

    /** A sign-out KIFF hasn't confirmed: the phone stays signed in, and says so, and keeps trying. */
    val NOT_SIGNED_OUT get() = Words.SignIn.notSignedOut
  }
}

/**
 * The tafs that use a connection: a live Card rule, or a tool, on one of the actions it connects. KIFF's tools are the
 * account's, so a taf can draw on a connection another taf asked for; [except] leaves out the one being removed.
 */
fun AppState.tafsUsing(grant: bot.tafs.app.kiff.Grant, except: String? = null): List<Taf> {
  val actions = (grant.tools ?: grant.request.tools.filter { it.tafs }.map { it.action }).toSet()
  if (actions.isEmpty()) return emptyList()
  return tafs.filter { t ->
    t.id != except && t.state != TafState.revoked && !t.card.revoked &&
      (t.card.rules.any { it.action in actions } || t.tools.any { it in actions })
  }
}

/** A receipts export: the files to offer, or why it couldn't be made. */
sealed interface ReceiptsResult {
  data class Ready(val export: bot.tafs.app.kiff.ReceiptsExport) : ReceiptsResult
  data class NotExported(val why: String) : ReceiptsResult
}

/** The receipt on the secure sheet: which grant got its key, and the pasted key's last 4 characters, never kept after it closes. */
data class KeyReceipt(val grant: String, val last4: String)

/** The key sheet opened from Coach's approval card: the card, answered once the key went, and the draft version it showed. */
data class KeyForCard(val card: SetupComponent, val draft: String?)

/** "Allow this from now on", open on [call]: tafs cloud's answer, KIFF's draft as this phone read it, and whether it's what was offered. */
data class AllowSheetState(
  val call: HeldCall,
  val answer: bot.tafs.app.kiff.AllowAnswer? = null,
  val draft: AgentDraft? = null,
  val matches: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
  val sendHeld: Boolean = true,
  /** The rounded figure was asked for: the offer no longer offers to round. */
  val rounded: Boolean = false,
)
