package bot.tafs.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.ConnectWords
import bot.tafs.app.views.ConnectApprovalSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** An API-key app from Connect apps: its approval sheet takes the key, given with the same confirmation, as on iPhone and iPad. */
class ConnectKeySheetTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val grant = KiffClient.json.decodeFromString<Grant>(
    """{"id":"g8","status":"pending","text":"Connect Holded: read invoices.","digest":"d1","request":{"server":"api.holded.com","agent":"books-1","tools":[]}}""",
  ).copy(digestMatches = true)
  private val given = mutableListOf<String>()
  private val approved = mutableListOf<String>()

  private fun show(needsKey: Boolean) = rule.setShown {
    TafsTheme {
      ConnectApprovalSheet(
        AppState(mode = Mode.kiff, ready = true, grants = listOf(grant)), grant,
        loadWords = {}, onApprove = { approved += it.id }, onClose = {},
        needsKey = needsKey, onGiveKey = { g, key -> given += "${g.id} $key" },
      )
    }
  }

  @Test fun theKeyIsTypedHiddenAndGivenWithTheConfirmation() {
    show(needsKey = true)
    rule.onNodeWithText("Give the key with", substring = true).assertIsNotEnabled()
    rule.onNode(hasSetTextAction()).performTextInput("sk_test_4eC39H")
    rule.waitForIdle()
    // Drawn as dots: a password field, as the phone's own keyboard and screen readers treat one.
    rule.onNode(hasSetTextAction()).assert(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
    rule.onNodeWithText("Give the key with", substring = true).assertIsEnabled().performClick()
    rule.waitForIdle()
    assertEquals(listOf("g8 sk_test_4eC39H"), given)
    assertEquals("not an approval with a vendor's sign-in", emptyList<String>(), approved)
  }

  @Test fun anAppThatSignsInHasNoKeyField() {
    show(needsKey = false)
    assertEquals(0, rule.onAllNodes(hasText(ConnectWords.KEY)).fetchSemanticsNodes().size)
    assertEquals(0, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
    rule.onNodeWithText("Approve with", substring = true).performClick()
    rule.waitForIdle()
    assertEquals(listOf("g8"), approved)
  }
}
