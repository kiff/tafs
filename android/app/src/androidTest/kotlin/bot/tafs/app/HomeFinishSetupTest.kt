package bot.tafs.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.HomeFinish
import bot.tafs.app.model.Card
import bot.tafs.app.model.Taf
import bot.tafs.app.views.HomeView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Another agent whose setup stopped partway (docs/tafs.md): Home shows it by the name tafs cloud sends, with its words in place of
 * what it's doing, and a tap opens Coach's chat where it stopped. One with no finish opens as before.
 */
class HomeFinishSetupTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val refunds = Taf(id = "refunds-1", name = "Refunds", job = "", tools = emptyList(), colorIndex = 1, card = Card(emptyList(), "You"), cloudId = "taf_1")
  private val finish = HomeFinish(taf = "setup_1", session = "s7", words = "Finish setting up")
  private val halfMade = Taf(
    id = "invoicing-day-test-x", name = "Invoicing day test", job = "", tools = emptyList(), colorIndex = 2, card = Card(emptyList(), "You"),
    now = "Finish setting up", finish = finish,
  )
  private val coachUser = bot.tafs.app.kiff.Account("t_01", "acme", capabilities = listOf("members.manage"))
  private val other = Taf(id = "ops-bot", name = "ops-bot", job = "", tools = emptyList(), colorIndex = 3, card = Card(emptyList(), "You"))

  @Test fun aTafWhoseSetupStoppedOpensCoachWhereItStoppedAndAnotherAgentOpensAsBefore() {
    val opened = mutableListOf<String>()
    rule.setShown {
      TafsTheme {
        HomeView(
          AppState(
            mode = Mode.kiff, ready = true, listLoaded = true, kiffAccount = coachUser, coachCloudId = "setup_1",
            tafs = listOf(refunds), otherAgents = listOf(halfMade, other),
          ),
          onNeedsYou = {}, onPlayNext = {}, onOpenTaf = { opened += "open $it" }, onHaltAll = {},
          onFinishSetup = { opened += "finish ${it.session}" },
        )
      }
    }
    rule.onNodeWithText("Finish setting up").performScrollTo().assertExistsAndClick()
    rule.onNodeWithText("ops-bot").performScrollTo().assertExistsAndClick()
    rule.waitForIdle()
    assertEquals(listOf("finish s7", "open ops-bot"), opened)
  }

  private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsAndClick() {
    assertExists()
    performClick()
  }

  @Test fun someoneWhoCantUseCoachOrAnotherTafsChatOpensTheAgentAsBefore() {
    val opened = mutableListOf<String>()
    var state by androidx.compose.runtime.mutableStateOf(
      AppState(mode = Mode.kiff, ready = true, listLoaded = true, kiffAccount = bot.tafs.app.kiff.Account("t_01", "acme", capabilities = emptyList()),
        coachCloudId = "setup_1", tafs = listOf(refunds), otherAgents = listOf(halfMade)),
    )
    rule.setShown {
      TafsTheme {
        HomeView(state, onNeedsYou = {}, onPlayNext = {}, onOpenTaf = { opened += "open $it" }, onHaltAll = {}, onFinishSetup = { opened += "finish ${it.session}" })
      }
    }
    rule.onNodeWithText("Finish setting up").performScrollTo().assertExistsAndClick()
    // A finish naming another taf than Coach's own, for someone who may use Coach: as before too.
    state = state.copy(kiffAccount = coachUser, coachCloudId = "setup_2")
    rule.waitForIdle()
    rule.onNodeWithText("Finish setting up").performScrollTo().assertExistsAndClick()
    rule.waitForIdle()
    assertEquals(listOf("open invoicing-day-test-x", "open invoicing-day-test-x"), opened)
  }
}
