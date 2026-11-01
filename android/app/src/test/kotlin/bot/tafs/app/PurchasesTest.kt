package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.billing.PlayBillingStore
import bot.tafs.app.billing.PlayStore
import bot.tafs.app.billing.PurchaseOutcome
import bot.tafs.app.billing.Purchases
import bot.tafs.app.billing.StoreProduct
import bot.tafs.app.billing.StorePurchase
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffError
import bot.tafs.app.model.UsageWords
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google Play stands in as a fake: what it sells, what it reports, and which purchases were finished. */
class FakePlayStore : PlayStore {
  val finished = mutableListOf<String>()
  var bought: (String) -> PlayStore.Bought = { id -> PlayStore.Bought(made(id)) }
  var held = mutableListOf<StorePurchase>()
  val asked = mutableListOf<Pair<String, String>>()
  override val updates = MutableSharedFlow<StorePurchase>()

  fun made(id: String, token: String = "tok_$id") = StorePurchase(id, token) { finished += token }

  /** What Play lists, read by read: each read counts in [reads]. */
  var listed: (List<String>) -> List<StoreProduct> = { ids -> ids.map { StoreProduct(it, it, "€4.99", period = if (it.startsWith("solo")) "month" else null) } }
  var reads = 0

  override suspend fun products(ids: List<String>): List<StoreProduct> {
    reads += 1
    return listed(ids)
  }
  override suspend fun purchase(id: String, accountToken: String): PlayStore.Bought {
    asked += id to accountToken
    return bought(id)
  }
  override suspend fun unfinished(): List<StorePurchase> = held.toList()
}

class PurchasesTest {
  private val play = FakePlayStore()
  private val purchases = Purchases(play)
  private val sent = mutableListOf<Triple<String, String, String?>>()

  private fun recording(fail: Exception? = null) {
    purchases.record = { product, token, request ->
      sent += Triple(product, token, request)
      fail?.let { throw it }
    }
  }

  // MARK: Purchases

  @Test fun playsOffersAreReadAgainEachTimeSoAUsedTrialIsntOffered() = runTest {
    play.listed = { ids -> ids.map { StoreProduct(it, "Solo", "€10.99", period = "month", freeTrial = "1 week") } }
    purchases.load(listOf("solo_monthly"))
    assertEquals("1 week", purchases.products.value["solo_monthly"]?.freeTrial)
    // The trial used, or the Play Store switched to an account that had it: Play no longer lists it.
    play.listed = { ids -> ids.map { StoreProduct(it, "Solo", "€10.99", period = "month") } }
    purchases.load(listOf("solo_monthly"))
    assertEquals(2, play.reads)
    assertNull(purchases.products.value["solo_monthly"]?.freeTrial)
    assertTrue(UsageWords.soloTerms("€10.99", "month", purchases.products.value["solo_monthly"]?.freeTrial).startsWith("€10.99 a month. "))
  }

  @Test fun aProductPlayNoLongerListsIsntOfferedAndAFailedReadKeepsTheLast() = runTest {
    purchases.load(listOf("pack_rayo", "pack_pico"))
    play.listed = { ids -> ids.filter { it != "pack_pico" }.map { StoreProduct(it, it, "€9.99") } }
    purchases.load(listOf("pack_rayo", "pack_pico"))
    assertEquals(setOf("pack_rayo"), purchases.products.value.keys)
    play.listed = { throw IllegalStateException("Play is unreachable") }
    purchases.load(listOf("pack_rayo"))
    assertEquals(setOf("pack_rayo"), purchases.products.value.keys)
  }

  @Test fun aPurchaseIsFinishedOnlyOnceKiffRecordedIt() = runTest {
    recording()
    var told = 0
    purchases.recorded = { told += 1 }
    assertEquals(PurchaseOutcome.Done, purchases.buy("pack_rayo", "acct_1", requestId = "req_9"))
    assertEquals(listOf("pack_rayo" to "acct_1"), play.asked)
    assertEquals(listOf(Triple("pack_rayo", "tok_pack_rayo", "req_9")), sent)
    assertEquals(listOf("tok_pack_rayo"), play.finished)
    assertEquals(1, told)
  }

  @Test fun notRecordedIsKeptToSendAgain() = runTest {
    recording(fail = java.io.IOException("offline"))
    val o = purchases.buy("pack_rayo", "acct_1")
    assertTrue(o is PurchaseOutcome.NotRecorded)
    assertTrue(play.finished.isEmpty())
  }

  @Test fun aTestPurchaseOrAnotherAccountsIsFinishedWithNothingAdded() = runTest {
    recording(fail = KiffError.Server(403, "test_purchase"))
    assertEquals(PurchaseOutcome.NotAdded(Purchases.TEST_PURCHASE), purchases.buy("pack_rayo", "acct_1"))
    recording(fail = KiffError.Server(403, "not_this_account"))
    assertEquals(PurchaseOutcome.NotAdded(Purchases.ANOTHER_ACCOUNT), purchases.buy("pack_vonk", "acct_1"))
    assertEquals(listOf("tok_pack_rayo", "tok_pack_vonk"), play.finished)
  }

  @Test fun onePaidForThatKiffCantAddIsKeptForSupport() = runTest {
    recording(fail = KiffError.Server(409, "billed_elsewhere"))
    assertEquals(PurchaseOutcome.NeedsSupport(Purchases.CONTACT_SUPPORT), purchases.buy("solo_monthly", "acct_1"))
    recording(fail = KiffError.Server(422, "unknown_product"))
    assertEquals(PurchaseOutcome.NeedsSupport(Purchases.CONTACT_SUPPORT), purchases.buy("pack_x", "acct_1"))
    assertTrue("never finished: the person paid", play.finished.isEmpty())
  }

  @Test fun cancelledPendingAndFailedSendNothing() = runTest {
    recording()
    play.bought = { PlayStore.Bought(null) }
    assertEquals(PurchaseOutcome.Cancelled, purchases.buy("pack_rayo", "acct_1"))
    play.bought = { PlayStore.Bought(null, pending = true) }
    assertEquals(PurchaseOutcome.Pending, purchases.buy("pack_rayo", "acct_1"))
    play.bought = { throw IllegalStateException("Google Play isn't available on this phone.") }
    assertEquals(PurchaseOutcome.Failed("Google Play isn't available on this phone."), purchases.buy("pack_rayo", "acct_1"))
    assertTrue(sent.isEmpty())
    assertNull(purchases.buying.value)
  }

  @Test fun restoreSendsEachAndSaysTheWorst() = runTest {
    play.held = mutableListOf(play.made("pack_a"), play.made("pack_b"))
    purchases.record = { product, _, _ -> if (product == "pack_b") throw KiffError.Server(409, "billed_elsewhere") }
    assertEquals(PurchaseOutcome.NeedsSupport(Purchases.CONTACT_SUPPORT), purchases.restore())
    assertEquals(listOf("tok_pack_a"), play.finished)
  }

  @Test fun notSignedInRecordsNothing() = runTest {
    assertTrue(purchases.buy("pack_rayo", "acct_1") is PurchaseOutcome.NotRecorded)
    assertTrue(play.finished.isEmpty())
  }

  @Test fun playsPeriodsReadAsWords() {
    assertEquals("month", PlayBillingStore.periodWords("P1M"))
    assertEquals("year", PlayBillingStore.periodWords("P1Y"))
    assertEquals("1 week", PlayBillingStore.lengthWords("P1W"))
    assertEquals("14 days", PlayBillingStore.lengthWords("P14D"))
    assertEquals(
      "Free for 1 week, then €4.99 a month. Renews every month until cancelled. Cancel any time in Google Play's subscriptions, at least a day before it renews.",
      UsageWords.soloTerms("€4.99", "month", "1 week"),
    )
  }

  // MARK: In the app

  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)

  private fun usage(google: Boolean, canAdd: Boolean = true) = """{"plan":"trial","period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},
    "can_add_tasks":$canAdd,"billing":{"source":""},
    "store":{"app_account_token":"acct_1","solo_product":"bot.tafs.solo"${if (google) ""","google_solo_product":"solo_monthly"""" else ""},
      "packs":[{"id":"rayo","name":"Rayo","line":"Rayo · 70 tasks","tasks":70,"months":12,"apple_product":"bot.tafs.rayo"${if (google) ""","google_product":"pack_rayo"""" else ""}}]}}"""

  private suspend fun model(playBilling: Boolean, google: Boolean = true, canAdd: Boolean = true, records: Int = 200): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/usage", 200, usage(google, canAdd))
    transport.answer("GET /v1/me/usage/tasks", 200, """{"items":[]}""")
    transport.answer("POST /v1/me/billing/google/purchases", records, if (records == 200) "{}" else """{"error":"could not record the purchase"}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    val m = AppModel(
      null, signer, KiffClient(transport), bot.tafs.app.kiff.TafsCloud(transport), secrets,
      clock = { Instant.parse("2026-10-28T09:00:00Z") }, sampleReplyDelayMs = 0, purchases = purchases, playBilling = playBilling,
    )
    m.restore()
    m.refreshUsage()
    return m
  }

  @Test fun nothingIsOfferedWithPlayBillingOff() = runTest {
    val m = model(playBilling = false)
    assertFalse(m.state.value.offersPurchases)
    m.buy("pack_rayo")
    assertTrue(play.asked.isEmpty())
  }

  @Test fun nothingIsOfferedUntilKiffNamesPlaysProducts() = runTest {
    assertFalse(model(playBilling = true, google = false).state.value.offersPurchases)
  }

  @Test fun nothingIsOfferedToSomeoneWhoCantAddTasks() = runTest {
    assertFalse(model(playBilling = true, canAdd = false).state.value.offersPurchases)
  }

  @Test fun buyingCarriesTheAccountsTokenAndKiffRecordsItBeforePlayIsTold() = runTest {
    val m = model(playBilling = true)
    assertTrue(m.state.value.offersPurchases)
    assertEquals("€4.99", m.state.value.storeProducts["pack_rayo"]?.price)
    m.buy("pack_rayo")
    assertEquals(listOf("pack_rayo" to "acct_1"), play.asked)
    val body = String(transport.requestsTo("POST /v1/me/billing/google/purchases").single().body!!)
    assertEquals("""{"product":"pack_rayo","purchase_token":"tok_pack_rayo"}""", body)
    assertEquals(listOf("tok_pack_rayo"), play.finished)
    assertNull(m.state.value.tasksMessage)
  }

  @Test fun aPurchaseKiffHasntRecordedSaysSoUnderWhatWasTappedWithRestoreRightThere() = runTest {
    val m = model(playBilling = true, records = 500)
    val before = play.reads
    m.buy("pack_rayo")
    val note = m.state.value.purchaseNote!!
    assertEquals("pack_rayo", note.at)
    assertEquals("Bought. KIFF hasn't added it yet: tafs tries again when it opens.", note.words)
    assertTrue(note.offersRestore)
    // Not at the panel's end, and not finished with Play.
    assertNull(m.state.value.tasksMessage)
    assertTrue(play.finished.isEmpty())
    assertTrue("Play's offers are read again after a purchase", play.reads > before)
  }

  @Test fun aRestoreSaysHowItWentUnderRestore() = runTest {
    val m = model(playBilling = true)
    m.restorePurchases()
    assertEquals(bot.tafs.app.app.PurchaseNote(bot.tafs.app.app.PurchaseNote.RESTORE, "Purchases restored."), m.state.value.purchaseNote)
    assertNull(m.state.value.buying)
  }

  @Test fun whatWasntFinishedIsSentAtSignIn() = runTest {
    play.held = mutableListOf(play.made("pack_rayo", token = "tok_left"))
    model(playBilling = true)
    assertEquals(1, transport.requestsTo("POST /v1/me/billing/google/purchases").size)
    assertEquals(listOf("tok_left"), play.finished)
  }

  @Test fun soloIsOfferedOnlyOnTheTrialWithNoPlanBilled() = runTest {
    val m = model(playBilling = true)
    val u = m.state.value.usage!!
    assertTrue(UsageWords.offersSolo(u))
    assertFalse(UsageWords.offersSolo(u.copy(plan = "solo", billing = bot.tafs.app.kiff.UsageBilling("google"))))
  }
}
