package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.Card
import bot.tafs.app.model.Taf
import bot.tafs.app.model.shortTime
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafActivityList
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A signed-in taf's Activity says when it runs next, with Run now and Pause; a taf that works from its chat says so instead. */
class ActivityRunsTest {
  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val next = Instant.parse("2026-11-06T08:00:00Z")
  private val scheduled = Taf(id = "invoices-1", name = "Invoice reminders", job = "", tools = emptyList(), colorIndex = 1,
    card = Card(rules = emptyList(), issuedBy = "You"), now = "", cloudId = "taf_1", nextRun = next)
  private val ran = mutableListOf<String>()
  private val paused = mutableListOf<Pair<String, Boolean>>()

  private fun show(taf: Taf) {
    val actions = TafActions(
      answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
      sendChat = { _, _, _, _ -> }, issuer = "You",
      doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
      coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
      connections = ConnectionActions({}, { false }),
      runNow = { ran += it },
      setPaused = { id, on -> paused += id to on },
    )
    rule.setShown {
      TafsTheme { TafActivityList(taf, AppState(mode = Mode.kiff, ready = true, tafs = listOf(taf)), actions, selection = "", clearSelection = {}) }
    }
  }

  @Test fun aScheduledTafsActivityHasItsNextRunWithRunNowAndPause() {
    show(scheduled)
    rule.onNodeWithText("Next run at ${shortTime(next)}").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Run now").performScrollTo().performClick()
    rule.onNodeWithText("Pause").performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(listOf("invoices-1"), ran)
    assertEquals(listOf("invoices-1" to true), paused)
  }

  @Test fun aTafThatWorksFromItsChatSaysSoWithNoRunNow() {
    show(scheduled.copy(worksFromChat = true, nextRun = null))
    rule.onNodeWithText("It works when you tell it, in its chat: no schedule runs it.").performScrollTo().assertIsDisplayed()
    assertEquals(0, rule.onAllNodesWithText("Run now").fetchSemanticsNodes().size)
  }
}

