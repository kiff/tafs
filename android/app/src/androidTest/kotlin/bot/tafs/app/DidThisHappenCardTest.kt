package bot.tafs.app

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.UnsureCall
import bot.tafs.app.views.UnsureCallCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** "Did this happen?" with KIFF's context draws it as sent and offers KIFF's three answers; from an older KIFF, the app's two. */
class DidThisHappenCardTest {
  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val call = KiffClient.json.decodeFromString<UnsureCall>(
    """{"proposal":"prop_1","title":"Refund a payment","status":"unknown","taf":{"agent":"refunds-1","name":"Refunds"},"vendor":"Stripe",
      "amount_words":"€40.00","when_words":"today 09:14","counterparty":{"noun":"charge","id":"ch_1","words":"Order 1042"},
      "why":"Its answer was lost on the way back.","record_url":"https://dashboard.example.com/charges/ch_1",
      "answers":[{"answer":"yes","words":"Yes, it went through","detail":"It stays counted on the Card.","key":"window"},
        {"answer":"no","words":"No, it didn't","detail":"The Card gets its room back.","key":"critical"},
        {"answer":"not_sure","words":"Not sure, help me check","detail":"Coach reads the vendor's records with you.","key":"device"}]}""",
  )
  private val answered = mutableListOf<String>()

  private fun show(c: UnsureCall) = rule.setShown {
    TafsTheme {
      androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        UnsureCallCard(c, answering = false, onAnswer = { _, happened -> answered += if (happened) "yes" else "no" }, onCheck = { answered += "not_sure" })
      }
    }
  }

  @Test fun kiffsContextAndThreeAnswersAreDrawnAndEachAnswersAsItSays() {
    show(call)
    listOf("€40.00", "Refunds · today 09:14", "charge Order 1042", "Its answer was lost on the way back.", "See it at Stripe", "Coach reads the vendor's records with you.")
      .forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    rule.onNodeWithText("Not sure, help me check").performScrollTo().performClick()
    rule.onNodeWithText("Yes, it went through").performScrollTo().performClick()
    rule.onNodeWithText("No, it didn't").performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(listOf("not_sure", "yes", "no"), answered)
  }

  @Test fun anOlderKiffGetsTheAppsTwoAnswersAndNoNotSure() {
    show(call.copy(answers = emptyList()))
    rule.onNodeWithText("Yes, it did").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("No").performScrollTo().assertIsDisplayed()
    assertEquals(0, rule.onAllNodesWithText("Not sure, help me check").fetchSemanticsNodes().size)
  }
}
