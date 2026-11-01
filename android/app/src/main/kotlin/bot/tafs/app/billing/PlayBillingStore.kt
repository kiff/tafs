package bot.tafs.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Google Play through its Billing Library. Solo is a subscription; packs are one-time products, consumed once KIFF has
 * recorded them so they can be bought again. KIFF acknowledges each purchase itself (KIFF's side: tracked privately). The purchase sheet
 * shows on the activity that's on screen, which attaches itself.
 */
class PlayBillingStore(context: Context) : PlayStore {
  private var activity = WeakReference<Activity>(null)
  private var waiting: CompletableDeferred<Pair<BillingResult, List<Purchase>?>>? = null
  private val details = mutableMapOf<String, ProductDetails>()
  private val reported = MutableSharedFlow<StorePurchase>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  private val listener = PurchasesUpdatedListener { result, purchases ->
    val asked = waiting
    if (asked != null && !asked.isCompleted) {
      asked.complete(result to purchases)
    } else {
      // Not from a purchase made here just now: one that cleared later, or made on another device.
      purchases.orEmpty().filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { reported.tryEmit(it.asStorePurchase()) }
    }
  }

  private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
    .setListener(listener)
    .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
    .build()

  fun attach(activity: Activity) {
    this.activity = WeakReference(activity)
  }

  override val updates: Flow<StorePurchase> = reported

  private suspend fun connected() {
    if (client.isReady) return
    val result = suspendCancellableCoroutine { cont ->
      client.startConnection(object : BillingClientStateListener {
        override fun onBillingSetupFinished(result: BillingResult) {
          if (cont.isActive) cont.resume(result)
        }

        override fun onBillingServiceDisconnected() = Unit
      })
    }
    if (result.responseCode != BillingClient.BillingResponseCode.OK) throw IllegalStateException(result.debugMessage.ifEmpty { "Google Play isn't available on this phone." })
  }

  override suspend fun products(ids: List<String>): List<StoreProduct> {
    connected()
    val found = listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP).flatMap { type ->
      val params = QueryProductDetailsParams.newBuilder()
        .setProductList(ids.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build() })
        .build()
      client.queryProductDetails(params).productDetailsList.orEmpty()
    }
    // What Play lists now, for this Google account: a product it no longer lists is forgotten, never sold from an older read.
    ids.forEach { details.remove(it) }
    found.forEach { details[it.productId] = it }
    return found.map { it.asStoreProduct() }
  }

  override suspend fun purchase(id: String, accountToken: String): PlayStore.Bought {
    connected()
    // Read again just before: the offer sent is one Play gives this Google account now (its trial only while it can have it).
    products(listOf(id))
    val product = details[id] ?: throw IllegalStateException("Google Play doesn't sell this here.")
    val on = activity.get() ?: throw IllegalStateException("Open tafs to buy.")
    val each = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
    product.offer()?.let { each.setOfferToken(it.offerToken) }
    val params = BillingFlowParams.newBuilder()
      .setProductDetailsParamsList(listOf(each.build()))
      .setObfuscatedAccountId(accountToken)
      .build()
    val answer = CompletableDeferred<Pair<BillingResult, List<Purchase>?>>()
    waiting = answer
    try {
      val launched = client.launchBillingFlow(on, params)
      if (launched.responseCode != BillingClient.BillingResponseCode.OK) throw IllegalStateException(launched.debugMessage.ifEmpty { "Google Play couldn't open the purchase." })
      val (result, purchases) = answer.await()
      return when (result.responseCode) {
        BillingClient.BillingResponseCode.OK -> {
          val p = purchases.orEmpty().firstOrNull { id in it.products }
          when (p?.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PlayStore.Bought(p.asStorePurchase())
            Purchase.PurchaseState.PENDING -> PlayStore.Bought(null, pending = true)
            else -> PlayStore.Bought(null)
          }
        }
        BillingClient.BillingResponseCode.USER_CANCELED -> PlayStore.Bought(null)
        // Bought already and not yet finished: it's among the unfinished, and is sent to KIFF from there.
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PlayStore.Bought(unfinished().firstOrNull { it.productId == id })
        else -> throw IllegalStateException(result.debugMessage.ifEmpty { "Google Play couldn't sell it (${result.responseCode})." })
      }
    } finally {
      waiting = null
    }
  }

  override suspend fun unfinished(): List<StorePurchase> {
    connected()
    val subs = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()).purchasesList
    val packs = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()).purchasesList
    // A pack still held is one KIFF hasn't recorded (it's consumed once it has); Solo, until KIFF has acknowledged it.
    return (subs.filter { !it.isAcknowledged } + packs)
      .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
      .map { it.asStorePurchase() }
  }

  private fun Purchase.asStorePurchase(): StorePurchase {
    val product = products.first()
    val subscription = details[product]?.productType == BillingClient.ProductType.SUBS
    return StorePurchase(product, purchaseToken) {
      if (!subscription) {
        connected()
        client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build())
      }
    }
  }

  private fun ProductDetails.asStoreProduct(): StoreProduct {
    oneTimePurchaseOfferDetails?.let { return StoreProduct(productId, name, it.formattedPrice) }
    val phases = offer()?.pricingPhases?.pricingPhaseList.orEmpty()
    val paid = phases.lastOrNull()
    val trial = phases.firstOrNull { it.priceAmountMicros == 0L }
    return StoreProduct(productId, name, paid?.formattedPrice ?: "", period = paid?.billingPeriod?.let(::periodWords), freeTrial = trial?.billingPeriod?.let(::lengthWords))
  }

  /**
   * The subscription offer shown and bought: the free trial when Play lists one for this account (it lists it only while the
   * account can have it), else the base plan. The same one for the words and the purchase, never just the first listed.
   */
  private fun ProductDetails.offer(): ProductDetails.SubscriptionOfferDetails? = subscriptionOfferDetails?.let { offers ->
    offers.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
      ?: offers.firstOrNull { it.offerId == null }
      ?: offers.firstOrNull()
  }

  companion object {
    /** "P1M" → "month", as a price is said per period. */
    fun periodWords(iso: String): String = when (iso) {
      "P1W" -> "week"
      "P1M" -> "month"
      "P3M" -> "3 months"
      "P6M" -> "6 months"
      "P1Y" -> "year"
      else -> lengthWords(iso)
    }

    /** "P1W" → "1 week", "P14D" → "14 days". */
    fun lengthWords(iso: String): String {
      val m = Regex("""P(\d+)([DWMY])""").matchEntire(iso) ?: return iso
      val n = m.groupValues[1].toInt()
      val unit = when (m.groupValues[2]) { "D" -> "day"; "W" -> "week"; "M" -> "month"; else -> "year" }
      return "$n $unit" + if (n == 1) "" else "s"
    }
  }
}
