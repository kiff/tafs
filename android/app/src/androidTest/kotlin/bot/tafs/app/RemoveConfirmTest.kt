package bot.tafs.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.Account
import bot.tafs.app.model.Card
import bot.tafs.app.model.Taf
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.REMOVE_CONFIRM_DELAY_MS
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafDetailView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Remove for good removes the taf its dialog named, and only after a confirm meant for it: a Home refresh that changes the list
 * closes the dialog, and a tap the moment it opens (aimed at what was there before) doesn't confirm.
 */
class RemoveConfirmTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private fun taf(id: String, name: String, cloudId: String) =
    Taf(id = id, name = name, job = "Refund late or damaged orders", tools = emptyList(), colorIndex = 1, card = Card(emptyList(), "You"), cloudId = cloudId)

  private fun state(vararg tafs: Taf) = AppState(
    mode = Mode.kiff, ready = true, tafs = tafs.toList(), kiffAccount = Account("t_01", "acme", capabilities = listOf("cards.manage")),
    access = tafs.associate { it.id to Access(use = true, oversee = true, manage = true) },
  )

  private val refunds3 = taf("refunds-3", "Refunds 3", "taf_3")
  private val refunds4 = taf("refunds-4", "Refunds 4", "taf_4")
  private val shown = mutableStateOf(state(refunds3, refunds4))
  private val removed = mutableListOf<String>()

  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
    remove = { removed += it; null },
  )

  /** Opens Setup and taps Remove for good; [beforeTap] runs just before the tap (the clock stopped, say). */
  private fun askToRemove(beforeTap: () -> Unit = {}) {
    rule.setShown {
      TafsTheme { TafDetailView("refunds-3", shown.value, actions, onBack = {}, onHalt = {}, onUsage = {}, onVersions = {}) }
    }
    rule.onNodeWithText("Setup").performClick()
    val button = rule.onNodeWithText("Remove for good").performScrollTo()
    beforeTap()
    button.performClick()
  }

  @Test fun theConfirmRemovesTheTafTheDialogNamed() {
    askToRemove()
    rule.onNodeWithText("Remove Refunds 3 for good?").assertIsDisplayed()
    rule.mainClock.advanceTimeBy(REMOVE_CONFIRM_DELAY_MS + 100)
    rule.onNodeWithText("Remove").performClick()
    rule.waitForIdle()
    assertEquals(listOf("refunds-3"), removed)
  }

  @Test fun aRefreshBetweenTheTapAndTheConfirmRemovesNothing() {
    askToRemove()
    rule.onNodeWithText("Remove Refunds 3 for good?").assertIsDisplayed()
    // Home is read again while the dialog is up, and the list changes under it.
    shown.value = state(refunds3, refunds4, taf("refunds-5", "Refunds 5", "taf_5"))
    rule.waitForIdle()
    assertEquals(0, rule.onAllNodesWithText("Remove Refunds 3 for good?").fetchSemanticsNodes().size)
    assertEquals(0, rule.onAllNodesWithText("Remove").fetchSemanticsNodes().size)
    assertTrue(removed.isEmpty())
  }

  @Test fun aTapTheMomentTheDialogOpensDoesntConfirm() {
    askToRemove { rule.mainClock.autoAdvance = false }
    rule.mainClock.advanceTimeByFrame()
    rule.onNodeWithText("Remove Refunds 3 for good?").assertIsDisplayed()
    rule.onNodeWithText("Remove").performClick()
    rule.mainClock.advanceTimeByFrame()
    assertTrue("a tap before the dialog has been up a moment confirms nothing", removed.isEmpty())
    rule.mainClock.advanceTimeBy(REMOVE_CONFIRM_DELAY_MS + 100)
    rule.onNodeWithText("Remove").performClick()
    rule.mainClock.advanceTimeByFrame()
    rule.mainClock.autoAdvance = true
    rule.waitForIdle()
    assertEquals(listOf("refunds-3"), removed)
  }
}
