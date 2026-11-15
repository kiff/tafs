package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
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
import bot.tafs.app.model.Money
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafCard
import bot.tafs.app.model.Words
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.LocalViewOnly
import bot.tafs.app.views.REVOKE_NEEDS_APPROVER
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafDetailView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * A view-only session (KIFF's `approval_mode: "view"`) isn't offered Revoke, as on every device: the Card tab says where
 * it's done instead. A session that approves keeps Revoke, for whoever manages the taf.
 */
class RevokeViewOnlyTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val revoked = mutableListOf<String>()

  private val taf = Taf(
    id = "refunds-1", name = "Refunds", job = "Refund late or damaged orders", tools = listOf("create_refund"), colorIndex = 1,
    card = Card(listOf(CardRule(action = "create_refund", label = "Refund a payment", perWindow = Money.bare(30000))), "You"),
    cloudId = "taf_1",
    kiffCards = listOf(TafCard("card_1", "Refunds", rules = listOf(CardRule(action = "create_refund", label = "Refund a payment", perWindow = Money.bare(30000))))),
  )

  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = { revoked += it }, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
  )

  private fun show(viewOnly: Boolean) = rule.setShown {
    val state = AppState(
      mode = Mode.kiff, ready = true, tafs = listOf(taf), kiffAccount = Account("t_01", "acme", capabilities = listOf("cards.manage")),
      access = mapOf("refunds-1" to Access(use = true, oversee = true, manage = true)), approvalViewOnly = viewOnly,
    )
    TafsTheme {
      CompositionLocalProvider(LocalBiometry provides "your fingerprint", LocalViewOnly provides viewOnly) {
        TafDetailView("refunds-1", state, actions, onBack = {}, onHalt = {}, onUsage = {}, onVersions = {})
      }
    }
  }

  @Test fun aViewOnlySessionIsToldWhereToRevokeAndOfferedNoRevoke() {
    show(viewOnly = true)
    rule.onNodeWithText("Card").performClick()
    rule.onNodeWithTag(REVOKE_NEEDS_APPROVER).performScrollTo().assertIsDisplayed()
    assertEquals("To revoke this Card, use a device that can approve.", Words.Taf.revokeNeedsApprover)
    assertEquals(0, rule.onAllNodes(hasText(Words.Taf.revoke)).fetchSemanticsNodes().size)
    assertEquals(emptyList<String>(), revoked)
  }

  @Test fun aSessionThatApprovesKeepsRevoke() {
    show(viewOnly = false)
    rule.onNodeWithText("Card").performClick()
    rule.onNodeWithText(Words.Taf.revoke).performScrollTo().assertIsDisplayed()
    assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag(REVOKE_NEEDS_APPROVER)).fetchSemanticsNodes().size)
  }
}
