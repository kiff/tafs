package bot.tafs.app

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.ReportResult
import bot.tafs.app.model.ReportWords
import bot.tafs.app.views.AnswerWithActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Under an answer (docs/chat.md): Copy, and More actions with Report this answer, a reason and a note, Send.
 * Run with `-e hold 8` to keep the sheet for a screenshot.
 */
class ReportAnswerTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val sent = mutableListOf<Pair<String, String?>>()

  private fun show(answer: ReportResult) = rule.setShown {
    TafsTheme {
      AnswerWithActions("Refunded Ana €40.", { reason, note -> sent += reason to note; answer }) { Text("Refunded Ana €40.") }
    }
  }

  // The sheet scrolls: on a small screen (CI's emulator is 320×640) each part is scrolled to, as a person would.
  private fun report(note: String? = null) {
    rule.onNodeWithContentDescription(bot.tafs.app.model.AnswerWords.MORE).performClick()
    rule.onNodeWithText(ReportWords.MENU).assertIsDisplayed().performClick()
    rule.onNodeWithText("Wrong").performScrollTo().assertIsDisplayed().performClick()
    note?.let { rule.onNodeWithText("Add a note (optional)").performScrollTo().performTextInput(it) }
    rule.onNodeWithText("Send").performScrollTo().performClick()
  }

  @Test fun anAnswerIsReportedFromItsLongPressAndTheSheetSaysThanks() {
    show(ReportResult.Sent(first = true))
    report("It refunded the wrong order.")
    // On a slow emulator the sheet settles after the keyboard goes: wait for the thanks, then scroll to it.
    rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasText(ReportWords.THANKS)).fetchSemanticsNodes().isNotEmpty() }
    rule.onNodeWithText(ReportWords.THANKS).performScrollTo().assertIsDisplayed()
    assertEquals(listOf("wrong" to "It refunded the wrong order."), sent)
    rule.onNodeWithText("Done").performClick()
  }

  @Test fun copyCopiesTheWholeAnswerAndSaysSo() {
    show(ReportResult.Sent(first = true))
    rule.onNodeWithText(bot.tafs.app.model.AnswerWords.COPY).assertIsDisplayed().performClick()
    rule.onNodeWithText(bot.tafs.app.model.AnswerWords.COPIED).assertIsDisplayed()
    val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    val copied = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().let { i ->
      var text: CharSequence? = null
      i.runOnMainSync { text = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text }
      text?.toString()
    }
    assertEquals("Refunded Ana €40.", copied)
    assertEquals("opening nothing reported", emptyList<Pair<String, String?>>(), sent)
  }

  @Test fun anAnswerThatCantBeReportedStillCopiesAndHasNoMoreActions() {
    rule.setShown { TafsTheme { AnswerWithActions("Lately: refund order #4471.", null) { Text("Lately: refund order #4471.") } } }
    rule.onNodeWithText(bot.tafs.app.model.AnswerWords.COPY).assertIsDisplayed()
    assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(bot.tafs.app.model.AnswerWords.MORE)).fetchSemanticsNodes().size)
  }

  @Test fun anAnswerReportedBeforeSaysSo() {
    show(ReportResult.Sent(first = false))
    report()
    rule.onNodeWithText(ReportWords.ALREADY).performScrollTo().assertIsDisplayed()
  }

  @Test fun aReportThatDidntGoKeepsTheSheetToSendAgain() {
    show(ReportResult.NotSent("You're offline. Try again once you're online."))
    report()
    rule.onNodeWithText(ReportWords.notSent("You're offline. Try again once you're online.")).performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Send").performScrollTo().assertIsDisplayed()
  }
}
