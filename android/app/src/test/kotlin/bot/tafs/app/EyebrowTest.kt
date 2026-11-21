package bot.tafs.app

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import bot.tafs.app.design.eyebrowStyle
import bot.tafs.app.design.ideographic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A section's small heading in Korean, Japanese or Chinese keeps its words together: plain face, no tracking (as on iOS). */
class EyebrowTest {
  @Test fun koreanJapaneseAndChineseAreIdeographicLatinIsnt() {
    for (t in listOf("내 TAFS", "기억하는 것", "確認が必要", "ひらがな", "需要你")) assertTrue(t, ideographic(t))
    for (t in listOf("YOUR TAFS", "Needs you", "À VOUS", "Ваши")) assertFalse(t, ideographic(t))
  }

  @Test fun anIdeographicHeadingIsPlainAndUntrackedALatinOneStaysMonospacedAndTracked() {
    val ko = eyebrowStyle("확인 필요")
    assertEquals(null, ko.fontFamily)
    assertEquals(TextUnit.Unspecified, ko.letterSpacing)
    val en = eyebrowStyle("Needs you")
    assertEquals(FontFamily.Monospace, en.fontFamily)
    assertEquals(0.7.sp, en.letterSpacing)
  }
}
