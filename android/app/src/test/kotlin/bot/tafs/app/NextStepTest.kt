package bot.tafs.app

import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.GrantRequest
import bot.tafs.app.kiff.GrantTool
import bot.tafs.app.kiff.NextStep
import bot.tafs.app.kiff.TafActivityPage
import bot.tafs.app.model.NextStepWords
import bot.tafs.app.model.NextStepWords.Action
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A not-done call's next step on its Activity row (kiff/tafs#476, gap 10): tafs cloud says which; the app finds what it opens. */
class NextStepTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val tool = "list_inboxes_k3f9"
  private fun grant(id: String, status: String, agent: String = "chase-1", tools: List<String>? = null, requested: List<String> = emptyList()) =
    Grant(id = id, status = status, tools = tools, request = GrantRequest(agent = agent, tools = requested.map { GrantTool(tool = it) }))

  @Test fun aNotDoneRowReadsItsNextStepAsTafsCloudSendsIt() {
    val page = json.decodeFromString<TafActivityPage>("""{"entries":[
      {"id":"e1","at":"2026-11-06T10:00:00Z","kind":"not_done","title":"List inboxes","detail":"The app refused it: \"Agent Mail refused it (it answered 403): Forbidden.\"",
       "time":"10:00","call":{"proposal_id":"p1","action_name":"list_inboxes_k3f9","outcome":"allowed","created_at":"2026-11-06T10:00:00Z"},
       "next":{"kind":"reconnect","words":"Connect it again","tool":"$tool","taf":"Chase invoices"}},
      {"id":"e2","at":"2026-11-06T10:01:00Z","kind":"done","title":"List inboxes","detail":"Done, within its Card","time":"10:01","call":{"proposal_id":"p2","action_name":"list_inboxes_k3f9","outcome":"allowed","created_at":"2026-11-06T10:01:00Z"}}]}""")
    assertEquals(NextStep("reconnect", "Connect it again", tool, "Chase invoices"), page.entries[0].next)
    assertNull(page.entries[1].next)
  }

  @Test fun connectItAgainSignsInAgainWhereItsVendorSignedItOutElseOpensConnectApps() {
    val next = NextStep("reconnect", "Connect it again", tool)
    val out = grant("g_out", "needs_reconnect", tools = listOf(tool))
    assertEquals(Action.SignInAgain(out), NextStepWords.action(next, "chase-1", listOf(out), ""))
    // Still connected, its key refused: Connect apps, where it's connected again.
    assertEquals(Action.ConnectApps, NextStepWords.action(next, "chase-1", listOf(grant("g_on", "connected", tools = listOf(tool))), ""))
    // No connection has that tool: Connect apps too.
    assertEquals(Action.ConnectApps, NextStepWords.action(next, "chase-1", emptyList(), ""))
  }

  @Test fun theConnectionIsTheOneWhoseToolsIncludeTheCallsToolTheTafsOwnFirst() {
    val other = grant("g_other", "connected", agent = "refunds-1", tools = listOf(tool))
    val own = grant("g_own", "needs_reconnect", requested = listOf(tool))
    val gone = grant("g_gone", "disconnected", tools = listOf(tool))
    assertEquals("g_own", NextStepWords.connectionFor(tool, "chase-1", listOf(gone, other, own))?.id)
    assertEquals("g_other", NextStepWords.connectionFor(tool, "chase-1", listOf(gone, other))?.id)
    assertNull(NextStepWords.connectionFor(tool, "chase-1", listOf(gone)))
    assertNull(NextStepWords.connectionFor("", "chase-1", listOf(other)))
  }

  @Test fun askCoachWritesTafsCloudsMessageInElseTheRowsOwnWords() {
    val words = "Change Chase invoices: List inboxes failed. Agent Mail refused it (it answered 404): Not found."
    assertEquals(Action.AskCoach(words), NextStepWords.action(NextStep("ask_coach", "Ask Coach to change the taf", tool, message = words), "chase-1", emptyList(), "row"))
    assertEquals(Action.AskCoach("List inboxes: The app refused it"),
      NextStepWords.action(NextStep("ask_coach", "Ask Coach to change the taf", tool, message = " "), "chase-1", emptyList(), "List inboxes: The app refused it"))
  }

  @Test fun tryLaterAndAKindTheAppDoesNotKnowAreALine() {
    assertEquals(Action.Line, NextStepWords.action(NextStep("try_later", "Try again later: the app was busy or down", tool), "chase-1", emptyList(), ""))
    assertEquals(Action.Line, NextStepWords.action(NextStep("call_support", "Call the vendor", tool), "chase-1", emptyList(), ""))
  }
}
