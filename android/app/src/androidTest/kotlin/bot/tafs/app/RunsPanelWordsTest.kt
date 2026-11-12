package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.Card
import bot.tafs.app.model.Taf
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.RunsPanel
import bot.tafs.app.views.TafActions
import java.time.Instant
import org.junit.Rule
import org.junit.Test

/** The Activity runs panel says the next run in tafs cloud's words, with its day, as Home and Setup do. */
class RunsPanelWordsTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val taf = Taf(
    id = "disputes-1", name = "Disputes", job = "Open disputes", tools = emptyList(), colorIndex = 2, card = Card(emptyList(), "You"),
    cloudId = "taf_1", nextRun = Instant.parse("2026-11-16T07:00:00Z"), nextRunWords = "Next run Mon 08:00",
  )

  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
  )

  @Test fun theNextRunIsTafsCloudsWordsWithItsDay() {
    rule.setShown { TafsTheme { RunsPanel(taf, inFlight = false, actions = actions) } }
    rule.onNodeWithText("Next run Mon 08:00").assertIsDisplayed()
  }
}
