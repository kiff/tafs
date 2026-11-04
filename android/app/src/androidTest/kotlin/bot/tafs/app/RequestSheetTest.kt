package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.RequestDetail
import bot.tafs.app.views.RequestSheet
import bot.tafs.app.views.RequestWords
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** K5: a request step by step, Try the reply now when a reply can be sent again, and Ask Coach. */
class RequestSheetTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val detail = KiffClient.json.decodeFromString<RequestDetail>(
    """{"id":"run_2","at":"2026-11-02T22:41:00Z","state":"retrying","line":"Refund $20 · order 4402","from":"By email",
        "steps":[{"label":"Received","line":"Email from anna@example.com","at":"22:41","state":"done"},
                 {"label":"Reply not delivered","line":"try 2 of 3","state":"failed"},
                 {"label":"Next try 23:15","state":"next"}]}""",
  )
  private val log = mutableListOf<String>()

  private fun show(canSend: Boolean) = rule.setShown {
    TafsTheme {
      RequestSheet(detail, sending = false, onTryReply = if (canSend) ({ log += "try" }) else null, onAskCoach = { log += "coach" }, onClose = {})
    }
  }

  @Test fun theStepsAreShownAsTafsCloudWroteThem() {
    show(canSend = true)
    rule.onNodeWithText("Refund $20 · order 4402").assertIsDisplayed()
    rule.onNodeWithText("By email").assertIsDisplayed()
    rule.onNodeWithText("Email from anna@example.com · 22:41").assertIsDisplayed()
    rule.onNodeWithText("Next try 23:15").assertIsDisplayed()
    rule.onNodeWithText(RequestWords.TRY_REPLY_NOW).performClick()
    rule.onNodeWithText(RequestWords.ASK_COACH).performClick()
    rule.waitForIdle()
    assertEquals(listOf("try", "coach"), log)
  }

  @Test fun withNothingToSendAgainThereIsNoTryNow() {
    show(canSend = false)
    assertEquals(0, rule.onAllNodes(hasText(RequestWords.TRY_REPLY_NOW)).fetchSemanticsNodes().size)
  }
}
