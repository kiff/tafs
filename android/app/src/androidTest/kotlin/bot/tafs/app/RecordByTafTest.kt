package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.Card
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.Taf
import bot.tafs.app.views.ALL_TAFS
import bot.tafs.app.views.RecordView
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The Record by taf: All tafs, then a chip per taf; each entry starts with its taf's name. */
class RecordByTafTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private fun taf(id: String, name: String, color: Int) =
    Taf(id = id, name = name, job = "", tools = emptyList(), colorIndex = color, card = Card(rules = emptyList(), issuedBy = "You"), now = "")

  private val at = Instant.parse("2026-11-03T09:00:00Z")
  private val record = listOf(
    RecordEntry(tafId = "refunds-1", kind = RecordEntry.Kind.done, title = "Refund", detail = "within its Card", at = at),
    RecordEntry(tafId = "chargebacks-1", kind = RecordEntry.Kind.done, title = "Answered a dispute", detail = "within its Card", at = at.plusSeconds(60)),
  )

  private fun count(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

  @Test fun oneTafsChipKeepsOnlyItsEntries() {
    rule.setShown {
      TafsTheme {
        RecordView(AppState(mode = Mode.sample, ready = true, tafs = listOf(taf("refunds-1", "Refunds", 1), taf("chargebacks-1", "Chargebacks", 2)), record = record))
      }
    }
    rule.onNodeWithText(ALL_TAFS).assertIsDisplayed()
    rule.onNodeWithText("Chargebacks · Answered a dispute").assertIsDisplayed()
    rule.onNodeWithText("Refunds").performClick()
    rule.waitForIdle()
    assertEquals("Refunds' entry, its taf first", 1, count("Refunds · Refund"))
    assertEquals("the other taf's entry is gone", 0, count("Answered a dispute"))
  }
}
