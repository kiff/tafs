package bot.tafs.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.TafsTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A main button's words read whole on a phone: French Needs you's "Approuver une fois avec votre empreinte digitale" was cut to
 * "…digit…" at an S23's 360 dp (the phone's Play screenshots, 16 Nov). It now takes two lines before anything is cut.
 */
class PrimaryButtonTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val french = "Approuver une fois avec votre empreinte digitale"

  private fun layoutOf(text: String): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
    return results.single()
  }

  @Test fun aLongLanguagesMainActionReadsWholeOnAPhone() {
    // The width a 360 dp phone gives a held call's card's button: the screen less the list's and the card's padding.
    rule.setShown { TafsTheme { Box(Modifier.width(296.dp)) { PrimaryButton(french, {}) } } }
    val layout = layoutOf(french)
    assertFalse("the words are cut: ${layout.lineCount} line(s)", layout.hasVisualOverflow)
    assertTrue(layout.lineCount <= 2)
  }

  @Test fun aShortActionStaysOneLine() {
    rule.setShown { TafsTheme { Box(Modifier.width(296.dp)) { PrimaryButton("Send", {}) } } }
    val layout = layoutOf("Send")
    assertFalse(layout.hasVisualOverflow)
    assertTrue(layout.lineCount == 1)
  }
}
