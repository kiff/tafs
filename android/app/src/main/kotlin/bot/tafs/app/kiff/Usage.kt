package bot.tafs.app.kiff

import bot.tafs.app.model.InstantAsString
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// KIFF's usage read, for what the app reads of it beside tafs cloud's balance (docs/accounts.md#in-the-apps): the plan and its
// period, what can be bought through Google Play (the store), who bills the plan, and requests to the admins. Its other fields
// stay in KIFF's shape, inert, and the app ignores them.

@Serializable
data class UsagePeriod(
  @Serializable(with = InstantAsString::class) val start: Instant,
  @Serializable(with = InstantAsString::class) val end: Instant,
)

/** A pack of credits for sale, which never expire: its line as KIFF writes it, its credits, and its product in each store. */
@Serializable
data class StorePack(
  val id: String,
  val name: String,
  val line: String = "",
  val credits: Int = 0,
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

/** KIFF's usage read: the plan (`solo`, `team` for Company, `company` for an agreement, `internal`), its period, and the rest. */
@Serializable
data class Usage(
  val plan: String = "",
  val period: UsagePeriod? = null,
  val billing: UsageBilling? = null,
  val store: UsageStore? = null,
  /** Requests to the admins: this person's own, newest first; for someone who manages billing, everyone's. */
  val requests: List<PackRequest>? = null,
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

/**
 * A request to the account's admins (docs/accounts.md#the-balance): for a raise of the spend limit on Company, which a raise
 * answers, or for a pack on Solo, which an admin approves by buying it. Either can be declined with a note.
 */
@Serializable
data class PackRequest(
  val id: String,
  @SerialName("asked_by") val askedBy: String,
  /** `raise` or `pack`; a request from before kinds is for a pack. */
  val kind: String = PACK,
  /** The pack asked for; none on a raise. */
  val pack: String? = null,
  val reason: String = "",
  /** open, approved, declined, withdrawn or expired. */
  val status: String,
  @SerialName("created_at") @Serializable(with = InstantAsString::class) val createdAt: Instant,
  @SerialName("expires_at") @Serializable(with = InstantAsString::class) val expiresAt: Instant,
  val note: String? = null,
) {
  val isRaise: Boolean get() = kind == RAISE

  companion object {
    const val RAISE = "raise"
    const val PACK = "pack"
  }
}


