package bot.tafs.app.kiff

import bot.tafs.app.model.InstantAsString
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The account's usage this period, as KIFF sends it and the phone reads it: what the Usage screens show, and what can be
// bought through Google Play (the store). Members' requests are read apart.

@Serializable
data class UsagePeriod(
  @Serializable(with = InstantAsString::class) val start: Instant,
  @Serializable(with = InstantAsString::class) val end: Instant,
)

/** What wasn't counted, by why. A reason KIFF doesn't name counted nothing. */
@Serializable
data class UsageFree(
  val refused: Int = 0,
  @SerialName("held_refused") val heldRefused: Int = 0,
  val halted: Int = 0,
  val failed: Int = 0,
  val invalid: Int = 0,
  val retry: Int = 0,
  val read: Int = 0,
  val trial: Int = 0,
  /** Coach's and Setup's calls: KIFF named them `setup_read`, and `setup` once it frees every call made with a setup key. */
  @SerialName("setup_read") val setupRead: Int = 0,
  val setup: Int = 0,
) {
  /** Each reason with a count, as the owner reads it, in the order they matter. */
  val lines: List<Pair<String, Int>>
    get() = listOf(
      "Refused by KIFF" to refused,
      "Held, then refused" to heldRefused,
      "Halted" to halted,
      "Failed at the tool" to failed,
      "Not valid" to invalid,
      "Retries" to retry,
      "Reads" to read,
      "Trials of a new version" to trial,
      "Coach and Setup" to (setupRead + setup),
    ).filter { it.second > 0 }
}

/** One taf's part of the period: what it counted, and what it didn't and why. */
@Serializable
data class UsageAgent(val agent: String, val counted: Int = 0, val free: UsageFree? = null)

@Serializable
data class UsageMeter(
  val counted: Int = 0,
  val included: Int = 0,
  /** The tafs meter only: the pack tasks the period has. */
  val packs: Int? = null,
  /** The tasks left; null where there is no limit. */
  val left: Int? = null,
  @SerialName("by_agent") val byAgent: List<UsageAgent>? = null,
  val free: UsageFree? = null,
) {
  /** The tasks the period has: what the plan includes and the pack tasks. */
  val total: Int get() = included + (packs ?: 0)

  /** No tasks left: the tafs stop until tasks are added. */
  val usedUp: Boolean get() = left == 0
}

@Serializable
data class UsageTrial(
  /** Null until the trial starts: it starts at checkout on the web. */
  @SerialName("ends_at") @Serializable(with = InstantAsString::class) val endsAt: Instant? = null,
  @SerialName("days_left") val daysLeft: Int = 0,
  @SerialName("tasks_left") val tasksLeft: Int = 0,
  @SerialName("actions_left") val actionsLeft: Int = 0,
)

/** A pack of tasks for sale: "Rayo · 70 tasks", how long its tasks last, and its product in each store. */
@Serializable
data class StorePack(
  val id: String,
  val name: String,
  val line: String = "",
  val tasks: Int = 0,
  val months: Int = 0,
  @SerialName("apple_product") val appleProduct: String? = null,
  /** Google Play's product; absent until KIFF records Play purchases, and then nothing is offered on Android. */
  @SerialName("google_product") val googleProduct: String? = null,
)

/**
 * What can be bought for the account, as KIFF says: Solo and the packs, each by its store's product, and the account's token,
 * which every purchase carries so KIFF knows whose it is (Play's `obfuscatedAccountId`).
 */
@Serializable
data class UsageStore(
  @SerialName("app_account_token") val appAccountToken: String? = null,
  @SerialName("solo_product") val soloProduct: String? = null,
  @SerialName("google_solo_product") val googleSoloProduct: String? = null,
  val packs: List<StorePack> = emptyList(),
) {
  /** The Play products KIFF names: none until KIFF records Play purchases. */
  val googleProducts: List<String> get() = listOfNotNull(googleSoloProduct) + packs.mapNotNull { it.googleProduct }
}

/** Who bills the plan: "apple", "google", "stripe" (KIFF), "contract", or empty when none does. */
@Serializable
data class UsageBilling(val source: String = "")

/** One of the account's packs with tasks left. */
@Serializable
data class UsagePack(
  val id: String,
  val name: String,
  val tasks: Int = 0,
  @SerialName("tasks_left") val tasksLeft: Int = 0,
  @SerialName("expires_at") @Serializable(with = InstantAsString::class) val expiresAt: Instant? = null,
)

/** The account's usage this billing period: the tafs meter and the KIFF meter, the trial, and who bills the plan. */
@Serializable
data class Usage(
  val plan: String = "",
  val period: UsagePeriod,
  val tasks: UsageMeter = UsageMeter(),
  val actions: UsageMeter = UsageMeter(),
  val trial: UsageTrial? = null,
  val billing: UsageBilling? = null,
  @SerialName("pack_list") val packList: List<UsagePack>? = null,
  /** Whether this person may add tasks (an admin), and what can be bought. */
  @SerialName("can_add_tasks") val canAddTasks: Boolean? = null,
  val store: UsageStore? = null,
)

/** One counted task: the Record behind it, which taf, which tool, and when. */
@Serializable
data class CountedTask(
  val record: String,
  val agent: String,
  val tool: String = "",
  /** The tool as the Record names it: "Refund an order". */
  @SerialName("tool_title") val toolTitle: String? = null,
  @Serializable(with = InstantAsString::class) val at: Instant,
)

@Serializable
data class CountedTasks(val items: List<CountedTask> = emptyList(), val next: String? = null)

/** AI use from chats and Setup this period: included, never billed; past fair use the tafs ask before going on. */
@Serializable
data class AIUse(
  @SerialName("used_pct") val usedPct: Int? = null,
  @SerialName("asks_at_pct") val asksAtPct: Int? = null,
  val paused: Boolean = false,
)

/** A halt of one taf or of the whole account, as tafs cloud reads it from kiff/agents, KIFF and planque. */
@Serializable
data class Halt(
  /** running, halting or halted: halted only once all three hold it. A state this app doesn't know reads as halting. */
  val state: String = RUNNING,
  @SerialName("halted_at") @Serializable(with = InstantAsString::class) val haltedAt: Instant? = null,
  /** kiff/agents holds it: no run starts. */
  val runtime: Boolean = false,
  /** KIFF's gateway holds it: it refuses the calls. */
  val kiff: Boolean = false,
  /** planque holds it: it sends nothing to the vendor. */
  val planque: Boolean = false,
  /** Halted because the whole account is, not on its own. */
  val account: Boolean = false,
  /** How many runs this halt stopped. */
  val stopped: Int? = null,
) {
  val running: Boolean get() = state == RUNNING
  val halted: Boolean get() = state == HALTED
  val halting: Boolean get() = !running && !halted

  companion object {
    const val RUNNING = "running"
    const val HALTED = "halted"
    val Running = Halt()
  }
}

@Serializable
internal data class HaltResponse(val halt: Halt)

/** What deleting the account in tafs cloud did (kiff/tafs#185): done, or the step that failed, which asking again goes on from. */
@Serializable
data class AccountDeletion(val done: Boolean = false, val failed: String? = null)
