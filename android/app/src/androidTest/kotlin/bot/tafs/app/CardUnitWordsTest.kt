package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.Account
import bot.tafs.app.model.Card
import bot.tafs.app.model.CardRule
import bot.tafs.app.model.CardTotal
import bot.tafs.app.model.ConnectionWords
import bot.tafs.app.model.Money
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafCard
import bot.tafs.app.model.Window
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.LocalImproveInCoach
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafDetailView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * A Card total with no currency, on a taf's Card tab: KIFF's words for why under it, and Reconnect it in Coach for whoever
 * manages the taf, which opens Coach on it.
 */
class CardUnitWordsTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val words = "Its currency comes from Refund a payment, which isn't connected."
  private val opened = mutableListOf<String>()

  private val taf = Taf(
    id = "refunds-1", name = "Refunds", job = "Refund late or damaged orders", tools = listOf("create_refund"), colorIndex = 1,
    card = Card(listOf(CardRule(action = "create_refund", label = "Refund a payment", perWindow = Money.bare(30000))), "You"),
    cloudId = "taf_1",
    kiffCards = listOf(
      TafCard(
        "card_u", "Refunds", rules = listOf(CardRule(action = "create_refund", label = "Refund a payment", perWindow = Money.bare(30000))),
        total = CardTotal(Money.bare(30000), null, Window.day, unitWords = words),
      ),
    ),
  )

  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
  )

  private fun show(manages: Boolean) = rule.setShown {
    val state = AppState(
      mode = Mode.kiff, ready = true, tafs = listOf(taf), kiffAccount = Account("t_01", "acme", capabilities = listOf("cards.manage")),
      access = mapOf("refunds-1" to Access(use = true, oversee = true, manage = manages)),
    )
    TafsTheme {
      CompositionLocalProvider(LocalBiometry provides "your fingerprint", LocalImproveInCoach provides { id: String -> opened += id }) {
        TafDetailView("refunds-1", state, actions, onBack = {}, onHalt = {}, onUsage = {}, onVersions = {})
      }
    }
  }

  @Test fun kiffsWordsShowUnderTheTotalAndReconnectOpensCoachOnTheTaf() {
    show(manages = true)
    rule.onNodeWithText("Card").performClick()
    rule.onNodeWithText(words).performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(ConnectionWords.RECONNECT_IN_COACH).performScrollTo().assertIsDisplayed().performClick()
    rule.waitForIdle()
    assertEquals(listOf("refunds-1"), opened)
  }

  @Test fun someoneWhoDoesntManageTheTafSeesTheWordsAlone() {
    show(manages = false)
    rule.onNodeWithText("Card").performClick()
    rule.onNodeWithText(words).performScrollTo().assertIsDisplayed()
    assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText(ConnectionWords.RECONNECT_IN_COACH)).fetchSemanticsNodes().size)
  }
}
