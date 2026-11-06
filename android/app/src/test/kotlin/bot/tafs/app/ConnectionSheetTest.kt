package bot.tafs.app

import bot.tafs.app.kiff.ActivityCalls
import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.model.ConnectionWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A connection's sheet in Connect apps (gap 13): how it's fixed, and its last failed call. */
class ConnectionSheetTest {
  private fun grant(id: String, agent: String, kind: String? = null, tools: String = """[{"tool":"list_inboxes","name":"list_inboxes_k3f9"}]""") =
    KiffClient.json.decodeFromString<Grant>(
      """{"id":"$id","status":"connected","request":{"server":"https://mcp.example.com","agent":"$agent","tools":$tools${kind?.let { ",\"kind\":\"$it\"" } ?: ""}}}""",
    )

  private fun call(id: String, actor: String, action: String, at: String, status: String, message: String = "") =
    """{"proposal_id":"$id","action_name":"$action","title":"List inboxes","actor_id":"$actor","outcome":"allowed","held":false,"created_at":"$at",
        "tool_outcome":{"status":"$status","message":"$message"}}"""

  /**
   * Connect apps lists every connection to an app (Android 36's phone round): Stripe's row showed only the provider's
   * leftover, never the vendor's own sign-ins Coach started, which carry no catalog entry. Values as planque's records have them.
   */
  @Test fun everyConnectionToAnAppIsListedUnderItWhateverStartedIt() {
    fun g(id: String, request: String, entry: String? = null, status: String = "connected") =
      KiffClient.json.decodeFromString<Grant>("""{"id":"$id","status":"$status"${entry?.let { ",\"entry\":\"$it\"" } ?: ""},"request":$request}""")
    val vendorsOwn = g("g1", """{"server":"https://mcp.stripe.com","agent":"refunds-1","tools":[]}""")
    val provider = g("g2", """{"server":"https://backend.composio.dev/api/v3.1","agent":"refunds-0","connector":{"provider":"composio","toolkit":"stripe"},"tools":[]}""", entry = "connector:composio:stripe")
    val providerNoEntry = g("g3", """{"server":"https://backend.composio.dev/api/v3.1","agent":"chase-1","connector":{"provider":"composio","toolkit":"stripe"},"tools":[]}""")
    val notStripe = g("g4", """{"server":"https://notstripe.com/mcp","agent":"x-1","tools":[]}""")
    val otherProviderApp = g("g5", """{"server":"https://backend.composio.dev/api/v3.1","agent":"x-2","connector":{"provider":"composio","toolkit":"gmail"},"tools":[]}""")
    val gone = g("g6", """{"server":"https://mcp.stripe.com","agent":"refunds-9","tools":[]}""", status = "disconnected")
    val waiting = g("g7", """{"server":"mcp.stripe.com:443","agent":"refunds-2","tools":[]}""", status = "approved")
    val state = bot.tafs.app.app.AppState(grants = listOf(vendorsOwn, provider, providerNoEntry, notStripe, otherProviderApp, gone, waiting))
    val stripe = bot.tafs.app.kiff.ConnectApps.App(entry = "connector:composio:stripe", vendor = "Stripe", title = "Stripe")
    assertEquals(listOf("g1", "g2", "g3", "g7"), bot.tafs.app.views.connectionsOf(state, stripe).map { it.id })
    // A reviewed entry is the same app by its slug before "@"; g2, which planque named as the provider's app, stays that app's.
    assertEquals(listOf("g1", "g3", "g7"), bot.tafs.app.views.connectionsOf(state, stripe.copy(entry = "stripe@1")).map { it.id })
    // The provider's host is never an app's: Composio's own row lists none of them.
    val composio = bot.tafs.app.kiff.ConnectApps.App(entry = "composio@1", vendor = "Composio", title = "Composio")
    assertEquals(emptyList<String>(), bot.tafs.app.views.connectionsOf(state, composio).map { it.id })
    assertEquals("stripe", bot.tafs.app.views.AppMatch.slug(stripe))
    assertEquals("1password", bot.tafs.app.views.AppMatch.slug(stripe.copy(entry = "connector:composio:_1password")))
    // planque's own entry wins: a grant it named lists under that app alone, never another by its host.
    val named = g("g8", """{"server":"https://mcp.stripe.com","agent":"refunds-3","tools":[]}""", entry = "stripe@1")
    val withNamed = bot.tafs.app.app.AppState(grants = listOf(named))
    assertEquals(emptyList<String>(), bot.tafs.app.views.connectionsOf(withNamed, stripe).map { it.id })
    assertEquals(listOf("g8"), bot.tafs.app.views.connectionsOf(withNamed, stripe.copy(entry = "stripe@1")).map { it.id })
  }

  @Test fun aKeyConnectionIsFixedWithANewKeyAndASignInOneBySigningInAgain() {
    assertTrue(grant("g1", "chase-1", kind = "api_key").takesKey)
    assertFalse(grant("g2", "chase-1").takesKey)
  }

  @Test fun itsLastFailedCallIsTheNewestThroughItsToolsItsTafsOwnFirst() {
    val calls = KiffClient.json.decodeFromString<ActivityCalls>("""{"calls":[
      ${call("p1", "chase-1", "list_inboxes_k3f9", "2026-11-06T10:00:00Z", "refused_by_tool", "Agent Mail refused it (it answered 403): Forbidden.")},
      ${call("p2", "chase-1", "list_inboxes_k3f9", "2026-11-06T11:00:00Z", "done")},
      ${call("p3", "other-9", "list_inboxes_k3f9", "2026-11-06T12:00:00Z", "refused_by_tool", "Agent Mail refused it (it answered 401): Unauthorized.")},
      ${call("p4", "chase-1", "send_email_x1", "2026-11-06T13:00:00Z", "refused_by_tool", "Not this connection's tool.")}]}""").calls
    val record = KiffMapping.record(calls, setOf("chase-1", "other-9"))
    assertEquals("list_inboxes_k3f9", record.first { it.id.isNotEmpty() && it.at.toString().startsWith("2026-11-06T10") }.action)
    val line = ConnectionWords.lastFailure(grant("g1", "chase-1"), record)!!
    assertTrue(line, line.startsWith("Last failed: List inboxes · "))
    assertTrue("its own taf's, though another taf's is newer: $line", record.single { it.at.toString().startsWith("2026-11-06T10") }.detail in line)
    // Another taf's connection to the same tool reads that taf's.
    val other = ConnectionWords.lastFailure(grant("g9", "other-9"), record)!!
    assertTrue(other, record.single { it.at.toString().startsWith("2026-11-06T12") }.detail in other)
    // None through its tools: no line.
    assertNull(ConnectionWords.lastFailure(grant("g5", "chase-1", tools = """[{"tool":"get_contact"}]"""), record))
  }

  /** A sign-in that never came back (an approved grant, not signed in) is listed, and cancelled rather than disconnected. */
  @Test fun aSignInThatNeverCameBackIsListedAsWaitingAndCanBeCancelled() {
    val waiting = KiffClient.json.decodeFromString<Grant>("""{"id":"g7","status":"approved","vendor":"Google Calendar","request":{"server":"https://mcp.example.com","agent":"cal-1","tools":[]}}""")
    assertTrue(waiting.waitingSignIn)
    assertEquals("Waiting for the sign-in at Google Calendar", ConnectionWords.status(waiting))
    assertEquals("Cancel the sign-in to Google Calendar?", ConnectionWords.cancelTitle(waiting))
    assertEquals(listOf("g7"), bot.tafs.app.app.AppState(grants = listOf(waiting)).connections("cal-1").map { it.id })
    // Signed in already, or connected: not waiting.
    assertFalse(waiting.copy(signedIn = true).waitingSignIn)
    assertFalse(waiting.copy(status = "connected").waitingSignIn)
    assertFalse(waiting.marked(connected = true).waitingSignIn)
  }
}
