package bot.tafs.app

import androidx.compose.ui.graphics.Color
import bot.tafs.app.design.Theme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text and its ground, light and dark, at 4.5:1 or more (WCAG AA), from Theme's own values. */
class ContrastTest {
  @After fun light() { Theme.dark = false }

  private fun channel(c: Float): Double = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
  private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
  private fun ratio(a: Color, b: Color): Double {
    val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
  }

  private fun each(block: () -> Unit) = listOf(false, true).forEach { Theme.dark = it; block() }

  @Test fun textOnTheGradientReadsOnEveryStopLightOrDark() = each {
    Theme.BRAND_STOPS.forEach { stop -> assertTrue("onBrand on $stop, dark=${Theme.dark}", ratio(Theme.onBrand, stop) >= 4.5) }
  }

  @Test fun textOnSolidAmberReadsInDark() {
    Theme.dark = true
    assertTrue(ratio(Theme.amberInk, Theme.amber) >= 4.5)
  }

  @Test fun textReadsOnEverySurfaceLightOrDark() = each {
    for (ground in listOf(Theme.background, Theme.panel, Theme.raised)) {
      // Light's dim is set for the background and panels (kiff/tafs#210); dark's reaches it on raised too.
      val texts = if (ground == Theme.raised && !Theme.dark) listOf(Theme.ink, Theme.soft) else listOf(Theme.ink, Theme.soft, Theme.dim)
      for (text in texts) assertTrue("dark=${Theme.dark}", ratio(text, ground) >= 4.5)
    }
  }

  @Test fun theStatusFillsCarryThePanelsColourAsText() = each {
    for (fill in listOf(Theme.no, Theme.ok)) assertTrue("dark=${Theme.dark}", ratio(Theme.panel, fill) >= 4.5)
  }
}
