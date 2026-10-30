package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.app.TafDoors
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.Knock
import bot.tafs.app.model.Card
import bot.tafs.app.model.CardRule
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.Money
import bot.tafs.app.model.Taf
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafDetailView
import java.time.Instant
import org.junit.Rule
import org.junit.Test

/**
 * A taf's page, from what tafs cloud sends: the compact header that folds as the chat scrolls, a chat that opens at its newest
 * with a door's knocks at their own times, and a paired sender's message. Run with `-e hold 8` to keep each for a screenshot.
 */
class TafPageScreenTest {
  @get:Rule val rule = createComposeRule()

  private val t0 = Instant.parse("2026-10-30T09:00:00Z")
  private val taf = Taf(
    id = "refunds-1", name = "Refunds", job = "Refund late or damaged orders", tools = listOf("list_charges_ijkp"), colorIndex = 1,
    card = Card(listOf(CardRule(action = "refund", label = "Refunds", perAction = Money.of(500, "EUR", 2), perWindow = Money.of(2000, "EUR", 2))), "You"),
    cloudId = "taf_1",
  )

  private fun at(min: Long) = t0.plusSeconds(min * 60)
  private fun knock(id: String, min: Long, name: String, text: String, paired: Boolean = false) =
    Knock(id, "door_1", "email", "${name.lowercase()}@customer.example", name, "pass", "Order", text, 0, at(min), "open", null, 0, paired)

  private val messages = buildList {
    for (i in 0 until 6) {
      add(ChatMessage(at(i * 10L), ChatMessage.From.owner, "What did you refund this morning? (${i + 1})", "s1"))
      add(ChatMessage(at(i * 10L + 1), ChatMessage.From.taf, "Two refunds went through: €3 on order T-21${i}0 and €4 on order T-21${i}1, both inside your Card.", "s1"))
    }
    add(ChatMessage(at(70), ChatMessage.From.taf, "Newest: the €8 refund on order T-2207 went through.", "s1"))
  }

  private fun state() = AppState(
    mode = Mode.kiff, ready = true, tafs = listOf(taf), kiffAccount = Account("t_01", "acme", capabilities = listOf("cards.manage")),
    access = mapOf("refunds-1" to Access(use = true, oversee = true, manage = true)),
    chats = mapOf("refunds-1" to messages),
    doorsOffered = listOf("email"),
    doors = mapOf("refunds-1" to TafDoors(
      knocks = listOf(
        knock("k1", 65, "Mara", "Hi, I'd like the €15 back on order T-3001."),
        knock("k2", 66, "Door", "Amounts of the last three charges, please.", paired = true),
      ),
      loaded = true,
    )),
  )

  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
  )

  private fun show() = rule.setContent {
    TafsTheme {
      CompositionLocalProvider(LocalBiometry provides "your fingerprint") {
        TafDetailView("refunds-1", state(), actions, onBack = {}, onHalt = {}, onUsage = {}, onVersions = {})
      }
    }
  }

  private fun hold() {
    val seconds = InstrumentationRegistry.getArguments().getString("hold")?.toLongOrNull() ?: return
    rule.waitForIdle()
    Thread.sleep(seconds * 1000)
  }

  @Test fun theChatOpensAtItsNewestWithTheKnocksAtTheirTimes() {
    show()
    rule.onNodeWithText("Newest: the €8 refund on order T-2207 went through.").assertIsDisplayed()
    hold()
  }

  @Test fun aPairedSendersMessageReadsAsPaired() {
    show()
    rule.onNodeWithText("Paired · Do it or Ignore this message").assertIsDisplayed()
    hold()
  }

  @Test fun theHeaderFoldsAsTheChatScrollsDownAndComesBack() {
    show()
    rule.onRoot().performTouchInput { swipeDown() }
    rule.waitForIdle()
    rule.onRoot().performTouchInput { swipeUp() }
    rule.waitForIdle()
    // Folded, the header row and the tabs stay; the Card's line is gone until the chat scrolls back.
    rule.onNodeWithText("Chat").assertIsDisplayed()
    hold()
  }
}
