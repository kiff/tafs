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
   * Connect apps lists every connection to an app (Android 36's phone round), by the app planque names for it: its `entry`,
   * which planque gives every grant now (kiff/planque#115, #116), a vendor's own sign-in included. Values as planque lists them.
   */
  @Test fun everyConnectionToAnAppIsListedUnderItWhateverStartedIt() {
    fun g(id: String, request: String, entry: String? = null, status: String = "connected") =
      KiffClient.json.decodeFromString<Grant>("""{"id":"$id","status":"$status"${entry?.let { ",\"entry\":\"$it\"" } ?: ""},"request":$request}""")
    val stripeEntry = "connector:composio:stripe"
    val vendorsOwn = g("g1", """{"server":"https://mcp.stripe.com","agent":"refunds-1","tools":[]}""", entry = stripeEntry)
    val provider = g("g2", """{"server":"https://backend.composio.dev/api/v3.1","agent":"refunds-0","connector":{"provider":"composio","toolkit":"stripe"},"tools":[]}""", entry = stripeEntry)
    val otherApp = g("g3", """{"server":"https://backend.composio.dev/api/v3.1","agent":"x-2","connector":{"provider":"composio","toolkit":"gmail"},"tools":[]}""", entry = "connector:composio:gmail")
    val gone = g("g4", """{"server":"https://mcp.stripe.com","agent":"refunds-9","tools":[]}""", entry = stripeEntry, status = "disconnected")
    val waiting = g("g5", """{"server":"https://mcp.stripe.com","agent":"refunds-2","tools":[]}""", entry = stripeEntry, status = "approved")
    // planque left it unnamed (its app unclear): the phone never guesses one from its server.
    val unnamed = g("g6", """{"server":"https://mcp.stripe.com","agent":"x-3","tools":[]}""")
    val state = bot.tafs.app.app.AppState(grants = listOf(vendorsOwn, provider, otherApp, gone, waiting, unnamed))
    val stripe = bot.tafs.app.kiff.ConnectApps.App(entry = stripeEntry, vendor = "Stripe", title = "Stripe")
    assertEquals(listOf("g1", "g2", "g5"), bot.tafs.app.views.connectionsOf(state, stripe).map { it.id })
    // The grant tafs cloud named for a row is listed under it too, whatever its entry.
    assertEquals(listOf("g1", "g2", "g5", "g6"), bot.tafs.app.views.connectionsOf(state, stripe.copy(grant = "g6")).map { it.id })
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
