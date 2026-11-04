package bot.tafs.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.app.SourcePages
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.TafActivityPage
import bot.tafs.app.model.Card
import bot.tafs.app.model.Taf
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafActivityList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * A taf's Activity asks for its next page whenever it's at the end with a cursor, even when it got there during a refresh, and
 * says there's nothing older, in tafs cloud's words, under the last page.
 */
class ActivityPagingTest {
  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val taf = Taf(id = "refunds-1", name = "Refunds", job = "", tools = emptyList(), colorIndex = 1, card = Card(rules = emptyList(), issuedBy = "You"), now = "", cloudId = "taf_1")
  // As tafs cloud sends a page: its entries, and the same entries grouped by day.
  private val firstPage = KiffClient.json.decodeFromString<TafActivityPage>(
    """{"filters":[{"id":"all","title":"All"}],"next_cursor":"c2","groups":[{"id":"today","title":"Today","entries":[
        {"id":"e1","at":"2026-11-04T09:00:00Z","kind":"done","title":"List charges","detail":"Done, within its Card","time":"09:00",
         "call":{"proposal_id":"p1","action_name":"list_charges","outcome":"allowed","created_at":"2026-11-04T09:00:00Z"}}]}]}""",
  ).let { it.copy(entries = it.groups.flatMap { g -> g.entries }) }
  private val loads = mutableListOf<String>()
  private val lastPage = firstPage.copy(nextCursor = null, endWords = "Nothing older in this view",
    entries = firstPage.entries.map { it.copy(id = "e2") },
    groups = firstPage.groups.map { g -> g.copy(id = "monday", entries = g.entries.map { it.copy(id = "e2") }) })
  private var pages by mutableStateOf(SourcePages(listOf(firstPage)))

  // Like AppModel: marks the cache loading, waits on the network, then stores the page. A cancelled load leaves no page.
  private fun show() {
    val actions = TafActions(
      answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
      sendChat = { _, _, _, _ -> }, issuer = "You",
      doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
      coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
      connections = ConnectionActions({}, { false }),
      loadActivity = { _, filter, more, _ ->
        val before = pages
        // AppModel drops a request while a load runs, or for a next page when there is none.
        if (before.loading || (more && before.pages.last().nextCursor == null)) return@TafActions
        loads += "$filter ${if (more) "more" else "first"}"
        pages = before.copy(loading = true)
        try {
          delay(300)
          pages = if (more) before.copy(pages = before.pages + lastPage, loading = false) else before.copy(loading = false)
        } catch (e: CancellationException) { pages = before.copy(loading = false); throw e }
      },
    )
    rule.setShown {
      TafsTheme {
        TafActivityList(taf, AppState(mode = Mode.kiff, ready = true, tafs = listOf(taf), activityPages = mapOf("refunds-1:all:" to pages)),
          actions, selection = "", clearSelection = {})
      }
    }
  }

  @Test fun theEndReachedWhileTheFirstPageRefreshesLoadsTheNextOnceThenSaysThereIsNothingOlder() {
    // One short page, so the list is at its end at once while its first page refreshes.
    show()
    rule.waitUntil(5_000) { pages.pages.size == 2 }
    rule.onNodeWithText("Nothing older in this view").performScrollTo().assertIsDisplayed()
    // The next page was asked for once the refresh ended, and its own loading didn't cancel it.
    assertEquals(listOf("all first", "all more"), loads)
  }

  @Test fun aFailedPageWaitsForTryAgain() {
    pages = SourcePages(listOf(firstPage), error = "tafs cloud didn't answer.")
    show()
    rule.waitUntil(5_000) { loads.isNotEmpty() && !pages.loading }
    rule.waitForIdle()
    assertEquals("only the first page's refresh, no next page while it failed", listOf("all first"), loads.distinct())
  }
}
