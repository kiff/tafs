package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KnobWire
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.model.Card
import bot.tafs.app.model.KnobWords
import bot.tafs.app.model.Taf
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.CoachCard
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Coach's dial card (K4): drawn from the taf's knob draft, confirmed with one switch, Change back to Coach, replaced by a newer draft. */
class KnobDraftCardTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val taf = Taf(id = "refunds-1", name = "Refunds", job = "", tools = emptyList(), colorIndex = 1, card = Card(rules = emptyList(), issuedBy = "You"), now = "", cloudId = "taf_1")
  private val card = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"knob","id":"k1","taf_id":"taf_1","version":9}""")
  private val log = mutableListOf<String>()

  private fun show(draftVersion: Int?, live: Int = 8) = rule.setShown {
    val draft = draftVersion?.let {
      """{"version":$it,"wakes":["An email to refunds@yourshop.com, or a message in #refunds"],"when":["As they arrive, and weekdays at 09:00"],"results":["A reply to the sender"]}"""
    } ?: "null"
    val knob = KiffClient.json.decodeFromString<KnobWire>("""{"version":$live,"does":{"label":"Refund","line":""},"rim":{},"draft":$draft}""")
    TafsTheme {
      CoachCard(
        ShownComponent(Instant.parse("2026-11-03T01:00:00Z"), "s1", "r1", card),
        AppState(mode = Mode.kiff, ready = true, tafs = listOf(taf), knobs = mapOf("refunds-1" to knob)),
        CoachActions(
          answer = { type, id, a -> log += "$type $id $a" }, approve = { _, _, _ -> }, cancelSignIn = {},
          confirmKnob = { log += "live $it"; true },
        ),
      )
    }
  }

  @Test fun theDialIsShownByRingAndOneConfirmationMakesItLive() {
    show(draftVersion = 9)
    rule.onNodeWithText(KnobWords.draftTitle("Refunds"), ignoreCase = true).assertIsDisplayed()
    rule.onNodeWithText("An email to refunds@yourshop.com, or a message in #refunds").assertIsDisplayed()
    rule.onNodeWithText("A reply to the sender").assertIsDisplayed()
    rule.onNodeWithText("Confirm with", substring = true).performClick()
    rule.waitForIdle()
    assertEquals(listOf("live refunds-1", "knob k1 approved"), log)
  }

  @Test fun changeGoesBackToCoach() {
    show(draftVersion = 9)
    rule.onNodeWithText(KnobWords.CHANGE).performClick()
    rule.waitForIdle()
    assertEquals(listOf("knob k1 change"), log)
  }

  @Test fun aNewerDraftReplacesTheCard() {
    show(draftVersion = 10)
    rule.onNodeWithText(KnobWords.REPLACED).assertIsDisplayed()
  }

  @Test fun onceLiveTheCardSaysSo() {
    show(draftVersion = null, live = 9)
    rule.onNodeWithText(KnobWords.LIVE).assertIsDisplayed()
  }
}
