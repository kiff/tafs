package bot.tafs.app

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Segmented
import bot.tafs.app.design.TafsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** The taf page's tabs: each label on one line, whatever its language, shrunk to fit its segment rather than wrapped or cut. */
class SegmentedTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  @Test fun aLongLabelOnANarrowPhoneStaysWholeOnOneLine() {
    val tabs = listOf("Chat", "Actividad", "Tarjeta", "Configuración")
    rule.setShown { TafsTheme { Box(Modifier.width(320.dp)) { Segmented(tabs, "Chat", { it }) {} } } }
    tabs.forEach { tab ->
      val layouts = mutableListOf<TextLayoutResult>()
      val node = rule.onNodeWithText(tab).fetchSemanticsNode()
      node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
      val layout = layouts.single()
      assertEquals("$tab is on one line", 1, layout.lineCount)
      assertFalse("$tab isn't cut", layout.hasVisualOverflow)
    }
  }
}
