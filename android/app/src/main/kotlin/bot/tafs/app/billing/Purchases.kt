package bot.tafs.app.billing

import bot.tafs.app.kiff.KiffError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A product as the app shows it: its name and the price Google Play gives, and, for Solo, its terms. */
data class StoreProduct(
  val id: String,
  val name: String,
  /** Play's price, in the person's currency ("$4.99"). */
  val price: String,
  /** Solo only: how long a period is ("month"), and its free trial ("1 week") when this person can have it. */
  val period: String? = null,
  val freeTrial: String? = null,
)

/** A purchase Google Play reported: its token, which KIFF verifies, and finishing it once KIFF has recorded it. */
class StorePurchase(
  val productId: String,
  val token: String,
  /** Done once KIFF has it: a pack is consumed, so it can be bought again; Solo needs nothing more. */
  val finish: suspend () -> Unit,
)

/** How a purchase ended. */
sealed interface PurchaseOutcome {
  /** Bought and recorded by KIFF. */
  data object Done : PurchaseOutcome
  data object Cancelled : PurchaseOutcome
  /** Waiting on something outside the app (a payment that clears later): it arrives later. */
  data object Pending : PurchaseOutcome
  /** Bought, but KIFF hasn't recorded it yet: it's sent again at the next launch. */
  data class NotRecorded(val why: String) : PurchaseOutcome
  /** KIFF refused it for good, such as a test purchase on a real account: finished, with nothing added. */
  data class NotAdded(val why: String) : PurchaseOutcome
  /** KIFF can't add it, and a retry won't change that, though the person paid: kept unfinished for support. */
  data class NeedsSupport(val why: String) : PurchaseOutcome
  /** Google Play couldn't sell it. */
  data class Failed(val why: String) : PurchaseOutcome
}

/** Google Play, as the app uses it. [PlayBillingStore] is the Billing Library's; tests use their own. */
interface PlayStore {
  suspend fun products(ids: List<String>): List<StoreProduct>
  /** Buys a product for the account [accountToken] names. Null when cancelled or pending; which, [Bought.pending] says. */
  suspend fun purchase(id: String, accountToken: String): Bought
  /** Purchases Play reports while the app runs: one that cleared later, renewals, purchases made on another device. */
  val updates: Flow<StorePurchase>
  /** Purchases not finished yet, such as one KIFF didn't record before the app closed: also what Restore brings back. */
  suspend fun unfinished(): List<StorePurchase>

  data class Bought(val purchase: StorePurchase?, val pending: Boolean = false)
}

/**
 * Buying Solo and task packs in the app, through Google Play (docs/accounts.md), as iOS's `Purchases` does through the App Store.
 *
 * Every purchase carries the account's token, read from KIFF, so KIFF knows which account it is for. Each purchase, whether
 * made here, reported later or restored, is sent to KIFF, and finished only after KIFF has recorded it: one that isn't recorded
 * stays with Play and is sent again at the next launch.
 */
class Purchases(private val store: PlayStore) {
  private val _products = MutableStateFlow<Map<String, StoreProduct>>(emptyMap())
  val products: StateFlow<Map<String, StoreProduct>> = _products
  private val _buying = MutableStateFlow<String?>(null)
  val buying: StateFlow<String?> = _buying

  /** Sends a purchase (its product, its token) to KIFF, with the request it answers. */
  var record: (suspend (String, String, String?) -> Unit)? = null
  /** Called once KIFF has recorded a purchase, so the screens read the account again. */
  var recorded: (suspend () -> Unit)? = null

  val updates: Flow<StorePurchase> get() = store.updates

  /** Reads the products' names and prices from Play. */
  suspend fun load(ids: List<String>) {
    if (ids.isEmpty()) return
    // Read again each time, never kept from an earlier read: what Play offers changes with the Google account and its history
    // (a trial used, the Play Store switched to another account). Unreadable: what was read before stays.
    val read = try {
      store.products(ids)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return
    }
    // A product Play doesn't list now isn't offered.
    _products.update { it - ids.toSet() + read.associateBy { p -> p.id } }
  }

  /** Buys a product for the account, answering a member's request when there is one. */
  suspend fun buy(id: String, accountToken: String, requestId: String? = null): PurchaseOutcome {
    _buying.value = id
    try {
      val bought = store.purchase(id, accountToken)
      val purchase = bought.purchase ?: return if (bought.pending) PurchaseOutcome.Pending else PurchaseOutcome.Cancelled
      return deliver(purchase, requestId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return PurchaseOutcome.Failed(e.message ?: "Google Play couldn't sell it.")
    } finally {
      _buying.value = null
    }
  }

  /** Sends the purchases not finished yet: once signed in, at each launch and sign-in. */
  suspend fun sendUnfinished() {
    for (p in store.unfinished()) deliver(p, null)
  }

  /** Brings back this Google account's purchases not finished yet, and sends each to KIFF: the worst outcome is said. */
  suspend fun restore(): PurchaseOutcome {
    val list = try {
      store.unfinished()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return PurchaseOutcome.Failed(e.message ?: "Google Play couldn't be read.")
    }
    var outcome: PurchaseOutcome = PurchaseOutcome.Done
    for (p in list) {
      when (val o = deliver(p, null)) {
        is PurchaseOutcome.NeedsSupport -> outcome = o
        is PurchaseOutcome.NotRecorded -> if (outcome !is PurchaseOutcome.NeedsSupport) outcome = o
        is PurchaseOutcome.NotAdded -> if (outcome == PurchaseOutcome.Done) outcome = o
        else -> Unit
      }
    }
    return outcome
  }

  /** Sends a purchase to KIFF and finishes it once KIFF recorded it. */
  suspend fun deliver(p: StorePurchase, requestId: String?): PurchaseOutcome {
    val record = record ?: return PurchaseOutcome.NotRecorded("Not signed in.")
    try {
      record(p.productId, p.token, requestId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: KiffError.Server) {
      val code = e.message
      return when {
        // A test purchase (license testers) on an account that takes only real ones: KIFF adds nothing, now or on a retry,
        // so it's finished.
        e.status == 403 && code == "test_purchase" -> { p.finish(); PurchaseOutcome.NotAdded(TEST_PURCHASE) }
        // Another tafs account's purchase on this Google account: that account gets it from Play's own notice.
        e.status == 403 && code == "not_this_account" -> { p.finish(); PurchaseOutcome.NotAdded(ANOTHER_ACCOUNT) }
        // Paid for, but KIFF can't add it, and a retry won't change that: kept for support.
        (e.status == 409 && code == "billed_elsewhere") || (e.status == 422 && code == "unknown_product") -> PurchaseOutcome.NeedsSupport(CONTACT_SUPPORT)
        else -> PurchaseOutcome.NotRecorded(e.message ?: "KIFF answered ${e.status}")
      }
    } catch (e: Exception) {
      return PurchaseOutcome.NotRecorded(e.message ?: e.javaClass.simpleName)
    }
    p.finish()
    recorded?.invoke()
    return PurchaseOutcome.Done
  }

  companion object {
    const val TEST_PURCHASE = "Test purchases don't add tasks to this account."
    const val ANOTHER_ACCOUNT = "This purchase belongs to another tafs account on this Google account, so nothing was added here."
    const val CONTACT_SUPPORT = "KIFF can't add this purchase to this account. You paid for it, so contact support at support@kiff.dev and we'll sort it out."
    /** Play's own page for this Google account's subscriptions. */
    const val MANAGE_SUBSCRIPTIONS = "https://play.google.com/store/account/subscriptions"
  }
}

/** No Google Play: the sample, tests that don't buy, and a build with Play Billing off. */
object NoPlayStore : PlayStore {
  override suspend fun products(ids: List<String>): List<StoreProduct> = emptyList()
  override suspend fun purchase(id: String, accountToken: String): PlayStore.Bought = PlayStore.Bought(null)
  override val updates: Flow<StorePurchase> = kotlinx.coroutines.flow.emptyFlow()
  override suspend fun unfinished(): List<StorePurchase> = emptyList()
}
