package bot.tafs.app.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import bot.tafs.app.model.Answer
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
import bot.tafs.app.model.CoachAnswer
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
  /** Why Sign in again didn't go through. */
  val signInAgainError: String? = null,
  /** The held calls KIFF said this person may not answer, by call, with its reason: they show words, not buttons. */
  val unanswerable: Map<String, String> = emptyMap(),
  val tafs: List<Taf> = emptyList(),
  val held: List<HeldCall> = emptyList(),
  val record: List<RecordEntry> = emptyList(),
  val scriptPosition: Map<String, Int> = emptyMap(),
  val templateOf: Map<String, String> = emptyMap(),
  /** What each taf remembers, once loaded; not kept between launches. */
  val memories: Map<String, List<MemoryNote>> = emptyMap(),
  /** Each taf's thread, oldest first; the sample's lives here, not between launches. */
  val chats: Map<String, List<ChatMessage>> = emptyMap(),
  /** The tafs a message is on its way to, so one is sent at a time. */
  val chatSending: Set<String> = emptySet(),
  val chatErrors: Map<String, String> = emptyMap(),
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
  /** The hand-overs (Coach's result cards, by id) whose taf is being started, or failed to start (with why). */
  val startingTafs: Set<String> = emptySet(),
  val tafStartErrors: Map<String, String> = emptyMap(),
  /** The hand-overs this phone told Coach didn't start, before the thread shows the answer. */
  val toldStartFailures: Set<String> = emptySet(),
  /** The version each improvement result made, by its key; those being made, and those that failed (with why). */
  val improvedVersions: Map<String, Int> = emptyMap(),
  val makingVersions: Set<String> = emptySet(),
  val improveErrors: Map<String, String> = emptyMap(),
  /** Coach's cards in its thread, folded. */
  val coachComponents: List<ShownComponent> = emptyList(),
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
  fun connections(tafId: String): List<Grant> = grants.filter { it.request.agent == tafId && (it.connected || it.holdsSignIn) }

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
  val listedTafs: List<Taf>
    get() {
      val latest = mutableMapOf<String, Instant>()
      for (e in record) latest[e.tafId] = maxOf(latest[e.tafId] ?: Instant.MIN, e.at)
      for ((id, messages) in chats) messages.lastOrNull()?.at?.let { latest[id] = maxOf(latest[id] ?: Instant.MIN, it) }
      val waiting = held.map { it.tafId }.toSet()
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

  /**
   * A taf's chat as one timeline: its messages, the calls held for the owner where they were
   * held, and what became of earlier held calls (approved, refused) since the thread's first
   * message, each as one line.
   */
  fun chatItems(tafId: String): List<ChatItem> {
    val messages = chats[tafId] ?: emptyList()
    // Coach's thread has its cards in it, and the owner's answers to them read as what they chose.
    if (tafId == COACH_CHAT) {
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
    // The approval key counts only while it's the one KIFF registered: a key made anew since (the
    // lock screen turned off and on) registers again, and KIFF's answer says whether it may.
    val key = signer.approvalPublicKeyDER
    if (keyed && key.isNotEmpty() && KiffClient.sameKey(secrets.load(APPROVAL_KEYED), key)) useApprovalKey()
    // Drawn at once, saying it is loading, not after the network: the owner never waits on a blank screen.
    _state.update { it.copy(mode = Mode.kiff, ready = true, sessionHasKey = keyed, alertsAvailable = push.available, alertSound = secrets.load(ALERT_SOUND) != "0") }
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

  /** The app came back to the foreground: the sign-in asks KIFF again at once, so an approval made in the browser signs the phone in without a wait. */
  fun nudgeSignIn() {
    signInNudged = true
  }

  /**
   * Polls until the person approves the code in KIFF, then loads their account. It keeps polling
   * through a dropped connection or a KIFF error until the code expires.
   */
  suspend fun finishSignIn(start: DeviceStart) {
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
        val token = kiff.pollDeviceToken(start.deviceCode)
        useSession(token.accessToken, token.tenant)
        return
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
    val coach = coachWithTools()
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
    kiff.approvalSigner = { signer.signApproval(it, approvalReason) }
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
    else -> otherwise
  }

  private fun forgetApprovalKey() {
    sessionHasApprovalKey = false
    // A phone with no approval key (no fingerprint enrolled) grants nothing with its screen lock: what grants authority
    // stops at the signer, which says to add a fingerprint. One with a key KIFF doesn't hold yet signs as before.
    val signer = signer
    kiff.approvalSigner = if (signer.approvalPublicKeyDER.isEmpty()) { m -> signer.signApproval(m, approvalReason) } else null
  }

  /** KIFF answered that an act needs the approval key this phone doesn't hold: approving waits for a new sign-in, and says so. */
  private fun lostApprovalKey() {
    sessionHasApprovalKey = false
    _state.update { it.copy(approvalKeyLost = true) }
    kiff.approvalSigner = null
    secrets.delete(APPROVAL_KEYED)
  }

  /**
   * After this phone lost its approval key: the session ends at KIFF first, and its approval key with it, so a key that no
   * longer approves never stays listed. Only then is the phone signed out; the next sign-in, approved in the browser,
   * registers the phone's new key as a new device. False when KIFF didn't end the session, and nothing changed here.
   */
  suspend fun signInAgain(): Boolean {
    if (_state.value.mode != Mode.kiff) return false
    _state.update { it.copy(signInAgainError = null) }
    // While the session can still say so: this phone stops getting the account's alerts.
    pushToken?.let { token -> if (alertsWanted()) runCatching { cloud.unregisterPush(token) } }
    try {
      kiff.signOut()
    } catch (e: CancellationException) {
      throw e
    } catch (e: KiffError.Unauthorized) {
      // KIFF ended it already.
    } catch (e: Exception) {
      _state.update { it.copy(signInAgainError = "KIFF didn't end this sign-in, so nothing changed: ${e.message}. Try again.") }
      return false
    }
    signOut(tellKiff = false)
    return true
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

  /** Revokes a taf's Card: no new action goes through, and what waited on the owner is refused. */
  fun revoke(tafId: String) {
    val taf = _state.value.taf(tafId) ?: return
    val dropped = _state.value.heldCalls(tafId)
    update(taf.copy(card = taf.card.copy(revoked = true), state = TafState.revoked, now = "Stopped: Card revoked"))
    _state.update { s -> s.copy(held = s.held.filterNot { it.tafId == tafId }) }
    val detail = if (dropped.isEmpty()) "No new action goes through" else "No new action goes through. ${dropped.size} waiting action(s) refused."
    log(tafId, RecordEntry.Kind.revoked, "You revoked ${taf.name}'s Card", detail)
    save()
  }

  // MARK: Chat

  /**
   * Opens a taf's thread: in the sample it starts with the taf's hello; signed in, it is read from tafs cloud, and
   * followed while a run is at work in it.
   */
  suspend fun loadChat(tafId: String) {
    val taf = _state.value.taf(tafId) ?: return
    if (_state.value.mode == Mode.kiff) {
      readThread(tafId)
      followChat(tafId)
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
    val generation = accountGeneration
    val thread = try {
      if (isTrialChat(tafId)) cloud.trialConversation(cloudId) else cloud.conversation(cloudId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(chatErrors = it.chatErrors + (tafId to "The thread couldn't be loaded: ${e.message}")) }
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
      )
    }
    if (tafId == COACH_CHAT) {
      readApprovals()
      startHandedOver()
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
    if (_state.value.mode != Mode.kiff || !push.available || !alertsWanted()) return
    val token = pushToken ?: push.token()?.also { pushToken = it } ?: return
    val generation = accountGeneration
    val handoff = pendingHandoff()
    val status = attempt { cloud.registerPush(token, _state.value.alertSound, handoff?.target) } ?: return
    if (generation != accountGeneration) return
    if (handoff != null) keepHandoff(null)
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
            fail("Alerts aren't on: ${e.message}")
          }
          return false
        } catch (e: Exception) {
          fail("Alerts aren't on: ${e.message}")
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
          fail("Alerts are almost on: tafs cloud didn't answer (${e.message}). Tap Turn on alerts again.")
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
      cloud.setup()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(coachChecked = true, chatErrors = it.chatErrors + (COACH_CHAT to "Coach couldn't be reached: ${e.message}")) }
      return
    }
    if (generation == accountGeneration) {
      _state.update { it.copy(coachCloudId = setup?.id, coachAgent = setup?.kiffAgent, coachChecked = true, chatErrors = it.chatErrors - COACH_CHAT) }
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
        keepCoachTools(tenant, setup?.kiffAgent, generation)
        if (!_state.value.planqueReady) {
          try {
            planque.enrol(signer.publicKeyDER, kiff.mintConnectorKey())
          } catch (e: CancellationException) {
            throw e
          } catch (e: KiffError.Server) {
            startFailed(if (e.status == 403) "Only an admin of this KIFF account can connect tools." else "Connecting tools isn't set up: ${e.message}")
            return false
          } catch (e: PlanqueError.Server) {
            startFailed(if (e.status == 409) "Another device connects this account's tools for now. Connect them from that one." else "Connecting tools isn't set up: ${e.message}")
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
            startFailed("KIFF didn't answer as expected. Try again.")
            return false
          }
          setup = cloud.startSetup(draft.id, secret)
          // Coach's tools wait on its agent, which only a started Coach has.
          keepCoachTools(tenant, setup.kiffAgent, generation)
        }
      } catch (e: SignerException) {
        if (generation == accountGeneration) notSigned(e, "").takeIf { it.isNotEmpty() }?.let { startFailed(it) }
        return false
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == accountGeneration) startFailed("Couldn't start: ${e.message}")
        return false
      }
      if (generation != accountGeneration) return false
      _state.update { it.copy(coachCloudId = setup?.id, coachAgent = setup?.kiffAgent, coachChecked = true) }
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

  /** Where this phone remembers Coach's tools are given: per account, Coach and server. */
  private fun coachToolsKey(tenant: String, agent: String) = "coachTools.$tenant.$agent.${cloud.mcpUrl}"

  /** The account's Coach, once this phone gave it its tools; null before. Only its held version_install is an install. */
  private fun coachWithTools(): String? {
    val tenant = kiffTenant ?: return null
    val agent = _state.value.coachAgent?.takeIf { it.isNotEmpty() } ?: return null
    return agent.takeIf { secrets.load(coachToolsKey(tenant, it)) == "1" }
  }

  /**
   * Gives Coach its tools, inside a step the owner just confirmed, so no new prompt: each of the seven tools on tafs cloud's
   * server connected in KIFF as the account's control server, one at a time; then Coach's Cards, which name them. A connection
   * there already counts only when it's this server's control connection: a name another server holds stops it before any Card,
   * since the Cards grant by name. Done once per Coach and server on this phone; when it can't be now, Coach does without its
   * tools until the next confirmed step.
   */
  private suspend fun keepCoachTools(tenant: String, agent: String?, generation: Int) {
    if (agent.isNullOrEmpty()) return
    val key = coachToolsKey(tenant, agent)
    if (secrets.load(key) == "1") return
    try {
      val (missing, taken) = KiffClient.coachToolsPlan(kiff.connections(), cloud.mcpUrl)
      if (generation != accountGeneration) return
      if (taken != null) {
        fail("Coach can't have its tools yet: another tool in KIFF is named $taken. Rename or remove it in KIFF.")
        return
      }
      for (tool in missing) {
        if (generation != accountGeneration) return
        kiff.connectCoachTool(tool, cloud.mcpUrl)
      }
      if (generation != accountGeneration) return
      // Its own fingerprint, in its own words: it gives Coach its tools.
      approvalReason = "Give Coach its Card"
      kiff.submitCoachCards(agent)
      secrets.save("1", key)
    } catch (e: CancellationException) {
      throw e
    } catch (e: ApprovalKeyRenewed) {
      lostApprovalKey()
    } catch (_: Exception) {
      // Not now.
    }
  }

  // MARK: Coach's approvals (planque and KIFF)

  /** What a connection's approval came to. */
  sealed interface Connected {
    /** Connected, or its Cards confirmed. */
    data class Done(val grant: Grant) : Connected

    /** Nothing was done and nothing is to be told: the owner didn't confirm, the terms changed on the screen, or another approval is going through. */
    data object Stopped : Connected

    /** It didn't go through, and why. */
    data class Failed(val why: String) : Connected
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
        return Connected.Failed("Couldn't read what to approve: ${e.message}")
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
          return Connected.Failed("KIFF didn't issue the taf's Cards: ${e.message}")
        }
        return Connected.Done(grant)
      }
      if (grant.status !in setOf("pending", "approved", "signed_in")) {
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
        val connected = planque.confirm(grant.id, signedIn.code)
        refreshConnections()
        return Connected.Done(connected)
      } catch (e: VendorSignInCancelled) {
        refreshConnections()
        return Connected.Failed("The sign-in was closed. Nothing was connected.")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        refreshConnections()
        return Connected.Failed("Not connected: ${e.message}")
      }
    } finally {
      planque.signer = null
      kiff.setupSigner = null
      if (generation == accountGeneration) _state.update { it.copy(connecting = null) }
    }
  }

  /**
   * The owner approves a card Coach asked about: the connection, or the Cards over it. What it came to goes back to Coach as the
   * card's answer: "approved", or "failed: why" so Coach can say what went wrong and ask again. Nothing is told for a step the
   * owner didn't confirm or whose terms changed on the screen.
   */
  suspend fun approveCoach(c: SetupComponent, shownDigest: String?, shownDraft: String?) {
    val grantId = c.grant ?: return
    when (val result = connect(c.id, grantId, shownDigest, c.draft, shownDraft)) {
      is Connected.Done -> deliverChat(CoachAnswer.message("approval", c.id, "approved"), COACH_CHAT)
      is Connected.Failed -> {
        note(c.id, result.why)
        deliverChat(CoachAnswer.message("approval", c.id, "failed: " + result.why), COACH_CHAT)
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
      is Connected.Done -> deliverChat(CoachAnswer.message("sign_in", c.id, "signed in"), COACH_CHAT)
      is Connected.Failed -> {
        note(c.id, result.why)
        deliverChat(CoachAnswer.message("sign_in", c.id, "failed: " + result.why), COACH_CHAT)
      }
      Connected.Stopped -> Unit
    }
  }

  // MARK: Coach's hand-overs

  /** A hand-over's taf, by the KIFF agent it acts as. */
  private fun startedTaf(agent: String): Boolean = _state.value.tafs.any { it.id == agent }

  /** The agents whose re-handed-over result already started by itself once, so a refusal that isn't about the name can't loop with no one in it. */
  private val restartedAgents = mutableSetOf<String>()

  /** The results whose failure is being sent to Coach now, so it's sent once. */
  private val tellingStartFailures = mutableSetOf<String>()

  /**
   * Coach's hand-over starts its taf once, wherever the owner is: not only while the result card is on screen, so the taf reaches the
   * list either way. One that didn't start waits for the card's Try again; one whose failure Coach was told of is Coach's to hand over
   * again. An improvement makes the taf's next version, never a new taf.
   */
  private suspend fun startHandedOver() {
    if (_state.value.mode != Mode.kiff) return
    for (c in _state.value.coachComponents.map { it.component }.filter { it.type == "result" }) {
      val taf = c.taf ?: continue
      if (taf.improves != null) {
        val key = improvementKey(c)
        val s = _state.value
        if (key !in s.improvedVersions && key !in s.makingVersions && key !in s.improveErrors) makeImprovedVersion(c)
        continue
      }
      if (taf.job == null || taf.draft == null) continue
      val s = _state.value
      if (startedTaf(taf.agent) || c.id in s.startingTafs || c.id in s.tafStartErrors) continue
      if (startFailureTold(c)) continue
      // A result Coach handed over again after a failed start starts by itself once per agent; one that fails again waits for the
      // owner's Try again.
      val again = s.coachComponents.any { it.component.id != c.id && it.component.taf?.agent == taf.agent && startFailureTold(it.component) }
      if (again) {
        if (taf.agent in restartedAgents) {
          _state.update { it.copy(tafStartErrors = it.tafStartErrors + (c.id to "${taf.name} isn't started yet: its start failed before. Try again when you're ready.")) }
          continue
        }
        restartedAgents += taf.agent
      }
      startSetupTaf(c)
    }
  }

  /** Whether Coach was told this hand-over failed: it then shows a new result, and the card waits for it rather than offering the same start again. */
  fun startFailureTold(c: SetupComponent): Boolean =
    CoachWords.answerTo(c.id, _state.value.chats[COACH_CHAT].orEmpty())?.failed == true || c.id in _state.value.toldStartFailures

  /**
   * Starts the taf a result hands over to, once: tafs cloud stores it, and kiff/agents claims its key from Coach's draft. Then it shows
   * with the account's other tafs.
   */
  suspend fun startSetupTaf(c: SetupComponent) {
    val taf = c.taf ?: return
    val job = taf.job ?: return
    val draft = taf.draft ?: return
    if (_state.value.mode != Mode.kiff || taf.improves != null || startedTaf(taf.agent) || c.id in _state.value.startingTafs) return
    _state.update { it.copy(startingTafs = it.startingTafs + c.id, tafStartErrors = it.tafStartErrors - c.id) }
    val generation = accountGeneration
    var nameTaken = false
    try {
      cloud.startFromSetup(taf.name, job, draft, unscheduled = taf.unscheduled == true)
    } catch (e: CancellationException) {
      throw e
    } catch (e: CloudError) {
      if (generation != accountGeneration) return
      if (e.status == 409 && (e.message ?: "").contains("already have a taf named")) {
        // Started before, by this phone or another, unless another taf has the name.
        nameTaken = true
      } else {
        _state.update { it.copy(tafStartErrors = it.tafStartErrors + (c.id to "${taf.name} isn't started yet: ${e.message}")) }
        // tafs cloud refused it (a 4xx): trying the same again fails the same way, so Coach hears of it. A server error or a lost
        // connection may pass: the card's Try again stays, and Coach isn't told. Not a 401: signing in again fixes it, as time does a 429.
        if (e.status in 400..499 && e.status != 401 && e.status != 429) tellCoachStartFailed(c, e.message ?: "tafs cloud refused it")
        return
      }
    } catch (e: Exception) {
      if (generation == accountGeneration) _state.update { it.copy(tafStartErrors = it.tafStartErrors + (c.id to "${taf.name} isn't started yet: ${e.message}")) }
      return
    } finally {
      if (generation == accountGeneration) _state.update { it.copy(startingTafs = it.startingTafs - c.id) }
    }
    if (generation != accountGeneration) return
    refresh()
    // A 409 can also mean another taf has the name: say so rather than wait forever.
    if (nameTaken && !startedTaf(taf.agent)) {
      _state.update { it.copy(tafStartErrors = it.tafStartErrors + (c.id to "${taf.name} isn't started yet: the account already has a taf by that name.")) }
      tellCoachStartFailed(c, "the account already has a taf named ${taf.name}")
    }
  }

  /**
   * Tells Coach, once, that the taf its result handed over wasn't created and why, as the answer to that result: its draft is
   * confirmed and unclaimed, so Coach hands it over again, under a new name when the name was taken.
   */
  private suspend fun tellCoachStartFailed(c: SetupComponent, why: String) {
    if (startFailureTold(c) || c.id in tellingStartFailures) return
    tellingStartFailures += c.id
    try {
      val sent = deliverChat(CoachAnswer.message("result", c.id, "failed: the taf wasn't created: $why"), COACH_CHAT)
      // Told only once it went: a send that failed leaves Try again on the card, and the next failure tries to tell Coach again.
      if (sent) _state.update { it.copy(toldStartFailures = it.toldStartFailures + c.id) }
    } finally {
      tellingStartFailures -= c.id
    }
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
      if (generation == accountGeneration) _state.update { it.copy(improveErrors = it.improveErrors + (key to "${taf.name}'s new version isn't ready: ${e.message}")) }
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
      fail("Not disconnected: ${e.message}")
      return false
    } finally {
      planque.signer = null
    }
    refreshConnections()
    return true
  }

  /** The owner's answer to one of Coach's cards: a message, "[<type> <id>] <answer>", one answer per card. */
  fun answerCoach(type: String, id: String, answer: String) {
    viewModelScope.launch { deliverChat(CoachAnswer.message(type, id, answer), COACH_CHAT) }
  }

  /**
   * Sends what the owner wrote, from a screen: it shows at once and the taf answers after it.
   * [onNotSent] gets the words back when they didn't go, so the screen can keep them.
   */
  fun sendChat(text: String, tafId: String, onNotSent: (String) -> Unit) {
    viewModelScope.launch { if (!deliverChat(text, tafId)) onNotSent(text) }
  }

  /**
   * Sends the owner's message to the taf and waits for its answer. The sample answers from its
   * own record; signed in, the thread is tafs cloud's.
   */
  suspend fun deliverChat(text: String, tafId: String): Boolean {
    val words = text.trim()
    if (words.isEmpty()) return false
    if (words.length > Chat.LIMIT) {
      _state.update { it.copy(chatErrors = it.chatErrors + (tafId to "That's longer than ${Chat.LIMIT} characters. Say it in fewer words.")) }
      return false
    }
    if (_state.value.mode == Mode.kiff) return deliverToTafsCloud(words, tafId)
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
  private suspend fun deliverToTafsCloud(words: String, tafId: String): Boolean {
    val taf = _state.value.taf(tafId)
    val cloudId = taf?.cloudId
    if (cloudId == null) {
      _state.update { it.copy(chatErrors = it.chatErrors + (tafId to "This taf isn't running on tafs cloud, so it can't be messaged.")) }
      return false
    }
    if (tafId in _state.value.chatSending) return false
    _state.update { it.copy(chatSending = it.chatSending + tafId, chatErrors = it.chatErrors - tafId) }
    val generation = accountGeneration
    val pending = ChatMessage(nextStamp(tafId), ChatMessage.From.owner, words, "pending")
    append(tafId, pending)
    _state.update { it.copy(chatSent = it.chatSent + (tafId to (it.chatSent[tafId].orEmpty() + pending)), chatEnded = it.chatEnded - tafId) }
    var streamed: kotlinx.coroutines.flow.Flow<StreamEvent>? = null
    try {
      if (isTrialChat(tafId)) {
        // A trial's turn runs in the background, and its chat is read again as it goes.
        cloud.trialMessage(cloudId, words)
      } else {
        val run = _state.value.chatLive[tafId]?.run ?: _state.value.chatWorking[tafId]?.run
        var steered = false
        if (run != null) {
          try {
            cloud.steer(cloudId, run, words)
            steered = true
          } catch (e: CloudError) {
            // A run that has ended throws 409, and the message goes as a new one.
            if (e.status != 409) throw e
          }
        }
        // Streamed, so the taf's words show as it writes them; sent plainly when the stream can't open.
        if (!steered) streamed = openStream(cloudId, words)
        if (!steered && streamed == null) cloud.sendMessage(cloudId, words)
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
      followStream(tafId, events, retry = words)
      return true
    }
    readThread(tafId)
    followChat(tafId)
    return true
  }

  /**
   * Opens the streamed send. Null when the stream couldn't open and the plain send should take the message (no network to it,
   * tafs cloud without the route, or failing); a refusal (the flood guard, the role) throws, as the plain send's would.
   */
  private suspend fun openStream(cloudId: String, text: String): kotlinx.coroutines.flow.Flow<StreamEvent>? = try {
    cloud.streamMessage(cloudId, text)
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
  private fun followStream(tafId: String, events: kotlinx.coroutines.flow.Flow<StreamEvent>, retry: String?) {
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
              // At work in that chat, for steering and Stop.
              _state.update { it.copy(chatWorking = it.chatWorking + (tafId to ChatRun(e.session, e.run))) }
            }
            is StreamEvent.Text -> live { it.copy(text = it.text + e.text, activity = null, thinking = "") }
            is StreamEvent.Thinking -> live { it.copy(thinking = it.thinking + e.text) }
            is StreamEvent.Activity -> live { a ->
              // The words written so far stay, and the answer goes on below them.
              val kept = if (a.text.isNotEmpty() && !a.text.endsWith("\n\n")) a.copy(text = a.text + "\n\n") else a
              kept.began(e.tool, e.step ?: KiffMapping.toolWords(e.tool), e.call)
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
      if (generation == accountGeneration) fail("Not stopped: ${e.message}")
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

  /** Back in a chat whose run is at work: its stream is picked back up once, its steps so far, then the rest. */
  private suspend fun resumeChat(tafId: String, cloudId: String, run: String) {
    if (followers[tafId]?.isActive == true || run in resumedRuns || isTrialChat(tafId)) return
    resumedRuns += run
    val events = try {
      cloud.resumeRun(cloudId, run)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return
    }
    val working = _state.value.chatWorking[tafId]
    _state.update { it.copy(chatLive = it.chatLive + (tafId to LiveAnswer(started = clock(), session = working?.session, run = run))) }
    followStream(tafId, events, retry = null)
  }

  /** Why a message wasn't sent, in words. */
  private fun sendFailure(e: Exception, name: String): String =
    if (e is CloudError && e.status == 429) "That's a lot of messages in a few minutes. Give $name a moment, then try again."
    else "The message wasn't sent: ${e.message}"

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

  /** The taf's notes: in the sample, the kind of thing a real one writes down as it learns. */
  fun loadMemory(tafId: String) {
    if (_state.value.memories.containsKey(tafId)) return
    _state.update { it.copy(memories = it.memories + (tafId to sampleNotes(tafId, clock()))) }
  }

  /** Forgets one note. */
  fun forget(note: MemoryNote, tafId: String) =
    _state.update { s -> s.copy(memories = s.memories + (tafId to (s.memories[tafId] ?: emptyList()).filterNot { it.id == note.id })) }

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
        // KIFF's message: this hold, this answer, the hold's version, now.
        val signedAt = DateTimeFormatter.ISO_INSTANT.format(clock().truncatedTo(ChronoUnit.SECONDS))
        val step = (answer as? Answer.NextStep)?.step
        val message = KiffClient.answerMessage(tenant, kiffId, answer.kiffName, step, null, version, signedAt)
        // Approving, or raising a Card, grants authority: the approval key signs it, behind its own fingerprint, which
        // the key checks itself (kiff/tafs#233). An approval key KIFF doesn't hold yet registers first. A phone with no
        // approval key doesn't approve with the screen lock: it says to add a fingerprint. Refusing is the signing key's.
        val approving = answer is Answer.ApproveOnce || answer is Answer.ExtraRoom
        if (approving && !sessionHasApprovalKey && signer.approvalPublicKeyDER.isNotEmpty()) registerApprovalKey()
        when {
          approving && (sessionHasApprovalKey || signer.approvalPublicKeyDER.isEmpty()) -> signer.signApproval(message, reason)
          confirmed -> signer.signConfirmed(message)
          else -> signer.sign(message, reason)
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
        decide(call, RecordEntry.Kind.approved, "Extra room by you · $at · $summary", "Its Card got the room this call needed, until that total resets", signature)
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
      if (generation == accountGeneration) versionError(tafId, "Its versions couldn't be loaded: ${e.message}")
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
      if (generation == accountGeneration) versionError(tafId, "Couldn't read this version's Card: ${e.message}")
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
        versionError(tafId, "Version ${version.n} can't go live now: ${e.message}")
      }
      null
    } catch (e: Exception) {
      if (generation == accountGeneration) versionError(tafId, "Version ${version.n} can't go live now: ${e.message}")
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
      if (generation == accountGeneration) versionError(tafId, "Version ${version.n} can't go live now: ${e.message}")
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
        if (generation == accountGeneration) versionError(tafId, "Couldn't read this version's Card: ${e.message}")
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
          versionError(tafId, "Installing version ${version.n} didn't finish: KIFF didn't confirm its Card (${e.message}). Your taf doesn't run until you finish or cancel it.")
        }
        return false
      }
      // Within the step the owner just confirmed: Coach's tools, if this phone hasn't given them yet.
      keepCoachTools(tenant, _state.value.coachAgent, generation)
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
          if (sw.draft == null) "Version ${version.n} isn't live: ${e.message}"
          else "Installing version ${version.n} didn't finish (${e.message}). Your taf doesn't run until you finish or cancel it.",
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
      versionError(tafId, "The switch wasn't cancelled: ${e.message}")
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
      if (generation == accountGeneration) updateDoors(tafId) { it.copy(error = e.message ?: "tafs cloud didn't answer.") }
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
      val words = if (e is CloudError && e.status == 429) "That's a lot of messages in a few minutes. Wait a little, then send it again: nothing was sent." else e.message ?: "tafs cloud didn't answer."
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
      if (generation == accountGeneration) trialError(tafId, "The trial couldn't be read: ${e.message}")
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
      if (generation == accountGeneration) trialError(tafId, "Version ${version.n}'s trial Card couldn't be read: ${e.message}")
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
        if (generation == accountGeneration) trialError(tafId, "Version ${version.n} can't be tried now: ${e.message}")
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
        if (generation == accountGeneration) trialError(tafId, "Trying version ${version.n} didn't start: ${e.message}")
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
        if (generation == accountGeneration) trialError(tafId, "Installing version ${open.n} didn't finish: ${e.message}")
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
        if (generation == accountGeneration) trialError(tafId, "The trial didn't end: ${e.message}")
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
      if (generation == accountGeneration) fail("tafs couldn't halt ${if (tafId == null) "your tafs" else "it"}: ${e.message}. Try again.")
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
      if (generation == accountGeneration) _state.update { it.copy(timeZoneError = "The time zone didn't change: ${e.message}") }
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
      if (generation == accountGeneration) _state.update { it.copy(peopleError = "People and groups couldn't be read: ${e.message}") }
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
      val words = if (e is SignerException) notSigned(e, "$what didn't go through: it wasn't confirmed.") else "$what didn't go through: ${e.message}"
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
      if (generation == accountGeneration) _state.update { it.copy(devicesError = "Couldn't load your devices: ${e.message}") }
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
      if (generation == accountGeneration) _state.update { it.copy(devicesError = "$what didn't go through: ${e.message}") }
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
        return stopped(if (e.status == 403) "Only the account's owner can delete it." else "tafs couldn't erase your tafs: ${e.message}. Your KIFF account isn't deleted. Try again.")
      } catch (e: Exception) {
        return stopped("tafs couldn't erase your tafs: ${e.message}. Your KIFF account isn't deleted. Try again.")
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
          return stopped("Your tafs are erased, but your connections aren't yet: ${e.message}. Try again.")
        }
        try {
          kiff.deleteAccount()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return stopped("Your tafs and connections are erased, but KIFF didn't delete the account: ${e.message}. Try again.")
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
      if (generation == accountGeneration) fail("KIFF didn't resume ${name ?: "your tafs"}: ${e.message}")
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
      if (generation == accountGeneration) fail("KIFF resumed ${name ?: "your tafs"}, but tafs cloud didn't yet: ${e.message}. Try again.")
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
          if (generation == accountGeneration) _state.update { it.copy(usageError = "Usage couldn't be read: ${e.message}") }
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
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      AppModel(store, signer, kiff, cloud, secrets, deviceName = deviceName, push = push, planque = planque, vendorSignIn = vendorSignIn, purchases = purchases, playBilling = playBilling) as T
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

    // What alerts keep on this phone: a target's secret not yet handed to tafs cloud, whether alerts were turned on, and the sound.
    const val HANDOFF = "kiff.alert-handoff"
    const val ALERTS_ON = "kiff.alerts-on"
    const val ALERT_SOUND = "kiff.alert-sound"

    /** The waits between a failed refresh's tries, then none until the person comes back or pulls (kiff/tafs#318). */
    val REFRESH_RETRY_SECONDS = listOf(2L, 10L, 30L, 60L, 120L)
  }
}
