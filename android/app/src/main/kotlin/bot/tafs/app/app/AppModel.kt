package bot.tafs.app.app

import bot.tafs.app.model.ErrorWords
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import bot.tafs.app.model.Answer
import bot.tafs.app.model.AttachmentRef
import bot.tafs.app.model.FilePrep
import bot.tafs.app.model.PendingFile
import bot.tafs.app.model.PreparedFile
import bot.tafs.app.model.RunReport
import bot.tafs.app.model.Chat
import bot.tafs.app.model.ChatItem
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.ChatEnd
import bot.tafs.app.model.LiveAnswer
import bot.tafs.app.model.LiveStep
import bot.tafs.app.kiff.StreamEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import bot.tafs.app.kiff.AIUse
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
import bot.tafs.app.kiff.CountedTask
import bot.tafs.app.kiff.Halt
import bot.tafs.app.kiff.Usage
import bot.tafs.app.kiff.UsageAgent
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

/** What a person may do, as KIFF says it: a KIFF that doesn't say leaves it to KIFF to decide. */
internal fun allows(capabilities: List<String>?, capability: String): Boolean = capabilities?.contains(capability) ?: true

/** An Approve again waiting for the browser: the code the phone shows, which KIFF's page shows too, and the page's link. */
data class Relink(val userCode: String, val link: String)

/** What the screens draw. */
data class AppState(
  val mode: Mode = Mode.signedOut,
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
  /** What the owner sent that tafs cloud's thread doesn't have yet, by taf: kept on screen until it does. */
  val chatSent: Map<String, List<ChatMessage>> = emptyMap(),
  /** Each halt as last read, by the taf's id, "" for the whole account. */
  val halts: Map<String, Halt> = emptyMap(),
  /** The account's usage this period, once read; and why it couldn't be. */
  val usage: Usage? = null,
  val usageError: String? = null,
  /** The period's counted tasks, newest first; and each taf's own, once its page read them. */
  val countedTasks: List<CountedTask> = emptyList(),
  val tafTasks: Map<String, List<CountedTask>> = emptyMap(),
  val aiUse: AIUse? = null,
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
  /** The version each improvement result made, by its key; those being made, and those that failed (with why). */
  val improvedVersions: Map<String, Int> = emptyMap(),
  val makingVersions: Set<String> = emptySet(),
  val improveErrors: Map<String, String> = emptyMap(),
  /** Coach's cards in its thread, folded. */
  val coachComponents: List<ShownComponent> = emptyList(),
  /** The Coach conversation shown (kiff/tafs#73): one the owner reopened or started here, or null for the latest ones together. */
  val coachChat: String? = null,
  /** The owner asked for a new conversation: the next message starts one. */
  val coachFresh: Boolean = false,
  /** The taf a new Coach conversation improves (kiff/tafs#107), by its id here: its first message goes to the improve route. */
  val coachImproving: String? = null,
  /** Coach's past conversations, newest first, and the archived ones (kiff/tafs#97). */
  val coachChats: List<TafsCloud.Chat> = emptyList(),
  val coachArchived: List<TafsCloud.Chat> = emptyList(),
  /** News of the tasks Coach handed tafs, until Coach reads it (kiff/tafs#206). */
  val coachLines: List<TafsCloud.CoachLine> = emptyList(),
  /** Coach was asked to open, from a screen without it: the tab screen opens it, then says so. */
  val coachRequested: Boolean = false,
  /** Coach's tools as KIFF has them, from tafs cloud; null until read. And why giving them didn't go. */
  val coachTools: bot.tafs.app.kiff.CoachTools? = null,
  val coachToolsError: String? = null,
  /** The account's planque grants: connections, and connections waiting for the owner; and whether planque answered this phone yet. */
  val grants: List<Grant> = emptyList(),
  val planqueReady: Boolean = false,
  /** The grant being connected now, so a second tap starts nothing. */
  val connecting: String? = null,
  /** The drafts Coach's approvals name, read from KIFF so the owner sees the Cards. */
  val setupDrafts: Map<String, AgentDraft> = emptyMap(),
  /** Why a card's step wasn't done, by card. */
  val coachNotes: Map<String, String> = emptyMap(),
  /** Alerts can work in this build (Firebase is set up), whether this phone gets them, and whether this person may answer holds, as tafs cloud last said. */
  val alertsAvailable: Boolean = false,
  val alertsReady: Boolean = false,
  val canAnswerHolds: Boolean? = null,
  val turningOnAlerts: Boolean = false,
  /** Sound for alerts on this phone: off, tafs cloud sends this phone's alerts quietly. */
  val alertSound: Boolean = true,
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
  val buying: String? = null,
  val tasksMessage: String? = null,
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

  /**
   * Doors are tafs cloud's, and only where it offers one: a taf it runs, that this person manages: who may give it work
   * and from where is the manager's to say.
   */
  fun hasDoors(tafId: String): Boolean = mode == Mode.kiff && doorsOffered.isNotEmpty() && taf(tafId)?.cloudId != null && manages(tafId)

  /**
   * A taf's connections: the grants made for its KIFF agent that are connected, or whose sign-in planque holds while Coach chooses
   * the tools, so the owner can see and end them.
   */
  fun connections(tafId: String): List<Grant> = grants.filter { it.request.agent == tafId && (it.connected || it.holdsSignIn || it.needsReconnect) }

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

  /** Halting is tafs cloud's: the account's, or a taf it runs. */
  fun canHalt(tafId: String?): Boolean = mode == Mode.kiff && (tafId == null || taf(tafId)?.cloudId != null)

  /** The taf's part of the period, from KIFF's by-agent counts; null until usage is read. A taf KIFF doesn't list counted nothing. */
  fun tafUsage(tafId: String): UsageAgent? =
    usage?.let { u -> u.tasks.byAgent?.firstOrNull { it.agent == tafId } ?: UsageAgent(tafId) }

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
   * Whether buying is offered here: Play Billing in this build, someone who may add tasks, and KIFF naming Play's products and
   * the account's token. Until KIFF records Play purchases it names none, so nothing is offered.
   */
  val offersPurchases: Boolean
    get() = playBilling && mode == Mode.kiff && usage?.canAddTasks == true &&
      usage.store?.appAccountToken != null && usage.store.googleProducts.isNotEmpty()

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

  /** Calls KIFF allowed today that the vendor refused, which still count against the taf's Card totals, newest first. */
  fun refusedToday(tafId: String): List<RecordEntry> =
    entries(tafId).filter { it.refusedByTool == true && localDate(it.at) == java.time.LocalDate.now(zone) }

  /** The account this person is in, by its name, where they can always see it. Null outside KIFF. */
  val accountLabel: String? get() = if (mode == Mode.kiff) kiffAccount?.name else null

  val workingCount: Int get() = tafs.count { it.state == TafState.working }

  fun taf(id: String): Taf? = tafs.firstOrNull { it.id == id } ?: otherAgents.firstOrNull { it.id == id } ?: trialTaf(id) ?: coachTaf(id)

  /**
   * Whether this person may use Coach: KIFF's answer for the account's Setup agent, else by default an account admin
   * (the one who manages members).
   */
  val mayUseCoach: Boolean
    get() = mode == Mode.kiff && (access[COACH_AGENT]?.use ?: (kiffAccount?.capabilities?.contains("members.manage") ?: false))

  /** Coach's chat drawn as a taf, so the one chat screen serves both. */
  private fun coachTaf(chatId: String): Taf? {
    if (chatId != COACH_CHAT) return null
    val cloudId = coachCloudId ?: return null
    return Taf(id = COACH_CHAT, name = "Coach", job = "", tools = emptyList(), colorIndex = 2, card = Card(rules = emptyList(), issuedBy = ""), now = "", cloudId = cloudId)
  }

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
      name = "${taf.name} · v${trial.n}",
      badge = trial.label ?: "Trial",
      now = listOfNotNull(trial.title ?: "Trying version ${trial.n}", trial.ends?.let { "ends ${shownDate(it)} if unused" }).joinToString(" · "),
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
   * A taf's chat as one timeline: its messages, the calls held for the owner where they were
   * held, and what became of earlier held calls (approved, refused) since the thread's first
   * message, each as one line.
   */
  /**
   * A chat's items, each once: the list keys its rows by id, and a repeated key crashes it, so an exact repeat (the same
   * message twice in a thread) shows once.
   */
  fun chatItems(tafId: String): List<ChatItem> = chatItemsAsRead(tafId).distinctBy { it.id }

  private fun chatItemsAsRead(tafId: String): List<ChatItem> {
    val messages = chats[tafId] ?: emptyList()
    // Coach's thread has its cards in it, and the owner's answers to them read as what they chose.
    if (tafId == COACH_CHAT) {
      // An older thread's answers, sent as text before cards were answered as data, read as what the owner chose.
      return CoachWords.items(messages.map { if (it.from == ChatMessage.From.owner) it.copy(text = CoachWords.ownerWords(it.text, coachComponents)) else it }, coachComponents)
    }
    val items = mutableListOf<ChatItem>()
    items += messages.map { ChatItem.Message(it) }
    items += heldCalls(tafId).map { ChatItem.Held(it) }
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
  private val ownerName: String = "You",
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
   * The owner's answers to KIFF's held calls, by their record id, until KIFF records the outcome
   * (kiff/tafs#138): a refresh just after an answer still reads the call as held.
   */
  private val decided = mutableMapOf<String, RecordEntry>()

  init {
    // KIFF wants the approval key this phone doesn't hold (kiff/tafs#233).
    kiff.approvalKeyRequired = { lostApprovalKey() }
  }

  // MARK: Where the owner left off

  /** Restores where the owner left off: signed in to KIFF, or the saved sample. */
  suspend fun restore() {
    val token = secrets.load(TOKEN)
    if (token != null) {
      resumeSession(token, secrets.load(TENANT), keyed = secrets.load(KEYED) == "1")
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
    _state.update { it.copy(mode = Mode.kiff, ready = true, sessionHasKey = keyed, alertsAvailable = push.available, alertSound = secrets.load(ALERT_SOUND) != "0") }
    if (keyGone) lostApprovalKey()
    refresh()
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
  suspend fun startSignIn(): DeviceStart {
    signInNudged = false
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
        )
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

  /** Polls until the person approves the code in KIFF, then loads their account. */
  suspend fun finishSignIn(start: DeviceStart) {
    val token = untilApproved(start) { kiff.pollDeviceToken(start.deviceCode) }
    useSession(token.accessToken, token.tenant)
  }

  /**
   * Asks KIFF with [poll] until the person approves [start]'s code in their browser. It keeps asking through a dropped
   * connection or a KIFF error until the code expires.
   */
  private suspend fun <T> untilApproved(start: DeviceStart, poll: suspend () -> T): T {
    var intervalMs = maxOf(start.interval, signInMinimumIntervalSeconds) * 1000L
    val deadline = clock().plusSeconds(start.expiresIn.toLong())
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
    forgetPlanqueDevice()
    relinkJob?.cancel()
    decided.clear()
    followers.values.forEach { it.cancel() }
    followers.clear()
    // Nothing of an earlier session or of the sample shows in this one.
    _state.value = AppState(mode = Mode.kiff, ready = true, sessionHasKey = tenant != null, alertsAvailable = push.available, alertSound = secrets.load(ALERT_SOUND) != "0")
    refresh()
    registerApprovalKey()
    syncPush()
    startPurchases()
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
  suspend fun refresh(retry: Boolean = false) {
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
      // A held install is Coach's only from Coach's own agent: read which that is, once, when one is held.
      if (_state.value.coachAgent == null && read.exceptions.any { it.action == "version_install" }) {
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
      // Removals tafs cloud began and didn't finish: archived tafs aren't listed, so the app finishes them.
      if (read.home.removing.isNotEmpty()) finishRemovals(read.home.removing, generation)
      nameRecordLines(generation)
    } catch (e: CancellationException) {
      cancelled = true
      throw e
    } catch (_: KiffError.Unauthorized) {
      // KIFF ended this session: signed out, with the words to sign in again.
      if (generation == accountGeneration) {
        signOut(tellKiff = false)
        fail(KiffError.Unauthorized.message ?: "Signed out of KIFF. Sign in again.")
      }
      return
    } catch (_: CloudError) {
      if (generation != accountGeneration) return
      // A refresh is the app's own doing, so it never shows as "Not done" (kiff/tafs#318).
      refreshFailed("Couldn't load your tafs", generation)
      _state.update { if (it.listLoaded) it else it.copy(listUnread = true) }
    } catch (e: Exception) {
      if (generation != accountGeneration) return
      refreshFailed(refreshWords(e), generation)
      _state.update { if (it.listLoaded) it else it.copy(listUnread = true) }
    } finally {
      // Read or not, this account's list is no longer loading (KIFF's error shows).
      if (!cancelled && generation == accountGeneration && _state.value.mode == Mode.kiff) _state.update { it.copy(listLoaded = true) }
    }
  }

  private var retrying: Job? = null
  private var retries = 0

  /** Words true to what failed: the network, or KIFF answering with an error. */
  private fun refreshWords(e: Exception): String = if (e is java.io.IOException) "Can't reach KIFF" else "Couldn't load from KIFF"

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
    val held = read.exceptions.map { KiffMapping.held(it, coach) }
    _state.update { s ->
      s.copy(
        tafs = tafs,
        otherAgents = others,
        held = held,
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
  private var approvalReason = "Approve on KIFF"

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
    e is KiffError.Expired -> "The code expired before it was approved in your browser. Try again."
    e is KiffError.Server && e.message == "access_denied" -> "It wasn't approved in the browser."
    e is KiffError.Server && e.message == "relink_in_progress" -> "KIFF is finishing the last one. Try again in a moment."
    e is ApprovalKeyRenewed -> "Your fingerprints changed just now. Try again."
    e is SignerException -> e.message ?: RELINK_FAILED
    e is IOException -> "No connection. Try again."
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
    // This phone stops getting the account's alerts, while the session can still say so.
    if (tellKiff && _state.value.mode == Mode.kiff) pushToken?.let { token -> if (alertsWanted()) runCatching { cloud.unregisterPush(token) } }
    if (tellKiff && _state.value.mode == Mode.kiff) runCatching { kiff.signOut() }
    secrets.delete(ALERTS_ON)
    secrets.delete(HANDOFF)
    secrets.delete(TOKEN)
    secrets.delete(TENANT)
    secrets.delete(KEYED)
    secrets.delete(APPROVAL_KEYED)
    forgetApprovalKey()
    kiff.token = null
    cloud.token = null
    kiff.tenant = null
    kiffTenant = null
    accountGeneration += 1
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
    // Nothing the person attached and opened stays on the phone after them.
    openedFiles.deleteRecursively()
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
    _state.value.tafs.firstOrNull { it.name == "Refunds" }?.let { refunds ->
      val question = "How did this morning go?"
      say(refunds.id, ChatMessage.From.taf, Chat.hello(refunds))
      say(refunds.id, ChatMessage.From.owner, question)
      say(refunds.id, ChatMessage.From.taf, sampleReply(question, refunds.id))
    }
  }

  fun draft(job: String): TafDraft = TafTemplates.draft(job)

  /** The owner's name on a Card they issue, and the colour the next taf takes. */
  val issuer: String get() = ownerName
  val nextColorIndex: Int get() = _state.value.tafs.size

  /**
   * Issues the Card, after the owner confirms it on the phone, and starts the taf; null when they
   * didn't. The sample starts from its form; signed in, a new taf starts in Coach mode.
   */
  suspend fun start(draft: TafDraft): Taf? {
    try {
      signer.sign(Messages.issue("card-${newId()}"), "Issue ${draft.name}'s Card")
    } catch (_: SignerException) {
      fail("The Card was not issued: it wasn't confirmed.")
      return null
    }
    return create(draft)
  }

  private fun create(draft: TafDraft): Taf {
    val s = _state.value
    val taf = Taf(
      name = draft.name, job = draft.job, tools = draft.tools, colorIndex = s.tafs.size,
      card = Card(rules = draft.rules, issuedBy = ownerName, expires = threeMonthsOn(clock())),
      state = TafState.working, now = "Starting",
    )
    _state.update {
      it.copy(
        tafs = listOf(taf) + it.tafs,
        templateOf = if (draft.templateKey != null) it.templateOf + (taf.id to draft.templateKey) else it.templateOf,
        record = listOf(entry(taf.id, RecordEntry.Kind.started, "${taf.name} started", "Card issued by $ownerName")) + it.record,
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
    update(taf.copy(state = if (paused) TafState.paused else TafState.working, now = if (paused) "Paused by you" else "Back to work"))
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
      if (generation == accountGeneration) fail("KIFF didn't revoke the Card. ${ErrorWords.of(e)}")
      return false
    }
    if (generation != accountGeneration) return false
    revoked(tafId)
    return true
  }

  private fun revoked(tafId: String) {
    val taf = _state.value.taf(tafId) ?: return
    val dropped = _state.value.heldCalls(tafId)
    update(taf.copy(card = taf.card.copy(revoked = true), state = TafState.revoked, now = "Stopped: Card revoked"))
    _state.update { s -> s.copy(held = s.held.filterNot { it.tafId == tafId }) }
    val detail = if (dropped.isEmpty()) "No new action goes through" else "No new action goes through. ${dropped.size} waiting action(s) refused."
    log(tafId, RecordEntry.Kind.revoked, "You revoked ${taf.name}'s Card", detail)
    save()
  }

  // MARK: Coach's conversations (kiff/tafs#73, #82, #97, #107)

  /** A new Coach conversation: an empty chat, and the next message starts it. */
  fun newCoachChat() {
    _state.update { s ->
      s.copy(
        coachChat = null, coachFresh = true, coachImproving = null, coachComponents = emptyList(), coachLines = emptyList(),
        chats = s.chats - COACH_CHAT, chatSent = s.chatSent - COACH_CHAT, chatAwaiting = s.chatAwaiting - COACH_CHAT,
        chatEnded = s.chatEnded - COACH_CHAT, chatLive = s.chatLive - COACH_CHAT, chatWorking = s.chatWorking - COACH_CHAT,
        chatErrors = s.chatErrors - COACH_CHAT,
      )
    }
  }

  /** A new Coach conversation on one taf, to improve it: its first message goes to the improve route. */
  fun improveTaf(tafId: String) {
    newCoachChat()
    if (_state.value.taf(tafId)?.cloudId != null) _state.update { it.copy(coachImproving = tafId) }
  }

  /** Improves a taf from wherever its hold is ("Change the limit in Coach mode"): Coach opens on a new conversation about it. */
  fun improveInCoach(tafId: String) {
    improveTaf(tafId)
    _state.update { it.copy(coachRequested = true) }
  }

  /** The tab screen opened Coach. */
  fun coachOpened() = _state.update { it.copy(coachRequested = false) }

  /** Reopens one of Coach's conversations: it's shown alone, and messages go on in it. */
  suspend fun openCoachChat(session: String) {
    _state.update { s ->
      s.copy(
        coachChat = session, coachFresh = false, coachImproving = null,
        chatSent = s.chatSent - COACH_CHAT, chatEnded = s.chatEnded - COACH_CHAT, chatErrors = s.chatErrors - COACH_CHAT,
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
      if (generation == accountGeneration) fail("Not archived. ${ErrorWords.of(e)}")
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
      if (generation == accountGeneration) fail("Not put back. ${ErrorWords.of(e)}")
      return false
    }
    if (generation != accountGeneration) return false
    _state.update { s -> s.copy(coachArchived = s.coachArchived.filterNot { it.session == session }) }
    loadCoachChats()
    return true
  }

  // MARK: Runs, pausing and removing a taf on tafs cloud

  /** Runs a taf now, outside its schedule. The run's session; null when it didn't start, or a Run now for it is on its way. */
  suspend fun runNow(tafId: String): String? {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return null
    if (tafId in _state.value.runsInFlight) return null
    _state.update { it.copy(runsInFlight = it.runsInFlight + tafId) }
    val generation = accountGeneration
    try {
      val session = cloud.runNow(cloudId)
      if (generation == accountGeneration) loadReport(tafId)
      return session
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) fail("The run didn't start. ${ErrorWords.of(e)}")
      return null
    } finally {
      _state.update { it.copy(runsInFlight = it.runsInFlight - tafId) }
    }
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
    val generation = accountGeneration
    val work = try {
      cloud.work(cloudId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(workErrors = it.workErrors + (tafId to "Its work couldn't be read. ${ErrorWords.of(e)}")) }
      return
    }
    if (generation == accountGeneration) _state.update { it.copy(works = it.works + (tafId to work), workErrors = it.workErrors - tafId) }
  }

  /**
   * Send again: a door reply given up on after its tries goes once more (`POST …/deliveries/door_reply/send`). It does no work
   * and signs nothing. The work is read again after, whatever came of it.
   */
  suspend fun sendAgain(tafId: String, run: String) {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    if (run in _state.value.sendingAgain) return
    _state.update { it.copy(sendingAgain = it.sendingAgain + run, workErrors = it.workErrors - tafId) }
    val generation = accountGeneration
    val failed = try {
      cloud.sendAgain(cloudId, run)
      null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      "It wasn't sent again. ${ErrorWords.of(e)}"
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
      (_state.value.reports[tafId] ?: RunReport()).copy(error = "Its runs couldn't be loaded. ${ErrorWords.of(e)}")
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
      if (generation == accountGeneration) fail("tafs cloud didn't ${if (paused) "pause" else "resume"} it. ${ErrorWords.of(e)}")
      return
    }
    if (generation != accountGeneration) return
    val taf = _state.value.taf(tafId) ?: return
    var updated = taf.copy(paused = schedule.paused, nextRun = schedule.nextRun)
    if (taf.state != TafState.waiting && taf.state != TafState.revoked && taf.state != TafState.halted) {
      updated = updated.copy(
        state = if (schedule.paused) TafState.paused else TafState.working,
        now = if (schedule.paused) "Paused by you" else schedule.nextRun?.let { "Next run ${shortTime(it)}" } ?: "Back to work",
      )
    }
    update(updated)
  }

  /**
   * Removes a taf for good, after one confirmation: KIFF revokes its Cards first, so it can't act whatever happens next;
   * then tafs cloud stops it, deletes its runs, chats, memory and key, and frees its name. What to tell the owner; null
   * when nothing was done.
   */
  suspend fun removeTaf(tafId: String): String? {
    val s = _state.value
    val taf = s.taf(tafId)
    val cloudId = taf?.cloudId
    if (s.mode != Mode.kiff || taf == null || cloudId == null) return null
    val generation = accountGeneration
    try {
      signer.sign("tafs.remove.v1|$cloudId".toByteArray(), "Remove ${taf.name} for good")
    } catch (_: SignerException) {
      return null
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
    val cards = if (cardsLeft == 0) "Its Cards are revoked in KIFF." else "KIFF didn't revoke $cardsLeft of its Cards: revoke them in KIFF."
    val removal = try {
      cloud.remove(cloudId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation != accountGeneration) return null
      return "$cards ${ErrorWords.of(e)} If the removal began, tafs finishes it the next time it refreshes."
    }
    if (generation != accountGeneration) return null
    if (cardsLeft == 0) _state.value.taf(tafId)?.let { update(it.copy(card = it.card.copy(revoked = true), state = TafState.revoked, now = "Removed")) }
    if (!removal.done) {
      return "$cards Not finished: its ${removal.failed.joinToString(", ") { removalStep(it) }} couldn't be removed. tafs tries again each time it refreshes."
    }
    log(tafId, RecordEntry.Kind.revoked, "You removed ${taf.name}", "Schedule, memory and key deleted; name free")
    refresh()
    return "$cards Its schedule, chats, run reports, memory and key are deleted, and the name ${taf.name} is free. KIFF's receipts stay."
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

  /** Finishes removals tafs cloud lists as begun. They were confirmed when they began; finishing only deletes what's left of a taf already stopped. */
  private suspend fun finishRemovals(removing: List<bot.tafs.app.kiff.PendingRemoval>, generation: Int) {
    for (r in removing) {
      if (generation != accountGeneration) return
      attempt { cloud.remove(r.id) }
    }
  }

  private fun removalStep(step: String): String = when (step) {
    "history" -> "runs and chats"
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
  fun followChat(tafId: String) {
    if (followers[tafId]?.isActive == true) return
    followers[tafId] = scope.launch {
      var polls = 0
      while (polls < CHAT_POLL_BUDGET && busy(tafId)) {
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

  /** Reads a taf's thread from tafs cloud and shows it; what the owner sent that it doesn't have yet stays, dimmed. */
  private suspend fun readThread(tafId: String) {
    val cloudId = _state.value.taf(tafId)?.cloudId ?: return
    // A new Coach conversation has nothing to read until its first message is sent.
    if (tafId == COACH_CHAT && _state.value.coachFresh) return
    val generation = accountGeneration
    val thread = try {
      if (isTrialChat(tafId)) cloud.trialConversation(cloudId)
      else cloud.conversation(cloudId, session = if (tafId == COACH_CHAT) _state.value.coachChat else null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(chatErrors = it.chatErrors + (tafId to "The thread couldn't be loaded. ${ErrorWords.of(e)}")) }
      return
    }
    if (generation != accountGeneration || _state.value.mode != Mode.kiff) return
    val now = clock()
    _state.update { s ->
      // What the owner sent stays on screen until the thread has it (kiff/tafs#138, 48).
      val (messages, waiting) = SentMessages.merge(thread.messages, s.chatSent[tafId].orEmpty(), now)
      // The wait ends once tafs cloud says the taf is at work, once it answered after the owner wrote, or after
      // two minutes with no sign.
      val since = s.chatAwaiting[tafId]
      val answered = since != null && thread.messages.any { it.from == ChatMessage.From.taf && it.at >= since }
      val settled = since != null && (thread.working != null || answered || java.time.Duration.between(since, now) > java.time.Duration.ofSeconds(120))
      s.copy(
        chats = s.chats + (tafId to messages),
        chatSent = if (waiting.isEmpty()) s.chatSent - tafId else s.chatSent + (tafId to waiting),
        chatErrors = s.chatErrors - tafId,
        chatWorking = thread.working?.let { s.chatWorking + (tafId to ChatRun(it, thread.workingRun)) } ?: (s.chatWorking - tafId),
        chatAwaiting = if (settled) s.chatAwaiting - tafId else s.chatAwaiting,
        coachComponents = if (tafId == COACH_CHAT) thread.components else s.coachComponents,
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
    val result = connect(grant.id, grant.id, grant.digest)
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
    val status = attempt { cloud.registerPush(token, _state.value.alertSound, handoff?.target) } ?: return
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
      fail("Sign in with KIFF again to turn on alerts: this sign-in can't sign for the phone.")
      return false
    }
    if (!push.available) {
      fail("Alerts aren't set up in this build of tafs.")
      return false
    }
    if (_state.value.turningOnAlerts) return false
    _state.update { it.copy(turningOnAlerts = true) }
    try {
      val generation = accountGeneration
      if (!push.allowed()) {
        fail("Allow notifications for tafs in Settings, then try again.")
        return false
      }
      val token = pushToken ?: push.token()?.also { pushToken = it }
      if (token == null) {
        fail("This phone didn't give tafs a push token. Try again in a moment.")
        return false
      }
      var handoff = pendingHandoff()
      if (handoff == null) {
        try {
          signer.sign("tafs.alerts.v1|$tenant".toByteArray(), "Get an alert when a taf needs you")
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
              if ((e.message ?: "").contains("phone_alert_url_forbidden") || (e.message ?: "").contains("app backend")) "KIFF doesn't send alerts to tafs yet on this account."
              else "Your role on this KIFF account can't turn on alerts.",
            )
          } else {
            fail("Alerts aren't on. " + ErrorWords.of(e))
          }
          return false
        } catch (e: Exception) {
          fail("Alerts aren't on. " + ErrorWords.of(e))
          return false
        } finally {
          kiff.setupSigner = null
        }
      }
      return try {
        val status = cloud.registerPush(token, _state.value.alertSound, handoff.target)
        if (generation != accountGeneration) return false
        keepHandoff(null)
        secrets.save("1", ALERTS_ON)
        applyPush(status)
        status.alerts
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == accountGeneration) {
          fail("Alerts aren't on for this phone yet. " + ErrorWords.of(e))
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
      cloud.setupAndTools()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(coachChecked = true, chatErrors = it.chatErrors + (COACH_CHAT to "Coach couldn't be reached. ${ErrorWords.of(e)}")) }
      return
    }
    if (generation == accountGeneration) {
      val (coach, tools) = setup
      _state.update { it.copy(coachCloudId = coach?.id, coachAgent = coach?.kiffAgent, coachChecked = true, coachTools = tools, chatErrors = it.chatErrors - COACH_CHAT) }
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
      startFailed("Sign in with KIFF again: this sign-in can't sign for the phone.")
      return false
    }
    if (_state.value.approvalKeyLost) {
      startFailed(KiffClient.APPROVAL_KEY_LOST)
      return false
    }
    if (_state.value.planqueReady && _state.value.coachCloudId != null) return true
    if (_state.value.startingCoach) return false
    _state.update { it.copy(startingCoach = true, coachStartError = null) }
    val generation = accountGeneration
    val signer = signer
    val sign: (ByteArray) -> String = { signer.signConfirmed(it) }
    try {
      try {
        // The owner says yes before anything is made: Coach's connector key and its drafts. Its drafts grant authority, so
        // with the approval key in KIFF each asks for its own fingerprint as well.
        signer.sign("tafs.setup.v1|$tenant".toByteArray(), "Connect your tools")
      } catch (e: SignerException) {
        notSigned(e, "").takeIf { it.isNotEmpty() }?.let { startFailed(it) }
        return false
      }
      if (generation != accountGeneration) return false
      kiff.tenant = tenant
      kiff.setupSigner = sign
      planque.tenant = tenant
      planque.deviceLoader = { kiff.thisDeviceId() }
      planque.signer = sign
      var setup = _state.value.coachCloudId?.let { id -> CloudSetup(id, kiffAgent = _state.value.coachAgent) }
      try {
        if (setup == null) setup = cloud.setup()
        if (!_state.value.planqueReady) {
          try {
            planque.enrol(signer.publicKeyDER, kiff.mintConnectorKey())
          } catch (e: CancellationException) {
            throw e
          } catch (e: KiffError.Server) {
            startFailed(if (e.status == 403) "Only an admin of this KIFF account can connect tools." else "Connecting tools isn't set up. ${ErrorWords.of(e)}")
            return false
          } catch (e: PlanqueError.Server) {
            startFailed(if (e.status == 409) "Another device connects this account's tools for now. Connect them from that one." else "Connecting tools isn't set up. ${ErrorWords.of(e)}")
            return false
          }
          if (generation != accountGeneration) return false
        }
        if (setup == null) {
          approvalReason = "Start Coach"
          val draft = kiff.submitNoToolsDraft(COACH_AGENT, "Coach")
          val secret = draft.claimSecret
          if (generation != accountGeneration) return false
          if (secret == null) {
            startFailed("Coach didn't start. " + ErrorWords.OURS)
            return false
          }
          setup = cloud.startSetup(draft.id, secret)
        }
      } catch (e: SignerException) {
        if (generation == accountGeneration) notSigned(e, "").takeIf { it.isNotEmpty() }?.let { startFailed(it) }
        return false
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == accountGeneration) startFailed("Couldn't start. ${ErrorWords.of(e)}")
        return false
      }
      if (generation != accountGeneration) return false
      _state.update { it.copy(coachCloudId = setup?.id, coachAgent = setup?.kiffAgent, coachChecked = true) }
      // Within the step the owner just confirmed: Coach's tools, as tafs cloud says they're still to give.
      giveCoachTools()
    } finally {
      planque.signer = null
      kiff.setupSigner = null
      if (generation == accountGeneration) _state.update { it.copy(startingCoach = false) }
    }
    refreshConnections()
    readThread(COACH_CHAT)
    return true
  }

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
    val tenant = kiffTenant ?: return false
    if (_state.value.mode != Mode.kiff || _state.value.coachCloudId == null) return false
    val generation = accountGeneration
    val tools = try {
      cloud.coachTools()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(coachToolsError = "Couldn't read Coach's tools. ${ErrorWords.of(e)}") }
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
        approvalReason = "Give Coach its Card"
        kiff.submitDraft(it.body)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: SignerException) {
      if (generation == accountGeneration) notSigned(e, "").takeIf { it.isNotEmpty() }?.let { words -> _state.update { it.copy(coachToolsError = words) } }
      return false
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(coachToolsError = "Coach's tools weren't given. ${ErrorWords.of(e)}") }
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

    /**
     * It didn't go through: [why] for the owner, in the app's words; [told] for Coach, the card's answer, which keeps the server's own
     * reason so Coach can say what went wrong and ask again.
     */
    data class Failed(val why: String, val told: String = why) : Connected
  }

  private fun note(cardId: String, words: String?) =
    _state.update { it.copy(coachNotes = if (words == null) it.coachNotes - cardId else it.coachNotes + (cardId to words)) }

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
  suspend fun connect(cardId: String, grantId: String, shownDigest: String?, draftId: String? = null, shownDraft: String? = null): Connected {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || !_state.value.sessionHasKey || tenant == null || !_state.value.planqueReady) {
      return Connected.Failed("Connecting tools isn't set up for this account from this phone. Start from tafs on an iPhone.")
    }
    if (_state.value.approvalKeyLost) return Connected.Failed(KiffClient.APPROVAL_KEY_LOST)
    if (_state.value.connecting != null) {
      note(cardId, "Another approval is going through. Approve this one once it's done.")
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
        grant = planque.grants().firstOrNull { it.id == grantId } ?: return Connected.Failed("There's no connection with that id.")
        if (draftId != null && grant.connected) draft = kiff.draft(draftId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return Connected.Failed("Couldn't read what to approve. ${ErrorWords.of(e)}", "couldn't read what to approve: ${e.message}")
      }
      if (grant.connected) {
        // The second approval: the vendor is connected, and the taf's Cards over its tools wait as a draft. One confirmation confirms them.
        val d = draft
        if (d == null || d.confirmed) return Connected.Done(grant)
        if (d.version != shownDraft) {
          _state.update { it.copy(setupDrafts = it.setupDrafts + (d.id to d)) }
          note(cardId, VersionWords.OUT_OF_DATE_CARD)
          return Connected.Stopped
        }
        try {
          confirmGrant("tafs.cards.v1|$tenant|${d.id}|${d.version}", "Give ${d.agentId} its Card")
          if (generation != accountGeneration) return Connected.Stopped
          kiff.setupSigner = sign
          kiff.confirmDraft(d.id, d.version)
        } catch (e: SignerException) {
          note(cardId, notSigned(e, "Not done: it wasn't confirmed."))
          return Connected.Stopped
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return Connected.Failed("KIFF didn't issue the taf's Cards. ${ErrorWords.of(e)}", "KIFF didn't issue the taf's Cards: ${e.message}")
        }
        return Connected.Done(grant)
      }
      // A grant its vendor signed out (needs_reconnect) is approved and signed in again, as a new one is.
      if (grant.status !in setOf("pending", "approved", "signed_in", "needs_reconnect")) {
        return Connected.Failed("This connection is ${grant.status.replace('_', ' ')}" + (grant.reason?.let { ": $it" } ?: "."))
      }
      if (!grant.digestMatches) return Connected.Failed(PlanqueError.DigestMismatch.message!!)
      if (grant.digest != shownDigest) {
        _state.update { s -> s.copy(grants = if (s.grants.any { it.id == grant.id }) s.grants.map { if (it.id == grant.id) grant else it } else s.grants + grant) }
        note(cardId, VersionWords.OUT_OF_DATE_CARD)
        return Connected.Stopped
      }
      try {
        signer.sign("tafs.connect.v1|$tenant|${grant.id}|${grant.digest}".toByteArray(), "Connect ${grant.vendor} to ${grant.request.agent}")
      } catch (_: SignerException) {
        note(cardId, "Not done: it wasn't confirmed.")
        return Connected.Stopped
      }
      if (generation != accountGeneration) return Connected.Stopped
      try {
        val approved = planque.approve(grant)
        val url = approved.signInUrl
        if (url == null) {
          // planque already holds the sign-in: approving the tools Setup chose from the vendor's list connected them, with no second sign-in.
          if (approved.status != "connected") {
            refreshConnections()
            return Connected.Failed("The connection came back ${approved.status} with no sign-in link. Nothing was connected.")
          }
          refreshConnections()
          return Connected.Done(_state.value.grants.firstOrNull { it.id == grant.id && it.connected } ?: grant.marked(true))
        }
        val back = vendorSignIn.signIn(url)
        val signedIn = PlanqueClient.signedIn(back)
        if (signedIn == null || signedIn.grant != grant.id || signedIn.tenant != tenant) {
          return Connected.Failed("The sign-in at ${grant.vendor} came back without its confirmation code. Nothing was connected.")
        }
        if (generation != accountGeneration) return Connected.Stopped
        // Back from the vendor: signed in only once planque says the grant is connected, never because the page closed.
        _state.update { it.copy(connectStep = CoachWords.CHECKING_SIGN_IN) }
        val connected = planque.confirm(grant.id, signedIn.code)
        refreshConnections()
        if (!connected.connected) return Connected.Failed(CoachWords.notConnected(grant.vendor, connected.status), "the sign-in at ${grant.vendor} came back, but planque says the connection is ${connected.status}")
        return Connected.Done(connected)
      } catch (e: VendorSignInCancelled) {
        refreshConnections()
        return Connected.Failed("The sign-in was closed. Nothing was connected.")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        refreshConnections()
        return Connected.Failed("Not connected. ${ErrorWords.of(e)}", "Not connected: ${e.message}")
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

  /** The sign-in card's Sign in again, run by the model for the same reason as [startApproval]. */
  fun startSignInAgain(c: SetupComponent) {
    scope.launch { signInAgain(c) }
  }

  /** A taf page's Sign in again (a connection its vendor signed out), run by the model for the same reason. */
  fun startReconnect(grant: Grant) {
    scope.launch { reconnect(grant) }
  }

  suspend fun approveCoach(c: SetupComponent, shownDigest: String?, shownDraft: String?) {
    val grantId = c.grant ?: return
    when (val result = connect(c.id, grantId, shownDigest, c.draft, shownDraft)) {
      is Connected.Done -> answerCard("approval", c.id, "approved")
      is Connected.Failed -> {
        note(c.id, result.why)
        answerCard("approval", c.id, "failed: " + result.told)
      }
      Connected.Stopped -> Unit
    }
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

  /**
   * Coach's results. A new taf is tafs cloud's to set up: it answers the card itself, and the phone only shows the answer,
   * reading the list again once for a taf it set up. An improvement makes the taf's next version here, once.
   */
  private suspend fun followHandOvers() {
    if (_state.value.mode != Mode.kiff) return
    var readList = false
    for (shown in _state.value.coachComponents.filter { it.component.type == "result" }) {
      val c = shown.component
      val taf = c.taf ?: continue
      if (taf.improves != null) {
        val key = improvementKey(c)
        val s = _state.value
        if (key !in s.improvedVersions && key !in s.makingVersions && key !in s.improveErrors) makeImprovedVersion(c)
        continue
      }
      val id = shown.answer?.outcome?.tafId ?: continue
      if (_state.value.tafs.none { it.cloudId == id || it.id == taf.agent } && setUpTafs.add(id)) readList = true
    }
    if (readList) refresh()
  }

  /**
   * An improvement result, by its card and what it carries (its draft, else its note). The card's id is Coach's to pick and comes back
   * in another conversation: keyed by the id alone, a new conversation's card showed the version an earlier one made.
   */
  fun improvementKey(c: SetupComponent): String = CoachWords.improvementKey(c)

  /**
   * Makes the version an improvement result hands over, once: tafs cloud builds it from Coach's words and keeps one per result, so a
   * repeat gives the same version. Nothing goes live: the owner tries it and switches from the taf's Versions.
   */
  suspend fun makeImprovedVersion(c: SetupComponent) {
    val taf = c.taf ?: return
    val named = taf.improves ?: return
    if (_state.value.mode != Mode.kiff) return
    val key = improvementKey(c)
    if (key in _state.value.makingVersions) return
    // The taf it improves, by its tafs id, or by its agent's when Coach changed a taf it set up or found in this conversation.
    val tafs = _state.value.tafs
    val improves = tafs.firstOrNull { it.cloudId == named }?.cloudId ?: tafs.firstOrNull { it.id == named }?.cloudId ?: tafs.firstOrNull { it.id == taf.agent }?.cloudId ?: named
    _state.update { it.copy(makingVersions = it.makingVersions + key, improveErrors = it.improveErrors - key) }
    val generation = accountGeneration
    try {
      val n = cloud.createVersion(improves, taf.note.orEmpty(), taf.notes, taf.draft, key, taf.agent.takeIf { it.isNotEmpty() }, taf.tryWith)
      if (generation != accountGeneration) return
      _state.update { it.copy(improvedVersions = it.improvedVersions + (key to n)) }
      tafs.firstOrNull { it.cloudId == improves }?.let { loadVersions(it.id) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(improveErrors = it.improveErrors + (key to "${taf.name}'s new version isn't ready. ${ErrorWords.of(e)}")) }
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(makingVersions = it.makingVersions - key) }
    }
  }

  /** Disconnects a vendor: planque removes its tools from KIFF and revokes the sign-in at the vendor. Asks for the owner's confirmation. */
  suspend fun disconnect(grantId: String): Boolean {
    val tenant = kiffTenant
    val grant = _state.value.grants.firstOrNull { it.id == grantId }
    if (_state.value.mode != Mode.kiff || !_state.value.sessionHasKey || tenant == null || grant == null || !_state.value.planqueReady) return false
    val generation = accountGeneration
    try {
      signer.sign("tafs.disconnect.v1|$tenant|$grantId".toByteArray(), "Disconnect ${grant.vendor}")
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
      fail("Not disconnected. ${ErrorWords.of(e)}")
      return false
    } finally {
      planque.signer = null
    }
    refreshConnections()
    return true
  }

  /** The owner's answer to one of Coach's cards. */
  fun answerCoach(type: String, id: String, answer: String) {
    viewModelScope.launch { answerCard(type, id, answer) }
  }

  /**
   * Answers one of Coach's cards as data (docs/chat.md): tafs cloud hands it to the run that waits on the card, which goes on.
   * The card shows the answer once the thread has it, in kiff/agents' words; until then it says the answer is on its way. The
   * run that goes on is followed like any run at work, so Coach's next words show without leaving it. A questions card's
   * answer is its JSON. A card that no longer waits (409) is read again as it is. Only signed in: the sample has no Coach.
   */
  suspend fun answerCard(type: String, id: String, answer: String): Boolean {
    if (_state.value.mode != Mode.kiff) return false
    val cloudId = _state.value.coachCloudId ?: return false
    if (id in _state.value.cardsAnswering) return false
    val value: kotlinx.serialization.json.JsonElement =
      if (type == "questions") runCatching { KiffClient.json.parseToJsonElement(answer) }.getOrElse { kotlinx.serialization.json.JsonPrimitive(answer) }
      else kotlinx.serialization.json.JsonPrimitive(answer)
    _state.update { it.copy(cardsAnswering = it.cardsAnswering + id, cardErrors = it.cardErrors - id) }
    val generation = accountGeneration
    val answered = try {
      cloud.answerCard(cloudId, id, value, _state.value.coachComponents.firstOrNull { it.id == id }?.session?.ifEmpty { null } ?: _state.value.coachChat)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation != accountGeneration) return false
      val gone = e is CloudError && e.status == 409
      if (!gone) _state.update { it.copy(cardErrors = it.cardErrors + (id to "Your answer wasn't sent. ${ErrorWords.of(e)}")) }
      _state.update { it.copy(cardsAnswering = it.cardsAnswering - id) }
      readThread(COACH_CHAT)
      return false
    } finally {
      // Whatever comes after (the thread read, following the run), the answer is no longer on its way once tafs cloud has
      // answered: a screen torn down meanwhile (the vendor's page opening) never leaves the card on "Sending your answer…".
      _state.update { it.copy(cardsAnswering = it.cardsAnswering - id) }
    }
    if (generation != accountGeneration) return false
    // Taken: the card shows it's answered at once, until the thread's words for it come.
    _state.update { it.copy(cardsTaken = it.cardsTaken + id) }
    answered.session?.let { if (_state.value.coachChat == null) settleCoachChat(it) }
    // Coach is at work again: it reads as thinking until its run is followed, and the run's end reads the thread again.
    _state.update { it.copy(chatAwaiting = it.chatAwaiting + (COACH_CHAT to clock()), chatEnded = it.chatEnded - COACH_CHAT) }
    readThread(COACH_CHAT)
    // The run paused on the card and goes on now: its stream is followed to its end, which reads the thread again.
    answered.run?.let { resumeChat(COACH_CHAT, cloudId, it, again = true) } ?: followChat(COACH_CHAT)
    return true
  }

  /**
   * Sends what the owner wrote, from a screen: it shows at once and the taf answers after it. [withFiles] takes the files
   * ready in the chat's tray with it; Send again and Retry send the words only. [onNotSent] gets the words back when they
   * didn't go, so the screen can keep them.
   */
  fun sendChat(text: String, tafId: String, withFiles: Boolean = false, onNotSent: (String) -> Unit) {
    viewModelScope.launch { if (!deliverChat(text, tafId, withFiles)) onNotSent(text) }
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
      _state.update { it.copy(chatErrors = it.chatErrors + (tafId to "That's longer than ${Chat.LIMIT} characters. Say it in fewer words.")) }
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
  private suspend fun deliverToTafsCloud(words: String, tafId: String, withFiles: Boolean = false): Boolean {
    val taf = _state.value.taf(tafId)
    val cloudId = taf?.cloudId
    if (cloudId == null) {
      _state.update { it.copy(chatErrors = it.chatErrors + (tafId to "This taf isn't running on tafs cloud, so it can't be messaged.")) }
      return false
    }
    if (tafId in _state.value.chatSending) return false
    // Files go with a message of their own, never into a run at work, and never while one is still going up.
    if (withFiles && _state.value.holdsSend(tafId)) return false
    val files = if (withFiles) takeFiles(tafId) else emptyList()
    val refs = files.mapNotNull { it.ref }
    _state.update { it.copy(chatSending = it.chatSending + tafId, chatErrors = it.chatErrors - tafId) }
    val generation = accountGeneration
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
          settleCoachChat(cloud.improve(improving, words).session)
        } else {
          val run = if (fresh) null else _state.value.chatLive[tafId]?.run ?: _state.value.chatWorking[tafId]?.run
          var steered = false
          if (run != null && refs.isEmpty()) {
            try {
              cloud.steer(cloudId, run, words)
              steered = true
            } catch (e: CloudError) {
              // A run that has ended throws 409, and the message goes as a new one.
              if (e.status != 409) throw e
            }
          }
          // Streamed, so the taf's words show as it writes them; sent plainly when the stream can't open.
          if (!steered) streamed = openStream(cloudId, words, refs, session, fresh)
          if (!steered && streamed == null) cloud.sendMessage(cloudId, words, refs, session, fresh).also { sent ->
            if (coach) settleCoachChat(sent.session)
            sent.message?.let { markSent(tafId, pending, it.id) }
          }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) {
        _state.update { s ->
          val left = s.chatSent[tafId].orEmpty().filterNot { it == pending }
          s.copy(
            chats = s.chats + (tafId to s.chats[tafId].orEmpty().filterNot { it == pending }),
            chatSent = if (left.isEmpty()) s.chatSent - tafId else s.chatSent + (tafId to left),
            chatErrors = s.chatErrors + (tafId to sendFailure(e, taf.name)),
          )
        }
        // Its files didn't go: back in the tray for the next try.
        putBackFiles(files, tafId)
      }
      return false
    } finally {
      _state.update { it.copy(chatSending = it.chatSending - tafId) }
    }
    if (generation != accountGeneration) return false
    // The taf is answering now: until it has, or is at work, it reads as thinking.
    _state.update { it.copy(chatAwaiting = it.chatAwaiting + (tafId to clock())) }
    val events = streamed
    if (events != null) {
      _state.update { it.copy(chatLive = it.chatLive + (tafId to LiveAnswer(started = clock()))) }
      readThread(tafId)
      followStream(tafId, events, retry = words, sent = pending)
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
      refuseFile("A message carries at most ${FilePrep.PER_MESSAGE} files.", tafId)
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
        else "It didn't upload. " + ErrorWords.of(e),
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
      refuseFile("${ref.name} couldn't be opened. ${ErrorWords.of(e)}", tafId)
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

  private fun followStream(tafId: String, events: kotlinx.coroutines.flow.Flow<StreamEvent>, retry: String?, sent: ChatMessage? = null) {
    val generation = accountGeneration
    followers[tafId]?.cancel()
    followers[tafId] = scope.launch {
      var ended: StreamEvent.End? = null
      fun live(change: (LiveAnswer) -> LiveAnswer) = _state.update { s ->
        s.chatLive[tafId]?.let { s.copy(chatLive = s.chatLive + (tafId to change(it))) } ?: s
      }
      try {
        events.collect { e ->
          if (generation != accountGeneration) throw CancellationException("another account")
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
              live { it.newPart() }
              readThread(tafId)
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
      if (generation != accountGeneration) return@launch
      val end = ended
      _state.update { s ->
        val how = when {
          end == null -> null
          end.status == "ERROR" && retry != null -> ChatEnd.Failed(retry, end.done)
          end.status == "CANCELLED" -> ChatEnd.Stopped
          end.stop in CAP_STOPS -> ChatEnd.Capped(end.stop)
          else -> null
        }
        s.copy(chatEnded = if (how != null) s.chatEnded + (tafId to how) else s.chatEnded - tafId)
      }
      // Over, or paused on a held call: KIFF is read at once, so the hold shows and the Card's amounts are today's.
      if (end != null) refresh()
      readThread(tafId)
      _state.update { it.copy(chatLive = it.chatLive - tafId, chatAwaiting = if (end != null) it.chatAwaiting - tafId else it.chatAwaiting) }
      // No end came: the thread is read as the run goes on, as before the stream.
      if (end == null && busy(tafId)) {
        followers.remove(tafId)
        followChat(tafId)
      }
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
      if (generation == accountGeneration) fail("Not stopped. ${ErrorWords.of(e)}")
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
    val events = try {
      cloud.resumeRun(cloudId, run)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Its stream can't open: the thread is read on a timer while the run works, as before the stream.
      followChat(tafId)
      return
    }
    val working = _state.value.chatWorking[tafId]
    _state.update { it.copy(chatLive = it.chatLive + (tafId to LiveAnswer(started = clock(), session = working?.session, run = run))) }
    followStream(tafId, events, retry = null)
  }

  /** Why a message wasn't sent, in words. */
  private fun sendFailure(e: Exception, name: String): String =
    if (e is CloudError && e.status == 429) "That's a lot of messages in a few minutes. Give $name a moment, then try again."
    else "The message wasn't sent. ${ErrorWords.of(e)}"

  private fun nextStamp(tafId: String): Instant = Chat.stamp(_state.value.chats[tafId]?.maxOfOrNull { it.at }, clock())

  private fun append(tafId: String, message: ChatMessage) =
    _state.update { s -> s.copy(chats = s.chats + (tafId to ((s.chats[tafId] ?: emptyList()) + message))) }

  private fun say(tafId: String, from: ChatMessage.From, text: String) =
    append(tafId, ChatMessage(nextStamp(tafId), from, text, "sample"))

  /** A sample taf answers from its own record: what it did, what waits, or a pause. */
  private fun sampleReply(text: String, tafId: String): String {
    val lower = text.lowercase()
    val taf = _state.value.taf(tafId) ?: return "I'm not here any more."
    if (listOf("stop", "pause", "hold off").any { lower.contains(it) }) {
      if (taf.state != TafState.paused) togglePause(tafId)
      return "Paused. Nothing more goes out until you tell me to carry on."
    }
    if (listOf("go", "carry on", "resume", "continue").any { lower == it || lower.startsWith("$it ") }) {
      if (taf.state == TafState.paused) togglePause(tafId)
      return "Back to work."
    }
    if (listOf("more", "raise", "limit", "bigger", "increase").any { lower.contains(it) }) {
      return "I can't give myself more room: only you can, by changing my Card. Anything over it waits for you in Needs you."
    }
    val done = _state.value.entries(tafId).take(3).map { "• " + it.title }
    val waiting = _state.value.heldCalls(tafId).size
    val lines = mutableListOf<String>()
    lines += if (done.isEmpty()) "Nothing done yet today." else "Lately:\n" + done.joinToString("\n")
    if (waiting > 0) lines += "$waiting thing${if (waiting == 1) " waits" else "s wait"} on you in Needs you."
    lines += "(Sample reply. Signed in with KIFF, your taf answers for real.)"
    return lines.joinToString("\n\n")
  }

  // MARK: What a taf remembers

  /** The taf's notes: signed in, read from tafs cloud; in the sample, the kind of thing a real one writes down as it learns. */
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
      if (generation == accountGeneration) _state.update { it.copy(memoryErrors = it.memoryErrors + (tafId to "Its notes couldn't be loaded. ${ErrorWords.of(e)}")) }
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
      s.copy(memories = s.memories + (tafId to notes), memoryErrors = s.memoryErrors + (tafId to "It wasn't forgotten. ${ErrorWords.of(e)}"))
    }
  }

  private fun sampleNotes(tafId: String, now: Instant): List<MemoryNote> = listOf(
    Triple("/how-you-like-it.md", "You'd rather I explain a decision in one line than in a paragraph.", 3_600L),
    Triple("/customers/regulars.md", "Customers who write every week get a reply the same morning.", 86_400L),
    Triple("/from-the-owner.md", "Ask before anything unusual, even when it fits the Card.", 3 * 86_400L),
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
        log(tafId, RecordEntry.Kind.done, proposed.summary, amountLine(proposed, "within the Card"), proposed)
      }
      is Decision.Held -> {
        val now = clock()
        val rule = taf.card.rules.firstOrNull { it.action == proposed.action }
        val call = HeldCall(tafId = tafId, proposed = proposed, reason = decision.reason, heldAt = now,
          expiresAt = now.plus(Duration.ofMinutes(10)), nextSteps = rule?.nextSteps ?: emptyList())
        _state.update { it.copy(held = listOf(call) + it.held) }
        update(taf.copy(state = TafState.waiting, now = "Waiting on you · ${proposed.summary}"))
        log(tafId, RecordEntry.Kind.held, "Held: ${proposed.summary}", decision.reason, proposed)
      }
      is Decision.Refused -> {
        update(taf.copy(now = "Refused · ${proposed.summary}"))
        log(tafId, RecordEntry.Kind.refused, "Refused: ${proposed.summary}", decision.reason, proposed)
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
   * without asking again. [installed]: the version Coach's held call names is live already.
   */
  suspend fun answer(call: HeldCall, answer: Answer, confirmed: Boolean = false, installed: Boolean = false): Boolean {
    val before = _state.value
    if (before.held.none { it.id == call.id }) return false
    // Approving Coach's install is installing it (kiff/tafs#206): the switch and its Card come first, then this answer, so
    // tafs never hears of a half-done install.
    if (call.install != null && answer is Answer.ApproveOnce && !confirmed && !installed) {
      fail("Install it from its card: the version goes live, then Coach hears.")
      return false
    }
    val kiffId = call.kiffId
    // A call KIFF holds is answered here only when KIFF checks this phone's signature on it (kiff/tafs#138).
    if (kiffId != null && !before.canAnswer(call)) {
      fail(
        if (!before.sessionHasKey) "Sign out and in again to answer from this phone, so KIFF knows its key. Or answer this one in KIFF."
        else "Answer this one in KIFF. Answering from the phone arrives once KIFF checks the phone's signature.",
      )
      return false
    }
    if (kiffId == null && clock() > call.expiresAt) {
      resolve(call)
      log(call.tafId, RecordEntry.Kind.refused, "Expired: ${call.proposed.summary}", "Nobody answered in time")
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
    val reason = "Answer ${call.proposed.summary}"
    var signed: KiffClient.AnswerSignature? = null
    val signature = try {
      val tenant = kiffTenant
      val version = call.accountingVersion
      if (kiffId != null && tenant != null && version != null) {
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
        when {
          approving && (sessionHasApprovalKey || signer.approvalPublicKeyDER.isEmpty()) -> signer.signApproval(reason, message)
          confirmed -> signer.signConfirmed(message())
          else -> signer.sign(reason, message)
        }.also { signed = KiffClient.AnswerSignature(signedAt, it) }
      } else {
        signer.sign(Messages.answer(call.id, answer), reason)
      }
    } catch (e: SignerException) {
      fail(notSigned(e, "Not sent: it wasn't confirmed."))
      return false
    }
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

  private fun applyAnswer(call: HeldCall, answer: Answer, signature: String) {
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
        decide(call, RecordEntry.Kind.approved, "Approved once by you · $at · $summary", amountLine(call.proposed, "approved once by $ownerName"), signature)
      }
      is Answer.NextStep -> {
        taf?.let { update(it.copy(now = answer.step)) }
        decide(call, RecordEntry.Kind.refused, "Refused by you · $at, with a next step · $summary", "Refused, with a next step for the taf: ${answer.step}", signature)
      }
      Answer.ExtraRoom -> {
        taf?.let { update(it.copy(now = summary)) }
        decide(call, RecordEntry.Kind.approved, "Extra room by you · $at · $summary", "Its Card got the room for this total, until it resets. Over a limit for one call as well, the call is held again for that", signature)
        call.kiffId?.let { id -> _state.update { it.copy(roomGiven = it.roomGiven + id) } }
      }
      Answer.Refuse -> {
        taf?.let { update(it.copy(now = "Refused · $summary")) }
        decide(call, RecordEntry.Kind.refused, "Refused by you · $at · $summary", call.reason, signature)
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
      if (generation == accountGeneration) versionError(tafId, "Its versions couldn't be loaded. ${ErrorWords.of(e)}")
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
      if (generation == accountGeneration) versionError(tafId, "Couldn't read this version's Card. ${ErrorWords.of(e)}")
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
        versionError(tafId, VersionWords.blockedWords(version.n, e.blocking, stopBlocking))
      } else {
        versionError(tafId, "Version ${version.n} can't go live now. ${ErrorWords.of(e)}")
      }
      null
    } catch (e: Exception) {
      if (generation == accountGeneration) versionError(tafId, "Version ${version.n} can't go live now. ${ErrorWords.of(e)}")
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
   * live, signed under the same confirmation. What's in the way of the switch is stopped first.
   */
  suspend fun useVersion(version: Version, tafId: String, shownDraft: String? = null, approving: HeldCall? = null): Boolean {
    val s = _state.value
    val tenant = kiffTenant
    val taf = s.taf(tafId)
    val cloudId = taf?.cloudId
    if (s.mode != Mode.kiff || tenant == null || taf == null || cloudId == null) return false
    if (version.live || tafId in s.switchingVersion) return false
    _state.update { it.copy(switchingVersion = it.switchingVersion + (tafId to version.n), changedVersionCards = it.changedVersionCards - tafId) }
    try {
      if (!install(version, tafId, taf.name, cloudId, tenant, shownDraft, stopBlocking = approving != null)) return false
    } finally {
      kiff.setupSigner = null
      _state.update { it.copy(switchingVersion = it.switchingVersion - tafId) }
    }
    // Live: now Coach's held call is approved, with the confirmation just given.
    if (approving != null) answer(approving, Answer.ApproveOnce, confirmed = true)
    return true
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
      fail("Coach asked to install a version of a taf that isn't here.")
      return null
    }
    loadVersions(taf.id)
    val version = _state.value.versions[taf.id]?.firstOrNull { it.n == ask.n }
    if (version == null) {
      fail("${taf.name} has no version ${ask.n}.")
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

  private suspend fun install(version: Version, tafId: String, name: String, cloudId: String, tenant: String, shownDraft: String?, stopBlocking: Boolean = false): Boolean {
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
      if (generation == accountGeneration) versionError(tafId, "Version ${version.n} can't go live now. ${ErrorWords.of(e)}")
      return false
    }
    if (generation != accountGeneration) return false
    _state.update { it.copy(switches = it.switches + (tafId to sw)) }

    var draft: AgentDraft? = null
    if (sw.draft != null) {
      // Nothing shown, nothing signed.
      if (shownDraft == null) return false
      if (!_state.value.sessionHasKey) return dropSwitch(tafId, "Sign in with KIFF again: this sign-in can't sign for the phone.")
      if (_state.value.approvalKeyLost) return dropSwitch(tafId, KiffClient.APPROVAL_KEY_LOST)
      draft = try {
        kiff.draft(sw.draft)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == accountGeneration) versionError(tafId, "Couldn't read this version's Card. ${ErrorWords.of(e)}")
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
      try {
        confirmGrant("tafs.cards.v1|$tenant|${draft.id}|${draft.version}", "Use version ${version.n} of $name, with its Card")
        if (generation != accountGeneration) return false
        val signer = signer
        kiff.setupSigner = { signer.signConfirmed(it) }
        kiff.confirmDraft(draft.id, draft.version)
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
          versionError(tafId, "Version ${version.n}'s Card is out of date: a Card changed since it was drafted. Draft it again, then read it before you approve.")
        } else {
          versionError(tafId, "Installing version ${version.n} didn't finish: KIFF didn't confirm its Card. ${ErrorWords.of(e)} Your taf doesn't run until you finish or cancel it.")
        }
        return false
      }
      // Within the step the owner just confirmed: Coach's tools, if this phone hasn't given them yet.
      if (_state.value.coachTools?.toGive == true) giveCoachTools()
    } else {
      try {
        signer.sign("tafs.version.v1|$tenant|$cloudId|${version.n}".toByteArray(), "Use version ${version.n} of $name")
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
          if (sw.draft == null) "Version ${version.n} isn't live. ${ErrorWords.of(e)}"
          else "Installing version ${version.n} didn't finish. ${ErrorWords.of(e)} Your taf doesn't run until you finish or cancel it.",
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
      versionError(tafId, "The switch wasn't cancelled. ${ErrorWords.of(e)}")
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
    if (generation == accountGeneration) updateDoors(tafId) { it.copy(error = "The doors couldn't be read just now. They're tried again when you come back here.") }
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
      updateDoors(tafId) { it.copy(error = "Adding a WhatsApp door isn't in the Android app yet. Add it from tafs on an iPhone.") }
      return null
    }
    return doorWork(tafId) { cloud.addDoor(it, app.id) }
  }

  suspend fun setDoorOpen(tafId: String, door: Door, open: Boolean) {
    doorWork(tafId) { cloud.setDoorOpen(it, door.id, open) }
  }

  suspend fun removeDoor(tafId: String, door: Door) {
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
      val words = if (e is CloudError && e.status == 429) "That's a lot of messages in a few minutes. Wait a little, then send it again: nothing was sent." else "Nothing was sent. " + ErrorWords.of(e)
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
      if (generation == accountGeneration) trialError(tafId, "The trial couldn't be read. ${ErrorWords.of(e)}")
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
      if (generation == accountGeneration) trialError(tafId, "Version ${version.n}'s trial Card couldn't be read. ${ErrorWords.of(e)}")
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
        trialError(tafId, "Sign in with KIFF again: this sign-in can't sign for the phone.")
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
        if (generation == accountGeneration) trialError(tafId, "Version ${version.n} can't be tried now. ${ErrorWords.of(e)}")
        return null
      }
      if (generation != accountGeneration) return null
      // What the owner read is what the confirmation signs: changed since, it is read again.
      if (draft.version != shownDraft) {
        _state.update { it.copy(changedVersionCards = it.changedVersionCards + (tafId to draft)) }
        trialError(tafId, "What you're asked to approve changed since it was shown. Read it again, then start.")
        return null
      }
      try {
        confirmGrant("tafs.trial.v1|$tenant|${draft.id}|${draft.version}", "Try version ${version.n} of ${taf.name}, with its own Card")
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
        if (generation == accountGeneration) trialError(tafId, "Trying version ${version.n} didn't start. ${ErrorWords.of(e)}")
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
            trialError(tafId, "Sign in with KIFF again: this sign-in can't sign for the phone.")
            return false
          }
          confirmGrant("tafs.cards.v1|$tenant|${draft.id}|${draft.version}", "Install version ${open.n} of ${taf.name}, with its Card")
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
        if (generation == accountGeneration) trialError(tafId, "Installing version ${open.n} didn't finish. ${ErrorWords.of(e)}")
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
        if (generation == accountGeneration) trialError(tafId, "The trial didn't end. ${ErrorWords.of(e)}")
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
    val halt = try {
      cloud.halt(key.ifEmpty { null })
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) fail("tafs couldn't halt ${if (tafId == null) "your tafs" else "it"}. ${ErrorWords.of(e)}")
      return
    }
    if (generation != accountGeneration) return
    _state.update { it.copy(halts = it.halts + ((tafId.orEmpty()) to halt)) }
    // The list reads it halted at once, not at its next read.
    refresh()
  }

  /** Reads where a halt stands. */
  suspend fun refreshHalt(tafId: String?) {
    val key = haltKey(tafId)
    if (_state.value.mode != Mode.kiff || key == null) return
    val generation = accountGeneration
    val halt = attempt { cloud.halted(key.ifEmpty { null }) } ?: return
    if (generation == accountGeneration) _state.update { it.copy(halts = it.halts + (tafId.orEmpty() to halt)) }
  }

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
  suspend fun useThisDevicesTimeZone() {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || tenant == null) return
    val generation = accountGeneration
    _state.update { it.copy(timeZoneError = null) }
    val zone = java.time.ZoneId.systemDefault().id
    try {
      signer.sign("tafs.time-zone.v1|$tenant|$zone".toByteArray(), "Set your account's time zone to $zone")
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
      if (generation == accountGeneration) _state.update { it.copy(timeZoneError = "The time zone didn't change. ${ErrorWords.of(e)}") }
    } finally {
      kiff.setupSigner = null
    }
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
      if (generation == accountGeneration) _state.update { it.copy(peopleError = "People and groups couldn't be read. ${ErrorWords.of(e)}") }
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
      val words = if (e is SignerException) notSigned(e, "$what didn't go through: it wasn't confirmed.") else "$what didn't go through. ${ErrorWords.of(e)}"
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
    changePeople("Share $name", grants = add.isNotEmpty()) { kiff ->
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
      if (generation == accountGeneration) _state.update { it.copy(devicesError = "Couldn't load your devices. ${ErrorWords.of(e)}") }
    }
  }

  /** Signs another device out of the account, behind one confirmation. This device signs out with Sign out instead. False when it didn't go through. */
  suspend fun removeDevice(device: Device): Boolean {
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || tenant == null || device.thisDevice) return false
    val generation = accountGeneration
    val what = "Sign ${device.name ?: "this device"} out of your account"
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
      if (generation == accountGeneration) _state.update { it.copy(devicesError = "$what didn't go through. ${ErrorWords.of(e)}") }
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
      _state.update { it.copy(deleteAccountError = "Sign in with KIFF again: this sign-in can't sign for the phone.") }
      return false
    }
    val generation = accountGeneration
    try {
      signer.sign("tafs.delete-account.v1|$tenant".toByteArray(), "Delete your account")
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
        if (!cloud.deleteAccount().done) return stopped("tafs couldn't erase everything yet. Your KIFF account isn't deleted. Try again.")
      } catch (e: CancellationException) {
        throw e
      } catch (e: CloudError) {
        return stopped(if (e.status == 403) "Only the account's owner can delete it." else "tafs couldn't erase your tafs. Your KIFF account isn't deleted. ${ErrorWords.of(e)}.")
      } catch (e: Exception) {
        return stopped("tafs couldn't erase your tafs. Your KIFF account isn't deleted. ${ErrorWords.of(e)}.")
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
          return stopped("Your tafs are erased, but your connections aren't yet. ${ErrorWords.of(e)}")
        }
        try {
          kiff.deleteAccount()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return stopped("Your tafs and connections are erased, but KIFF didn't delete the account. ${ErrorWords.of(e)}")
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
      fail("Sign in with KIFF again: this sign-in can't sign for the phone.")
      return false
    }
    if (_state.value.approvalKeyLost) {
      fail(KiffClient.APPROVAL_KEY_LOST)
      return false
    }
    val name = tafId?.let { _state.value.taf(it)?.name }
    val generation = accountGeneration
    try {
      // Resuming gives authority back: the approval key signs KIFF's request, behind its own fingerprint.
      confirmGrant("tafs.resume.v1|$tenant|${tafId ?: "*"}", name?.let { "Resume $it" } ?: "Resume all your tafs")
      if (generation != accountGeneration) return false
      kiff.tenant = tenant
      val signer = signer
      kiff.setupSigner = { signer.signConfirmed(it) }
      kiff.resume(tafId)
    } catch (e: SignerException) {
      if (generation == accountGeneration) fail(notSigned(e, "Not resumed: it wasn't confirmed."))
      return false
    } catch (_: KiffError.ApprovalKeyRequired) {
      fail(KiffClient.APPROVAL_KEY_LOST)
      return false
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) fail("KIFF didn't resume ${name ?: "your tafs"}. ${ErrorWords.of(e)}")
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
      if (generation == accountGeneration) fail("KIFF resumed ${name ?: "your tafs"}, but tafs cloud didn't yet. ${ErrorWords.of(e)}")
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
    purchases.record ={ product, token, request -> kiff.recordGooglePurchase(product, token, request) }
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
    _state.update { it.copy(storeProducts = purchases.products.value) }
  }

  // MARK: Asking an admin for tasks

  /** Asks the account's admins for a pack. False, saying why, when KIFF didn't take it. */
  suspend fun askForPack(pack: String, reason: String): Boolean {
    _state.update { it.copy(tasksMessage = null) }
    if (_state.value.mode != Mode.kiff) return false
    return try {
      kiff.askForPack(pack, reason.trim())
      refreshUsage()
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _state.update { it.copy(tasksMessage = "The request didn't go. ${ErrorWords.of(e)}") }
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
      _state.update { it.copy(tasksMessage = "The request wasn't withdrawn. ${ErrorWords.of(e)}") }
    }
    refreshUsage()
  }

  /** Declines a member's request, with a note they'll read: the admin's answer, behind one confirmation, signed. */
  suspend fun declineRequest(id: String, note: String): Boolean {
    _state.update { it.copy(tasksMessage = null) }
    val tenant = kiffTenant
    if (_state.value.mode != Mode.kiff || tenant == null) return false
    if (!_state.value.sessionHasKey) {
      _state.update { it.copy(tasksMessage = "Sign in with KIFF again: this sign-in can't sign for the phone.") }
      return false
    }
    val generation = accountGeneration
    try {
      signer.sign("tafs.decline-request.v1|$tenant|$id".toByteArray(), "Decline the request")
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
      _state.update { it.copy(tasksMessage = "The request wasn't declined. ${ErrorWords.of(e)}") }
      refreshUsage()
      return false
    } finally {
      kiff.setupSigner = null
    }
    refreshUsage()
    return true
  }

  /** Buys Solo or a pack for the account, answering a member's request when there is one. */
  suspend fun buy(product: String, requestId: String? = null) {
    _state.update { it.copy(tasksMessage = null) }
    val token = _state.value.usage?.store?.appAccountToken
    if (!_state.value.offersPurchases || token == null) {
      _state.update { it.copy(tasksMessage = "Only the account's admin adds tasks.") }
      return
    }
    _state.update { it.copy(buying = product) }
    val outcome = purchases.buy(product, token, requestId)
    _state.update { it.copy(buying = null, tasksMessage = buyWords(outcome) ?: it.tasksMessage) }
  }

  /** Brings back this Google account's purchases not finished yet, and sends each to KIFF. */
  suspend fun restorePurchases() {
    _state.update { it.copy(tasksMessage = null) }
    if (!_state.value.offersPurchases) return
    val words = when (val o = purchases.restore()) {
      PurchaseOutcome.Done -> "Purchases restored."
      is PurchaseOutcome.NotRecorded -> "Restored. KIFF hasn't added everything yet: tafs tries again when it opens."
      is PurchaseOutcome.NotAdded -> o.why
      is PurchaseOutcome.NeedsSupport -> o.why
      is PurchaseOutcome.Failed -> "Purchases couldn't be restored: ${o.why}"
      else -> null
    }
    _state.update { it.copy(tasksMessage = words) }
  }

  private fun buyWords(o: PurchaseOutcome): String? = when (o) {
    PurchaseOutcome.Done, PurchaseOutcome.Cancelled -> null
    PurchaseOutcome.Pending -> "The purchase is waiting for approval. Your tasks are added once it's done."
    is PurchaseOutcome.NotRecorded -> "Bought. KIFF hasn't added it yet: tafs tries again when it opens."
    is PurchaseOutcome.NotAdded -> o.why
    is PurchaseOutcome.NeedsSupport -> o.why
    is PurchaseOutcome.Failed -> "The purchase didn't go through: ${o.why}"
  }

  suspend fun refreshUsage() {
    when (_state.value.mode) {
      Mode.signedOut -> _state.update { it.copy(usage = null, countedTasks = emptyList(), aiUse = null) }
      Mode.sample -> {
        val now = clock()
        _state.update { it.copy(usage = sampleUsage(now), countedTasks = emptyList(), usageError = null, aiUse = AIUse(30, 100, false)) }
      }
      Mode.kiff -> {
        val generation = accountGeneration
        try {
          val read = kiff.usage()
          val page = attempt { kiff.countedTasks() }
          // AI use is tafs cloud's; the rest of the screen doesn't wait on it.
          val ai = attempt { cloud.aiUse() }
          if (generation != accountGeneration) return
          _state.update { it.copy(usage = read, countedTasks = page?.items.orEmpty(), usageError = null, aiUse = ai) }
          loadStore()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          if (generation == accountGeneration) _state.update { it.copy(usageError = "Usage couldn't be read. ${ErrorWords.of(e)}") }
        }
      }
    }
  }

  /** Reads the period's usage if it isn't yet, and the taf's own counted tasks. */
  suspend fun refreshTafUsage(tafId: String) {
    if (_state.value.usage == null) refreshUsage()
    if (_state.value.mode != Mode.kiff) return
    val generation = accountGeneration
    val page = attempt { kiff.countedTasks(agent = tafId) } ?: return
    if (generation == accountGeneration) _state.update { it.copy(tafTasks = it.tafTasks + (tafId to page.items)) }
  }

  /** The sample's month: a trial two days in. */
  private fun sampleUsage(now: Instant): Usage = Usage(
    plan = "trial",
    period = bot.tafs.app.kiff.UsagePeriod(now.minus(Duration.ofDays(2)), now.plus(Duration.ofDays(5))),
    tasks = bot.tafs.app.kiff.UsageMeter(
      counted = 14, included = 25, packs = 0, left = 11, byAgent = emptyList(),
      free = bot.tafs.app.kiff.UsageFree(refused = 3, failed = 1, read = 42, trial = 2),
    ),
    actions = bot.tafs.app.kiff.UsageMeter(counted = 0, included = 500),
    trial = bot.tafs.app.kiff.UsageTrial(endsAt = now.plus(Duration.ofDays(5)), daysLeft = 5, tasksLeft = 11, actionsLeft = 500),
  )

  private fun fail(words: String) = _state.update { it.copy(lastError = words) }

  /** The "Not done" alert was read. */
  fun dismissError() = _state.update { it.copy(lastError = null) }

  private fun resolve(call: HeldCall) = _state.update { s -> s.copy(held = s.held.filterNot { it.id == call.id }) }

  private fun update(taf: Taf) = _state.update { s -> s.copy(tafs = s.tafs.map { if (it.id == taf.id) taf else it }) }

  private fun amountLine(p: ProposedAction, tail: String): String =
    p.amount?.let { "${it.formatted}, $tail" } ?: tail.replaceFirstChar { it.uppercase() }

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
    val line = entry(call.tafId, kind, title, detail, call.proposed, signature).copy(id = id, at = call.heldAt)
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
    /** How often a thread is read again while a run is at work in it, and how many times at most. */
    const val CHAT_POLL_MS = 2_500L
    const val CHAT_POLL_BUDGET = 150

    /** The stops at a cap of the run's own: it ends with no words for it. */
    val CAP_STOPS = setOf("cost_cap", "turn_cap")

    /** What the chat says when its latest run stopped at a cap, never "…". */
    fun capWords(stop: String, taf: String): String? = when (stop) {
      "cost_cap" -> "$taf stopped: this task would cost more than its limit for one task. Ask for something narrower, or someone who manages $taf can raise the limit."
      "turn_cap" -> "$taf stopped: this task took more steps than its limit for one task. Ask for something narrower."
      else -> null
    }

    /** A run that failed part-way, with what went through before it stopped, from the run's own record. */
    fun failedWords(taf: String, done: List<bot.tafs.app.kiff.DoneCall>): String {
      if (done.isEmpty()) return "$taf stopped part-way. Nothing went through."
      val calls = done.joinToString("; ") { c -> KiffMapping.toolWords(c.tool) + (c.resource?.let { " on $it" } ?: "") }
      return "$taf stopped part-way, after this went through: $calls."
    }

    /** Waits between tries of a doors read that failed. */
    val DOORS_RETRY_MS = listOf(1_000L, 3_000L)

    /** The platform KIFF records for this device's key. */
    const val PLATFORM = "android"

    // What the session is kept under, as iOS's Keychain accounts.
    const val TOKEN = "kiff.session"
    const val TENANT = "kiff.tenant"
    const val KEYED = "kiff.session-keyed"
    const val APPROVAL_KEYED = "kiff.session-approval-keyed"

    /** What the fingerprint prompt says when Approve again proves this phone holds its new key. */
    const val RELINK_REASON = "Approve again with this fingerprint"

    /** KIFF answered, but holds another key, or this one was turned off while the browser waited. */
    const val RELINK_DIDNT_TAKE = "This phone still can't approve. Try again, or sign in again in Settings."

    const val RELINK_FAILED = "Approving again didn't work. Try again, or sign in again in Settings."

    // What alerts keep on this phone: a target's secret not yet handed to tafs cloud, whether alerts were turned on, and the sound.
    const val HANDOFF = "kiff.alert-handoff"
    const val ALERTS_ON = "kiff.alerts-on"
    const val ALERT_SOUND = "kiff.alert-sound"

    /** The waits between a failed refresh's tries, then none until the person comes back or pulls (kiff/tafs#318). */
    val REFRESH_RETRY_SECONDS = listOf(2L, 10L, 30L, 60L, 120L)

    /** How long a sign-out waits before asking KIFF again to end the session, the last one again and again until it has. */
    val SIGN_OUT_RETRY_SECONDS = listOf(2L, 10L, 30L, 60L, 120L)

    /** A sign-out KIFF hasn't confirmed: the phone stays signed in, and says so, and keeps trying. */
    const val NOT_SIGNED_OUT = "Not signed out at KIFF yet. Try again, or sign this phone out from another device's Devices."
  }
}
