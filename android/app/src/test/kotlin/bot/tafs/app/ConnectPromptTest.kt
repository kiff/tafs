package bot.tafs.app

import bot.tafs.app.kiff.ConnectApps
import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.GrantRequest
import bot.tafs.app.model.ConnectionWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The prompt read last before a connection is signed names the app and the taf as the person knows them: tafs cloud's Connect apps
 * title, else planque's vendor name; the taf's name, else the name planque keeps. Never an agent id or a server's slug.
 */
class ConnectPromptTest {
  private val grant = Grant(
    id = "grt_1", request = GrantRequest(server = "https://mcp.stripe.com", agent = "litiges-lundi-fh7ehd7r"),
    vendorName = "stripe-mcp", entry = "mcp:stripe",
  )
  private val apps = ConnectApps(apps = listOf(ConnectApps.App(entry = "mcp:stripe", title = "Stripe", grant = "grt_1")))

  @Test fun itNamesTheAppAndTheTafNeverTheirIds() {
    val prompt = ConnectionWords.connectPrompt(grant, apps) { if (it == "litiges-lundi-fh7ehd7r") "Open disputes" else null }
    assertEquals("Connect Stripe to Open disputes", prompt)
    assertFalse(prompt.contains("fh7ehd7r"))
    assertFalse(prompt.contains("stripe-mcp"))
    assertEquals("Give Stripe its key for Open disputes", ConnectionWords.giveKeyPrompt(grant, apps) { "Open disputes" })
  }

  @Test fun aTafNotListedYetTakesTheNamePlanqueKeeps() {
    assertEquals("Connect Stripe to Open disputes", ConnectionWords.connectPrompt(grant.copy(agentName = "Open disputes"), apps) { null })
  }

  @Test fun withNoNameForTheTafThePromptLeavesItOutRatherThanShowAnId() {
    val prompt = ConnectionWords.connectPrompt(grant, apps) { null }
    assertEquals("Connect Stripe", prompt)
  }

  @Test fun anAppNotInTheCatalogReadsAsPlanqueNamesIt() {
    assertEquals("Connect stripe-mcp to Open disputes", ConnectionWords.connectPrompt(grant, null) { "Open disputes" })
  }
}
