package bot.tafs.wear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import bot.tafs.watchlink.TafLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Colour by meaning (W7, docs/watch-wear.md, Version 2, Colour): the watch's theme is its own, never Material's default purple, and
 * the colours mean something: Send orange, Halt now red, a button that means nothing grey. Read from the pixels drawn.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = WatchApplication::class)
class WatchColoursTest {
  @get:Rule val compose = createComposeRule()

  @Before fun words() {
    // Tall, so no list item sits in the round face's scaled and faded edge.
    RuntimeEnvironment.setQualifiers("en-w192dp-h2400dp-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
  }

  /** The colour drawn at a share of [node]'s width and height. */
  private fun colourAt(node: SemanticsNodeInteraction, x: Float, y: Float): Int {
    val pixels = node.captureToImage().toPixelMap()
    return pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()].toArgb()
  }

  @Test fun theWatchsThemeIsSendsOrangeOnGreyNeverMaterialsPurple() {
    var scheme: ColorScheme? = null
    compose.setContent { WatchTheme { scheme = MaterialTheme.colorScheme } }
    compose.waitForIdle()
    val drawn = scheme!!
    assertNotEquals("Material's default primary (the purple) is gone", ColorScheme().primary, drawn.primary)
    assertEquals(Look.send, drawn.primary)
    assertEquals(Look.neutral, drawn.surfaceContainer)
    assertEquals(Look.danger, drawn.error)
    assertEquals(Color.Black, drawn.background)
  }

  @Test fun theReviewsSendIsOrangeItsDeleteRedOnGrey() {
    compose.setContent { WatchTheme { VoiceScreenV2(WatchModel.Voice.Review("Move my call", null), Actions.none) } }
    // Above the glyph, inside the round button: its ground.
    assertEquals(Look.send.toArgb(), colourAt(compose.onNodeWithTag(REVIEW_SEND), 0.5f, 0.12f))
    assertEquals(Look.neutral.toArgb(), colourAt(compose.onNodeWithTag(REVIEW_DELETE), 0.5f, 0.12f))
    assertEquals(Look.neutral.toArgb(), colourAt(compose.onNodeWithTag(REVIEW_AGAIN), 0.5f, 0.12f))
  }

  @Test fun haltNowIsRedAndCancelGrey() {
    val taf = TafLine("taf_1", "Late parcels", "Waiting for you", halted = false)
    compose.setContent { WatchTheme { HaltPickScreen(WatchModel.HaltPick(listOf(taf), setOf("taf_1"), confirming = true), Actions.none) } }
    // At the button's left end, halfway down: its ground, clear of its words.
    assertEquals(Look.danger.toArgb(), colourAt(compose.onNodeWithText(Words.Halt.now), 0.04f, 0.5f))
    assertEquals(Look.neutral.toArgb(), colourAt(compose.onNodeWithText(Words.Common.cancel), 0.04f, 0.5f))
  }

  @Test fun coachsHomeMicrophoneIsSendsOrangeAndItsMenuGrey() {
    val read = WatchModel.Shown.Read(WatchModel.NeedsYouRead(0, "Nothing needs you", emptyList()), WatchModel.TafsRead(emptyList(), allHalted = false))
    compose.setContent {
      WatchTheme {
        WatchPages(read, outcome = null, confirmingHaltAll = false, voice = null, result = null, working = emptyList(), ready = emptyList(), act = Actions.none, pages = pages(false))
      }
    }
    assertEquals(Look.send.toArgb(), colourAt(compose.onNodeWithContentDescription(Words.Watch.ask), 0.5f, 0.1f))
    assertEquals(Look.neutral.toArgb(), colourAt(compose.onNodeWithText(Words.Watch.menu), 0.04f, 0.5f))
  }
}
