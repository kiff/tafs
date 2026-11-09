package bot.tafs.app

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.Card
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafState
import bot.tafs.app.views.HomeView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Signed in, a halted taf's chip in the list opens Halt for that taf. */
class HomeHaltedChipTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val halted = Taf(
    id = "refunds-1", name = "Refunds", job = "", tools = emptyList(), colorIndex = 1,
    card = Card(rules = emptyList(), issuedBy = "You"), state = TafState.halted, cloudId = "taf_1",
  )

  @Test fun aHaltedChipOnTheSignedInListOpensHaltForItsTaf() {
    val opened = mutableListOf<String>()
    rule.setShown {
      TafsTheme {
        HomeView(
          AppState(mode = Mode.kiff, ready = true, tafs = listOf(halted)),
          onNeedsYou = {}, onPlayNext = {}, onOpenTaf = { opened += "open $it" }, onHaltAll = {},
          onHalted = { opened += "halt $it" },
        )
      }
    }
    rule.onNodeWithText(TafState.halted.label).performClick()
    rule.waitForIdle()
    assertEquals(listOf("halt refunds-1"), opened)
  }
}
