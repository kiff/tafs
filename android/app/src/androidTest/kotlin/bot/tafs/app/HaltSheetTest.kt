package bot.tafs.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Halt
import bot.tafs.app.views.AccountActions
import bot.tafs.app.views.HaltSheet
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

/** The Halt screen's button says what's under way: Halting… while a halt goes, even once the screen shows it halted. */
class HaltSheetTest {
  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createStableComposeRule()

  @Test fun aHaltUnderWaySaysHaltingEvenOnceTheResumeButtonShows() {
    var halt by mutableStateOf(Halt(state = Halt.RUNNING))
    val finish = CompletableDeferred<Unit>()
    val actions = AccountActions(
      // As AppModel does: the screen reads halted before the halt has returned.
      halt = { halt = Halt(state = Halt.HALTED, runtime = true, kiff = true, planque = true); finish.await() },
      refreshHalt = {}, resumeHalt = { false }, refreshUsage = {}, refreshTafUsage = {},
    )
    rule.setShown {
      TafsTheme {
        CompositionLocalProvider(LocalBiometry provides "your fingerprint") {
          HaltSheet(null, AppState(mode = Mode.kiff, ready = true, halts = mapOf("" to halt)), actions, onClose = {})
        }
      }
    }
    rule.onNodeWithText("Halt all now").performClick()
    rule.waitForIdle()
    rule.onAllNodesWithText("Resuming…").assertCountEquals(0)
    rule.onAllNodesWithText("Halting…").assertCountEquals(1)
    finish.complete(Unit)
    rule.waitForIdle()
    rule.onAllNodesWithText("Resume with your fingerprint").assertCountEquals(1)
  }
}
