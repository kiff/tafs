package bot.tafs.app

import bot.tafs.app.app.AppState
import bot.tafs.app.kiff.ConnectApps
import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.Taf
import bot.tafs.app.views.ConnectAppsWords
import bot.tafs.app.views.byTaf
import bot.tafs.app.views.connectionsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Connect apps by taf (the owner's ask, the same on iPhone): an app's connections in one section per taf, those needing the person
 * first, then by name, and those whose taf the app can't name last, read only. Nothing here says a taf was removed: that's a fact
 * only the server can give.
 */
class ConnectAppsByTafTest {
  private val stripeEntry = "connector:composio:stripe"
  private val stripe = ConnectApps.App(entry = stripeEntry, vendor = "Stripe", title = "Stripe")

  private fun g(id: String, agent: String, status: String = "connected", account: String? = null) =
    KiffClient.json.decodeFromString<Grant>(
      """{"id":"$id","status":"$status","entry":"$stripeEntry"${account?.let { ",\"account\":\"$it\"" } ?: ""},
         "request":{"server":"https://mcp.stripe.com","agent":"$agent","tools":[]}}""",
    )

  private fun taf(id: String, name: String) =
    Taf(id = id, name = name, job = "custom", tools = emptyList(), colorIndex = 0, card = bot.tafs.app.model.Card(rules = emptyList(), issuedBy = "you"))

  private val tafs = listOf(taf("refunds-1", "Refunds"), taf("chargebacks-1", "Chargebacks"), taf("save-1", "Save Cancels"))

  @Test fun eachTafIsASectionTheOnesNeedingThePersonFirstThenByNameAndTheRestApart() {
    val state = AppState(
      tafs = tafs,
      grants = listOf(
        g("g1", "save-1"),
        g("g2", "gone-1"),
        g("g3", "refunds-1", account = "acct_1Rk…"),
        g("g4", "chargebacks-1"),
        // Approved, and its sign-in never came back: it needs the person.
        g("g5", "save-1", status = "approved"),
        g("g6", "setup-1"),
        g("g7", "refunds-1"),
      ),
    )
    val (sections, others) = byTaf(state, connectionsOf(state, stripe))
    assertEquals(listOf("Save Cancels", "Chargebacks", "Refunds"), sections.map { it.taf })
    assertEquals("a taf's connections stay together, in planque's order", listOf("g3", "g7"), sections.last().grants.map { it.id })
    // A removed taf's, a taf this person doesn't see, or an agent that isn't a taf: the app can't tell which, so they're just other.
    assertEquals(listOf("g2", "g6"), others.map { it.id })
  }

  @Test fun beforeTheTafsAreReadEveryConnectionIsOtherAndNoneIsCalledRemoved() {
    val state = AppState(grants = listOf(g("g1", "refunds-1"), g("g2", "save-1")))
    val (sections, others) = byTaf(state, connectionsOf(state, stripe))
    assertTrue(sections.isEmpty())
    assertEquals(listOf("g1", "g2"), others.map { it.id })
  }

  @Test fun aRowIsTheVendorsAccountElseTheAppsName() {
    assertEquals("acct_1Rk…", ConnectAppsWords.rowTitle("Stripe", g("g1", "refunds-1", account = "acct_1Rk…")))
    assertEquals("Stripe", ConnectAppsWords.rowTitle("Stripe", g("g1", "refunds-1")))
    assertEquals("Stripe", ConnectAppsWords.rowTitle("Stripe", g("g1", "refunds-1", account = " ")))
  }
}
