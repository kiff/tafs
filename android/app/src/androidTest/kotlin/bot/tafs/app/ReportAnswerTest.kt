package bot.tafs.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.ReportResult
import bot.tafs.app.model.ReportWords
import bot.tafs.app.views.ReportableAnswer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Reporting an answer from inside the app (docs/chat.md): a long press on it, Report this answer, a reason and a note, Send.
 * Run with `-e hold 8` to keep the sheet for a screenshot.
 */
class ReportAnswerTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val sent = mutableListOf<Pair<String, String?>>()

  private fun show(answer: ReportResult) = rule.setShown {
    TafsTheme {
      ReportableAnswer({ reason, note -> sent += reason to note; answer }) { Text("Refunded Ana €40.") }
    }
  }

  private fun report(note: String? = null) {
    rule.onNodeWithText("Refunded Ana €40.").performTouchInput { longClick() }
    rule.onNodeWithText(ReportWords.MENU).assertIsDisplayed().performClick()
    rule.onNodeWithText("Wrong").assertIsDisplayed().performClick()
    note?.let { rule.onNodeWithText("Add a note (optional)").performTextInput(it) }
    rule.onNodeWithText("Send").performClick()
  }

  @Test fun anAnswerIsReportedFromItsLongPressAndTheSheetSaysThanks() {
    show(ReportResult.Sent(first = true))
    report("It refunded the wrong order.")
    rule.onNodeWithText(ReportWords.THANKS).assertIsDisplayed()
    assertEquals(listOf("wrong" to "It refunded the wrong order."), sent)
    rule.onNodeWithText("Done").performClick()
  }

  @Test fun anAnswerReportedBeforeSaysSo() {
    show(ReportResult.Sent(first = false))
    report()
    rule.onNodeWithText(ReportWords.ALREADY).assertIsDisplayed()
  }

  @Test fun aReportThatDidntGoKeepsTheSheetToSendAgain() {
    show(ReportResult.NotSent("You're offline. Try again once you're online."))
    report()
    rule.onNodeWithText(ReportWords.notSent("You're offline. Try again once you're online.")).assertIsDisplayed()
    rule.onNodeWithText("Send").assertIsDisplayed()
  }
}
